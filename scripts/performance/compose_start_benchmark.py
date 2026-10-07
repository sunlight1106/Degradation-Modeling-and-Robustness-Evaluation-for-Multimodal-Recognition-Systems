#!/usr/bin/env python3
"""Measure a real stop/start cycle of the current Compose installation, without rebuilding.

Requires --restart: this briefly interrupts the project. Does not stop Docker Desktop,
remove containers/volumes, change configuration, or invoke model providers.
"""
import argparse
import json
from pathlib import Path
import subprocess
import time
import urllib.request


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--restart', action='store_true', required=True)
    parser.add_argument('--output', required=True)
    parser.add_argument('--url', default='http://127.0.0.1:4173')
    args = parser.parse_args()
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    expected = subprocess.check_output(['docker', 'compose', 'config', '--services'], text=True).split()
    subprocess.run(['docker', 'compose', 'stop'], check=True)
    started = time.perf_counter()
    client = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    ready, web = {}, None
    with output.with_suffix('.log').open('w') as log:
        process = subprocess.Popen(['docker', 'compose', 'start', '--wait', '--wait-timeout', '600'], stdout=log, stderr=subprocess.STDOUT)
        while True:
            rows = subprocess.check_output(['docker', 'compose', 'ps', '--format', '{{.Service}}|{{.State}}|{{.Health}}'], text=True)
            for row in rows.splitlines():
                service, state, health = row.split('|')
                if service in expected and state == 'running' and health == 'healthy':
                    ready.setdefault(service, round(time.perf_counter() - started, 2))
            if web is None:
                try:
                    with client.open(args.url.rstrip('/') + '/api/v1/public/models', timeout=1) as response:
                        if response.status == 200:
                            web = round(time.perf_counter() - started, 2)
                except OSError:
                    pass
            if process.poll() is not None:
                break
            time.sleep(.5)
    report = {'method': 'Existing containers stopped then compose start --wait; Docker engine already running; no image build or download', 'allReadySeconds': round(time.perf_counter() - started, 2), 'apiSeconds': web, 'healthySeconds': ready, 'exitCode': process.returncode}
    output.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(report), flush=True)
    if process.returncode or set(ready) != set(expected):
        raise SystemExit('Startup incomplete; inspect the saved log. No volumes were removed.')


if __name__ == '__main__':
    main()
