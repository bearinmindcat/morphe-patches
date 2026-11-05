"""Makes what Search by content ships (patches/src/main/resources/samsunggallery/search/):

  vision.param   ncnn's description of nomic-embed-vision-v1.5 as written in vision.py (pnnx from the traced model)
  vision.recipe  how the phone writes the matching weight file from Nomic's onnx/model_quantized.onnx (recipe.py)
  labels.bin     the words (labels.py), their nomic-embed-text-v1.5 embeddings, their usual similarity over the
                 calibration photos, Gallery's keys, and the choice rules (SearchLabels.java reads it)

and prints the SHA-256 values SearchModel.java pins. Needs Python with torch, onnx, onnxruntime, tokenizers, pillow,
numpy; pnnx 20250912 (the ncnn release in native/build.sh is 20250916). Inputs, all from their original hosts:

  nomic-ai/nomic-embed-vision-v1.5 @ e3a725bce72db07ca4adb1d83da08903f3ee02f8: onnx/model_quantized.onnx
  nomic-ai/nomic-embed-text-v1.5 @ e9b6763023c676ca8431644204f50c2b100d9aab: onnx/model.onnx, tokenizer.json
  the calibration photos in calibration.json (CC0 / public domain, from Openverse)

  python convert.py <model_quantized.onnx> <text model dir> <calibration photo dir> <pnnx binary> <work dir> <out dir>
"""
import glob
import os
import shutil
import subprocess
import sys

import numpy as np
import torch
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import labels  # noqa: E402
import recipe  # noqa: E402
import text  # noqa: E402
import vision  # noqa: E402

LABELS_VERSION = 1
# z_word, delta_word, floor, max_words, key_rank, key_pair, z_head (SearchLabels.choose; tuned on CC0 test photos of
# dogs, cats, food, beaches, mountains, documents, receipts, generated cards: 70 of 86 photos got the expected key,
# most misses were photos that do not show what their source said)
PARAMS = [2.5, 0.02, 0.06, 10, 2, 6, 1.0]


def main(onnx_path, text_dir, calibration_dir, pnnx, work, out):
    os.makedirs(work, exist_ok=True)
    os.makedirs(out, exist_ok=True)
    torch.set_num_threads(4)
    model = vision.Vision(vision.read_weights(onnx_path)).eval()

    # 1. The network for ncnn, and the recipe for its weights.
    with torch.no_grad():
        traced = torch.jit.trace(model, (torch.zeros(1, 3, 224, 224), model.latent))
    traced.save(work + "/vision.pt")
    subprocess.run([pnnx, work + "/vision.pt", "inputshape=[1,3,224,224],[1,1,768]", "fp16=0"], cwd=work, check=True,
                   stdout=subprocess.DEVNULL)
    entries, tensors = recipe.make(work + "/vision.ncnn.param", work + "/vision.ncnn.bin", onnx_path,
                                   {"rotation": vision.rotation().ravel()})
    _, _, latent_offset, latent_length = tensors["selector.attn.latent"]
    recipe.write_recipe(out + "/vision.recipe", entries, (latent_offset, latent_length // 4))
    shutil.copy(work + "/vision.ncnn.param", out + "/vision.param")
    bin_sha, bin_size, latent_sha = recipe.build(out + "/vision.recipe", onnx_path, work + "/vision.bin",
                                                 work + "/vision.latent")
    print("BIN_SHA256 %s BIN_SIZE %d LATENT_SHA256 %s" % (bin_sha, bin_size, latent_sha))

    # 2. The words.
    words = labels.words()
    emb = text.TextEncoder(text_dir).embed(words)
    photos = sorted(glob.glob(calibration_dir + "/*.jpg"))
    with torch.no_grad():
        cal = np.concatenate([model(torch.from_numpy(vision.preprocess(Image.open(p))), model.latent).numpy()
                              for p in photos])
    cal /= np.linalg.norm(cal, axis=1, keepdims=True)
    index = {w: i for i, w in enumerate(words)}
    keys = []
    for entry in labels.SAMSUNG:
        heads = [index[w] for w in labels.split(entry[3])] if len(entry) > 3 else []
        keys.append((entry[0], entry[1], [index[w] for w in labels.split(entry[2])], heads))
    # The similarities the phone computes: int8 word vectors.
    scale = np.abs(emb).max(1, keepdims=True) / 127.0
    deq = np.round(emb / scale) * scale
    s = cal @ deq.T.astype(np.float32)
    key_index = {entry[0]: k for k, entry in enumerate(labels.SAMSUNG)}
    requires = [(index[w], key_index[key]) for key, text in labels.REQUIRES.items() for w in labels.split(text)]
    write_labels(out + "/labels.bin", words, emb, s.mean(0), s.std(0), keys, requires, PARAMS)
    print("%d words, %d keys, %d calibration photos" % (len(words), len(keys), len(photos)))


def write_labels(path, words, emb, mu, sd, keys, requires, params):
    import struct
    scale = np.abs(emb).max(1) / 127.0
    q = np.round(emb / scale[:, None]).astype(np.int8)
    with open(path, "wb") as f:
        f.write(b"UGLB" + struct.pack("<III", LABELS_VERSION, len(words), emb.shape[1]))
        for w in words:
            b = w.encode()
            f.write(struct.pack("<H", len(b)) + b)
        f.write(scale.astype("<f4").tobytes())
        f.write(mu.astype("<f4").tobytes())
        f.write(sd.astype("<f4").tobytes())
        f.write(q.tobytes())
        f.write(struct.pack("<I", len(keys)))
        for key, parent, members, heads in keys:
            for s in (key, parent or ""):
                b = s.encode()
                f.write(struct.pack("<H", len(b)) + b)
            f.write(struct.pack("<H", len(members)) + struct.pack("<%dH" % len(members), *members))
            f.write(struct.pack("<H", len(heads)) + struct.pack("<%dH" % len(heads), *heads))
        f.write(struct.pack("<I", len(requires)))
        for word, key in requires:
            f.write(struct.pack("<HH", word, key))
        f.write(struct.pack("<I", len(params)) + struct.pack("<%df" % len(params), *params))


if __name__ == "__main__":
    main(*sys.argv[1:7])
