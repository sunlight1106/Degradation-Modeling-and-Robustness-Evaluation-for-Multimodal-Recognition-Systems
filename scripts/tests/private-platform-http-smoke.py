#!/usr/bin/env python3
"""Packaged production JAR + disposable MySQL smoke. No provider or antivirus mocks.

Requires PRIVATE_PLATFORM_DISPOSABLE_MYSQL=1 and MYSQL_TEST_URL pointing to a
throwaway loopback MySQL server. Creates/drops a new random schema; never uses
an existing schema or app data. No network calls to AI providers are enabled.

Example (inside the disposable MySQL server's same execution namespace):
  PRIVATE_PLATFORM_DISPOSABLE_MYSQL=1 python3 scripts/tests/private-platform-http-smoke.py \
    --jar /path/to/fresh/personal-platform-backend-0.6.0.jar --report /tmp/jar-smoke.json

Successful recognition with an explicitly synthetic upstream/AV boundary is
covered separately by PersonalPlatformHttpIntegrationTest. This script verifies
that the real production JAR refuses external execution by default and refuses
recognition of an image whose antivirus status is SKIPPED.
"""
from __future__ import annotations

import argparse
import base64
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid


class SmokeFailure(RuntimeError):
    pass


def check(condition, label):
    if not condition:
        raise SmokeFailure(label)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", required=True, type=Path)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    check(os.environ.get("PRIVATE_PLATFORM_DISPOSABLE_MYSQL") == "1", "explicit disposable MySQL guard missing")
    jar = args.jar.resolve()
    check(jar.is_file(), "supplied JAR is missing")
    configured = os.environ.get("MYSQL_TEST_URL", "")
    check(configured.startswith("jdbc:mysql://"), "MYSQL_TEST_URL must be a MySQL URL")
    server = urllib.parse.urlsplit(configured[len("jdbc:"):])
    check(server.hostname in {"127.0.0.1", "localhost", "::1"}, "smoke requires disposable loopback MySQL")
    check(not server.username and not server.password, "database credentials must not appear in URL")
    mysql = shutil.which("mysql")
    if not mysql and os.environ.get("MYSQL_HOME"):
        mysql = str(Path(os.environ["MYSQL_HOME"]) / "bin/mysql")
    check(mysql and Path(mysql).is_file(), "mysql client unavailable")
    java = str(Path(os.environ.get("JAVA_HOME", "/workspace/shared/tooling/jdk17")) / "bin/java")
    check(Path(java).is_file(), "Java 17 runtime unavailable")
    schema = "rv_jar_http_" + uuid.uuid4().hex
    db_user = os.environ.get("MYSQL_TEST_USERNAME", "root")
    db_password = os.environ.get("MYSQL_TEST_PASSWORD", "")
    mysql_env = {key: value for key, value in os.environ.items() if key in {"PATH", "LD_LIBRARY_PATH", "HOME", "TMPDIR"}}
    mysql_env["MYSQL_PWD"] = db_password
    mysql_args = [mysql, "--no-defaults", "--protocol=TCP", "-h", server.hostname,
                  "-P", str(server.port or 3306), "-u", db_user, "--batch", "--skip-column-names"]

    def sql(statement):
        response = subprocess.run(mysql_args, input=statement, text=True, capture_output=True,
                                  env=mysql_env, timeout=30)
        check(response.returncode == 0, "disposable MySQL command failed")
        return response.stdout.strip()

    report = {"jar_sha256": hashlib.sha256(jar.read_bytes()).hexdigest(), "actual_packaged_jar": True,
              "actual_http": True, "database": None, "provider_calls": 0,
              "remote_execution": "production default disabled",
              "rate_limit": "enabled; bounded Redis-unavailable fallback permitted",
              "antivirus": "real disabled configuration; SKIPPED, not a clean scan",
              "synthetic_credentials_only": True, "requests": 0, "checks": [],
              "schema_dropped": False, "jar_stopped": False, "result": "FAIL"}
    process = None
    schema_created = False
    password = "SmokeTest9!" + secrets.token_hex(16)
    key_a, key_b = "synthetic-jar-a-" + secrets.token_hex(16), "synthetic-jar-b-" + secrets.token_hex(16)
    jwt_secret = secrets.token_hex(48)
    master_key = secrets.token_hex(48)
    protected_values = [password, key_a, key_b, jwt_secret, master_key]
    issued_tokens = []
    error = None

    with tempfile.TemporaryDirectory(prefix="rv-jar-http-") as work:
        workdir = Path(work)
        try:
            sql(f"CREATE DATABASE `{schema}` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;")
            schema_created = True
            report["database"] = sql("SELECT VERSION();")
            with socket.socket() as reserved:
                reserved.bind(("127.0.0.1", 0))
                port = reserved.getsockname()[1]
            base = f"http://127.0.0.1:{port}"
            # Keep ambient application credentials/settings out of this process entirely.
            env = {key: value for key, value in os.environ.items() if key in {"PATH", "JAVA_HOME", "LD_LIBRARY_PATH", "HOME", "TMPDIR"}}
            env.update({"DB_URL": f"jdbc:mysql://{server.netloc}/{schema}?{server.query}",
                        "DB_USERNAME": db_user, "DB_PASSWORD": db_password,
                        "JWT_SECRET": jwt_secret, "CREDENTIAL_MASTER_KEY": master_key,
                        "BOOTSTRAP_ADMIN_USERNAME": "synthetic_smoke_admin",
                        "BOOTSTRAP_ADMIN_PASSWORD": password,
                        "BOOTSTRAP_ADMIN_EMAIL": "synthetic_smoke_admin@example.invalid",
                        "BOOTSTRAP_TEST_PASSWORD": "", "STORAGE_MODE": "filesystem",
                        "STORAGE_ROOT": str(workdir / "uploads"), "LEGACY_STORAGE_ROOT": str(workdir / "uploads"),
                        "ANTIVIRUS_ENABLED": "false", "RATE_LIMIT_ENABLED": "true",
                        "MODEL_MODE": "demo", "DB_POOL_MAX": "4", "DB_POOL_MIN_IDLE": "1"})
            # PERSONAL_AI_REMOTE_ENABLED deliberately absent: test the shipped default.
            log = (workdir / "app.log").open("w")
            process = subprocess.Popen([java, "-Xmx512m", "-XX:MaxMetaspaceSize=256m", "-jar", str(jar),
                                        "--server.address=127.0.0.1", f"--server.port={port}"],
                                       cwd=workdir, env=env, stdout=log, stderr=subprocess.STDOUT)
            opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

            def http(method, path, token=None, body=None, expected=200, content_type="application/json"):
                payload = body if isinstance(body, bytes) else (json.dumps(body, ensure_ascii=False).encode() if body is not None else None)
                headers = {"Content-Type": content_type, "User-Agent": "SyntheticPrivatePlatformJarSmoke"}
                if token:
                    headers["Authorization"] = "Bearer " + token
                request = urllib.request.Request(base + path, data=payload, method=method, headers=headers)
                try:
                    response = opener.open(request, timeout=20)
                except urllib.error.HTTPError as failure:
                    response = failure
                with response:
                    status, text = response.status, response.read().decode()
                report["requests"] += 1
                check(not any(value in text for value in protected_values), "secret exposed by HTTP response")
                if path != "/api/v1/auth/login":
                    check(not any(value in text for value in issued_tokens), "session token exposed outside login")
                result = json.loads(text)
                code = result.get("error", {}).get("code", "") if isinstance(result.get("error"), dict) else ""
                check(status == expected, f"{method} {path}: expected {expected}, received {status}, error={code}")
                return result

            deadline = time.monotonic() + 150
            while True:
                check(process.poll() is None, "packaged JAR exited during startup")
                try:
                    http("GET", "/api/v1/public/models")
                    break
                except (urllib.error.URLError, TimeoutError, ConnectionError):
                    check(time.monotonic() < deadline, "packaged JAR startup timed out")
                    time.sleep(1)

            def data(method, path, token=None, body=None, expected=200):
                return http(method, path, token, body, expected).get("data")

            http("GET", "/api/v1/account/profile", expected=401)
            suffix = uuid.uuid4().hex[:8]
            accounts = []
            for name in ("a", "b"):
                username = f"jar_{name}_{suffix}"
                user = data("POST", "/api/v1/auth/register", body={"username": username,
                            "email": username + "@example.invalid", "password": password})
                login = data("POST", "/api/v1/auth/login", body={"username": username, "password": password})
                token = login["token"]
                check(bool(token), "login token missing")
                issued_tokens.append(token)
                accounts.append((user, token))
            (user_a, a), (user_b, b) = accounts
            check(data("GET", "/api/v1/account/profile", a)["id"] == user_a["id"], "profile owner mismatch")
            providers = data("GET", "/api/v1/personal-ai/providers", a)
            check(providers and all(not provider["remoteEnabled"] for provider in providers), "production default unexpectedly permits remote calls")
            for token, key in ((a, key_a), (b, key_b)):
                setting = data("PUT", "/api/v1/personal-ai/settings/OPENAI", token,
                               {"model": "synthetic-jar-model", "apiKey": key, "enabled": True})
                check(setting["configured"] and "apiKey" not in setting and "encryptedKey" not in setting, "setting leaked a credential field")
            cipher = sql(f"SELECT encrypted_key FROM `{schema}`.personal_ai_setting WHERE owner_id={int(user_a['id'])};")
            check(bool(cipher) and key_a not in cipher, "personal key was not encrypted at rest")
            report["checks"].append("registration/login/profile; distinct owner settings; encrypted key storage; no response secrets")

            preview = data("POST", "/api/v1/personal-ai/preview", a, {"provider": "OPENAI", "action": "draft",
                           "title": "Synthetic packaged JAR", "body": "Original test notebook text", "selectedTaskIds": []})
            check(preview["outboundBytes"] > 0 and preview["endpoint"] == "https://api.openai.com/v1/chat/completions", "text payload preview incorrect")
            denied = http("POST", "/api/v1/personal-ai/execute", a,
                          {"previewToken": preview["previewToken"], "confirmed": True}, 503)
            check(denied["error"]["code"] == "PERSONAL_AI_REMOTE_DISABLED", "remote text execution not refused")
            denied = http("POST", "/api/v1/personal-ai/recognition/execute", a,
                          {"previewToken": "synthetic-no-approved-image", "confirmed": True}, 503)
            check(denied["error"]["code"] == "PERSONAL_AI_REMOTE_DISABLED", "remote recognition execution not refused")
            report["checks"].append("shipped default refuses both text and recognition external execution")

            png = base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=")
            boundary = "SyntheticJarBoundary" + uuid.uuid4().hex
            multipart = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="synthetic.png"\r\nContent-Type: image/png\r\n\r\n'.encode()
                         + png + f"\r\n--{boundary}--\r\n".encode())
            file = http("POST", "/api/v1/files", a, multipart, 200, "multipart/form-data; boundary=" + boundary)["data"]
            check(file["scanStatus"] == "SKIPPED", "disabled scanner must not claim CLEAN")
            request = {"provider": "OPENAI", "fileId": file["id"], "taskType": "RECEIPT"}
            denied = http("POST", "/api/v1/personal-ai/recognition/preview", a, request, 400)
            check(denied["error"]["code"] == "RECOGNITION_REQUEST_INVALID", "unscanned image was not rejected")
            http("POST", "/api/v1/personal-ai/recognition/preview", b, request, 404)
            check(data("GET", "/api/v1/personal-ai/recognition/results", a) == [], "denied recognition unexpectedly saved a result")
            report["checks"].append("real PNG upload; SKIPPED scan accurately disclosed; unscanned recognition and foreign owner rejected")

            note = data("POST", "/api/v1/notes", a, {"title": "Original private note", "body": "Synthetic own note", "tags": "http,original"})
            path = "/api/v1/notes/" + note["id"]
            check(data("GET", path, a)["body"] == "Synthetic own note", "note read mismatch")
            http("GET", path, b, expected=404)
            http("PATCH", path, b, {"body": "foreign attempt"}, 404)
            http("DELETE", path, b, expected=404)
            check(data("PATCH", path, a, {"body": "Updated synthetic note"})["body"] == "Updated synthetic note", "note update mismatch")
            check(data("GET", "/api/v1/notes", b) == [], "foreign note visible")
            report["checks"].append("notebook create/read/update/delete authorization uses persisted ownership")

            terms = {"cobalt": "钴蓝色", "meadow": "草地", "lantern": "灯笼", "orbit": "轨道"}
            words = [{"term": term, "ipa": "/test/", "pos": "n.", "meaning": meaning,
                      "example": f"An original test sentence for {term}.", "exampleTranslation": "原创测试例句",
                      "distractors": [other for other in terms.values() if other != meaning]} for term, meaning in terms.items()]
            book = data("POST", "/api/v1/vocabulary/books/import", a, {"title": "Original JAR test vocabulary",
                        "description": "Four original synthetic test words", "attribution": "Original test fixtures", "rightsConfirmed": True, "words": words})
            bid = book["id"]
            denied = http("POST", "/api/v1/vocabulary/next", a, {"bookId": bid, "mode": "LEARN"}, 400)
            check(denied["error"]["code"] == "VOCAB_TIMEZONE_REQUIRED", "timezone not required")
            data("PUT", "/api/v1/vocabulary/settings", a, {"zoneId": "Etc/UTC", "dailyGoal": 1, "selectedBookId": bid})
            today = data("GET", "/api/v1/vocabulary/dashboard", a)["today"]["date"]
            tomorrow = (dt.date.fromisoformat(today) + dt.timedelta(days=1)).isoformat()
            http("GET", f"/api/v1/vocabulary/books/{bid}/words", b, expected=404)
            learned_term = None
            for count in range(1, 5):
                question = data("POST", "/api/v1/vocabulary/next", a, {"bookId": bid, "mode": "LEARN"})["question"]
                check("correctOptionId" not in question and "meaning" not in question, "question leaked its answer")
                learned_term = learned_term or question["term"]
                check(question["term"] == learned_term, "daily-goal one did not repeat same word")
                option = next(option["id"] for option in question["options"] if option["meaning"] == terms[question["term"]])
                answer_path = f"/api/v1/vocabulary/questions/{question['id']}/answer"
                if count == 1:
                    http("POST", answer_path, b, {"optionId": option}, 404)
                answer = data("POST", answer_path, a, {"optionId": option})
                check(answer["correct"] and answer["learningCorrect"] == count, "vocabulary credit mismatch")
                if count == 4:
                    check(answer["newlyLearned"] and answer["dueDate"] == tomorrow, "four-correct word was not scheduled tomorrow")
            dashboard = data("GET", "/api/v1/vocabulary/dashboard", a)
            check(dashboard["today"]["learned"] == 1 and dashboard["today"]["correct"] == 4, "daily vocabulary summary mismatch")
            check(data("POST", "/api/v1/vocabulary/next", a, {"bookId": bid, "mode": "LEARN"})["question"] is None, "daily goal not enforced")
            check(data("GET", "/api/v1/vocabulary/dashboard", b)["today"]["learned"] == 0, "vocabulary progress crossed owners")
            report["checks"].append("four original imported words; explicit UTC; four correct answers master one word; due tomorrow; owner isolation")

            usage = data("GET", "/api/v1/account/usage", a)
            check(usage["ai"]["total"] == 0 and usage["recognitionCount"] == 0, "disabled calls incorrectly logged as execution")
            check(usage["files"]["count"] == 1 and usage["noteCount"] == 1, "owner usage aggregates incorrect")
            check(data("GET", "/api/v1/account/usage", b)["noteCount"] == 0, "usage crossed owners")
            sessions = data("GET", "/api/v1/account/sessions", a)
            check(len(sessions) == 1 and sessions[0]["current"], "current session missing")
            http("DELETE", "/api/v1/account/sessions/" + sessions[0]["id"], b, {"currentPassword": password}, 404)
            data("DELETE", path, a)
            http("GET", path, a, expected=404)
            data("POST", "/api/v1/account/logout", a)
            http("GET", "/api/v1/account/profile", a, expected=401)
            http("POST", "/api/v1/account/logout", a, expected=401)
            data("GET", "/api/v1/account/profile", b)
            report["checks"].append("account aggregates; owner-only sessions; logout token replay refused; other account remains active")
            report["migrations"] = int(sql(f"SELECT COUNT(*) FROM `{schema}`.flyway_schema_history WHERE success=1;"))
            report["result"] = "PASS"
        except Exception as failure:
            error = f"{type(failure).__name__}: {failure}"
            for value in protected_values + issued_tokens:
                error = error.replace(value, "[REDACTED]")
            report["error"] = error
        finally:
            if process:
                if process.poll() is None:
                    process.terminate()
                    try:
                        process.wait(timeout=25)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait(timeout=10)
                report["jar_stopped"] = process.poll() is not None
                log.close()
                log_text = (workdir / "app.log").read_text(errors="replace")
                if any(value in log_text for value in protected_values + issued_tokens):
                    report["result"] = "FAIL"
                    report["error"] = "Synthetic credential/session token appeared in app logs; values withheld"
            if schema_created:
                try:
                    sql(f"DROP DATABASE `{schema}`;")
                    report["schema_dropped"] = True
                except Exception:
                    report["result"] = "FAIL"
                    report["error"] = "Failed to drop owned disposable schema " + schema
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if report["result"] == "PASS" else 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except SmokeFailure as failure:
        print("Packaged JAR smoke refused: " + str(failure), file=sys.stderr)
        sys.exit(2)
