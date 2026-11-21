#!/usr/bin/env python3
"""Two isolated replicas + owned Redis: restart, outage fail-closed and reconnect."""
import argparse, base64, json, os, socket, subprocess, tempfile, threading, time, urllib.error, urllib.request, uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

def free_port():
    with socket.socket() as s:s.bind(('127.0.0.1',0));return s.getsockname()[1]

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar',type=Path,default=Path(os.environ.get('LLMGW_TEST_JAR', 'llmgw-app/target/llmgw-app-0.5.0.jar')))
    args=parser.parse_args()
    jar=args.jar.resolve();redis_port=free_port();ports=[free_port(),free_port()];requests=[];processes=[];redis=None
    # Both replicas must share one root key: uploaded file payloads are encrypted
    # at rest with it, so replica B has to decrypt what replica A wrote.
    root_key=base64.b64encode(uuid.uuid4().bytes+uuid.uuid4().bytes).decode()
    class Mock(BaseHTTPRequestHandler):
        def do_POST(self):
            self.rfile.read(int(self.headers.get('Content-Length','0')));requests.append(1)
            body=b'{"id":"file-audit","object":"file"}' if self.path=='/v1/files' else b'{"choices":[{"message":{"content":"ok"}}],"usage":{"prompt_tokens":1,"completion_tokens":1}}'
            self.respond(body)
        def do_GET(self):
            requests.append(1);self.respond(b'audit-file-bytes' if self.path.endswith('/content') else b'{"id":"file-audit","object":"file"}')
        def respond(self,body):
            self.send_response(200);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body)
        def log_message(self,*args):pass
    mock=ThreadingHTTPServer(('127.0.0.1',0),Mock);thread=threading.Thread(target=mock.serve_forever,daemon=True);thread.start()
    opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
    def request(port,path,body=None,token="audit-recovery",content_type="application/json",admin=False):
        data=body if isinstance(body,bytes) else None if body is None else json.dumps(body).encode()
        headers={'Content-Type':content_type}
        headers['X-Admin-Token' if admin else 'Authorization']=('audit-admin' if admin else 'Bearer '+token)
        req=urllib.request.Request(f'http://127.0.0.1:{port}{path}',data=data,headers=headers)
        try:
            with opener.open(req,timeout=8) as r:return r.status,r.read()
        except urllib.error.HTTPError as e:return e.code,e.read()
    def call(port):return request(port,'/v1/chat/completions',{'model':'qwen-plus','messages':[{'role':'user','content':uuid.uuid4().hex}],'stream':False})
    def stop(p):
        p.terminate()
        try:p.wait(timeout=10)
        except subprocess.TimeoutExpired:p.kill();p.wait()
    with tempfile.TemporaryDirectory(prefix='llmgw-redis-recovery-') as folder:
        folder=Path(folder)
        def start_redis():
            log=(folder/'redis.log').open('a');p=subprocess.Popen(['redis-server','--bind','127.0.0.1','--port',str(redis_port),'--dir',str(folder),'--save','','--appendonly','no'],stdout=log,stderr=subprocess.STDOUT);log.close()
            for _ in range(100):
                if p.poll() is not None:raise RuntimeError('owned Redis exited before readiness')
                result=subprocess.run(['redis-cli','-p',str(redis_port),'INFO','server'],capture_output=True,text=True)
                if f'process_id:{p.pid}' in result.stdout.splitlines():return p
                time.sleep(.05)
            stop(p);raise RuntimeError('owned Redis did not start')
        def start_replica(index):
            log=(folder/f'gateway-{index}.log').open('a');engine=['webflux','vt'][index]
            cmd=['java','-jar',str(jar),'--spring.profiles.active='+engine,'--server.address=127.0.0.1',f'--server.port={ports[index]}','--gateway.mock.enabled=false','--gateway.upstream.api-key=audit-upstream','--gateway.routes[0].api-key=audit-upstream','--gateway.redis.enabled=true','--gateway.redis.host=127.0.0.1',f'--gateway.redis.port={redis_port}','--gateway.redis.timeout-ms=300','--gateway.api-keys[0].id=audit','--gateway.api-keys[0].token=audit-recovery','--gateway.api-keys[0].quota-tokens=1000000','--gateway.api-keys[1].id=foreign','--gateway.api-keys[1].token=foreign-recovery','--gateway.api-keys[1].quota-tokens=1000000',f'--gateway.routes[0].target-url=http://127.0.0.1:{mock.server_port}/v1','--gateway.keys-store-path=','--gateway.governance.snapshot-path=']
            p=subprocess.Popen(cmd,cwd=folder,stdout=log,stderr=subprocess.STDOUT,env={**os.environ,'LLMGW_ROOT_KEY':root_key});log.close();processes.append(p)
            for _ in range(160):
                if p.poll() is not None:raise RuntimeError((folder/f'gateway-{index}.log').read_text()[-4000:])
                try:
                    if request(ports[index],'/actuator/health')[0]==200:return p
                except OSError:pass
                time.sleep(.25)
            raise RuntimeError('replica start timeout')
        def snapshot(port):return json.loads(request(port,'/admin/snapshot')[1])['usage'].get('audit',{})
        def settled(count):
            end=time.monotonic()+5
            while time.monotonic()<end:
                a,b=[snapshot(port) for port in ports]
                if a==b and a.get('requests')==count:return a
                time.sleep(.05)
            raise AssertionError((count,a,b))
        try:
            redis=start_redis();replicas=[start_replica(0),start_replica(1)]
            for port in ports:assert call(port)[0]==200
            initial=settled(2)
            print('PASS shared ledger: WebFlux/VT report identical two-request usage',flush=True)
            stop(replicas[1]);replicas[1]=start_replica(1);assert snapshot(ports[1])==initial;assert call(ports[1])[0]==200
            settled(3)
            print('PASS replica restart: shared usage survives and request succeeds',flush=True)
            subprocess.run(['redis-cli','-p',str(redis_port),'save'],check=True,capture_output=True)
            stop(redis);redis=None;before=len(requests)
            for port in ports:
                status,_=call(port);assert status>=500,(port,status)
            assert len(requests)==before,'Redis outage must never reach the upstream'
            print('PASS Redis outage: both replicas fail closed with no upstream calls',flush=True)
            redis=start_redis()
            for port in ports:
                end=time.monotonic()+10
                while True:
                    status,_=call(port)
                    if status==200:break
                    assert time.monotonic()<end,(port,status);time.sleep(.2)
            final=settled(5)
            print('PASS Redis restart: pools reconnect, Lua scripts reload, shared ledger preserved',flush=True)
            multipart=b'--audit\r\nContent-Disposition: form-data; name="purpose"\r\n\r\nbatch\r\n--audit\r\nContent-Disposition: form-data; name="file"; filename="input.jsonl"\r\nContent-Type: application/json\r\n\r\n{}\r\n--audit--\r\n'
            status,body=request(ports[0],'/v1/files',multipart,content_type='multipart/form-data; boundary=audit')
            assert status==200,(status,body)
            assert json.loads(body)['id']=='file-audit'
            status,body=request(ports[1],'/v1/files/file-audit/content');assert (status,body)==(200,b'audit-file-bytes'),(status,body)
            before=len(requests)
            assert request(ports[1],'/v1/files/file-audit/content',token='foreign-recovery')[0]==404
            assert len(requests)==before,'foreign tenant must not reach the upstream'
            # v0.4.0 contract: file/batch listings live in the persistent local
            # dir, so replicas on one host share them (200, not the old 501);
            # the legacy /admin plane is disabled by default and denies key
            # CRUD with a non-success response.
            assert request(ports[1],'/v1/files')[0]==200
            assert request(ports[1],'/v1/batches')[0]==200
            # v0.5.0 contract: listings are shared via the persistent local dir,
            # and key authority is the SHARED SQLite control DB (never Redis) --
            # a runtime key created through the legacy /admin CRUD on one replica
            # authenticates on both replicas of the same host topology.
            status,body=request(ports[1],'/admin/keys',{'id':'shared-runtime','name':'shared-runtime'})
            assert status==200,(status,body)
            runtime_key=json.loads(body)['token']
            assert request(ports[1],'/v1/chat/completions',{'model':'qwen-plus','messages':[{'role':'user','content':uuid.uuid4().hex}],'stream':False},token=runtime_key)[0] in (200,402,429)
            assert request(ports[0],'/v1/chat/completions',{'model':'qwen-plus','messages':[{'role':'user','content':uuid.uuid4().hex}],'stream':False},token=runtime_key)[0] in (200,402,429),'key authority must be the shared SQLite control DB, not replica-local'
            print('PASS shared file ownership: upload A / download B, foreign tenant 404; listings shared and runtime keys live in the shared SQLite control DB (never Redis)',flush=True)
        except Exception:
            for log in folder.glob('gateway-*.log'):print(log.name,log.read_text()[-3000:],flush=True)
            raise
        finally:
            for p in processes:
                if p.poll() is None:stop(p)
            if redis is not None:stop(redis)
            mock.shutdown();mock.server_close();thread.join(timeout=5)
if __name__=='__main__':main()
