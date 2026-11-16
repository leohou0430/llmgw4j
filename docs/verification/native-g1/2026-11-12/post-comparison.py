"""Run G1 slow-reader and local certificate contracts only after performance cleanup."""
import json,os,subprocess,time,uuid
from pathlib import Path
root=Path(__file__).resolve().parents[4];out=Path(__file__).resolve().parent;art=root/'bench/results/native-g1-2026-11-12';report={'checks':[],'supplier_requests':0};env=os.environ.copy();env['JAVA_HOME']='/Users/leo/Library/Java/JavaVirtualMachines/graalvm-jdk-25/Contents/Home';env['PATH']=env['JAVA_HOME']+'/bin:'+env['PATH']
def save():(out/'post-results.json').write_text(json.dumps(report,indent=2)+'\n')
def docker(*args):return subprocess.check_output(['docker',*map(str,args)],text=True,stderr=subprocess.STDOUT).strip()
def run(name,cmd):
 print(name,'started',flush=True)
 with (out/(name+'.log')).open('w') as log:r=subprocess.run(cmd,cwd=root,env=env,stdout=log,stderr=subprocess.STDOUT)
 report['checks'].append({'name':name,'command':cmd,'exit_code':r.returncode});save();print(name,r.returncode,flush=True)
 if r.returncode:raise RuntimeError(name+' failed')
try:
 while True:
  try:state=json.loads((out/'comparison-status.json').read_text())
  except json.JSONDecodeError:time.sleep(5);continue
  if state['status'] in ('finished','failed'):break
  time.sleep(5)
 assert state['status']=='finished' and state['exit_code']==0,state
 run('summary',['python3',str(out/'summarize.py')])
 for heartbeat in (0,50):
  name=f'slow-webflux-h{heartbeat}'
  run(name,['python3','bench/capacity/slow_clients.py','--jar','bench/results/linux-native-2026-11-11/evidence/jvm-default.jar','--native-dir',str(art/'perf-bin'),'--engine','webflux','--output',str(art/name),'--clients','64','--padding','1048400','--chunks','4096','--hold-seconds','45','--header-timeout','10','--heartbeat-ms',str(heartbeat)])
 cid=None
 try:
  cid=docker('create','--name','llmgw-g1-tls-'+uuid.uuid4().hex[:10],'--cpus','2','--memory','2g','--memory-swap','2g','--entrypoint','bash','llmgw-native-audit:linux-20261111','-c','sleep infinity');report['tls_container']=cid;save();docker('start',cid);docker('exec',cid,'mkdir','-p','/audit');docker('cp',art/'evidence/llmgw-webflux-g1-linux',cid+':/audit/llmgw-webflux-linux');docker('cp',out/'tls-g1.py',cid+':/audit/tls-g1.py');run('tls',['docker','exec',cid,'python3','/audit/tls-g1.py'])
 finally:
  if cid:
   try:docker('cp',cid+':/audit/tls/.',art/'tls')
   finally:docker('rm','-f',cid);report['tls_container_cleaned']=True;save()
 report['complete']=True
except BaseException as error:report['failure']=repr(error);raise
finally:save()
