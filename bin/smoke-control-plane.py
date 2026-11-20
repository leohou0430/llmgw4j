#!/usr/bin/env python3
"""Real HTTP control-plane acceptance; isolated SQLite, two engines, local upstreams only."""
import argparse
import base64
import secrets
import hashlib
import http.cookiejar
import http.server
import json
import os
from pathlib import Path
import socket
import sqlite3
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
PASSWORD = 'control-smoke-password-2026'


def free_port():
    with socket.socket() as listener:
        listener.bind(('127.0.0.1', 0))
        return listener.getsockname()[1]


class Upstream(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        raw = self.rfile.read(int(self.headers.get('Content-Length', 0)))
        if self.path.endswith('/files'):
            self.respond({'id': 'file-' + self.server.marker, 'object': 'file', 'source': self.server.marker})
            return
        if b'inflight-probe' in raw:
            self.server.entered.set()
            self.server.release.wait(30)
        payload = json.dumps({'id': 'chat-smoke', 'object': 'chat.completion', 'model': 'm',
            'choices': [{'index': 0, 'message': {'role': 'assistant', 'content': self.server.marker},
                         'finish_reason': 'stop'}], 'usage': {'prompt_tokens': 1, 'completion_tokens': 1, 'total_tokens': 2}}).encode()
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def respond(self, body):
        payload = json.dumps(body).encode()
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def do_GET(self):
        self.respond({'id': self.path.rsplit('/', 1)[-1], 'object': 'file', 'source': self.server.marker})

    def log_message(self, *args):
        pass


class Client:
    def __init__(self, port):
        self.port = port
        self.csrf = ''
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}),
            urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))

    def request(self, path, method='GET', body=None, expected=200, token=None):
        headers = {'Content-Type': 'multipart/form-data; boundary=probe' if isinstance(body, bytes) else 'application/json', 'X-CSRF-Token': self.csrf}
        if token:
            headers['Authorization'] = 'Bearer ' + token
        request = urllib.request.Request(f'http://127.0.0.1:{self.port}' + path,
            data=None if body is None else body if isinstance(body, bytes) else json.dumps(body).encode(), method=method, headers=headers)
        try:
            response = self.opener.open(request, timeout=15)
        except urllib.error.HTTPError as failure:
            response = failure
        with response:
            status, raw = response.status, response.read()
            assert status == expected, f'{method} {path}: expected {expected}, got {status}: {raw[:500]!r}'
            return json.loads(raw) if raw else {}

    def login(self, username='platform'):
        # The first administrator is seeded by an ApplicationRunner, which runs
        # AFTER the web server reports healthy; retry briefly on 401.
        deadline = time.time() + 10
        while True:
            try:
                self.csrf = self.request('/control/login', 'POST', {'username': username, 'password': PASSWORD})['csrfToken']
                break
            except AssertionError as failure:
                if 'got 401' not in str(failure) or time.time() >= deadline:
                    raise
                time.sleep(0.25)
        return self.request('/control/session')['account']

    def change(self, path, body, expected=200):
        return self.request('/control' + path, 'POST', body, expected)


