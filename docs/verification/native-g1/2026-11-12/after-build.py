"""Follow the exact owned builder, then require a five-minute quiet window for A/B/C."""
import json,os,subprocess,time
from pathlib import Path
root=Path(__file__).resolve().parents[4];out=Path(__file__).resolve().parent;report={'status':'waiting_build','checks':[]}
def save():(out/'comparison-status.json').write_text(json.dumps(report,indent=2)+'\n')
cid=json.loads((out/'run.json').read_text())['builder'];save()
while subprocess.run(['docker','inspect',cid],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode==0:time.sleep(30)
build=json.loads((out/'run.json').read_text());assert build['exit_code']==0 and build['cleaned_builder']==cid,build
art=root/'bench/results/native-g1-2026-11-12';evidence=art/'evidence';validation=json.loads((evidence/'status.json').read_text());assert validation['complete']
bin_dir=art/'perf-bin';bin_dir.mkdir(exist_ok=True);binary=evidence/'llmgw-webflux-g1-linux';(bin_dir/'llmgw-webflux-linux').symlink_to(binary.resolve())
report['status']='waiting_quiet';stable=0;last=None
while stable<11:
 processes=subprocess.check_output(['ps','-ww','-Ao','args='],text=True).splitlines()
 compilers=sum(1 for line in processes if line.split()[0].endswith(('clang','clang++','cc1','cc1plus')))
 drivers=sum(1 for line in processes if '/build.rb' in line and 'ruby' in line.split()[0])
 load=os.getloadavg();quiet=compilers==0 and drivers==0 and load[0]<=.75*(os.cpu_count() or 1);stable=stable+1 if quiet else 0
 row={'time':time.strftime('%Y-%m-%dT%H:%M:%S%z'),'compilers':compilers,'build_drivers':drivers,'loadavg':load,'quiet_samples':stable};report['checks'].append(row);save()
 current='settling' if quiet else 'host busy'
 if current!=last:print(current,row,flush=True);last=current
 if stable<11:time.sleep(30)
report['status']='running';save();print('starting JVM / Serial / G1 comparison',flush=True)
jar='bench/results/linux-native-2026-11-11/evidence/jvm-default.jar';cmd=['python3',str(out/'run-threeway.py'),'--before',jar,'--after',jar,'--native-dir',str(bin_dir),'--output',str(art/'threeway'),'--rounds','3','--duration','60s','--warmup','60s','--warmup-vus','64','--vus','64','--modes','sse','--labels','jvm-webflux,before-webflux,after-webflux','--skip-long','--respect-retry-after','--max-host-load-ratio','0.75'];env=os.environ.copy();env['JAVA_HOME']='/Users/leo/Library/Java/JavaVirtualMachines/graalvm-jdk-25/Contents/Home';env['PATH']=env['JAVA_HOME']+'/bin:'+env['PATH'];report['command']=cmd;save()
with (out/'threeway.log').open('w') as log:r=subprocess.run(cmd,cwd=root,env=env,stdout=log,stderr=subprocess.STDOUT)
report.update(status='finished' if r.returncode==0 else 'failed',exit_code=r.returncode);save();print(report['status'],r.returncode,flush=True)
raise SystemExit(r.returncode)
