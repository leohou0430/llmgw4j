import json, os, random, socket, subprocess, time, urllib.request
from pathlib import Path
OUT=Path('/tmp/llmgw-audit-20261116'); folder=OUT/'spool-crash';folder.mkdir(exist_ok=True)
tmp=folder/'tmp';tmp.mkdir(exist_ok=True)
def port():
    while True:
        p=random.randrange(30000,38000)
        try:
            with socket.socket() as s:s.bind(('127.0.0.1',p))
            return p
        except OSError:pass
p,m=port(),port()
env={k:v for k,v in os.environ.items() if not k.startswith(('LLMGW_','SPRING_','GATEWAY_','DASHSCOPE_'))}
env['LLMGW_CONTROL_DB']=str(folder/'control.db')
cmd=['java',f'-Djava.io.tmpdir={tmp}','-jar',str(OUT/'audited.jar'),'--spring.profiles.active=webflux',f'--server.port={p}',
     f'--gateway.mock.port={m}',f'--gateway.upstream.base-url=http://127.0.0.1:{m}/mock/v1',
     f'--gateway.routes[0].target-url=http://127.0.0.1:{m}/mock/v1','--gateway.admin-token=','--gateway.admin-read-token=']
log=open(folder/'gateway.log','w')
client=urllib.request.build_opener(urllib.request.ProxyHandler({}))
def start():
    proc=subprocess.Popen(cmd,cwd=folder,env=env,stdout=log,stderr=subprocess.STDOUT)
    for _ in range(200):
        if proc.poll() is not None:raise RuntimeError('gateway exited')
        try:
            if client.open(f'http://127.0.0.1:{p}/actuator/health',timeout=1).status==200:return proc
        except OSError:pass
        time.sleep(.2)
    proc.kill();proc.wait();raise RuntimeError('timeout')
proc=None;s=None
try:
    proc=start()
    s=socket.create_connection(('127.0.0.1',p))
    s.sendall((f'POST /v1/files HTTP/1.1\r\nHost: 127.0.0.1:{p}\r\nAuthorization: Bearer sk-demo\r\n'
       'Content-Type: multipart/form-data; boundary=crash-test\r\nContent-Length: 4000000\r\n\r\n').encode())
    s.sendall(b'--crash-test\r\nContent-Disposition: form-data; name="purpose"\r\n\r\nbatch\r\n'
              b'--crash-test\r\nContent-Disposition: form-data; name="file"; filename="audit.bin"\r\nContent-Type: application/octet-stream\r\n\r\n'+b'a'*(1024*1024))
    files=[]
    for _ in range(100):
        files=[f for f in tmp.glob('llmgw-webflux-spool-*/spool-*.tmp') if f.stat().st_size>0]
        if files:break
        time.sleep(.1)
    assert files,'no spool observed'
    old=files[0]; size=old.stat().st_size
    proc.kill();proc.wait();s.close();s=None
    proc=start()
    result={'engine':'webflux','spooled_bytes_before_crash':size,'old_spool_exists_after_restart':old.exists(),
            'old_spool_bytes_after_restart':old.stat().st_size if old.exists() else 0}
    (OUT/'spool-crash-result.json').write_text(json.dumps(result,indent=2)); print(json.dumps(result))
finally:
    if s:s.close()
    if proc and proc.poll() is None:
        proc.terminate()
        try:proc.wait(timeout=15)
        except subprocess.TimeoutExpired:proc.kill();proc.wait()
    for f in tmp.glob('llmgw-webflux-spool-*/spool-*.tmp'):f.unlink()
    for f in tmp.glob('llmgw-webflux-spool-*'):
        if f.is_dir():f.rmdir()
    log.close()
