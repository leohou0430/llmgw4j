"""Build and validate an existing G1 profile in one owned disposable Linux builder."""
import hashlib,json,subprocess,uuid
from pathlib import Path
root=Path(__file__).resolve().parents[4];out=Path(__file__).resolve().parent;art=root/'bench/results/native-g1-2026-11-12';art.mkdir(parents=True,exist_ok=True);cid=None;report={}
def docker(*args):return subprocess.check_output(['docker',*map(str,args)],text=True,stderr=subprocess.STDOUT).strip()
def save():(out/'run.json').write_text(json.dumps(report,indent=2)+'\n')
try:
 inputs=json.loads((root/'docs/verification/linux-native/2026-11-11/inputs.json').read_text())
 assert all(hashlib.sha256((root/name).read_bytes()).hexdigest()==sha for name,sha in inputs['source_files'].items()),'source changed since checkpoint'
 report['image']=json.loads(docker('image','inspect','llmgw-native-audit:linux-20261111'))[0]['Id']
 cid=docker('create','--name','llmgw-native-g1-'+uuid.uuid4().hex[:10],'--cpus','12','--memory','16g','--memory-swap','16g','--entrypoint','bash','llmgw-native-audit:linux-20261111','-c','sleep infinity');report['builder']=cid;save();docker('start',cid);docker('exec',cid,'mkdir','-p','/audit')
 for source,target in [(out/'g1-audit.py','g1-audit.py'),(root/'docs/verification/linux-native/2026-11-11/inputs.json','inputs.json'),(root/'bench/results/linux-native-2026-11-11/evidence/jvm-default.jar','jvm-default.jar'),(root/'docs/verification/lifecycle/2026-11-11/native-idle-progress.py','native-idle-progress.py')]:docker('cp',source,cid+':/audit/'+target)
 with (out/'runner.log').open('w') as log:r=subprocess.run(['docker','exec',cid,'python3','/audit/g1-audit.py'],stdout=log,stderr=subprocess.STDOUT)
 report['exit_code']=r.returncode;save()
finally:
 if cid:
  try:docker('cp',cid+':/audit/.',art/'evidence');report['state']=json.loads(docker('inspect',cid))[0]['State']
  finally:docker('rm','-f',cid);report['cleaned_builder']=cid;save()
if report.get('exit_code',1):raise SystemExit(report.get('exit_code',1))
