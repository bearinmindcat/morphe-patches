"""nomic-embed-vision-v1.5 written with ops ncnn knows, weights read from the published ONNX files."""
import numpy as np
import onnx
import torch
import torch.nn as nn
from onnx import numpy_helper

D, H, HD, N, P = 768, 12, 64, 197, 196
LAYERS = 12


def read_weights(path):
    """name -> numpy array. Matrix weights come as <module>.wT (in x out, the MatMul B operand)."""
    m = onnx.load(path)
    g = m.graph
    init = {t.name: t for t in g.initializer}
    w = {}
    for name, t in init.items():
        if not name.startswith("onnx::") and not name.startswith("/"):
            w[name] = numpy_helper.to_array(t).astype(np.float32)
    for n in g.node:
        if n.op_type == "MatMul" and n.input[1] in init:
            key = n.name[1:].rsplit("/MatMul", 1)[0].replace("/", ".") + ".wT"
            w[key] = numpy_helper.to_array(init[n.input[1]]).astype(np.float32)
        elif n.op_type == "MatMulInteger":
            key = n.name[1:].rsplit("/MatMul_quant", 1)[0].replace("/", ".") + ".wT"
            q = numpy_helper.to_array(init[n.input[1]]).astype(np.float32)
            zp = numpy_helper.to_array(init[n.input[3]]).astype(np.float32)
            scale = numpy_helper.to_array(init[n.input[1].replace("_quantized", "_scale")]).astype(np.float32)
            w[key] = (q - zp) * scale
    # Rotary tables (the same for every layer): q * cos + rot(q) * sin.
    for n in g.node:
        if n.name == "/layers.0/attn/Mul":
            w["rope.cos"] = numpy_helper.to_array(init[n.input[1]]).astype(np.float32)
        if n.name == "/layers.0/attn/Mul_1":
            w["rope.sin"] = numpy_helper.to_array(init[n.input[1]]).astype(np.float32)
    return w


def linear(wT, b):
    l = nn.Linear(wT.shape[0], wT.shape[1], bias=b is not None)
    l.weight.data = torch.from_numpy(np.ascontiguousarray(wT.T))
    if b is not None:
        l.bias.data = torch.from_numpy(b)
    return l


def norm(w, b, eps):
    l = nn.LayerNorm(w.shape[0], eps=eps)
    l.weight.data = torch.from_numpy(w)
    l.bias.data = torch.from_numpy(b)
    return l


class Layer(nn.Module):
    def __init__(self, w, p):
        super().__init__()
        self.norm1 = norm(w[p + "norm1.weight"], w[p + "norm1.bias"], 1e-6)
        self.qkv = linear(w[p + "attn.Wqkv.wT"], w[p + "attn.Wqkv.bias"])
        self.out = linear(w[p + "attn.out_proj.wT"], w[p + "attn.out_proj.bias"])
        self.norm2 = norm(w[p + "norm2.weight"], w[p + "norm2.bias"], 1e-6)
        self.fc11 = linear(w[p + "mlp.fc11.wT"], w[p + "mlp.fc11.bias"])
        self.fc12 = linear(w[p + "mlp.fc12.wT"], w[p + "mlp.fc12.bias"])
        self.mlp_norm = norm(w[p + "mlp.norm.weight"], w[p + "mlp.norm.bias"], 1e-5)
        self.fc2 = linear(w[p + "mlp.fc2.wT"], w[p + "mlp.fc2.bias"])


def rope(x, cos, sin, rot):
    # EVA's interleaved rotation, pairs (x0, x1) -> (-x1, x0), as a product with a fixed +-1 matrix (no step-2 slices).
    return x * cos + torch.matmul(x, rot) * sin


def rotation():
    r = np.zeros((HD, HD), np.float32)
    for i in range(0, HD, 2):
        r[i + 1, i] = -1.0
        r[i, i + 1] = 1.0
    return r


