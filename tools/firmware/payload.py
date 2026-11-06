#!/usr/bin/env python3
"""Read partitions out of an Android A/B OTA (payload.bin) without downloading the whole file.

  payload.py <update.zip | payload.bin | https://...> list
  payload.py <update.zip | payload.bin | https://...> extract <partition> <out.img>

The source is a URL (read with HTTP range requests, only the bytes needed) or a local file. It is either the
update.zip that holds payload.bin (stored, as OTA zips are) or a bare payload.bin. Only full OTAs work: an
incremental (delta) OTA needs the old partitions and is refused. Needs python3 and curl; the `zstandard` module
only for payloads that use ZSTD operations. JOBS=<n> sets how many requests run at once (default 6).
"""
import bz2, concurrent.futures, lzma, os, struct, subprocess, sys, threading

if len(sys.argv) < 3 or sys.argv[2] not in ("list", "extract") or (sys.argv[2] == "extract" and len(sys.argv) < 5):
    sys.exit(__doc__)
SRC = sys.argv[1]
REMOTE = SRC.startswith(("http://", "https://"))


def rng(a, n):
    """n bytes at offset a of the source."""
    if not REMOTE:
        with open(SRC, "rb") as f:
            f.seek(a)
            return f.read(n)
    for _ in range(5):
        r = subprocess.run(["curl", "-sL", "-m", "900", "--retry", "5", "-r", f"{a}-{a + n - 1}", SRC], capture_output=True)
        if len(r.stdout) == n:
            return r.stdout
    sys.exit(f"range {a}+{n} failed (does the server answer range requests?)")


def varint(b, i):
    v = s = 0
    while True:
        c = b[i]; i += 1; v |= (c & 0x7f) << s; s += 7
        if c < 0x80:
            return v, i


def fields(b):
    """Protobuf wire format -> [(field number, value)]."""
    i = 0; out = []
    while i < len(b):
        k, i = varint(b, i); f, w = k >> 3, k & 7
        if w == 0: v, i = varint(b, i)
        elif w == 2: l, i = varint(b, i); v = b[i:i + l]; i += l
        elif w == 1: v = b[i:i + 8]; i += 8
        elif w == 5: v = b[i:i + 4]; i += 4
        else: raise ValueError(f"wire type {w}")
        out.append((f, v))
    return out


# Where payload.bin starts: offset 0 for a bare payload, else walk the zip's local file headers to its entry.
pay = None
if rng(0, 4) == b"CrAU":
    pay = 0
else:
    off = 0
    while True:
        lh = rng(off, 30)
        if lh[:4] != b"PK\x03\x04":
            break
        flags, method = struct.unpack("<HH", lh[6:10])
        csize = struct.unpack("<I", lh[18:22])[0]
        nl, el = struct.unpack("<HH", lh[26:30])
        name = rng(off + 30, nl).decode(errors="replace")
        data = off + 30 + nl + el
        if name == "payload.bin":
            if method != 0:
                sys.exit("payload.bin is compressed inside the zip; unzip it first and pass payload.bin")
            pay = data
            break
        if flags & 8 or csize == 0xFFFFFFFF:
            sys.exit(f"cannot step over {name} (no size in its local header); unzip payload.bin first")
        off = data + csize
if pay is None:
    sys.exit("no payload.bin found: not an A/B OTA (older zips have system.new.dat.br or images instead)")

h = rng(pay, 24)
assert h[:4] == b"CrAU", "bad payload magic"
ver, msize = struct.unpack(">QQ", h[4:20]); ssize = struct.unpack(">I", h[20:24])[0]
assert ver == 2, f"payload version {ver} (only 2 is known)"
man = rng(pay + 24, msize)
dataoff = pay + 24 + msize + ssize

# DeltaArchiveManifest: 3 block_size, 13 partitions (PartitionUpdate: 1 name, 7 new_partition_info, 8 operations).
parts = []; bs = 4096
for f, v in fields(man):
    if f == 3: bs = v
    if f == 13:
        name = None; ops = []; size = 0
        for g, w in fields(v):
            if g == 1: name = w.decode()
            if g == 8: ops.append(w)
            if g == 7:
                for a, b in fields(w):
                    if a == 1: size = b
        parts.append((name, size, ops))

if sys.argv[2] == "list":
    for n, s, o in parts:
        tot = sum(b for op in o for a, b in fields(op) if a == 3)
        print(f"{n:24s} size {s / 1e6:9.1f} MB  to download {tot / 1e6:9.1f} MB  ops {len(o)}")
    sys.exit()

want, out = sys.argv[3], sys.argv[4]
match = [p for p in parts if p[0] == want]
if not match:
    sys.exit(f"no partition {want!r}; have: {', '.join(p[0] for p in parts)}")
n, s, ops = match[0]
with open(out, "wb") as fo:
    fo.truncate(s)
fd = os.open(out, os.O_WRONLY)
lock = threading.Lock(); done = [0, 0]


def work(op):
    # InstallOperation: 1 type, 2 data_offset, 3 data_length, 6 dst_extents (1 start_block, 2 num_blocks).
    t = doff = dlen = 0; ext = []
    for a, b in fields(op):
        if a == 1: t = b
        elif a == 2: doff = b
        elif a == 3: dlen = b
        elif a == 6:
            sb = nb = 0
            for c, d in fields(b):
                if c == 1: sb = d
                if c == 2: nb = d
            ext.append((sb, nb))
    if t in (6, 7):  # ZERO, DISCARD: the file is already zero-filled
        return
    blob = rng(dataoff + doff, dlen)
    if t == 0: data = blob                      # REPLACE
    elif t == 1: data = bz2.decompress(blob)    # REPLACE_BZ
    elif t == 8: data = lzma.decompress(blob)   # REPLACE_XZ
    elif t == 14:                               # ZSTD
        import zstandard
        data = zstandard.ZstdDecompressor().decompress(blob, max_output_size=1 << 31)
    else:
        sys.exit(f"operation type {t} needs the old partition: this is an incremental OTA, use a full one")
    q = 0
    for sb, nb in ext:
        os.pwrite(fd, data[q:q + nb * bs], sb * bs); q += nb * bs
    with lock:
        done[0] += 1; done[1] += dlen
        if done[0] % 200 == 0:
            print(f"{done[0]}/{len(ops)} {done[1] / 1e6:.0f} MB", flush=True)


with concurrent.futures.ThreadPoolExecutor(int(os.environ.get("JOBS", "6"))) as ex:
    for fut in [ex.submit(work, op) for op in ops]:
        fut.result()
os.close(fd)
print("ok", out)
