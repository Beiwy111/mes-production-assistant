"""Pinned E5 ONNX CPU inference; downloads are an explicit CLI action only."""
import hashlib
import json
import threading
from pathlib import Path

import numpy as np
import onnxruntime as ort
from huggingface_hub import hf_hub_download
from tokenizers import Tokenizer

from .corpus import sha256_file

MODEL_NAME = 'intfloat/multilingual-e5-small'
MODEL_REVISION = '614241f622f53c4eeff9890bdc4f31cfecc418b3'
MODEL_FILES = {
    'onnx/model.onnx': 'ca456c06b3a9505ddfd9131408916dd79290368331e7d76bb621f1cba6bc8665',
    'tokenizer.json': '0b44a9d7b51c3c62626640cda0e2c2f70fdacdc25bbbd68038369d14ebdf4c39',
}


def prepare_model(directory: Path) -> dict:
    directory.mkdir(parents=True, exist_ok=True)
    hashes = {}
    for name, expected in MODEL_FILES.items():
        path = directory / name
        if not path.exists() or sha256_file(path) != expected:
            downloaded = hf_hub_download(MODEL_NAME, filename=name, revision=MODEL_REVISION,
                                         local_dir=directory, force_download=path.exists(), token=False)
            if sha256_file(Path(downloaded)) != expected:
                raise ValueError('Downloaded file differs from pinned E5 model: ' + name)
        hashes[name] = expected
    metadata = {'name': MODEL_NAME, 'revision': MODEL_REVISION, 'files': hashes,
                'dimension': 384, 'pooling': 'attention-mask-mean-l2',
                'document_prefix': 'passage: ', 'query_prefix': 'query: ', 'max_tokens': 512}
    from .indexing import atomic_json
    atomic_json(directory / 'model-manifest.json', metadata)
    return metadata


class E5Embedding:
    dimension = 384

    def __init__(self, directory: Path, threads: int = 4):
        metadata = json.loads((directory / 'model-manifest.json').read_text(encoding='utf-8'))
        if metadata['name'] != MODEL_NAME or metadata['revision'] != MODEL_REVISION:
            raise ValueError('Embedding model revision differs from this implementation')
        if metadata['files'] != MODEL_FILES:
            raise ValueError('Model files differ from the pinned manifest')
        for name, expected in metadata['files'].items():
            if sha256_file(directory / name) != expected:
                raise ValueError('Embedding file checksum mismatch')
        self.signature = hashlib.sha256(json.dumps(metadata, sort_keys=True).encode()).hexdigest()
        self.tokenizer = Tokenizer.from_file(str(directory / 'tokenizer.json'))
        self.counter = Tokenizer.from_file(str(directory / 'tokenizer.json'))
        self.counter.no_truncation()
        self.counter.no_padding()
        self.tokenizer.enable_truncation(max_length=512)
        self.tokenizer.enable_padding(pad_id=1, pad_token='<pad>')
        options = ort.SessionOptions()
        options.intra_op_num_threads = threads
        options.inter_op_num_threads = 1
        self.session = ort.InferenceSession(str(directory / 'onnx/model.onnx'), options,
                                           providers=['CPUExecutionProvider'])
        self._lock = threading.Lock()

    def _encode(self, texts: list[str]) -> list[list[float]]:
        with self._lock:
            encodings = self.tokenizer.encode_batch(texts)
            ids = np.asarray([e.ids for e in encodings], dtype=np.int64)
            mask = np.asarray([e.attention_mask for e in encodings], dtype=np.int64)
            inputs = {'input_ids': ids, 'attention_mask': mask,
                      'token_type_ids': np.zeros_like(ids)}
            feed = {i.name: inputs[i.name] for i in self.session.get_inputs()}
            hidden = self.session.run(None, feed)[0]
            expanded = mask[..., None]
            pooled = (hidden * expanded).sum(axis=1) / expanded.sum(axis=1).clip(min=1)
            normalized = pooled / np.linalg.norm(pooled, axis=1, keepdims=True).clip(min=1e-12)
            if normalized.shape != (len(texts), self.dimension) or not np.isfinite(normalized).all():
                raise ValueError('Invalid embedding output')
            return normalized.tolist()

    def documents(self, texts: list[str]) -> list[list[float]]:
        prefixed = ['passage: ' + text for text in texts]
        if any(len(self.counter.encode(text).ids) > 512 for text in prefixed):
            raise ValueError('Chunk plus retrieval metadata exceeds embedding budget; refusing silent truncation')
        return self._encode(prefixed)

    def query(self, text: str) -> list[float]:
        return self._encode(['query: ' + text])[0]
