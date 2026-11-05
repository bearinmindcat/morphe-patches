"""nomic-embed-text-v1.5 (the text side of nomic-embed-vision-v1.5), as its model card uses it for text-to-image."""
import numpy as np
import onnxruntime as ort
from tokenizers import Tokenizer


class TextEncoder:
    def __init__(self, folder):
        self.tok = Tokenizer.from_file(folder + "/tokenizer.json")
        self.tok.enable_padding(pad_id=0, pad_token="[PAD]")
        self.tok.enable_truncation(64)
        so = ort.SessionOptions()
        so.intra_op_num_threads = 4
        self.sess = ort.InferenceSession(folder + "/onnx/model.onnx", so, providers=["CPUExecutionProvider"])

    def embed(self, texts, prefix="search_query: ", batch=64):
        out = []
        for i in range(0, len(texts), batch):
            enc = self.tok.encode_batch([prefix + t for t in texts[i:i + batch]])
            ids = np.array([e.ids for e in enc], np.int64)
            mask = np.array([e.attention_mask for e in enc], np.int64)
            h = self.sess.run(["last_hidden_state"], {"input_ids": ids, "token_type_ids": np.zeros_like(ids),
                                                      "attention_mask": mask})[0]
            m = mask[:, :, None].astype(np.float32)
            e = (h * m).sum(1) / np.clip(m.sum(1), 1e-9, None)
            e = (e - e.mean(1, keepdims=True)) / np.sqrt(e.var(1, keepdims=True) + 1e-5)
            out.append(e / np.linalg.norm(e, axis=1, keepdims=True))
        return np.concatenate(out).astype(np.float32)
