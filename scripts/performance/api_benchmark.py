#!/usr/bin/env python3
"""Read-only authenticated HTTP benchmark; preserves rate limits and reports failures."""
import argparse
import datetime as dt
from concurrent.futures import ThreadPoolExecutor
import http.client
import json
import itertools
import math
import os
import platform
import statistics
import sys
import time
import threading
import urllib.parse
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default='http://127.0.0.1:8080')
    parser.add_argument('--label', required=True)
    parser.add_argument('--output', required=True)
    parser.add_argument('--samples', type=int, default=15)
    parser.add_argument('--warmup', type=int, default=3)
    parser.add_argument('--concurrency', type=int, default=1,
                        help='Connections/workers (1–32); warmup becomes requests per worker')
    parser.add_argument('--interval', type=float, default=0.65,
                        help='Pause between requests; default stays below the stock 120/minute limit')
    parser.add_argument('--username', default='admin')
    parser.add_argument('--password-env', default='PERF_PASSWORD')
    parser.add_argument('--token-env', default='PERF_TOKEN')
    parser.add_argument('--path', action='append', dest='paths')
    args = parser.parse_args()
    if args.samples < 1 or args.warmup < 0 or args.interval < 0 or not 1 <= args.concurrency <= 32:
        parser.error('samples must be positive; warmup/interval nonnegative; concurrency 1–32')
    url = urllib.parse.urlsplit(args.base_url)
    if url.scheme not in ('http', 'https') or not url.hostname or url.username or url.password:
        parser.error('base-url must be an HTTP(S) origin without embedded credentials')
    if url.path not in ('', '/') or url.query or url.fragment:
        parser.error('base-url must be an origin, without path/query/fragment')
    paths = args.paths or ['/api/v1/dashboard/summary', '/api/v1/knowledge/topics',
                          '/api/v1/knowledge/entries', '/api/v1/workspaces']
    if any(not path.startswith('/api/') or '\r' in path or '\n' in path for path in paths):
        parser.error('benchmark paths must be local /api/ paths')
    connection_type = http.client.HTTPSConnection if url.scheme == 'https' else http.client.HTTPConnection
    thread_local = threading.local()
    connections = []
    connection_lock = threading.Lock()

    def get_connection():
        if not hasattr(thread_local, 'connection'):
            thread_local.connection = connection_type(url.hostname, url.port, timeout=30)
            with connection_lock:
                connections.append(thread_local.connection)
        return thread_local.connection

    def request(method, path, token='', body=None):
        connection = get_connection()
        headers = {'Accept': 'application/json'}
        if token:
            headers['Authorization'] = 'Bearer ' + token
        payload = None if body is None else json.dumps(body).encode()
        if payload is not None:
            headers['Content-Type'] = 'application/json'
        start = time.perf_counter_ns()
        connection.request(method, path, payload, headers)
        response = connection.getresponse()
        raw = response.read()
        elapsed = (time.perf_counter_ns() - start) / 1e6
        try:
            parsed = json.loads(raw)
        except (json.JSONDecodeError, UnicodeDecodeError):
            parsed = None
        return response.status, elapsed, raw, parsed, response.getheader('X-RateLimit-Status')

    token = os.environ.get(args.token_env, '')
    if not token:
        password = os.environ.get(args.password_env, '')
        if not password:
            parser.error('provide token or password through the named environment variable; neither is logged')
        status, _, _, login, _ = request('POST', '/api/v1/auth/login', body={'username': args.username, 'password': password})
        if status != 200 or not isinstance(login, dict):
            raise RuntimeError(f'Login failed with HTTP{status}; credentials/body omitted')
        token = login.get('data', {}).get('token', '')
        if not token:
            raise RuntimeError('Login returned no token; body omitted')
    output = {
        'label': args.label, 'started_at_utc': dt.datetime.now(dt.timezone.utc).isoformat(),
        'base_url': args.base_url, 'client': 'Python stdlib HTTP/1.1 reusable connection',
        'python': platform.python_version(), 'warmup_per_worker': args.warmup,
        'concurrency': args.concurrency, 'samples': args.samples,
        'interval_seconds': args.interval, 'auth_and_rate_limit': 'unchanged', 'endpoints': []
    }
    failures = False
    executor = ThreadPoolExecutor(max_workers=args.concurrency) if args.concurrency > 1 else None

    def batches(count, path):
        remaining = count
        while remaining:
            batch_size = min(args.concurrency, remaining)
            if executor is None:
                responses = [request('GET', path, token=token)]
            else:
                responses = list(executor.map(lambda _: request('GET', path, token=token), range(batch_size)))
            yield from responses
            # Pace aggregate request volume, not each thread independently. Security stays enabled.
            time.sleep(args.interval * batch_size)
            remaining -= batch_size

    try:
        for path in paths:
            samples = []
            statuses = {}
            sizes = []
            degraded = False
            shape = None
            warmup_failures = []
            warmup_count = args.warmup * args.concurrency
            # Complete warmup before measurement, then reuse the same worker connections.
            responses = ((True, response) for response in batches(warmup_count, path))
            responses = itertools.chain(responses, ((False, response) for response in batches(args.samples, path)))
            for is_warmup, response in responses:
                status, elapsed, raw, parsed, limit_status = response
                success = status == 200 and isinstance(parsed, dict) and parsed.get('success') is True
                degraded |= limit_status == 'degraded'
                if is_warmup:
                    if not success:
                        warmup_failures.append(status)
                else:
                    samples.append(elapsed)
                    statuses[str(status)] = statuses.get(str(status), 0) + 1
                    sizes.append(len(raw))
                failures |= not success
                if success:
                    data = parsed.get('data')
                    shape = {'items': len(data)} if isinstance(data, list) else {
                        key: value for key, value in (data.items() if isinstance(data, dict) else [])
                        if key in ('totalTasks', 'completedTasks', 'failedTasks', 'successRate', 'averageConfidenceLift')
                    }
            ordered = sorted(samples)
            output['endpoints'].append({
                'path': path, 'median_ms': round(statistics.median(samples), 3),
                'p95_ms': round(ordered[max(0, math.ceil(len(ordered) * .95) - 1)], 3),
                'min_ms': round(min(samples), 3), 'max_ms': round(max(samples), 3),
                'statuses': statuses, 'response_bytes_median': statistics.median(sizes),
                'warmup_failures': warmup_failures, 'rate_limit_degraded': degraded,
                'data_shape': shape, 'samples_ms': [round(value, 3) for value in samples]
            })
    finally:
        if executor is not None:
            executor.shutdown(wait=True)
        for connection in connections:
            connection.close()
        output['finished_at_utc'] = dt.datetime.now(dt.timezone.utc).isoformat()
        output['all_requests_successful'] = not failures and len(output['endpoints']) == len(paths)
        Path(args.output).parent.mkdir(parents=True, exist_ok=True)
        Path(args.output).write_text(json.dumps(output, indent=2) + '\n')
    for result in output['endpoints']:
        print(f"{args.label} {result['path']}: median={result['median_ms']}ms p95={result['p95_ms']}ms statuses={result['statuses']} degraded={result['rate_limit_degraded']}")
    return 1 if failures else 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (OSError, http.client.HTTPException, RuntimeError) as error:
        # Never print request bodies/headers, which could contain authentication secrets.
        print(f'Benchmark failed: {type(error).__name__}: {error}', file=sys.stderr)
        sys.exit(2)
