"""Turns the ncnn model pnnx made from vision.py into a recipe: how to write ncnn's weight file from the published
nomic-embed-vision-v1.5 ONNX file (onnx/model_quantized.onnx), which is what the phone downloads. The phone follows
the recipe (SearchModel.java); build() here does exactly the same, so the result can be pinned by its SHA-256.

Weights that ncnn loads with a flag (type 0) are written in ncnn's 8-bit table format: the ONNX file's int8 values as
indices, the table holding index * scale. Nothing is rounded twice: ncnn reads back exactly the dequantized weights.
"""
import hashlib
import struct

import numpy as np

MAGIC = b"UGRC"
VERSION = 1
OP_INT8_TABLE = 1   # int8 tensor from the ONNX file -> flag + 256-entry table + indices (type 0)
OP_F32_FLAG = 2     # float32 tensor from the ONNX file -> zero flag + raw floats (type 0)
OP_F32_RAW = 3      # float32 tensor from the ONNX file -> raw floats (type 1)
OP_ROTATION = 5     # the fixed 64x64 pair rotation of vision.rotation() -> raw floats (type 1)


def varint(buf, i):
    v, shift = 0, 0
    while True:
        b = buf[i]
        i += 1
        v |= (b & 0x7F) << shift
        if b < 0x80:
            return v, i
        shift += 7


def fields(buf, start, end):
    i = start
    while i < end:
        key, i = varint(buf, i)
        f, wt = key >> 3, key & 7
        if wt == 0:
            v, i = varint(buf, i)
            yield f, wt, v, None
        elif wt == 2:
            n, i = varint(buf, i)
            yield f, wt, i, i + n
            i += n
        elif wt == 5:
            yield f, wt, i, None
            i += 4
        elif wt == 1:
            yield f, wt, i, None
            i += 8
        else:
            raise ValueError("wire type %d" % wt)


def onnx_tensors(path):
    """name -> (data_type, dims, raw_data offset, raw_data length) for every initializer."""
    buf = open(path, "rb").read()
    out = {}
    for f, wt, a, b in fields(buf, 0, len(buf)):
        if f != 7 or wt != 2:
            continue
        for g, gwt, ga, gb in fields(buf, a, b):
            if g != 5 or gwt != 2:
                continue
            name, dtype, dims, raw = None, None, [], None
            for t, twt, ta, tb in fields(buf, ga, gb):
                if t == 1 and twt == 0:
                    dims.append(ta)
                elif t == 1 and twt == 2:
                    j = ta
                    while j < tb:
                        d, j = varint(buf, j)
                        dims.append(d)
                elif t == 2:
                    dtype = ta
                elif t == 8:
                    name = buf[ta:tb].decode()
                elif t == 9:
                    raw = (ta, tb - ta)
                elif t == 4 and twt == 2:  # float_data, packed
                    raw = (ta, tb - ta)
                elif t == 4 and twt == 5:  # float_data, one value
                    raw = (ta, 4)
            if raw is not None:
                out[name] = (dtype, dims, raw[0], raw[1])
    return out, buf


def param_blobs(param):
    """The weight blobs ncnn reads, in order: (layer, kind, count, type)."""
    lines = open(param).read().split("\n")
    blobs = []
    for line in lines[2:]:
        if not line.strip():
            continue
        p = line.split()
        kind, name, nin, nout = p[0], p[1], int(p[2]), int(p[3])
        kv = {}
        for x in p[4 + nin + nout:]:
            k, v = x.split("=", 1)
            kv[int(k)] = v
        g = lambda k, d=0: int(float(kv.get(k, d)))
        if kind == "Convolution":
            blobs.append((name, "weight", g(6), 0))
            if g(5):
                blobs.append((name, "bias", g(0), 1))
        elif kind == "Gemm":
            M, N, K = g(7), g(8), g(9)
            if g(4):
                blobs.append((name, "A", M * K, 0))
            if g(5):
                blobs.append((name, "B", N * K, 0))
            if g(6) and g(10) != -1:
                blobs.append((name, "C", {0: 1, 1: M, 2: M, 3: N * M, 4: N}[g(10)], 0))
        elif kind == "LayerNorm":
            if g(2):
                blobs.append((name, "gamma", g(0), 1))
                blobs.append((name, "beta", g(0), 1))
        elif kind == "MemoryData":
            n = 1
            for k in (0, 1, 11, 2):
                if g(k):
                    n *= g(k)
            blobs.append((name, "data", n, g(21, 1)))
        elif kind in ("InnerProduct", "MultiHeadAttention", "Embed", "Deconvolution", "ConvolutionDepthWise"):
            raise SystemExit("layer type %s is not handled" % kind)
    return blobs


def read_pnnx_bin(path, blobs):
    data = open(path, "rb").read()
    i, out = 0, []
    for name, part, n, t in blobs:
        if t == 0:
            flag = struct.unpack_from("<I", data, i)[0]
            if flag != 0:
                raise SystemExit("%s.%s: expected float32 (pnnx fp16=0)" % (name, part))
            i += 4
        out.append(np.frombuffer(data, np.float32, n, i))
        i += 4 * n
    if i != len(data):
        raise SystemExit("bin size mismatch %d != %d" % (i, len(data)))
    return out


