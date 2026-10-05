"""Fixed, reproducible CPU classifier. User input is data, never executable code."""
import hashlib
import re
import unicodedata
import torch
from torch import nn

DIMENSIONS = 2048


def normalize(text):
    return " ".join(unicodedata.normalize("NFKC", text).casefold().split())


def features(texts):
    vectors = torch.zeros(len(texts), DIMENSIONS)
    for i, text in enumerate(texts):
        text = normalize(text)
        tokens = re.findall(r"[a-z0-9_]+|[\u3400-\u9fff]", text)
        tokens += ["".join(pair) for pair in zip(tokens, tokens[1:])]
        for token in tokens:
            index = int.from_bytes(hashlib.blake2b(token.encode(), digest_size=4).digest(), "little") % DIMENSIONS
            vectors[i, index] += 1
    return nn.functional.normalize(vectors, p=2, dim=1)


def classifier(classes):
    return nn.Sequential(nn.Linear(DIMENSIONS, 64), nn.ReLU(), nn.Linear(64, classes))
