"""Native real-HTTP regression: partial SSE/error-body progress vs a genuine read stall."""
import argparse,hashlib,http.server,json,os,socket,subprocess,tempfile,threading,time,urllib.error,urllib.request
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--binary',type=Path,required=True);p.add_argument('--out',type=Path,required=True);p.add_argument('--engine',choices=['webflux','vt'],default='webflux');args=p.parse_args()
out=args.out.resolve();out.mkdir(parents=True,exist_ok=True);mode='sse';report={'binary_sha256':hashlib.sha256(args.binary.read_bytes()).hexdigest(),'checks':[]}
class Upstream(http.server.BaseHTTPRequestHandler):
    protocol_version='HTTP/1.1'
    def log_message(self,*args):pass
    def do_POST(self):
        self.rfile.read(int(self.headers.get('Content-Length','0')))
        selected=mode;self.send_response(503 if selected=='error' else 200);self.send_header('Transfer-Encoding','chunked');self.send_header('Content-Type','text/event-stream');self.end_headers()
        payload=b'unavailable' if selected=='error' else b'data: [DONE]\n\n'
        try:
            for index,value in enumerate(payload):
                self.wfile.write(b'1\r\n'+bytes([value])+b'\r\n');self.wfile.flush()
                time.sleep(.7 if selected=='stall' and index==0 else .05)
            self.wfile.write(b'0\r\n\r\n');self.wfile.flush()
        except (BrokenPipeError,ConnectionResetError):pass

def call(port,path,body=None):
    req=urllib.request.Request(f'http://127.0.0.1:{port}{path}',data=body,headers={'Authorization':'Bearer idle-client','Content-Type':'application/json'})
    try:
        with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(req,timeout=5) as r:return r.status,r.read().decode()
    except urllib.error.HTTPError as e:return e.code,e.read().decode()
server=http.server.ThreadingHTTPServer(('127.0.0.1',0),Upstream);thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start();process=None
try:
    with socket.socket() as s:s.bind(('127.0.0.1',0));port=s.getsockname()[1]
    url=f'http://127.0.0.1:{server.server_port}/v1'
    with tempfile.TemporaryDirectory(prefix='llmgw-native-idle-') as temp, (out/'native-idle-gateway.log').open('w') as log:
        cmd=[str(args.binary.resolve()),'--spring.profiles.active='+args.engine,f'--server.port={port}','--gateway.mock.enabled=false',f'--gateway.upstream.base-url={url}',f'--gateway.routes[0].target-url={url}','--gateway.routes[0].api-key=idle-upstream','--gateway.api-keys[0].token=idle-client','--gateway.api-keys[0].quota-tokens=1000000000','--gateway.upstream.idle-read-timeout-ms=250','--gateway.upstream.heartbeat-interval-ms=0','--gateway.upstream.timeout-ms=5000',f'--gateway.keys-store-path={temp}/keys.json',f'--gateway.governance.snapshot-path={temp}/snapshot.json']
        report['command']=cmd;process=subprocess.Popen(cmd,cwd=temp,stdout=log,stderr=subprocess.STDOUT)
        try:
            for _ in range(120):
                if process.poll() is not None:raise RuntimeError('native gateway exited')
                try:
                    if call(port,'/actuator/health')[0]==200:break
                except OSError:pass
                time.sleep(.25)
            else:raise RuntimeError('native gateway not healthy')
            for mode in ('sse','error','stall'):
                body=json.dumps({'model':'qwen-plus','messages':[{'role':'user','content':mode}],'stream':True}).encode();started=time.monotonic();status,result=call(port,'/v1/chat/completions',body)
                row={'mode':mode,'status':status,'body':result,'seconds':time.monotonic()-started};report['checks'].append(row)
                if mode=='sse':assert status==200 and 'data: [DONE]' in result and 'event: error' not in result,row
                elif mode=='error':assert status==502 and '503' in result and 'idle read timeout' not in result,row
                else:assert status==502 and 'idle read timeout' in result,row
            report['complete']=True
        finally:
            if process.poll() is None:
                process.terminate()
                try:process.wait(timeout=15)
                except subprocess.TimeoutExpired:process.kill();process.wait()
            report['gateway_exit']=process.returncode
except BaseException as error:report['failure']=repr(error);raise
finally:
    server.shutdown();server.server_close();thread.join(timeout=5)
    (out/'native-idle-progress.json').write_text(json.dumps(report,indent=2)+'\n')
