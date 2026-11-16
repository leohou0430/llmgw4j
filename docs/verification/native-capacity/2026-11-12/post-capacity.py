"""Wait for the current capacity network to finish, then run slow readers and local TLS."""
import json,os,subprocess,time,uuid
from pathlib import Path
ROOT=Path(__file__).resolve().parents[4];OUT=Path(__file__).resolve().parent;ART=ROOT/'bench/results/native-capacity-2026-11-12';BIN=ROOT/'bench/results/linux-native-2026-11-11/evidence';env=os.environ.copy();env['JAVA_HOME']='/Users/leo/Library/Java/JavaVirtualMachines/graalvm-jdk-25/Contents/Home';env['PATH']=env['JAVA_HOME']+'/bin:'+env['PATH'];report={'checks':[],'paid_calls':0}
def save():(OUT/'post-results.json').write_text(json.dumps(report,indent=2)+'\n')
def run(name,cmd):
 print(name,'started',flush=True)
 with (OUT/(name+'.log')).open('w') as log:r=subprocess.run(cmd,cwd=ROOT,env=env,stdout=log,stderr=subprocess.STDOUT)
 report['checks'].append({'name':name,'command':cmd,'exit_code':r.returncode});save();print(name,r.returncode,flush=True)
 if r.returncode:raise RuntimeError(name+' failed')
def docker(*args):return subprocess.check_output(['docker',*map(str,args)],text=True,stderr=subprocess.STDOUT).strip()
try:
 manifest=json.loads((ART/'capacity/manifest.json').read_text());mock=manifest['containers']['mock'];deadline=time.monotonic()+1800
 while subprocess.run(['docker','inspect',mock],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode==0:
  if time.monotonic()>deadline:raise TimeoutError('capacity cleanup not observed; post-tests not started')
  time.sleep(3)
 rows=json.loads((ART/'capacity/samples.json').read_text());assert len(rows)==62 and all(r['exit']==0 for r in rows),'capacity prerequisites failed'
 for engine in ['webflux','vt']:
  for heartbeat in [0,50]:
   name=f'slow-{engine}-h{heartbeat}'
   run(name,['python3','bench/capacity/slow_clients.py','--jar',str(BIN/'jvm-default.jar'),'--native-dir',str(BIN),'--engine',engine,'--output',str(ART/name),'--clients','64','--padding','1048400','--chunks','4096','--hold-seconds','45','--header-timeout','10','--heartbeat-ms',str(heartbeat)])
 cid=None
 try:
  cid=docker('create','--name','llmgw-native-tls-'+uuid.uuid4().hex[:10],'--cpus','2','--memory','2g','--memory-swap','2g','--no-healthcheck','--entrypoint','bash','llmgw-native-audit:linux-20261111','-c','sleep infinity');report['tls_container']=cid;save();docker('start',cid);docker('exec',cid,'mkdir','-p','/audit')
  for engine in ['webflux','vt']:docker('cp',BIN/('llmgw-'+engine+'-linux'),cid+':/audit/llmgw-'+engine+'-linux')
  docker('cp',OUT/'tls-check.py',cid+':/audit/tls-check.py')
  run('tls',['docker','exec',cid,'python3','/audit/tls-check.py'])
 finally:
  if cid:
   try:docker('cp',cid+':/audit/tls/.',ART/'tls')
   finally:docker('rm','-f',cid);report['tls_container_cleaned']=True;save()
 report['complete']=True
except BaseException as error:report['failure']=repr(error);raise
finally:save()
