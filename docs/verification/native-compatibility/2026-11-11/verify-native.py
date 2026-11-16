"""Validate the frozen WebFlux native artifact without paid provider calls."""
import hashlib,io,json,os,subprocess,time,zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[4];OUT=Path(__file__).resolve().parent
BINARY=ROOT/'bench/results/native-compatibility-2026-11-11/llmgw-sdk-webflux'
JAR=ROOT/'bench/results/compatibility-2026-11-11/sdk-final-default.jar'
env=os.environ.copy();env['JAVA_HOME']='/Users/leo/Library/Java/JavaVirtualMachines/graalvm-jdk-25/Contents/Home';env['PATH']=env['JAVA_HOME']+'/bin:'+env['PATH'];env['BINARY']=str(BINARY);env['ENGINE']='webflux'
report={'binary_sha256':hashlib.sha256(BINARY.read_bytes()).hexdigest(),'jvm_jar_sha256':hashlib.sha256(JAR.read_bytes()).hexdigest(),'engine':'webflux','checks':[],'paid_calls':0}
def save(): (OUT/'native-results.json').write_text(json.dumps(report,indent=2)+'\n')
def run(name,command):
 row={'name':name,'command':command};report['checks'].append(row);save();start=time.monotonic();print(name,'started',flush=True)
 with (OUT/(name+'.log')).open('w') as log:result=subprocess.run(command,cwd=ROOT,env=env,stdout=log,stderr=subprocess.STDOUT)
 row.update(exit_code=result.returncode,seconds=time.monotonic()-start);save();print(name,result.returncode,flush=True)
 if result.returncode:raise RuntimeError(name+' failed')
try:
 sources=json.loads((OUT/'build-source.json').read_text());mismatches=[name for name,sha in sources.items() if hashlib.sha256((ROOT/name).read_bytes()).hexdigest()!=sha]
 report['source_mismatches']=mismatches;assert not mismatches
 class_mismatches=[];count=0
 with zipfile.ZipFile(JAR) as outer:
  for name in outer.namelist():
   if not name.startswith('BOOT-INF/lib/llmgw-'):continue
   module=Path(name).name.removesuffix('-0.3.1.jar');dirs=list(ROOT.glob('**/'+module+'/target/classes'));assert len(dirs)==1,(module,dirs)
   with zipfile.ZipFile(io.BytesIO(outer.read(name))) as inner:
    for item in inner.namelist():
     if item.endswith('.class'):
      count+=1
      if (dirs[0]/item).read_bytes()!=inner.read(item):class_mismatches.append(module+':'+item)
 report.update(compared_jvm_classes=count,class_mismatches=class_mismatches);save();assert not class_mismatches
 python=ROOT/'bench/results/compatibility-2026-11-11/sdk-venv/bin/python'
 run('native-sdk',[str(python),'bin/smoke-sdk-compatibility.py','--binary',str(BINARY),'--engine','webflux','--out',str(OUT/'native-sdk')])
 sdk=json.loads((OUT/'native-sdk/sdk-results.json').read_text());assert sdk['complete'] and len(sdk['checks'])==26
 for script in ['smoke-stream-errors.sh','smoke-multi-protocol.sh']:run('native-'+script.removesuffix('.sh'),['bash','bin/'+script,'webflux'])
 run('native-tenant',['python3','bin/smoke-tenant-isolation.py'])
 run('native-idle-progress',['python3',str(ROOT/'docs/verification/lifecycle/2026-11-11/native-idle-progress.py'),'--binary',str(BINARY),'--out',str(OUT/'idle')])
 run('module-layout',['bash','bin/check-module-layout.sh'])
 run('native-boundaries',['python3','bin/check-native-boundaries.py'])
 run('native-rule-tests',['python3','-m','unittest','discover','-s','bin','-p','test_native_*.py'])
 run('diff-check',['git','diff','--check'])
 jvm=json.loads((OUT/'jvm-sdk-fixed/sdk-results.json').read_text());assert jvm['complete'] and len(jvm['checks'])==52
 report['complete']=True
except BaseException as error:report['failure']=repr(error);raise
finally:save()
