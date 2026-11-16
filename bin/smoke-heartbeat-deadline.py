#!/usr/bin/env python3
"""Heartbeat must not commit HTTP before successful upstream response headers."""
import argparse
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


def verify(jar, engine):
    frame = b'data: {"choices":[{"delta":{"content":"ok"}}]}\n\ndata: [DONE]\n\n'

    class Upstream(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def do_POST(self):
            case = json.loads(self.rfile.read(int(self.headers['Content-Length'])))['messages'][0]['content']
            try:
                if case == 'headers':
                    time.sleep(1)
                self.send_response(503 if case == 'status' else 200)
                self.send_header('Content-Type', 'text/event-stream')
                self.end_headers()
                self.wfile.flush()
                if case == 'status':
                    self.wfile.write(b'x'); self.wfile.flush(); time.sleep(.75)
                else:
                    if case == 'idle':
                        time.sleep(.3)
                    self.wfile.write(frame)
                self.wfile.flush()
            except (BrokenPipeError, ConnectionResetError):
                pass

    upstream = ThreadingHTTPServer(('127.0.0.1', 0), Upstream)
    thread = threading.Thread(target=upstream.serve_forever, daemon=True); thread.start()
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0)); port = sock.getsockname()[1]
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with tempfile.TemporaryDirectory(prefix='llmgw-heartbeat-') as folder:
        log_path = Path(folder) / 'gateway.log'
        command = ['java', '-jar', str(jar), f'--spring.profiles.active={engine}',
                   f'--server.port={port}', '--server.address=127.0.0.1', '--gateway.production=false',
                   '--gateway.mock.enabled=false', f'--gateway.routes[0].target-url=http://127.0.0.1:{upstream.server_port}/v1',
                   '--gateway.routes[0].fallbacks=', '--gateway.upstream.heartbeat-interval-ms=40',
                   '--gateway.upstream.first-byte-timeout-ms=500', '--gateway.upstream.timeout-ms=2000',
                   '--gateway.max-concurrent-requests=1']
        process = None
        try:
            with log_path.open('w') as log:
                process = subprocess.Popen(command, cwd=folder, stdout=log, stderr=subprocess.STDOUT)
            for _ in range(180):
                if process.poll() is not None:
                    raise RuntimeError('gateway exited')
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
                req = urllib.request.Request(f'http://127.0.0.1:{port}/v1/chat/completions', data=body,
                    headers={'Content-Type': 'application/json', 'Authorization': 'Bearer sk-demo'})
                try:
                    response = opener.open(req, timeout=3)
                except urllib.error.HTTPError as error:
                    response = error
                with response:
                    return response.status, response.headers['Content-Type'], response.read()

            for case, message in [('headers', b'upstream first-byte timeout'), ('status', b'upstream status 503')]:
                status, content_type, body = request(case)
                assert status == 502 and 'application/json' in content_type and message in body, (case, status, content_type, body)
                assert b': keep-alive' not in body
            status, content_type, body = request('idle')
            assert status == 200 and 'text/event-stream' in content_type, (status, content_type, body)
            assert body.count(b': keep-alive') >= 2 and body.count(b'data: [DONE]') == 1, body
            assert b'event: error' not in body
            assert request('good')[0] == 200
            with opener.open(f'http://127.0.0.1:{port}/admin/snapshot', timeout=2) as response:
                usage = json.load(response)['usage']['demo']
                assert usage['errors'] == 2 and usage['requests'] == 4, usage
            print(f'PASS {engine}: heartbeat preserves first-byte/status 502, idle SSE, recovery and failure billing', flush=True)
        except Exception:
            print(log_path.read_text()[-4000:], flush=True)
            raise
        finally:
            if process is not None:
                process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill(); process.wait()
            upstream.shutdown(); upstream.server_close(); thread.join(timeout=2)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--jar', type=Path, default=Path(os.environ.get(
        'LLMGW_TEST_JAR', ROOT / 'llmgw-app/target/llmgw-app-0.3.1.jar')))
    parser.add_argument('--engine', choices=['webflux', 'vt'])
    args = parser.parse_args()
    for engine in ([args.engine] if args.engine else ['webflux', 'vt']):
        verify(args.jar.resolve(), engine)
