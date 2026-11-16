"""Read actuator metrics only from this experiment's exact owned containers."""
import json,subprocess,time,urllib.request
from pathlib import Path
out=Path('docs/verification/native-sse/2026-11-12/metrics.json');rows=[]
manifest=Path('bench/results/native-sse-2026-11-12/repro/manifest.json')
d=json.loads(manifest.read_text());ids={k:v for k,v in d['containers'].items() if k.endswith('webflux')}
for _ in range(40):
 for label,cid in ids.items():
  result=subprocess.run(['docker','port',cid,'8080/tcp'],capture_output=True,text=True)
  if result.returncode:continue
  port=result.stdout.strip().rsplit(':',1)[1];values={}
  for metric in ('system.cpu.count','jvm.gc.pause','jvm.gc.memory.allocated','jvm.threads.live','process.cpu.usage'):
   req=urllib.request.Request(f'http://127.0.0.1:{port}/actuator/metrics/{metric}',headers={'X-Admin-Token':'audit-admin','Authorization':'Bearer audit-admin'})
   try:
    with urllib.request.urlopen(req,timeout=2) as r:values[metric]=json.load(r)
   except Exception as e:values[metric]={'error':str(e)}
  rows.append({'time':time.time(),'label':label,'metrics':values})
 out.write_text(json.dumps(rows,indent=2)+'\n')
 if all(subprocess.run(['docker','inspect',cid],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode for cid in ids.values()):break
 time.sleep(3)
