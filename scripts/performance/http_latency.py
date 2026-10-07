#!/usr/bin/env python3
"""Measure local HTTP response times without invoking paid models or changing user content.

Uses the locally configured bootstrap account, creates one login session and revokes
it afterwards. Reports timing/status only; credentials and response bodies are never saved.
"""
import argparse
import gzip
import json
import math
from pathlib import Path
import re
import statistics
import time
import urllib.request


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True)
    parser.add_argument('--samples', type=int, default=15)
    args = parser.parse_args()
    if not 1 <= args.samples <= 100:
        parser.error('--samples must be between 1 and 100')
    env = {}
    for line in Path('.env').read_text(encoding='utf-8-sig').splitlines():
        if '=' in line and not line.lstrip().startswith('#'):
            key, value = line.split('=', 1)
            env[key.strip()] = value.strip().strip('"').strip("'")
    base = 'http://127.0.0.1:' + env.get('WEB_PORT', '4173')
    client = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    token = None

    def call(path, data=None):
        headers = {'Content-Type': 'application/json'}
        if token:
            headers['Authorization'] = 'Bearer ' + token
        request = urllib.request.Request(base + path, headers=headers,
            data=None if data is None else json.dumps(data).encode())
        started = time.perf_counter()
        with client.open(request, timeout=30) as response:
            body = response.read()
        return round((time.perf_counter() - started) * 1000, 2), body

    report = {'method': 'Sequential HTTP requests over loopback through the frontend proxy; first request separate from subsequent samples; no browser rendering or network latency', 'samplesPerEndpoint': args.samples}
    elapsed, body = call('/api/v1/auth/login', {
        'username': env.get('BOOTSTRAP_ADMIN_USERNAME', 'admin'),
        'password': env['BOOTSTRAP_ADMIN_PASSWORD']})
    token = json.loads(body)['data']['token']
    report['loginMs'] = elapsed
    try:
        for path in ['/', '/api/v1/dashboard/summary', '/api/v1/notes',
                     '/api/v1/knowledge/topics', '/api/v1/research/search?q=&type=ALL']:
            first, body = call(path)
            if path != '/' and not json.loads(body).get('success'):
                raise RuntimeError('Unsuccessful API response for ' + path)
            samples = [call(path)[0] for _ in range(args.samples)]
            report[path] = {'firstMs': first, 'medianMs': statistics.median(samples),
                            'p95Ms': sorted(samples)[math.ceil(.95 * len(samples)) - 1]}
        _, html = call('/')
        assets = []
        for path in re.findall(r'["\'](/assets/[^"\']+\.(?:js|css))["\']', html.decode()):
            _, original = call(path)
            request = urllib.request.Request(base + path, headers={'Accept-Encoding': 'gzip'})
            with client.open(request, timeout=30) as response:
                encoded = response.read()
                compressed = response.headers.get('Content-Encoding') == 'gzip'
            if (gzip.decompress(encoded) if compressed else encoded) != original:
                raise RuntimeError('Compressed asset differs from original: ' + path)
            assets.append({'path': path, 'originalBytes': len(original),
                           'transferredBytes': len(encoded), 'gzip': compressed})
        report['entryAssets'] = assets
    finally:
        call('/api/v1/account/logout', {})
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(report))


if __name__ == '__main__':
    main()