def make(param, pnnx_bin, onnx_path, literals):
    blobs = param_blobs(param)
    values = read_pnnx_bin(pnnx_bin, blobs)
    tensors, buf = onnx_tensors(onnx_path)
    # Candidates: every ONNX weight as stored and transposed, by the bytes of its float32 values.
    cand = {}
    for name, (dtype, dims, off, ln) in tensors.items():
        if dtype == 3 and name.endswith("_quantized"):  # int8
            q = np.frombuffer(buf, np.int8, ln, off).reshape(dims)
            sname = name[: -len("_quantized")] + "_scale"
            sd, _, so, _ = tensors[sname]
            scale = np.frombuffer(buf, np.float32, 1, so)[0]
            deq = q.astype(np.float32) * scale
            cand.setdefault(deq.tobytes(), (OP_INT8_TABLE, 0, dims, off, scale, name))
            if len(dims) == 2:
                cand.setdefault(np.ascontiguousarray(deq.T).tobytes(), (OP_INT8_TABLE, 1, dims, off, scale, name))
        elif dtype == 1 and int(np.prod(dims)) > 1:
            a = np.frombuffer(buf, np.float32, ln // 4, off)
            cand.setdefault(a.tobytes(), (OP_F32_RAW, 0, [a.size], off, 0.0, name))
    entries = []
    for (name, part, n, t), v in zip(blobs, values):
        hit = cand.get(v.tobytes())
        if hit is None:
            for lname, arr in literals.items():
                if arr.size == n and arr.tobytes() == v.tobytes():
                    hit = (OP_ROTATION, 0, [n], 0, 0.0, lname)
                    break
        if hit is None:
            raise SystemExit("no source for %s.%s (%d values)" % (name, part, n))
        op, transpose, dims, off, scale, src = hit
        if op == OP_F32_RAW and t == 0:
            op = OP_F32_FLAG
        if op == OP_INT8_TABLE and t != 0:
            raise SystemExit("%s.%s: int8 source for a raw slot" % (name, part))
        if op == OP_ROTATION and t == 0:
            raise SystemExit("%s.%s: rotation for a flagged slot" % (name, part))
        rows, cols = (dims[0], dims[1]) if len(dims) == 2 else (int(np.prod(dims)), 1)
        entries.append(dict(op=op, transpose=transpose, rows=rows, cols=cols, offset=off, scale=float(scale),
                            src=src))
    return entries, tensors


def write_recipe(path, entries, latent):
    with open(path, "wb") as f:
        f.write(MAGIC + struct.pack("<IIQI", VERSION, len(entries), latent[0], latent[1]))
        for e in entries:
            f.write(struct.pack("<BBIIQf", e["op"], e["transpose"], e["rows"], e["cols"], e["offset"], e["scale"]))


def rotation(n):
    """Pairs (x0, x1) -> (-x1, x0) as x @ R (vision.rotation())."""
    r = np.zeros(n, np.float32)
    side = int(round(n ** 0.5))
    r = r.reshape(side, side)
    for i in range(0, side, 2):
        r[i + 1, i] = -1.0
        r[i, i + 1] = 1.0
    return r


def build(recipe_path, onnx_path, bin_out, latent_out):
    """What the phone does (SearchModel.build): returns the SHA-256 of both files."""
    r = open(recipe_path, "rb").read()
    src = open(onnx_path, "rb").read()
    assert r[:4] == MAGIC
    version, count, lat_off, lat_n = struct.unpack_from("<IIQI", r, 4)
    i = 4 + struct.calcsize("<IIQI")
    out = bytearray()
    for _ in range(count):
        op, tr, rows, cols, off, scale = struct.unpack_from("<BBIIQf", r, i)
        i += struct.calcsize("<BBIIQf")
        n = rows * cols
        if op == OP_INT8_TABLE:
            q = np.frombuffer(src, np.uint8, n, off).reshape(rows, cols)
            if tr:
                q = q.T
            idx = np.ascontiguousarray(q) ^ np.uint8(0x80)  # int8 value v -> index v + 128
            table = (np.arange(256, dtype=np.float32) - np.float32(128)) * np.float32(scale)
            out += struct.pack("<I", 1) + table.astype(np.float32).tobytes() + idx.tobytes()
            out += b"\0" * ((4 - n % 4) % 4)
        elif op in (OP_F32_FLAG, OP_F32_RAW):
            a = np.frombuffer(src, np.float32, n, off).reshape(rows, cols)
            if tr:
                a = a.T
            if op == OP_F32_FLAG:
                out += struct.pack("<I", 0)
            out += np.ascontiguousarray(a).tobytes()
        elif op == OP_ROTATION:
            out += rotation(rows).tobytes()
        else:
            raise SystemExit("op %d" % op)
    open(bin_out, "wb").write(out)
    lat = src[lat_off:lat_off + 4 * lat_n]
    open(latent_out, "wb").write(lat)
    return hashlib.sha256(out).hexdigest(), len(out), hashlib.sha256(lat).hexdigest()

