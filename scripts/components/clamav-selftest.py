#!/usr/bin/env python3
"""Test actual clamd INSTREAM with harmless clean text and standard EICAR bytes.

No malware is downloaded, written to disk, or executed. EICAR is a standard
non-malicious antivirus test string. All requests remain on loopback.
"""
import argparse
import json
import socket
import struct


def request(port, command, payload=None):
    with socket.create_connection(("127.0.0.1", port), timeout=3) as stream:
        stream.settimeout(30)
        stream.sendall(b"z" + command.encode("ascii") + b"\0")
        if payload is not None:
            for offset in range(0, len(payload), 8192):
                chunk = payload[offset:offset + 8192]
                stream.sendall(struct.pack("!I", len(chunk)) + chunk)
            stream.sendall(struct.pack("!I", 0))
        result = bytearray()
        while not result.endswith(b"\0"):
            chunk = stream.recv(4096)
            if not chunk:
                break
            result.extend(chunk)
        return result.rstrip(b"\0").decode("utf-8")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=3310)
    parser.add_argument("--ping-only", action="store_true")
    args = parser.parse_args()
    ping = request(args.port, "PING")
    assert ping == "PONG", ping
    result = {"endpoint": f"127.0.0.1:{args.port}", "ping": ping,
              "version": request(args.port, "VERSION")}
    if not args.ping_only:
        eicar = (b"X5O!P%@AP[4\\PZX54(P^)7CC)7}$"
                 b"EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*")
        clean = request(args.port, "INSTREAM", b"Harmless local ClamAV integration test.\n")
        test = request(args.port, "INSTREAM", eicar)
        assert clean == "stream: OK", clean
        assert "FOUND" in test and "Eicar" in test, test
        result.update(clean=clean, eicar=test, passed=True)
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
