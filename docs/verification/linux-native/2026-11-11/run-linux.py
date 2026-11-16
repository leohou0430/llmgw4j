"""Own all containers; preserve build/test evidence even when a step fails."""
import hashlib,json,subprocess,uuid
from pathlib import Path
ROOT=Path(__file__).resolve().parents[4];OUT=Path(__file__).resolve().parent;ART=ROOT/'bench/results/linux-native-2026-11-11';prefix='llmgw-linux-native-'+uuid.uuid4().hex[:10];owned=[];report={}
def docker(*args):return subprocess.check_output(['docker',*map(str,args)],text=True,stderr=subprocess.STDOUT).strip()
def save():(OUT/'run.json').write_text(json.dumps(report,indent=2)+'\n')
try:
 inputs=json.loads((OUT/'inputs.json').read_text())
 assert all(hashlib.sha256((ROOT/name).read_bytes()).hexdigest()==sha for name,sha in inputs['source_files'].items()),'source changed since verified checkpoint'
 report['image']=json.loads(docker('image','inspect','llmgw-native-audit:linux-20261111'))[0]['Id']
 redis=docker('create','--name',prefix+'-redis','--memory','128m','--memory-swap','128m','--cpus','1','--entrypoint','redis-server','redis:7-alpine','--bind','127.0.0.1','--save','','--appendonly','no');owned.append(redis);docker('start',redis)
 builder=docker('create','--name',prefix+'-builder','--network','container:'+redis,'--memory','16g','--memory-swap','16g','--cpus','12','--entrypoint','/bin/bash','llmgw-native-audit:linux-20261111','-c','sleep infinity');owned.append(builder);report.update(builder=builder,redis=redis,source_mount=False);save();docker('start',builder)
 docker('exec',builder,'mkdir','-p','/audit')
 if (ART/'attempt-1').exists():
  docker('cp',str(ART/'attempt-1')+'/.',builder+':/audit')
  docker('exec',builder,'mv','/audit/status.json','/audit/status-prior.json')
  report['reused_checkpoint']='attempt-1';save()
 docker('cp',OUT/'linux-audit.py',builder+':/audit/linux-audit.py')
 docker('cp',ROOT/'docs/verification/lifecycle/2026-11-11/native-idle-progress.py',builder+':/audit/native-idle-progress.py')
 run=subprocess.run(['docker','exec',builder,'python3','/audit/linux-audit.py'],stdout=(OUT/'runner.log').open('w'),stderr=subprocess.STDOUT)
 report['exit_code']=run.returncode;save()
finally:
 if report.get('builder'):
  try:
   docker('cp',report['builder']+':/audit/.',ART/'evidence')
   report['container_state']=json.loads(docker('inspect',report['builder']))[0]['State']
  except Exception as error:report['evidence_copy_error']=str(error)
 for cid in reversed(owned):
  try:docker('rm','-f',cid)
  except Exception as error:report.setdefault('cleanup_errors',[]).append(str(error))
 report['cleaned_container_ids']=owned;save()
if report.get('exit_code',1):raise SystemExit(report.get('exit_code',1))
