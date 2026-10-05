FROM python:3.12-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 OMP_NUM_THREADS=2 TRAINING_DATA=/data
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir torch==2.10.0 --index-url https://download.pytorch.org/whl/cpu \
    && pip install --no-cache-dir -r requirements.txt \
    && useradd --uid 10002 --create-home trainer && mkdir /data && chown trainer /data
COPY . .
RUN python -m unittest -v test_training
USER trainer
EXPOSE 8090
CMD ["uvicorn", "app:create_app", "--factory", "--host", "0.0.0.0", "--port", "8090", "--workers", "1", "--no-access-log", "--limit-concurrency", "16"]
