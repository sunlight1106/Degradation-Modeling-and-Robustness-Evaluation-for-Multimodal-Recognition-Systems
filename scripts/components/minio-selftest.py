#!/usr/bin/env python3
"""Initialize a private bucket and verify real S3 operations on local MinIO.

Uses Python's standard library and SigV4. Credentials must be in the environment.
Only loopback endpoints are accepted; no credential or object content is logged.
"""
import datetime
import hashlib
import hmac
import ipaddress
import json
import os
import urllib.error
import urllib.parse
import urllib.request
import uuid


def main():
    endpoint = os.environ.get("S3_ENDPOINT", "http://127.0.0.1:9000").rstrip("/")
    endpoint_parts = urllib.parse.urlsplit(endpoint)
    host = endpoint_parts.hostname
    if host != "localhost" and not ipaddress.ip_address(host).is_loopback:
        raise SystemExit("Self-test only permits a loopback endpoint")
    access = os.environ["MINIO_ROOT_USER"]
    secret = os.environ["MINIO_ROOT_PASSWORD"]
    bucket = os.environ.get("S3_BUCKET", "personal-platform")
    region = "us-east-1"
    # Loopback is local and must not transmit credentials to an HTTP proxy.
    client = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def request(method, path, content=b"", signed=True):
        url = endpoint + urllib.parse.quote(path, safe="/-_.~")
        parts = urllib.parse.urlsplit(url)
        timestamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
        day = timestamp[:8]
        payload_hash = hashlib.sha256(content).hexdigest()
        headers = {"Host": parts.netloc, "x-amz-date": timestamp,
                   "x-amz-content-sha256": payload_hash}
        if signed:
            signed_headers = "host;x-amz-content-sha256;x-amz-date"
            canonical_headers = (f"host:{parts.netloc}\n"
                                 f"x-amz-content-sha256:{payload_hash}\n"
                                 f"x-amz-date:{timestamp}\n")
            canonical_request = "\n".join((method, parts.path, "", canonical_headers,
                                            signed_headers, payload_hash))
            scope = f"{day}/{region}/s3/aws4_request"
            string_to_sign = "\n".join(("AWS4-HMAC-SHA256", timestamp, scope,
                                        hashlib.sha256(canonical_request.encode()).hexdigest()))
            key = ("AWS4" + secret).encode()
            for value in (day, region, "s3", "aws4_request"):
                key = hmac.new(key, value.encode(), hashlib.sha256).digest()
            signature = hmac.new(key, string_to_sign.encode(), hashlib.sha256).hexdigest()
            headers["Authorization"] = (f"AWS4-HMAC-SHA256 Credential={access}/{scope}, "
                                         f"SignedHeaders={signed_headers}, Signature={signature}")
        req = urllib.request.Request(url, data=content if method in ("PUT", "POST") else None,
                                     headers=headers, method=method)
        try:
            with client.open(req, timeout=15) as response:
                return response.status, response.read()
        except urllib.error.HTTPError as error:
            return error.code, error.read()

    status, body = request("PUT", "/" + bucket)
    if status not in (200, 409):
        raise RuntimeError(f"Bucket initialization failed: {status}")
    status, _ = request("HEAD", "/" + bucket)
    assert status == 200, f"Bucket not accessible: {status}"
    key = f"/{bucket}/component-selftest/{uuid.uuid4().hex}.txt"
    content = b"MinIO real object round-trip verification\n"
    try:
        status, _ = request("PUT", key, content)
        assert status == 200, f"Put failed: {status}"
        status, actual = request("GET", key)
        assert status == 200 and actual == content, "Object round-trip mismatch"
        anonymous_status, _ = request("GET", key, signed=False)
        assert anonymous_status == 403, f"Bucket allows unexpected anonymous read: {anonymous_status}"
    finally:
        delete_status, _ = request("DELETE", key)
        assert delete_status == 204, f"Self-test object cleanup failed: {delete_status}"
    print(json.dumps({"component": "MinIO", "endpoint": endpoint, "bucket": bucket,
                      "authenticated_put_get_delete": "passed", "anonymous_get": anonymous_status,
                      "test_object_deleted": True}, indent=2))


if __name__ == "__main__":
    main()
