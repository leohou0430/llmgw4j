#!/usr/bin/env python3
"""Verify administrative credential boundaries over HTTP on both engines."""
import argparse
import json
from pathlib import Path
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]


def verify(jar, engine, mode):
    full = 'admin-full-test' if mode == 'protected' else ''
    read = 'admin-read-test' if mode != 'demo' else ''
    with socket.socket() as listener:
        listener.bind(('127.0.0.1', 0))
        port = listener.getsockname()[1]
    with tempfile.TemporaryDirectory(prefix='llmgw-admin-auth-') as folder:
        log_path = Path(folder) / 'gateway.log'
        with log_path.open('w') as log:
            process = subprocess.Popen(['java', '-jar', str(jar),
                f'--spring.profiles.active={engine}', f'--server.port={port}',
                '--server.address=127.0.0.1', '--gateway.production=false',
                '--gateway.mock.enabled=false', f'--gateway.admin-token={full}',
                f'--gateway.admin-read-token={read}'], cwd=folder, stdout=log, stderr=subprocess.STDOUT)
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

        def request(path, expected, token=None, read_token=None, method='GET', body=None):
            headers = {'Content-Type': 'application/json'}
            if token is not None:
                headers['X-Admin-Token'] = token
            if read_token is not None:
                headers['X-Admin-Read-Token'] = read_token
            req = urllib.request.Request(f'http://127.0.0.1:{port}' + path,
                data=None if body is None else json.dumps(body).encode(), headers=headers, method=method)
            try:
                with opener.open(req, timeout=5) as response:
                    status = response.status
            except urllib.error.HTTPError as error:
                with error:
                    status = error.code
            assert status == expected, f'{engine}/{mode} {method} {path}: expected {expected}, got {status}'

        try:
            deadline = time.monotonic() + 45
            while time.monotonic() < deadline:
                if process.poll() is not None:
                    raise RuntimeError(log_path.read_text()[-4000:])
                try:
                    request('/actuator/health', 200)
                    break
                except (OSError, AssertionError):
                    time.sleep(.25)
            else:
                raise RuntimeError('gateway startup timeout: ' + log_path.read_text()[-4000:])
            request('/admin/console', 200)
            if mode == 'demo':
                request('/admin/audit/export', 200)
                request('/admin/routes', 200)
                request('/admin/cache', 200, method='DELETE')
            else:
                # Check mutations first: a read-only configuration must never enable demo writes.
                for token in [None, 'invalid', read]:
                    request('/admin/cache', 401, token=token, method='DELETE')
                    request('/admin/snapshot/load', 401, token=token, method='POST')
                    request('/admin/routes/reload', 401, token=token, method='POST', body=[])
                    request('/admin/routes', 401, token=token)
                request('/admin/cache', 401, read_token=read, method='DELETE')
                request('/admin/audit/export', 401)
                request('/admin/audit/export', 401, read_token='invalid')
                request('/admin/audit/export', 200, read_token=read)
                if mode == 'protected':
                    request('/admin/audit/export', 200, token=full)
                    request('/admin/routes', 200, token=full)
                    request('/admin/cache', 200, token=full, method='DELETE')
            print(f'PASS {engine}/{mode}: administrative credential boundaries', flush=True)
        finally:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path, default=ROOT/'llmgw-app/target/llmgw-app-0.2.0.jar')
    parser.add_argument('--engine', choices=['webflux', 'vt'])
    args = parser.parse_args()
    failures = []
    for engine in [args.engine] if args.engine else ['webflux', 'vt']:
        for mode in ['protected', 'read-only', 'demo']:
            try:
                verify(args.jar.resolve(), engine, mode)
            except Exception as error:
                failures.append(str(error))
                print(f'FAIL {engine}/{mode}: {error}', flush=True)
    return 1 if failures else 0


if __name__ == '__main__':
    raise SystemExit(main())