class Vision(nn.Module):
    """Batch of one throughout (ncnn has no batch axis)."""

    def __init__(self, w):
        super().__init__()
        pw = w["embeddings.proj.wT"].T.reshape(D, 3, 16, 16)
        self.patch = nn.Conv2d(3, D, 16, 16)
        self.patch.weight.data = torch.from_numpy(np.ascontiguousarray(pw))
        self.patch.bias.data = torch.from_numpy(w["embeddings.proj.bias"])
        self.cls = nn.Parameter(torch.from_numpy(w["embeddings.cls_token"].reshape(1, 1, D)))
        self.pos = nn.Parameter(torch.from_numpy(w["embeddings.pos_embed"].reshape(1, N, D)))
        self.cos = nn.Parameter(torch.from_numpy(w["rope.cos"].reshape(1, 1, P, HD)))
        self.sin = nn.Parameter(torch.from_numpy(w["rope.sin"].reshape(1, 1, P, HD)))
        self.rot = nn.Parameter(torch.from_numpy(rotation()))
        self.layers = nn.ModuleList([Layer(w, "layers.%d." % i) for i in range(LAYERS)])
        s = "selector."
        # The pooling query: an input, so that the converter does not fold it (with Wq) into a constant.
        self.latent = torch.from_numpy(w[s + "attn.latent"].reshape(1, 1, D))
        self.wq = linear(w[s + "attn.Wq.wT"], w[s + "attn.Wq.bias"])
        self.wkv = linear(w[s + "attn.Wkv.wT"], w[s + "attn.Wkv.bias"])
        self.sel_out = linear(w[s + "attn.out_proj.wT"], w[s + "attn.out_proj.bias"])
        self.sel_norm = norm(w[s + "norm1.weight"], w[s + "norm1.bias"], 1e-6)
        self.sel_fc11 = linear(w[s + "mlp.fc11.wT"], w[s + "mlp.fc11.bias"])
        self.sel_fc12 = linear(w[s + "mlp.fc12.wT"], w[s + "mlp.fc12.bias"])
        self.sel_fc2 = linear(w[s + "mlp.fc2.wT"], w[s + "mlp.fc2.bias"])

    @staticmethod
    def heads(x, n):
        return x.reshape(1, n, H, HD).permute(0, 2, 1, 3)

    def attention(self, l, x):
        q, k, v = torch.split(l.qkv(x), D, 2)
        q, k, v = self.heads(q, N), self.heads(k, N), self.heads(v, N)
        q = torch.cat([q[:, :, 0:1], rope(q[:, :, 1:], self.cos, self.sin, self.rot)], 2)
        k = torch.cat([k[:, :, 0:1], rope(k[:, :, 1:], self.cos, self.sin, self.rot)], 2)
        a = torch.softmax(torch.matmul(q * 0.125, k.permute(0, 1, 3, 2)), 3)
        o = torch.matmul(a, v).permute(0, 2, 1, 3).reshape(1, N, D)
        return l.out(o)

    def forward(self, x, latent):
        x = self.patch(x).reshape(1, D, P).permute(0, 2, 1)
        h = torch.cat([self.cls, x], 1) + self.pos
        residual = None
        for l in self.layers:
            residual = h if residual is None else h + residual
            a = self.attention(l, l.norm1(residual))
            residual = a + residual
            n = l.norm2(residual)
            g = l.fc12(n)
            h = l.fc2(l.mlp_norm(l.fc11(n) * (g * torch.sigmoid(g))))
        h = h + residual
        # Attention pooling into the first token (the only one the embedding uses).
        q = self.heads(self.wq(latent), 1)
        k, v = torch.split(self.wkv(h), D, 2)
        k, v = self.heads(k, N), self.heads(v, N)
        a = torch.softmax(torch.matmul(q * 0.125, k.permute(0, 1, 3, 2)), 3)
        o = self.sel_out(torch.matmul(a, v).permute(0, 2, 1, 3).reshape(1, 1, D))
        n = self.sel_norm(o)
        g = self.sel_fc12(n)
        m = self.sel_fc2(self.sel_fc11(n) * (g * torch.sigmoid(g)))
        return (h[:, 0:1] + m).reshape(1, D)


MEAN = np.array([0.48145466, 0.4578275, 0.40821073], np.float32)
STD = np.array([0.26862954, 0.26130258, 0.27577711], np.float32)


def preprocess(img, resample=3):
    """CLIPImageProcessor: shortest side 224 (bicubic), centre crop 224, CLIP mean/std. -> [1,3,224,224]"""
    from PIL import Image
    img = img.convert("RGB")
    w, h = img.size
    s = 224 / min(w, h)
    img = img.resize((max(224, round(w * s)), max(224, round(h * s))), resample)
    w, h = img.size
    l, t = (w - 224) // 2, (h - 224) // 2
    img = img.crop((l, t, l + 224, t + 224))
    a = (np.asarray(img, np.float32) / 255.0 - MEAN) / STD
    return a.transpose(2, 0, 1)[None].astype(np.float32)
