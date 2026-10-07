"""Run: python predict.py '需要分类的文字' (beside training.json and weights.pt)."""
import json
from pathlib import Path
import sys
import torch
from model import classifier, features

if __name__ == "__main__":
    directory = Path(__file__).resolve().parent
    metadata = json.loads((directory / "training.json").read_text(encoding="utf-8"))
    model = classifier(len(metadata["labels"]), metadata.get("architecture", "mlp"))
    model.load_state_dict(torch.load(directory / "weights.pt", weights_only=True, map_location="cpu"))
    model.eval()
    with torch.inference_mode():
        scores = model(features([" ".join(sys.argv[1:])])).softmax(1)[0].tolist()
    print(json.dumps(sorted(zip(metadata["labels"], scores), key=lambda item: -item[1]), ensure_ascii=False))
