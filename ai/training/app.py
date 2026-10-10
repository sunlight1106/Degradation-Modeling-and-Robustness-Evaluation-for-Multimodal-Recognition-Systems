import hmac
import os
import re
from contextlib import asynccontextmanager
from typing import Annotated, Literal

import torch
from fastapi import Depends, FastAPI, Header, HTTPException, Request, Response
from pydantic import BaseModel, ConfigDict, Field, StringConstraints
from engine import Engine

Text = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=1000)]
Label = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=40)]


class Sample(BaseModel):
    model_config = ConfigDict(extra="forbid")
    text: Text
    label: Label


class TrainingRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    name: Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=80)]
    samples: list[Sample] = Field(min_length=8, max_length=500)
    epochs: int = Field(default=15, ge=1, le=50, strict=True)
    architecture: Literal["mlp", "linear"] = "mlp"
    seed: int = Field(default=42, ge=0, le=2147483647, strict=True)
    validationFraction: float = Field(default=.2, ge=.15, le=.4, allow_inf_nan=False)
    learningRate: float = Field(default=.01, ge=.0001, le=.05, allow_inf_nan=False)


class Prediction(BaseModel):
    model_config = ConfigDict(extra="forbid")
    text: Text


def create_app(root=None, token=None):
    secret = token if token is not None else os.environ.get("TRAINING_SERVICE_TOKEN", "")
    @asynccontextmanager
    async def lifespan(application):
        if len(secret) < 32:
            raise RuntimeError("TRAINING_SERVICE_TOKEN must contain at least 32 characters")
        yield
        application.state.engine.pool.shutdown(wait=True, cancel_futures=False)

    application = FastAPI(lifespan=lifespan, docs_url=None, redoc_url=None, openapi_url=None)
    application.state.engine = Engine(root or os.environ.get("TRAINING_DATA", "/data"))
    engine = application.state.engine

    def owner(authorization: str = Header(default=""), x_training_owner: str = Header(default="")):
        if len(secret) < 32 or not hmac.compare_digest(authorization, "Bearer " + secret):
            raise HTTPException(401, "训练服务认证失败")
        if not re.fullmatch(r"[1-9][0-9]{0,18}", x_training_owner):
            raise HTTPException(401, "缺少用户身份")
        return x_training_owner

    @application.middleware("http")
    async def limit_body(request: Request, call_next):
        if request.method in {"POST", "PUT"}:
            body = bytearray()
            async for part in request.stream():
                body.extend(part)
                if len(body) > 160000:
                    return Response(status_code=413)
            request._body = bytes(body)
        return await call_next(request)

    @application.get("/health")
    def health():
        return {"status": "ok"}

    @application.get("/environment")
    def environment(user=Depends(owner)):
        return dict(ready=True, framework="PyTorch", version=torch.__version__, device="CPU", template="文本分类 · 线性分类器 / 双层神经网络", maxSamples=500, maxEpochs=50)

    @application.get("/jobs")
    def jobs(user=Depends(owner)): return engine.list(user)

    @application.post("/jobs")
    def create(request: TrainingRequest, user=Depends(owner)): return engine.create(user, request)

    @application.get("/jobs/{job_id}")
    def get(job_id: str, user=Depends(owner)): return engine.get(user, job_id)

    @application.post("/jobs/{job_id}/cancel")
    def cancel(job_id: str, user=Depends(owner)): return engine.cancel(user, job_id)

    @application.delete("/jobs/{job_id}")
    def delete(job_id: str, user=Depends(owner)):
        engine.delete(user, job_id); return None

    @application.delete("/owner")
    def purge_owner(user=Depends(owner)):
        engine.purge_owner(user); return None

    @application.post("/jobs/{job_id}/predict")
    def predict(job_id: str, request: Prediction, user=Depends(owner)): return engine.predict(user, job_id, request.text)

    @application.get("/jobs/{job_id}/download")
    def download(job_id: str, user=Depends(owner)):
        return Response(engine.export(user, job_id), media_type="application/zip")
    return application
