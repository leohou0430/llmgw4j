"""Local TLS trust and hostname checks; no external provider traffic."""
import hashlib,json,os,socket,ssl,subprocess,tempfile,threading,time,urllib.request,urllib.error
from pathlib import Path
from http.server import ThreadingHTTPServer,BaseHTTPRequestHandler
OUT=Path('/audit/tls');OUT.mkdir(parents=True,exist_ok=True);report={'checks':[],'external_requests':0,'runtime':'native G1','sha256':hashlib.sha256(Path('/audit/llmgw-webflux-linux').read_bytes()).hexdigest()}
def save():(OUT/'results.json').write_text(json.dumps(report,indent=2)+'\n')
def command(*args):subprocess.run(list(args),check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
def port():
 with socket.socket() as s:s.bind(('127.0.0.1',0));return s.getsockname()[1]
class Mock(BaseHTTPRequestHandler):
 def log_message(self,*args):pass
 def do_POST(self):
  body=json.loads(self.rfile.read(int(self.headers['Content-Length'])))
  value={'id':'tls','choices':[{'index':0,'message':{'content':'ok'},'delta':{'content':'ok'},'finish_reason':'stop'}],'usage':{'prompt_tokens':1,'completion_tokens':1}}
  raw=('data: '+json.dumps(value)+'\n\ndata: [DONE]\n\n').encode() if body.get('stream') else json.dumps(value).encode()
  self.send_response(200);self.send_header('Content-Type','text/event-stream' if body.get('stream') else 'application/json');self.send_header('Content-Length',str(len(raw)));self.end_headers();self.wfile.write(raw)
opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
def request(p,path,body=None):
 req=urllib.request.Request(f'http://127.0.0.1:{p}{path}',data=None if body is None else json.dumps(body).encode(),headers={'Content-Type':'application/json','Authorization':'Bearer tls-client'})
 try:
  with opener.open(req,timeout=8) as r:return r.status,r.read().decode()
 except urllib.error.HTTPError as e:return e.code,e.read().decode()
with tempfile.TemporaryDirectory(prefix='llmgw-tls-') as temp:
 temp=Path(temp);ca=temp/'ca.pem';key=temp/'ca.key';store=temp/'trust.p12'
 command('openssl','req','-x509','-newkey','rsa:2048','-nodes','-keyout',str(key),'-out',str(ca),'-days','1','-subj','/CN=Local Audit CA')
 command('keytool','-importcert','-noprompt','-alias','audit','-file',str(ca),'-keystore',str(store),'-storepass','audit-password','-storetype','PKCS12')
 for mode,hostname in [('trusted','localhost'),('wrong-host','wrong.invalid')]:
  csr=temp/(mode+'.csr');serverkey=temp/(mode+'.key');cert=temp/(mode+'.pem');ext=temp/(mode+'.ext');ext.write_text('subjectAltName=DNS:'+hostname+'\nextendedKeyUsage=serverAuth\n')
  command('openssl','req','-newkey','rsa:2048','-nodes','-keyout',str(serverkey),'-out',str(csr),'-subj','/CN='+hostname)
  command('openssl','x509','-req','-in',str(csr),'-CA',str(ca),'-CAkey',str(key),'-CAcreateserial','-out',str(cert),'-days','1','-extfile',str(ext))
  server=ThreadingHTTPServer(('127.0.0.1',0),Mock);context=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER);context.load_cert_chain(cert,serverkey);server.socket=context.wrap_socket(server.socket,server_side=True);thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
  try:
   for engine in ['webflux']:
    for trust in ([True,False] if mode=='trusted' else [True]):
     binary=Path('/audit/llmgw-'+engine+'-linux');p=port();cmd=[str(binary)]
     if trust:cmd+=['-Djavax.net.ssl.trustStore='+str(store),'-Djavax.net.ssl.trustStorePassword=audit-password']
     cmd+=['--spring.profiles.active='+engine,f'--server.port={p}','--server.address=127.0.0.1','--gateway.mock.enabled=false','--gateway.keys-store-path=','--gateway.governance.snapshot-path=','--gateway.api-keys[0].token=tls-client','--gateway.api-keys[0].quota-tokens=100000','--gateway.routes[0].target-url=https://localhost:'+str(server.server_port)+'/v1','--gateway.routes[0].api-key=tls-upstream','--gateway.upstream.timeout-ms=5000','--gateway.upstream.retry.attempts=1']
     with (OUT/(engine+'-'+mode+'-'+str(trust)+'.log')).open('w') as log:
      process=subprocess.Popen(cmd,cwd=temp,stdout=log,stderr=subprocess.STDOUT)
      try:
       deadline=time.monotonic()+30
       while True:
        if process.poll() is not None:raise RuntimeError('native exited during startup')
        try:
         if request(p,'/actuator/health')[0]==200:break
        except OSError:pass
        assert time.monotonic()<deadline,'readiness timeout';time.sleep(.1)
       for stream in ([False,True] if mode=='trusted' and trust else [False]):
        status,body=request(p,'/v1/chat/completions',{'model':'m','messages':[{'role':'user','content':'tls'}],'stream':stream})
        expected=200 if mode=='trusted' and trust else 502
        row={'engine':engine,'certificate':mode,'custom_trust':trust,'stream':stream,'status':status,'expected':expected,'body':body[:500]};report['checks'].append(row);save();assert status==expected,row
        if expected==200:assert ('[DONE]' in body if stream else json.loads(body)['choices'][0]['message']['content']=='ok'),row
        elif mode=='wrong-host':assert 'subject alternative DNS name' in body,row
        else:assert 'certification path' in body,row
      finally:
       if process.poll() is None:process.terminate();process.wait(timeout=15)
  finally:server.shutdown();server.server_close();thread.join(timeout=5)
report['complete']=True;save()
