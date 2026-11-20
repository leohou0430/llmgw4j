#!/usr/bin/env python3
"""Exercise HS256 JWT claim checks through both real data planes and a local mock."""
import argparse
import base64
import hashlib
import http.server
import threading
import hmac
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
SECRET = b'jwt-smoke-only-secret'


def port():
    with socket.socket() as listener:
        listener.bind(('127.0.0.1', 0))
        return listener.getsockname()[1]


def token(claims):
    encode = lambda value: base64.urlsafe_b64encode(json.dumps(value, separators=(',', ':')).encode()).rstrip(b'=')
    body = encode({'alg': 'HS256'}) + b'.' + encode(claims)
    return (body + b'.' + base64.urlsafe_b64encode(hmac.new(SECRET, body, hashlib.sha256).digest()).rstrip(b'=')).decode()


class LocalUpstream(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        request = json.loads(self.rfile.read(int(self.headers.get('Content-Length', 0))))
        if request.get('stream'):
            frames = [
                {'choices': [{'index': 0, 'delta': {'content': 'ok'}, 'finish_reason': None}]},
                {'choices': [{'index': 0, 'delta': {}, 'finish_reason': 'stop'}],
                 'usage': {'prompt_tokens': 1, 'completion_tokens': 1}},
            ]
            payload = (''.join('data: ' + json.dumps(frame) + '\n\n' for frame in frames) + 'data: [DONE]\n\n').encode()
            content_type = 'text/event-stream'
        else:
            payload = json.dumps({'choices': [{'message': {'role': 'assistant', 'content': 'ok'}, 'finish_reason': 'stop'}],
                                  'usage': {'prompt_tokens': 1, 'completion_tokens': 1}}).encode()
            content_type = 'application/json'
        self.send_response(200)
        self.send_header('Content-Type', content_type)
        self.send_header('Content-Length', str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, *args):
        pass


def verify(engine, jar, binary=False):
    # Keep the mock's port bound from allocation through the last request.
    mock = http.server.ThreadingHTTPServer(('127.0.0.1', 0), LocalUpstream)
    threading.Thread(target=mock.serve_forever, daemon=True).start()
    gateway_port, mock_port = port(), mock.server_port
    with tempfile.TemporaryDirectory(prefix='llmgw-jwt-auth-') as folder:
        log_path = Path(folder)/'gateway.log'
        command = ([str(jar)] if binary else ['java', '-jar', str(jar)]) + [
            f'--spring.profiles.active={engine}', f'--server.port={gateway_port}',
            '--server.address=127.0.0.1', '--gateway.production=false',
            '--gateway.mock.enabled=false',
            f'--gateway.routes[0].target-url=http://127.0.0.1:{mock_port}/mock/v1',
            f'--gateway.auth.jwt-secret={SECRET.decode()}',
            '--gateway.auth.jwt-issuer=jwt-smoke', '--gateway.auth.jwt-audience=gateway',
            '--gateway.api-keys[0].id=static-smoke', '--gateway.api-keys[0].token=static-smoke-key']
        with log_path.open('w') as log:
            process = subprocess.Popen(command, cwd=folder, stdout=log, stderr=subprocess.STDOUT)
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

        def request(path, signed=None, stream=False):
            data = None if signed is None else json.dumps({'model':'qwen-plus',
                'messages':[{'role':'user','content':'jwt smoke request'}],'stream':stream}).encode()
            headers = {'Content-Type':'application/json'}
            if signed is not None:
                headers['Authorization'] = 'Bearer '+signed
            req = urllib.request.Request(f'http://127.0.0.1:{gateway_port}'+path, data=data, headers=headers)
            try:
                with opener.open(req, timeout=10) as response:
                    return response.status, response.read()
            except urllib.error.HTTPError as error:
                with error:
                    return error.code, error.read()

        try:
            deadline = time.monotonic()+45
            while time.monotonic()<deadline:
                if process.poll() is not None:
                    raise RuntimeError(log_path.read_text()[-3000:])
                try:
                    if request('/actuator/health')[0]==200:break
                except OSError:pass
                time.sleep(.25)
            else:raise RuntimeError('JWT gateway startup timeout')
            base = {'iss':'jwt-smoke','sub':'caller','exp':int(time.time())+600,'aud':'gateway'}
            for claims in [base,{**base,'aud':['other','gateway']}]:
                for stream in [False,True]:
                    status,body=request('/v1/chat/completions',token(claims),stream)
                    assert status==200,(engine,status,body[:100])
                    if stream:assert b'data: [DONE]' in body and b'event: error' not in body
            invalid = [{**base,'aud':'other'},{k:v for k,v in base.items() if k!='aud'},
                {**base,'aud':['gateway',123]},{**base,'exp':None},{**base,'exp':'not-a-date'},
                {**base,'exp':int(time.time())},{**base,'nbf':'tomorrow'},
                {**base,'nbf':int(time.time())+600},{**base,'sub':123}]
            for claims in invalid:
                for stream in [False,True]:
                    status,_=request('/v1/chat/completions',token(claims),stream)
                    assert status==401,(engine,claims,status)
            assert request('/v1/chat/completions',token(base)+'.')[0]==401
            assert request('/v1/chat/completions','static-smoke-key')[0]==200
            print(f'PASS {engine}: JWT JSON/SSE claims, audience, expiry and static-key compatibility',flush=True)
        finally:
            process.terminate()
            try:process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill();process.wait()
            mock.shutdown()
            mock.server_close()


if __name__=='__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--jar', type=Path, default=Path(os.environ.get(
        'LLMGW_TEST_JAR', ROOT/'llmgw-app/target/llmgw-app-0.5.0.jar')))
    parser.add_argument('--binary', type=Path)
    parser.add_argument('--engine', choices=['webflux','vt'])
    args = parser.parse_args()
    jar = (args.binary or args.jar).resolve()
    for engine in [args.engine] if args.engine else ['webflux','vt']:
        verify(engine, jar, bool(args.binary))
