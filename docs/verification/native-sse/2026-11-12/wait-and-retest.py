"""Wait in this task for LLVM to finish and three quiet host samples, then retest."""
import json,os,subprocess,time
from pathlib import Path
root=Path(__file__).resolve().parents[4];out=Path(__file__).resolve().parent
state={'status':'waiting','checks':[]};stable=0;last=None
while stable<3:
 processes=subprocess.check_output(['ps','-ww','-Ao','args='],text=True).splitlines()
 compiling=sum(1 for line in processes if line.split()[0].endswith(('clang','clang++','cc1','cc1plus')) and 'llvm-project' in line)
 drivers=sum(1 for line in processes if '/build.rb' in line and 'llvm' in line and 'ruby' in line.split()[0])
 load=os.getloadavg();quiet=compiling==0 and drivers==0 and load[0]<=1.5*(os.cpu_count() or 1)
 stable=stable+1 if quiet else 0
 row={'time':time.strftime('%Y-%m-%dT%H:%M:%S%z'),'llvm_compilers':compiling,'llvm_build_drivers':drivers,'loadavg':load,'quiet_samples':stable}
 state['checks'].append(row);(out/'wait-status.json').write_text(json.dumps(state,indent=2)+'\n')
 current='settling' if quiet else ('LLVM compiling' if compiling or drivers else 'host busy')
 if current!=last:print(current,row,flush=True);last=current
 if stable<3:time.sleep(30)
state['status']='running';(out/'wait-status.json').write_text(json.dumps(state,indent=2)+'\n');print('quiet environment confirmed; starting retest',flush=True)
env=os.environ.copy();env['JAVA_HOME']='/Users/leo/Library/Java/JavaVirtualMachines/graalvm-jdk-25/Contents/Home';env['PATH']=env['JAVA_HOME']+'/bin:'+env['PATH']
cmd=['python3','bench/capacity/run.py','--before','bench/results/linux-native-2026-11-11/evidence/jvm-default.jar','--after','bench/results/linux-native-2026-11-11/evidence/jvm-default.jar','--native-dir','bench/results/linux-native-2026-11-11/evidence','--output','bench/results/native-sse-2026-11-12/quiet-retest','--rounds','3','--duration','12s','--warmup','30s','--warmup-vus','64','--vus','64','--modes','sse','--labels','before-webflux,after-webflux','--skip-long','--respect-retry-after']
state['command']=cmd
with (out/'quiet-retest.log').open('w') as log:result=subprocess.run(cmd,cwd=root,env=env,stdout=log,stderr=subprocess.STDOUT)
state.update(status='finished' if result.returncode==0 else 'failed',exit_code=result.returncode);(out/'wait-status.json').write_text(json.dumps(state,indent=2)+'\n');print(state['status'],result.returncode,flush=True)
raise SystemExit(result.returncode)
