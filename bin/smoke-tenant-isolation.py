#!/usr/bin/env python3
"""Exercise tenant boundaries against both real data planes and the local mock."""
import json
import os
import threading
from concurrent.futures import ThreadPoolExecutor
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAR = Path(os.environ.get('LLMGW_TEST_JAR', str(ROOT / 'llmgw-app/target/llmgw-app-0.3.0.jar')))


def free_port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


def verify(engine):
    port, mock = free_port(), free_port()
    with tempfile.TemporaryDirectory(prefix='llmgw-tenant-') as folder:
        command = ['java', '-jar', str(JAR), f'--spring.profiles.active={engine}',
                   f'--server.port={port}', f'--gateway.mock.port={mock}',
                   f'--gateway.upstream.base-url=http://127.0.0.1:{mock}/mock/v1',
                   '--gateway.routes[0].id=mock', '--gateway.routes[0].model=*',
                   f'--gateway.routes[0].target-url=http://127.0.0.1:{mock}/mock/v1',
                   f'--gateway.cache.semantic-url=http://127.0.0.1:{mock}/mock/v1/embeddings',
                   '--gateway.cache.semantic-threshold=0.85',
                   '--gateway.max-concurrent-requests=1', '--gateway.mock.chunk-interval-ms=100']
        for index in range(2):
            command += [f'--gateway.api-keys[{index}].id=tenant-{index}',
                        f'--gateway.api-keys[{index}].token=test-{index}',
                        f'--gateway.api-keys[{index}].quota-tokens=1000000']
        with open(Path(folder) / 'gateway.log', 'w+') as log:
            process = subprocess.Popen(command, cwd=folder, stdout=log, stderr=subprocess.STDOUT)

            def request(path, method='GET', body=None, tenant=None, headers=None):
                req_headers = {'Content-Type': 'application/json', **(headers or {})}
                if tenant is not None:
                    req_headers['Authorization'] = f'Bearer test-{tenant}'
                payload = body if isinstance(body, bytes) else (json.dumps(body).encode() if body is not None else None)
                req = urllib.request.Request(f'http://127.0.0.1:{port}{path}', data=payload,
                                             headers=req_headers, method=method)
                try:
                    with urllib.request.urlopen(req, timeout=15) as response:
                        return response.status, response.read()
                except urllib.error.HTTPError as error:
                    return error.code, error.read()

            try:
                for _ in range(120):
                    if process.poll() is not None:
                        raise RuntimeError('gateway exited during startup')
                    try:
                        if request('/actuator/health')[0] == 200:
                            break
                    except OSError:
                        pass
                    time.sleep(0.25)
                else:
                    raise RuntimeError('gateway did not become healthy')
                chat = {'model': 'm', 'messages': [{'role': 'user', 'content': 'private prompt'}]}
                assert request('/v1/chat/completions', 'POST', chat, 0)[0] == 200
                # Drain the asynchronous cache writer before probing a different tenant/protocol.
                for _ in range(100):
                    if json.loads(request('/admin/snapshot')[1]).get('cacheSize', 0) >= 1:
                        break
                    time.sleep(0.02)
                status, body = request('/v1/responses', 'POST', {'model': 'n', 'input': 'private prompt'}, 1)
                assert status == 200 and json.loads(body).get('object') == 'response', body
                multipart = (b'--test\r\nContent-Disposition: form-data; name="purpose"\r\n\r\nbatch\r\n'
                             b'--test\r\nContent-Disposition: form-data; name="file"; filename="a.jsonl"\r\n'
                             b'Content-Type: application/json\r\n\r\n{}\r\n--test--\r\n')
                status, body = request('/v1/files', 'POST', multipart, 0,
                                       {'Content-Type': 'multipart/form-data; boundary=test'})
                assert status == 200, body
                resource = json.loads(body)['id']
                assert request('/v1/batches', 'POST', {'model': 'm', 'input_file_id': resource}, 1)[0] == 404
                for bypass in (False, True):
                    headers = {'X-LLMGW-No-Cache': '1'} if bypass else {}
                    status, body = request('/v1/batches', 'POST', {'model': 'm', 'input_file_id': resource}, 0, headers)
                    assert status == 200, body
                    task = json.loads(body)['id']
                    assert request(f'/v1/batches/{task}', tenant=0)[0] == 200
                    assert request(f'/v1/batches/{task}', tenant=1)[0] == 404
                    assert request(f'/v1/batches/{task}/cancel', 'POST', tenant=1)[0] == 404
                    assert request(f'/v1/batches/{task}/cancel', 'POST', tenant=0)[0] == 200
                assert request('/v1/batches/unknown', tenant=0)[0] == 404
                assert request('/v1/batches/unknown')[0] == 401
                assert request('/v1/files', tenant=0)[0] == 501
                assert request('/v1/batches', tenant=0)[0] == 501
                assert request(f'/v1/files/{resource}', tenant=0)[0] == 200
                assert request(f'/v1/files/{resource}', tenant=1)[0] == 404
                assert request(f'/v1/files/{resource}', 'DELETE', tenant=1)[0] == 404
                assert request(f'/v1/files/{resource}', 'DELETE', tenant=0)[0] == 200
                started = threading.Event()
                def hold_stream():
                    req = urllib.request.Request(f'http://127.0.0.1:{port}/v1/chat/completions',
                        data=json.dumps({**chat, 'stream': True}).encode(),
                        headers={'Content-Type': 'application/json', 'Authorization': 'Bearer test-0'})
                    with urllib.request.urlopen(req, timeout=15) as response:
                        response.read(1)
                        started.set()
                        return response.read()
                with ThreadPoolExecutor(max_workers=1) as executor:
                    pending = executor.submit(hold_stream)
                    assert started.wait(10), 'stream did not start'
                    assert request('/v1/chat/completions', 'POST', chat, 1)[0] == 503
                    pending.result(timeout=15)
                print(f'PASS {engine}: cache, task ownership, uploads, listing and concurrency boundaries', flush=True)
            except Exception:
                log.flush()
                log.seek(0)
                print(log.read()[-6000:])
                raise
            finally:
                process.terminate()
                try:
                    process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()


if __name__ == '__main__':
    for name in ('webflux', 'vt'):
        verify(name)
