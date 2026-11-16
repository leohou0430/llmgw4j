#!/usr/bin/env python3
"""Verify bounded upstream SSE lines through both actual HTTP engines."""
import argparse
from collections import Counter
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]


def free_port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


def verify(jar, engine):
    release = threading.Event()
    continue_mid = threading.Event()
    counts = Counter()
    frame = b'data: {"choices":[{"delta":{"content":"ok"}}]}\n\n'

    class Upstream(BaseHTTPRequestHandler):
        protocol_version = 'HTTP/1.1'

        def log_message(self, *_):
            pass

        def do_POST(self):
            self.close_connection = True
            body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
            case = body['messages'][0]['content']
            counts[case] += 1
            self.send_response(200)
            self.send_header('Content-Type', 'text/event-stream')
            self.send_header('Transfer-Encoding', 'chunked')
            self.send_header('Connection', 'close')
            self.end_headers()

            def chunk(value):
                self.wfile.write(f'{len(value):x}\r\n'.encode() + value + b'\r\n')
                self.wfile.flush()

            try:
                if case == 'mid':
                    chunk(frame)
                    if not continue_mid.wait(5):
                        return
                if case in ('oversize', 'mid'):
                    chunk(b'x' * 257)
                    # The gateway must fail while the upstream stays open.
                    release.wait(10)
                else:
                    # Exact 256-byte comment, CRLF, Unicode and total > max-response-bytes.
                    chunk(b':' + b'a' * 255 + b'\r\n\r\n')
                    for _ in range(100):
                        chunk(frame.replace(b'ok', '中🙂'.encode()))
                    chunk(b'data: [DONE]\n\n')
                self.wfile.write(b'0\r\n\r\n')
                self.wfile.flush()
            except (BrokenPipeError, ConnectionResetError):
                pass

    upstream = ThreadingHTTPServer(('127.0.0.1', 0), Upstream)
    thread = threading.Thread(target=upstream.serve_forever, daemon=True)
    thread.start()
    port = free_port()
    url = f'http://127.0.0.1:{upstream.server_port}/v1'
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with tempfile.TemporaryDirectory(prefix='llmgw-sse-line-') as folder:
        command = ['java', '-jar', str(jar), f'--spring.profiles.active={engine}',
                   f'--server.port={port}', '--server.address=127.0.0.1',
                   '--gateway.production=false', '--gateway.mock.enabled=false',
                   f'--gateway.routes[0].target-url={url}', '--gateway.routes[0].fallbacks=',
                   '--gateway.upstream.max-sse-line-bytes=256', '--gateway.max-response-bytes=64',
                   '--gateway.upstream.timeout-ms=5000', '--gateway.max-concurrent-requests=1']
        log_path = Path(folder) / 'gateway.log'
        process = None
        try:
            with log_path.open('w') as log:
                process = subprocess.Popen(command, cwd=folder, stdout=log, stderr=subprocess.STDOUT)
            deadline = time.monotonic() + 45
            while time.monotonic() < deadline:
                if process.poll() is not None:
                    raise RuntimeError('gateway exited during startup')
                try:
                    with opener.open(f'http://127.0.0.1:{port}/actuator/health', timeout=1) as response:
                        if response.status == 200:
                            break
                except OSError:
                    pass
                time.sleep(.25)
            else:
                raise RuntimeError('gateway startup timeout')

            def request(case):
                body = json.dumps({'model': 'm', 'stream': True,
                                   'messages': [{'role': 'user', 'content': case}]}).encode()
                return urllib.request.Request(f'http://127.0.0.1:{port}/v1/chat/completions', data=body,
                    headers={'Content-Type': 'application/json', 'Authorization': 'Bearer sk-demo'})

            start = time.monotonic()
            try:
                with opener.open(request('oversize'), timeout=2) as response:
                    raise AssertionError(f'expected 502, got {response.status}')
            except urllib.error.HTTPError as error:
                with error:
                    assert error.code == 502, error.code
                    assert b'upstream SSE line exceeds 256 bytes' in error.read()
            assert time.monotonic() - start < 3
            with opener.open(request('good'), timeout=2) as response:
                good = response.read()
                assert response.status == 200 and b'data: [DONE]' in good
                assert '中🙂'.encode() in good and len(good) > 4096
            with opener.open(request('mid'), timeout=2) as response:
                assert response.status == 200
                assert response.readline().startswith(b'data: ')
                continue_mid.set()
                rest = response.read()
                assert b'event: error' in rest and b'upstream SSE line exceeds 256 bytes' in rest, rest
                # Existing CHAT contract: error event, then one closing DONE; billed as failure.
                assert rest.count(b'data: [DONE]') == 1, rest
                assert rest.index(b'event: error') < rest.index(b'data: [DONE]'), rest
            with opener.open(request('recovery'), timeout=2) as response:
                assert response.status == 200 and b'[DONE]' in response.read()
            with opener.open(f'http://127.0.0.1:{port}/admin/snapshot', timeout=2) as response:
                usage = json.load(response)['usage']['demo']
                assert usage['errors'] == 2 and usage['requests'] == 4, usage
            assert counts['oversize'] == 1 and counts['mid'] == 1, counts
            print(f'PASS {engine}: early 502, CHAT error then one closing DONE, UTF-8, exact line limit, '
                  'long stream, capacity recovery and failure billing', flush=True)
        except Exception:
            print(log_path.read_text()[-5000:], flush=True)
            raise
        finally:
            release.set()
            continue_mid.set()
            if process is not None:
                process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()
            upstream.shutdown()
            upstream.server_close()
            thread.join(timeout=2)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--jar', type=Path, default=Path(os.environ.get(
        'LLMGW_TEST_JAR', ROOT / 'llmgw-app/target/llmgw-app-0.3.1.jar')))
    args = parser.parse_args()
    for engine in ('webflux', 'vt'):
        verify(args.jar.resolve(), engine)
