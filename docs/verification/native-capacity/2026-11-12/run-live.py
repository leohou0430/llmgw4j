"""Own one constrained container; no provider credential in arguments or files."""
import json,subprocess,uuid
from pathlib import Path
root=Path.cwd();out=root/'docs/verification/native-capacity/2026-11-12';art=root/'bench/results/linux-native-2026-11-11/evidence';cid=None

def docker(*args,**kwargs):return subprocess.check_output(['docker',*map(str,args)],text=True,**kwargs).strip()
try:
 cid=docker('create','--name','llmgw-native-live-'+uuid.uuid4().hex[:10],'--cpus','2','--memory','2g','--memory-swap','2g','--entrypoint','bash','llmgw-native-audit:linux-20261111','-c','sleep infinity')
 docker('start',cid);docker('exec',cid,'mkdir','-p','/audit')
 for e in ('webflux','vt'):docker('cp',art/f'llmgw-{e}-linux',cid+f':/audit/llmgw-{e}-linux')
 docker('cp',out/'verify-native-deepseek.py',cid+':/audit/verify-native-deepseek.py')
 status=subprocess.call(['docker','exec','-it',cid,'python3','/audit/verify-native-deepseek.py','--native-dir','/audit','--out','/audit/live'])
 if status:raise RuntimeError('live test exited '+str(status))
finally:
 if cid:
  subprocess.run(['docker','cp',cid+':/audit/live/.',str(out/'live')],check=False)
  docker('rm','-f',cid)