def verify(jar, engine, binary=False):
    with tempfile.TemporaryDirectory(prefix='llmgw-control-smoke-') as folder:
        directory = Path(folder)
        upstreams = []
        for marker in ('source-A', 'source-B'):
            server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Upstream)
            server.marker = marker
            server.entered = threading.Event()
            server.release = threading.Event()
            threading.Thread(target=server.serve_forever, daemon=True).start()
            upstreams.append(server)
        urls = [f'http://127.0.0.1:{server.server_port}/v1' for server in upstreams]
        port, mock = free_port(), free_port()
        database = directory / 'control.db'
        environment = {**os.environ, 'LLMGW_ADMIN_USERNAME': 'platform', 'LLMGW_ADMIN_PASSWORD': PASSWORD,
                       'LLMGW_CONTROL_DB': str(database), 'LLMGW_ROOT_KEY': base64.b64encode(secrets.token_bytes(32)).decode()}
        command = ([str(jar)] if binary else ['java', '-jar', str(jar)]) + [f'--spring.profiles.active={engine}', f'--server.port={port}',
            '--server.address=127.0.0.1', '--gateway.production=false', '--gateway.mock.enabled=true',
            f'--gateway.mock.port={mock}', f'--gateway.upstream.base-url={urls[0]}',
            '--gateway.routes[0].id=initial', '--gateway.routes[0].model=*', f'--gateway.routes[0].target-url={urls[0]}']
        process = None
        log = (directory / 'gateway.log').open('w+')

        def start():
            nonlocal process
            process = subprocess.Popen(command, cwd=directory, env=environment, stdout=log, stderr=subprocess.STDOUT)
            deadline = time.monotonic() + 60
            while time.monotonic() < deadline:
                if process.poll() is not None:
                    raise AssertionError('gateway exited: ' + (directory / 'gateway.log').read_text()[-6000:])
                try:
                    Client(port).request('/actuator/health')
                    return
                except (OSError, AssertionError):
                    time.sleep(.2)
            raise AssertionError('startup timeout')

        def stop():
            process.terminate()
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()

        try:
            start()
            admin = Client(port)
            admin.login()
            scopes = []
            for tenant in ('t1', 't2'):
                admin.change('/tenants', {'id': tenant, 'name': tenant})
                for number in (1, 2):
                    project = tenant + '-p' + str(number)
                    scope = {'tenantId': tenant, 'projectId': project}
                    scopes.append(scope)
                    admin.change('/projects', {'id': project, 'tenantId': tenant, 'name': project})
                    admin.change('/quotas', {**scope, 'quotaTokens': 100000, 'reason': 'acceptance setup'})
                    for role in ('PROJECT_ADMIN', 'PROJECT_VIEWER'):
                        admin.change('/accounts', {**scope, 'username': project + '-' + role,
                            'password': PASSWORD, 'role': role})
            issued = []
            for index, scope in enumerate(scopes):
                issued.append(admin.change('/keys', {**scope, 'keyId': 'key-' + str(index),
                    'name': 'key', 'quotaTokens': 10000})['plaintext'])
            chat = {'model': 'm', 'messages': [{'role': 'user', 'content': 'scope acceptance'}]}
            assert admin.request('/v1/chat/completions', 'POST', chat, token=issued[0])['choices'][0]['message']['content'] == 'source-A'
            # Every project viewer sees only its real tenant/project and its own usage/keys.
            for index, scope in enumerate(scopes):
                viewer = Client(port)
                viewer.login(scope['projectId'] + '-PROJECT_VIEWER')
                assert [row['id'] for row in viewer.request('/control/tenants')['tenants']] == [scope['tenantId']]
                assert [row['id'] for row in viewer.request('/control/projects')['projects']] == [scope['projectId']]
                query = '?' + urllib.parse.urlencode(scope)
                own_usage = viewer.request('/control/usage' + query)['usage']
                assert set(own_usage).issubset({'key-' + str(index)})
                foreign = scopes[(index + 1) % len(scopes)]
                foreign_query = '?' + urllib.parse.urlencode(foreign)
                for path in ('usage', 'catalog', 'audit'):
                    viewer.request('/control/' + path + foreign_query, expected=403)
                viewer.change('/keys', {**scope, 'keyId': 'forbidden', 'name': 'forbidden', 'quotaTokens': 1}, 403)
                viewer.change('/logout', {})
                viewer.request('/control/session', expected=401)
                project_admin = Client(port)
                project_admin.login(scope['projectId'] + '-PROJECT_ADMIN')
                assert [row['id'] for row in project_admin.request('/control/keys' + query)['keys']] == ['key-' + str(index)]
                project_admin.change('/keys/key-' + str((index + 1) % len(scopes)) + '/revoke', scope, 403)
                project_admin.change('/routes/publish', {'config': '{}', 'expectedVersion': 0}, 403)
            scope = scopes[0]
            replacement = admin.change('/keys/key-0/rotate', scope)['plaintext']
            admin.request('/v1/chat/completions', 'POST', chat, expected=401, token=issued[0])
            admin.request('/v1/chat/completions', 'POST', chat, token=replacement)
            sibling = admin.change('/keys', {**scope, 'keyId': 'sibling', 'name': 'sibling', 'quotaTokens': 10000})['plaintext']
            multipart = b'--probe\r\nContent-Disposition: form-data; name="purpose"\r\n\r\nbatch\r\n--probe\r\nContent-Disposition: form-data; name="file"; filename="a.jsonl"\r\nContent-Type: application/json\r\n\r\n{}\r\n--probe--\r\n'
            file_id = admin.request('/v1/files', 'POST', multipart, token=replacement)['id']
            assert admin.request('/v1/files/' + file_id, token=sibling)['source'] == 'source-A'
            admin.request('/v1/files/' + file_id, token=issued[1], expected=404)
            # A revoked key's existing request completes and bills its original project.
            inflight = admin.change('/keys', {**scope, 'keyId': 'inflight', 'name': 'inflight', 'quotaTokens': 10000})['plaintext']
            result, failure = [], []
            def invoke_inflight():
                try:
                    result.append(Client(port).request('/v1/chat/completions', 'POST',
                        {'model': 'm', 'messages': [{'role': 'user', 'content': 'inflight-probe'}]}, token=inflight))
                except Exception as error:
                    failure.append(error)
            worker = threading.Thread(target=invoke_inflight)
            worker.start()
            assert upstreams[0].entered.wait(10)
            active = admin.request('/control/quotas/pending?' + urllib.parse.urlencode(scope))['pending']
            reservation = next(item for item in active if item['keyId'] == 'inflight')
            assert reservation['active']
            admin.change('/quotas/reconcile', {**scope, 'reservationId': reservation['id'], 'promptTokens': 0,
                'completionTokens': 0, 'reason': 'must not clear in-flight'}, 400)
            admin.change('/keys/inflight/revoke', scope)
            admin.request('/v1/chat/completions', 'POST', chat, expected=401, token=inflight)
            upstreams[0].release.set()
            worker.join(15)
            assert not worker.is_alive() and not failure and result
            assert 'inflight' in admin.request('/control/usage?' + urllib.parse.urlencode(scope))['usage']
            # Persist, activate, conflict, rollback; observe actual forwarded source.
            def config(index):
                return json.dumps({'routes': [{'id': 'source-' + str(index), 'protocol': '*', 'model': '*', 'targetUrl': urls[index], 'provider': 'openai'}]})
            admin.change('/routes/publish', {'expectedVersion': 0, 'config': 'not JSON'}, 400)
            initial_version = admin.request('/control/status')['activeRouteVersion']
            first = admin.change('/routes/publish', {'expectedVersion': initial_version, 'config': config(0)})['version']
            second = admin.change('/routes/publish', {'expectedVersion': first, 'config': config(1)})['version']
            assert admin.request('/v1/files/' + file_id, token=sibling)['source'] == 'source-A'
            assert admin.request('/control/status')['activeRouteVersion'] == second
            assert admin.request('/v1/chat/completions', 'POST', chat, token=replacement)['choices'][0]['message']['content'] == 'source-B'
            admin.change('/routes/publish', {'expectedVersion': first, 'config': config(0)}, 409)
            rolled = admin.change('/routes/rollback', {'expectedVersion': second, 'targetVersion': first})['version']
            assert admin.request('/v1/chat/completions', 'POST', chat, token=replacement)['choices'][0]['message']['content'] == 'source-A'
            # Real management HTTP transaction: audit trigger failure must revert the mutation.
            with sqlite3.connect(database) as connection:
                connection.execute("CREATE TRIGGER fail_control_audit BEFORE INSERT ON audit_event BEGIN SELECT RAISE(ABORT,'acceptance audit failure'); END")
            admin.change('/tenants', {'id': 'must-rollback', 'name': 'must-rollback'}, 500)
            admin.change('/routes/publish', {'expectedVersion': rolled, 'config': config(1)}, 500)
            assert admin.request('/control/status')['activeRouteVersion'] == rolled
            assert admin.request('/v1/chat/completions', 'POST', chat, token=replacement)['choices'][0]['message']['content'] == 'source-A'
            with sqlite3.connect(database) as connection:
                assert connection.execute("SELECT count(*) FROM tenant WHERE id='must-rollback'").fetchone()[0] == 0
                connection.execute('DROP TRIGGER fail_control_audit')
            # Session cookie persists; previous CSRF becomes invalid after process restart; recovery fixes writes.
            old_csrf = admin.csrf
            stop()
            start()
            admin.change('/keys/key-0/revoke', scope, 403)
            recovered = admin.request('/control/session')
            admin.csrf = recovered['csrfToken']
            assert admin.csrf != old_csrf
            assert admin.request('/control/status')['activeRouteVersion'] == rolled
            admin.request('/v1/chat/completions', 'POST', chat, token=replacement)
            assert admin.request('/v1/files/' + file_id, token=sibling)['source'] == 'source-A'
            admin.change('/keys/key-0/revoke', scope)
            admin.request('/v1/chat/completions', 'POST', chat, expected=401, token=replacement)
            assert 'key-0' in admin.request('/control/usage?' + urllib.parse.urlencode(scope))['usage']
            limiter = Client(port)
            for _ in range(10):
                limiter.request('/control/login', 'POST', {'username': 'invalid', 'password': 'wrong'}, expected=401)
            limiter.request('/control/login', 'POST', {'username': 'invalid', 'password': 'wrong'}, expected=429)
            print(json.dumps({'engine': engine, 'result': 'PASS', 'artifactSha256': hashlib.sha256(jar.read_bytes()).hexdigest(),
                'scenarios': ['four-project scope matrix', 'key create/call/rotate/revoke', 'in-flight revoke settles', 'in-flight reconciliation rejected', 'same-project resources and pinned source survive restart', 'durable usage',
                              'route publish/rollback actual source', 'audit rollback', 'restart session/CSRF', 'HTTP login limit']}, ensure_ascii=False), flush=True)
        except Exception:
            log.flush()
            print((directory / 'gateway.log').read_text()[-8000:], flush=True)
            raise
        finally:
            if process and process.poll() is None:
                stop()
            log.close()
            for server in upstreams:
                server.shutdown()
                server.server_close()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path, default=Path(os.environ.get('LLMGW_TEST_JAR', ROOT / 'llmgw-app/target/llmgw-app-0.5.0.jar')))
    parser.add_argument('--binary', type=Path)
    parser.add_argument('--engine', choices=['webflux', 'vt'])
    arguments = parser.parse_args()
    for engine_name in [arguments.engine] if arguments.engine else ['webflux', 'vt']:
        verify((arguments.binary or arguments.jar).resolve(), engine_name, bool(arguments.binary))
