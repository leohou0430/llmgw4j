import hashlib, http.cookiejar, json, os, socket, sqlite3, subprocess, time, urllib.request, urllib.error
from pathlib import Path

ROOT = Path('/Users/leo/WorkBuddy/2026-11-06-01-50-12/llm-gateway')
OUT = Path(os.environ.get('AUDIT_OUT','/tmp/llmgw-audit-20261116'))
JAR = OUT / 'audited.jar'
results = []

def port():
    with socket.socket() as s:
        s.bind(('127.0.0.1', 0))
        return s.getsockname()[1]

def run(engine):
    folder = OUT / engine
    folder.mkdir(exist_ok=True)
    db = folder / 'control.db'
    p, mock = port(), port()
    base = f'http://127.0.0.1:{p}'
    env = {k:v for k,v in os.environ.items() if not k.startswith(('LLMGW_', 'SPRING_', 'GATEWAY_', 'DASHSCOPE_'))}
    env.update(LLMGW_CONTROL_DB=str(db), LLMGW_ADMIN_USERNAME='audit-admin', LLMGW_ADMIN_PASSWORD='audit-local-password',
               LLMGW_VIEWER_USERNAME='audit-viewer', LLMGW_VIEWER_PASSWORD='audit-viewer-password')
    command = ['java', '-jar', str(JAR), f'--spring.profiles.active={engine}', f'--server.port={p}',
               '--server.address=127.0.0.1', f'--gateway.mock.port={mock}', '--gateway.mock.enabled=true',
               f'--gateway.upstream.base-url=http://127.0.0.1:{mock}/mock/v1',
               f'--gateway.routes[0].target-url=http://127.0.0.1:{mock}/mock/v1', '--gateway.routes[0].id=audit-mock',
               '--gateway.routes[0].model=*', '--gateway.admin-token=', '--gateway.admin-read-token=',
               '--gateway.api-keys[0].id=audit-data', '--gateway.api-keys[0].token=audit-client',
               '--gateway.api-keys[0].quota-tokens=1000000', '--gateway.cache.enabled=false']
    jar = http.cookiejar.CookieJar()
    admin = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPCookieProcessor(jar))
    viewer = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    plain = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    def req(path, method='GET', body=None, client=admin, csrf=None, token=None):
        h = {'Content-Type':'application/json'}
        if csrf: h['X-CSRF-Token'] = csrf
        if token: h['Authorization'] = 'Bearer '+token
        data = json.dumps(body).encode() if body is not None else None
        r = urllib.request.Request(base+path, data=data, headers=h, method=method)
        try: response = client.open(r, timeout=20)
        except urllib.error.HTTPError as e: response = e
        with response:
            raw = response.read().decode()
            try: result=json.loads(raw)
            except ValueError: result=raw
            return response.status, result, dict(response.headers)
    def emit(name, data):
        row = {'engine':engine, 'case':name, **data}
        results.append(row)
        print(json.dumps(row, ensure_ascii=False), flush=True)
    log = open(folder/'gateway.log','w')
    process = subprocess.Popen(command, env=env, cwd=folder, stdout=log, stderr=subprocess.STDOUT)
    def stop():
        process.terminate()
        try: process.wait(timeout=15)
        except subprocess.TimeoutExpired:
            process.kill(); process.wait()
    try:
        for _ in range(180):
            if process.poll() is not None: raise RuntimeError(f'{engine} exited: {process.returncode}')
            try:
                if req('/actuator/health')[0]==200: break
            except OSError: pass
            time.sleep(.25)
        else: raise RuntimeError('startup timeout')
        login = req('/control/login','POST',{'username':'audit-admin','password':'audit-local-password'})
        assert login[0]==200, login
        csrf=login[1]['csrfToken']
        emit('login_cookie', {'set_cookie':login[2].get('Set-Cookie','').replace(next(iter(jar)).value,'<redacted>')})
        vlogin=req('/control/login','POST',{'username':'audit-viewer','password':'audit-viewer-password'},viewer)
        assert vlogin[0]==200
        vcsrf=vlogin[1]['csrfToken']
        for tid in ('t1','t2'):
            assert req('/control/tenants','POST',{'id':tid,'name':tid},csrf=csrf)[0]==200
        for tid,pid in [('t1','p1'),('t2','p2')]:
            assert req('/control/projects','POST',{'id':pid,'tenantId':tid,'name':pid},csrf=csrf)[0]==200
        emit('viewer_cross_tenant_orgs', {'tenants':req('/control/tenants',client=viewer)[:2], 'projects':req('/control/projects',client=viewer)[:2]})
        issued=[]
        for tid,pid in [('t1','p1'),('t2','p2')]:
            response=req('/control/keys','POST',{'tenantId':tid,'projectId':pid,'keyId':'key-'+pid,'name':'key-'+pid,'quotaTokens':1000},csrf=csrf)
            assert response[0]==200, response
            issued.append(response[1]['plaintext'])
        chat={'model':'m','messages':[{'role':'user','content':'audit hello'}]}
        configured=req('/v1/chat/completions','POST',chat,token='audit-client')
        time.sleep(.2)
        emit('managed_key_data_plane', {'issued_key_status':req('/v1/chat/completions','POST',chat,token=issued[0])[0],
                                      'configured_key_status':configured[0], 'upstream_usage':configured[1].get('usage'),
                                      'ledger':req('/admin/snapshot')[1].get('usage')})
        emit('viewer_usage_global', {'response':req('/control/usage',client=viewer)[:2]})
        with sqlite3.connect(db) as conn:
            conn.execute("UPDATE account SET role='PROJECT_ADMIN' WHERE username='audit-viewer'")
        emit('project_admin_all_keys', {'response':req('/control/keys',client=viewer)[:2]})
        published=req('/control/routes/publish','POST',{'tenantId':'t1','projectId':'p1','expectedVersion':0,'config':'not even JSON'},csrf=csrf)
        emit('route_publish_disconnected', {'published':published[:2], 'status':req('/control/status')[:2], 'actual_routes':req('/admin/routes')[:2]})
        emit('route_list_unauthenticated', {'response':req('/control/routes',client=plain)[:2]})
        # A repeatable fault injection affects only this task's isolated database.
        with sqlite3.connect(db) as conn:
            conn.execute("CREATE TRIGGER audit_failure BEFORE INSERT ON audit_event BEGIN SELECT RAISE(ABORT, 'audit injected failure'); END")
        failed=req('/control/tenants','POST',{'id':'audit-failed','name':'should rollback'},csrf=csrf)
        with sqlite3.connect(db) as conn:
            persisted=conn.execute("SELECT COUNT(*) FROM tenant WHERE id='audit-failed'").fetchone()[0]
            conn.execute('DROP TRIGGER audit_failure')
        emit('audit_failure_not_atomic', {'http_status':failed[0], 'tenant_persisted':persisted})
        wrong=[req('/control/login','POST',{'username':'audit-admin','password':'wrong'},plain)[0] for _ in range(15)]
        emit('login_no_throttle', {'wrong_password_statuses':wrong})
        # Persisted sessions use a new process-local CSRF signing key after restart.
        before_usage=req('/admin/snapshot')[1].get('usage',{})
        stop()
        process = subprocess.Popen(command, env=env, cwd=folder, stdout=log, stderr=subprocess.STDOUT)
        for _ in range(180):
            try:
                if req('/actuator/health')[0]==200: break
            except OSError: pass
            time.sleep(.25)
        after_read=req('/control/tenants')[0]
        after_write=req('/control/tenants','POST',{'id':'after-restart','name':'restart'},csrf=csrf)[0]
        after_usage=req('/admin/snapshot')[1].get('usage',{})
        emit('restart_session_csrf_usage', {'read_status':after_read,'old_csrf_write_status':after_write,'usage_before':before_usage,'usage_after':after_usage})
        with sqlite3.connect(db) as conn:
            conn.execute("UPDATE account SET role='PROJECT_VIEWER', tenant_id='t2', project_id='p2' WHERE username='audit-viewer'")
        # Re-login produces a valid CSRF token for the new process.
        vlogin=req('/control/login','POST',{'username':'audit-viewer','password':'audit-viewer-password'},viewer)
        emit('other_project_logout', {'login_status':vlogin[0], 'logout_status':req('/control/logout','POST',client=viewer,csrf=vlogin[1]['csrfToken'])[0]})
    finally:
        stop(); log.close()

print(json.dumps({'jar_sha256':hashlib.sha256(JAR.read_bytes()).hexdigest()}),flush=True)
for engine in ('webflux','vt'): run(engine)
(OUT/'runtime-results.json').write_text(json.dumps(results,ensure_ascii=False,indent=2))
