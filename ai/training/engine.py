import hashlib
import copy
import io
import json
import random
import shutil
import threading
import time
import uuid
import zipfile
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import torch
from fastapi import HTTPException
from model import classifier, features, normalize

ACTIVE = {"QUEUED", "RUNNING", "CANCELLING"}


class Engine:
    def __init__(self, root):
        self.root = Path(root)
        self.root.mkdir(parents=True, exist_ok=True)
        self.lock = threading.RLock()
        self.jobs = {}
        self.closed_owners = set()
        self.closed_file = self.root / "closed-owners.json"
        if self.closed_file.exists():
            self.closed_owners = set(json.loads(self.closed_file.read_text(encoding="utf-8")))
        self.cancels = {}
        self.pool = ThreadPoolExecutor(max_workers=1)
        torch.set_num_threads(2)
        for path in self.root.glob("*/job.json"):
            job = json.loads(path.read_text())
            if job["status"] in ACTIVE:
                job.update(status="FAILED", message="训练服务曾重启，请重新创建任务。")
            self.jobs[job["id"]] = job
            self._save(job)

    def _save(self, job):
        directory = self.root / job["id"]
        directory.mkdir(exist_ok=True)
        temporary = directory / "job.tmp"
        temporary.write_text(json.dumps(job, ensure_ascii=False), encoding="utf-8")
        temporary.replace(directory / "job.json")

    def _own(self, owner, job_id):
        job = self.jobs.get(job_id)
        if job is None or job["owner"] != owner:
            raise HTTPException(404, "任务不存在或无权访问")
        return job

    def _view(self, job):
        return copy.deepcopy({k: v for k, v in job.items() if k != "owner"})

    def list(self, owner):
        with self.lock:
            return [self._view(j) for j in sorted(self.jobs.values(), key=lambda j: j["createdAt"], reverse=True) if j["owner"] == owner]

    def get(self, owner, job_id):
        with self.lock:
            return self._view(self._own(owner, job_id))

    def create(self, owner, request):
        groups, seen = {}, set()
        for row in request.samples:
            normalized = normalize(row.text)
            if not normalized or normalized in seen:
                raise HTTPException(400, "样本不能为空或重复；重复样本会污染验证结果")
            seen.add(normalized)
            groups.setdefault(row.label, []).append(row.text)
        if not 2 <= len(groups) <= 12 or any(len(rows) < 4 for rows in groups.values()):
            raise HTTPException(400, "需要 2–12 个类别，每类至少 4 条不同样本")
        if sum(len(s.text) for s in request.samples) > 30000:
            raise HTTPException(400, "样本文字总量不能超过 30000 字")
        # All quotas and creation are serialized, including cross-account submissions.
        with self.lock:
            own = [j for j in self.jobs.values() if j["owner"] == owner]
            if owner in self.closed_owners:
                raise HTTPException(403, "此账号已注销")
            if len(own) >= 10 or any(j["status"] in ACTIVE for j in own):
                raise HTTPException(409, "每人最多保留 10 个任务，且同时只能训练一个；请先完成或删除旧任务")
            if len(self.jobs) >= 1000 or sum(j["status"] in ACTIVE for j in self.jobs.values()) >= 8:
                raise HTTPException(429, "训练队列或存储已满，请稍后重试")
            job_id = str(uuid.uuid4())
            job = dict(id=job_id, owner=owner, name=request.name, status="QUEUED", createdAt=time.time(),
                       epochs=request.epochs, learningRate=request.learningRate, seed=request.seed, architecture=request.architecture, validationFraction=request.validationFraction, epoch=0,
                       samples=len(request.samples), labels=sorted(groups), metrics=[], message="等待 CPU 训练资源")
            self.jobs[job_id] = job
            self.cancels[job_id] = threading.Event()
            self._save(job)
            (self.root / job_id / "dataset.json").write_text(request.model_dump_json(), encoding="utf-8")
            self.pool.submit(self._train, job_id, groups)
            return self._view(job)

    def _update(self, job_id, **changes):
        with self.lock:
            self.jobs[job_id].update(changes)
            self._save(self.jobs[job_id])

    def _train(self, job_id, groups):
        job, cancel = self.jobs[job_id], self.cancels[job_id]
        try:
            torch.manual_seed(job.get("seed", 42))
            rng = random.Random(job.get("seed", 42))
            train, validation = [], []
            for index, label in enumerate(job["labels"]):
                rows = list(groups[label]); rng.shuffle(rows)
                count = max(1, round(len(rows) * job.get("validationFraction", .2)))
                validation += [(text, index) for text in rows[:count]]
                train += [(text, index) for text in rows[count:]]
            x, y = features([t for t, _ in train]), torch.tensor([i for _, i in train])
            vx, vy = features([t for t, _ in validation]), torch.tensor([i for _, i in validation])
            model = classifier(len(groups), job.get("architecture", "mlp"))
            optimizer = torch.optim.Adam(model.parameters(), lr=job["learningRate"])
            loss_fn = torch.nn.CrossEntropyLoss()
            self._update(job_id, status="RUNNING", message="正在训练", trainSamples=len(train), validationSamples=len(validation))
            started = time.monotonic()
            split = {"train": [{"text": t, "label": job["labels"][i]} for t, i in train],
                     "validation": [{"text": t, "label": job["labels"][i]} for t, i in validation]}
            split_bytes = json.dumps(split, ensure_ascii=False, sort_keys=True).encode()
            (self.root / job_id / "split.json").write_bytes(split_bytes)
            self._update(job_id, datasetHash=hashlib.sha256(split_bytes).hexdigest())
            metrics = []
            best_loss, best_weights, best_epoch = float("inf"), None, 0
            for epoch in range(job["epochs"]):
                if cancel.is_set():
                    self._update(job_id, status="CANCELLED", message="已取消训练"); return
                if time.monotonic() - started > 300:
                    raise TimeoutError("Training exceeded five minutes")
                model.train(); total = 0
                order = torch.randperm(len(y))
                for indices in order.split(32):
                    optimizer.zero_grad()
                    loss = loss_fn(model(x[indices]), y[indices]); loss.backward(); optimizer.step()
                    total += loss.item() * len(indices)
                model.eval()
                with torch.inference_mode():
                    logits = model(vx)
                    prediction = logits.argmax(1)
                    accuracy = (prediction == vy).float().mean().item()
                    validation_loss = loss_fn(logits, vy).item()
                    matrix = [[int(((vy == a) & (prediction == b)).sum()) for b in range(len(groups))] for a in range(len(groups))]
                    f1s = []
                    for a in range(len(groups)):
                        tp = matrix[a][a]; fp = sum(row[a] for row in matrix)-tp; fn = sum(matrix[a])-tp
                        f1s.append(2*tp/(2*tp+fp+fn) if 2*tp+fp+fn else 0)
                    if validation_loss < best_loss:
                        best_loss, best_weights, best_epoch = validation_loss, copy.deepcopy(model.state_dict()), epoch+1
                        best_matrix, best_f1 = matrix, sum(f1s)/len(f1s)
                metrics.append(dict(epoch=epoch+1, loss=round(total/len(y), 6), accuracy=round(accuracy, 6), validationLoss=round(validation_loss, 6), macroF1=round(sum(f1s)/len(f1s), 6)))
                self._update(job_id, epoch=epoch+1, metrics=metrics)
            with self.lock:
                if cancel.is_set():
                    self._update(job_id, status="CANCELLED", message="已取消训练"); return
                torch.save(best_weights, self.root / job_id / "weights.pt")
                self._update(job_id, status="COMPLETED", bestEpoch=best_epoch, confusionMatrix=best_matrix, bestMacroF1=best_f1, message="训练完成，已保留验证损失最低的模型；验证集不是独立测试集")
        except Exception:
            self._update(job_id, status="FAILED", message="训练未完成，请检查样本后重试；单次训练限时 5 分钟")
        finally:
            with self.lock:
                self.cancels.pop(job_id, None)

    def cancel(self, owner, job_id):
        with self.lock:
            job = self._own(owner, job_id)
            if job["status"] in ACTIVE:
                self.cancels[job_id].set()
                self._update(job_id, status="CANCELLING", message="正在停止训练")
            return self._view(job)

    def delete(self, owner, job_id):
        with self.lock:
            job = self._own(owner, job_id)
            if job["status"] in ACTIVE:
                raise HTTPException(409, "请先取消训练，等待停止后再删除")
            shutil.rmtree(self.root / job_id)  # ID is generated internally, never taken as a path.
            del self.jobs[job_id]

    def purge_owner(self, owner):
        with self.lock:
            self.closed_owners.add(owner)
            temporary = self.root / "closed-owners.tmp"
            temporary.write_text(json.dumps(sorted(self.closed_owners)), encoding="utf-8")
            temporary.replace(self.closed_file)
            own = [j for j in self.jobs.values() if j["owner"] == owner]
            for job in own:
                if job["status"] in ACTIVE:
                    self.cancels[job["id"]].set()
            if any(j["status"] in ACTIVE for j in own):
                raise HTTPException(409, "正在停止训练，请稍后重试清理")
            for job in own:
                directory = self.root / job["id"]
                if directory.is_symlink() or directory.resolve().parent != self.root.resolve():
                    raise HTTPException(409, "训练目录无法安全清理")
                shutil.rmtree(directory)
                del self.jobs[job["id"]]

    def predict(self, owner, job_id, text):
        with self.lock:
            job = self._own(owner, job_id)
            if job["status"] != "COMPLETED":
                raise HTTPException(409, "训练完成后才能试用模型")
            model = classifier(len(job["labels"]), job.get("architecture", "mlp"))
            model.load_state_dict(torch.load(self.root / job_id / "weights.pt", map_location="cpu", weights_only=True))
            model.eval()
            with torch.inference_mode():
                scores = model(features([text])).softmax(1)[0].tolist()
            return sorted([dict(label=label, score=score) for label, score in zip(job["labels"], scores)], key=lambda row: -row["score"])

    def export(self, owner, job_id):
        with self.lock:
            job = self._own(owner, job_id)
            if job["status"] != "COMPLETED":
                raise HTTPException(409, "训练完成后才能下载模型")
            data = io.BytesIO()
            with zipfile.ZipFile(data, "w", zipfile.ZIP_DEFLATED) as archive:
                archive.write(self.root / job_id / "weights.pt", "weights.pt")
                archive.write(self.root / job_id / "dataset.json", "dataset.json")
                split_path = self.root / job_id / "split.json"
                if split_path.exists(): archive.write(split_path, "split.json")
                archive.writestr("training.json", json.dumps(self._view(job), ensure_ascii=False, indent=2))
                for filename in ("model.py", "predict.py", "MODEL_README.md"):
                    archive.write(Path(__file__).parent / filename, filename)
            return data.getvalue()
