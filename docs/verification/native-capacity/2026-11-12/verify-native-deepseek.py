"""At most two non-retried generations; provider credential only via hidden stdin and child env."""
import argparse,getpass,hashlib,json,os,re,socket,subprocess,tempfile,time,uuid,urllib.request
from pathlib import Path
from openai import OpenAI
p=argparse.ArgumentParser();p.add_argument('--native-dir',type=Path,required=True);p.add_argument('--out',type=Path,required=True);args=p.parse_args();out=args.out.resolve();out.mkdir(parents=True,exist_ok=True);native_dir=args.native_dir.resolve()
secret=getpass.getpass('DeepSeek credential (hidden; not persisted): ')
if not secret:raise SystemExit('missing credential')
report={'provider':'DeepSeek','model':'deepseek-flash','runtime':'Linux native','native_binaries':{e:hashlib.sha256((native_dir/f'llmgw-{e}-linux').read_bytes()).hexdigest() for e in ('webflux','vt')},'maximum_generation_requests':2,'max_tokens_per_request':32,'sdk_retries':0,'gateway_attempts':1,'thinking':'disabled','checks':[],'model_reference':'https://api-docs.deepseek.com/quick_start/pricing','thinking_reference':'https://api-docs.deepseek.com/guides/thinking_mode'}
def safe(value):return re.sub(r'sk-[A-Za-z0-9_-]+','[REDACTED]',str(value).replace(secret,'[REDACTED]'))
def save(): (out/'live-results.json').write_text(safe(json.dumps(report,indent=2,ensure_ascii=False))+'\n')
def free_port():
 with socket.socket() as s:s.bind(('127.0.0.1',0));return s.getsockname()[1]
process=None
try:
 for engine,stream in [('webflux',False),('vt',True)]:
  port=free_port();client_key=uuid.uuid4().hex;env=os.environ.copy();env.update(LLMGW_LIVE_PROVIDER_KEY=secret,LLMGW_LIVE_CLIENT_KEY=client_key,LLMGW_LIVE_ADMIN_KEY=uuid.uuid4().hex)
  cmd=[str(native_dir/f'llmgw-{engine}-linux'),'--spring.profiles.active='+engine,f'--server.port={port}','--server.address=127.0.0.1','--gateway.production=true','--gateway.mock.enabled=false','--gateway.keys-store-path=','--gateway.governance.snapshot-path=','--gateway.admin-token=${LLMGW_LIVE_ADMIN_KEY}','--gateway.api-keys[0].id=live-audit','--gateway.api-keys[0].token=${LLMGW_LIVE_CLIENT_KEY}','--gateway.api-keys[0].quota-tokens=10000','--gateway.routes[0].id=live-deepseek','--gateway.routes[0].protocol=chat','--gateway.routes[0].provider=openai','--gateway.routes[0].model=deepseek-flash','--gateway.routes[0].target-model=','--gateway.routes[0].target-url=https://api.deepseek.com/v1','--gateway.routes[0].api-key=${LLMGW_LIVE_PROVIDER_KEY}','--gateway.routes[0].fallback-ids=','--gateway.upstream.retry.attempts=1','--gateway.upstream.connect-timeout-ms=10000','--gateway.upstream.first-byte-timeout-ms=30000','--gateway.upstream.timeout-ms=60000']
  row={'engine':engine,'stream':stream,'command':cmd};report['checks'].append(row);save()
  with tempfile.TemporaryDirectory(prefix='llmgw-live-') as temp:
   process=subprocess.Popen(cmd,cwd=temp,env=env,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
   try:
    opener=urllib.request.build_opener(urllib.request.ProxyHandler({}));deadline=time.monotonic()+60
    while True:
     if process.poll() is not None:raise RuntimeError('gateway startup failed; stdout intentionally not persisted')
     try:
      with opener.open(f'http://127.0.0.1:{port}/actuator/health',timeout=1) as response:
       if response.status==200:break
     except OSError:pass
     if time.monotonic()>deadline:raise TimeoutError('gateway readiness')
     time.sleep(.1)
    print('READY',engine,'starting one generation',flush=True);started=time.monotonic()
    with OpenAI(api_key=client_key,base_url=f'http://127.0.0.1:{port}/v1',max_retries=0,timeout=70) as client:
     body={'model':'deepseek-flash','messages':[{'role':'user','content':'请只回复OK。'}],'max_tokens':32,'extra_body':{'thinking':{'type':'disabled'}},'stream':stream}
     if stream:
      body['stream_options']={'include_usage':True};parts=[];usage=None;finish=None;returned_model=None
      with client.chat.completions.create(**body) as events:
       for event in events:
        returned_model=event.model
        if event.usage:usage=event.usage
        for choice in event.choices:
         if choice.delta.content:parts.append(choice.delta.content)
         if choice.finish_reason:finish=choice.finish_reason
      text=''.join(parts)
     else:
      value=client.chat.completions.create(**body);text=value.choices[0].message.content or '';usage=value.usage;finish=value.choices[0].finish_reason;returned_model=value.model
     assert text.strip(), 'no final text returned'
     assert finish=='stop',f'expected complete response, got {finish}'
     assert usage is not None and usage.total_tokens>0,'missing upstream usage'
     row.update(passed=True,seconds=time.monotonic()-started,returned_model=returned_model,finish_reason=finish,text=safe(text[:80]),usage=usage.model_dump())
     print('PASS',engine,'stream' if stream else 'json','tokens',usage.total_tokens,flush=True);save()
   finally:
    if process.poll() is None:
     process.terminate()
     try:process.wait(timeout=15)
     except subprocess.TimeoutExpired:process.kill();process.wait()
    env.pop('LLMGW_LIVE_PROVIDER_KEY',None)
 report['complete']=len(report['checks'])==2 and all(r.get('passed') for r in report['checks'])
except BaseException as error:
 report['failure']=safe(repr(error));report['complete']=False;print('Live verification stopped; see sanitized report.',flush=True)
finally:save()
if not report['complete']:raise SystemExit(1)
