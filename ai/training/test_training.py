import io
import tempfile
import time
import unittest
import zipfile
from fastapi.testclient import TestClient
from app import create_app

TOKEN = "synthetic-test-token-with-no-real-credentials"


def data():
    return dict(name="分类测试", epochs=4, learningRate=.01, samples=[
        {"text": f"{'水果苹果香蕉' if i < 8 else '计算机编程代码'} 样本{i}", "label": "水果" if i < 8 else "技术"}
        for i in range(16)])


class TrainingTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.application = create_app(self.directory.name, TOKEN)
        self.client = TestClient(self.application)
        self.client.__enter__()
        self.headers = {"Authorization": "Bearer " + TOKEN, "X-Training-Owner": "1"}

    def tearDown(self):
        self.client.__exit__(None, None, None)
        self.directory.cleanup()

    def call(self, method, path, **kwargs):
        return self.client.request(method, path, headers=kwargs.pop("headers", self.headers), **kwargs)

    def complete(self, job_id):
        for _ in range(300):
            job = self.call("GET", "/jobs/" + job_id).json()
            if job["status"] not in {"QUEUED", "RUNNING", "CANCELLING"}: return job
            time.sleep(.02)
        self.fail("Training timed out")

    def test_linear_architecture_reproducible_split_and_metrics(self):
        payload=data();payload.update(architecture="linear",seed=73,validationFraction=.3)
        first=self.call("POST","/jobs",json=payload).json()
        job=self.complete(first["id"])
        self.assertEqual(job["status"],"COMPLETED")
        self.assertEqual(job["architecture"],"linear")
        self.assertEqual(len(job["confusionMatrix"]),2)
        self.assertGreaterEqual(job["bestMacroF1"],0)
        self.assertLessEqual(job["bestMacroF1"],1)
        self.assertGreaterEqual(job["bestEpoch"],1)
        second=self.call("POST","/jobs",json=payload).json()
        self.assertEqual(self.complete(second["id"])["datasetHash"],job["datasetHash"])
        package=self.call("GET","/jobs/"+job["id"]+"/download").content
        self.assertIn("split.json",zipfile.ZipFile(io.BytesIO(package)).namelist())

    def test_real_training_prediction_export_and_cross_owner_isolation(self):
        self.assertEqual(self.client.get("/jobs").status_code, 401)
        job = self.call("POST", "/jobs", json=data()).json()
        other = {**self.headers, "X-Training-Owner": "2"}
        for method, suffix, payload in [("GET", "", None), ("DELETE", "", None), ("POST", "/cancel", None),
                                         ("POST", "/predict", {"text": "hello"}), ("GET", "/download", None)]:
            self.assertEqual(self.call(method, "/jobs/"+job["id"]+suffix, headers=other, json=payload).status_code, 404)
        self.assertEqual(self.call("GET", "/jobs", headers=other).json(), [])
        result = self.complete(job["id"])
        self.assertEqual(result["status"], "COMPLETED")
        self.assertEqual(len(result["metrics"]), 4)
        self.assertEqual(result["trainSamples"] + result["validationSamples"], 16)
        prediction = self.call("POST", "/jobs/"+job["id"]+"/predict", json={"text": "计算机代码编程"}).json()
        self.assertEqual({row["label"] for row in prediction}, {"水果", "技术"})
        self.assertAlmostEqual(sum(row["score"] for row in prediction), 1, places=5)
        bundle = self.call("GET", "/jobs/"+job["id"]+"/download")
        with zipfile.ZipFile(io.BytesIO(bundle.content)) as archive:
            self.assertIn("weights.pt", archive.namelist())
            self.assertNotIn("owner", archive.read("training.json").decode())
        self.assertEqual(self.call("DELETE", "/jobs/"+job["id"]).status_code, 200)
        self.assertEqual(self.call("GET", "/jobs").json(), [])

    def test_validation_duplicates_code_and_oversized_requests(self):
        for change in ({"epochs": 51}, {"samples": [{"text": "x", "label": "a"}]*8}, {"owner": "2"}, {"script": "import os"}):
            self.assertGreaterEqual(self.call("POST", "/jobs", json={**data(), **change}).status_code, 400)
        request = data(); request["samples"][1]["text"] = request["samples"][0]["text"]
        self.assertEqual(self.call("POST", "/jobs", json=request).status_code, 400)
        self.assertEqual(self.call("POST", "/jobs", content=b"x"*160001).status_code, 413)

    def test_cancel_and_restart_recovery(self):
        # Occupy the executor so cancellation reliably happens while queued.
        import threading
        gate = threading.Event()
        self.application.state.engine.pool.submit(gate.wait, 3)
        job = self.call("POST", "/jobs", json=data()).json()
        self.assertEqual(self.call("POST", "/jobs", json=data()).status_code, 409)
        self.call("POST", "/jobs/"+job["id"]+"/cancel")
        gate.set()
        self.assertEqual(self.complete(job["id"])["status"], "CANCELLED")
        engine = self.application.state.engine
        engine._update(job["id"], status="RUNNING")
        from engine import Engine
        restored = Engine(self.directory.name)
        self.assertEqual(restored.get("1", job["id"])["status"], "FAILED")
        restored.pool.shutdown()


if __name__ == "__main__": unittest.main()
