"""Run in the owned Linux builder; no host source mounts or paid requests."""
import hashlib,io,json,os,platform,shutil,subprocess,time,zipfile,xml.etree.ElementTree as ET
from pathlib import Path
ROOT=Path('/work');OUT=Path('/audit');OUT.mkdir(exist_ok=True)
report={'platform':platform.platform(),'checks':[],'paid_provider_calls':0,'profiles':{},'native':{}}
env=os.environ.copy();env['LLMGW_TEST_REDIS_PORT']='6379'
def save():(OUT/'status.json').write_text(json.dumps(report,indent=2)+'\n')
def run(name,cmd,extra=None):
 row={'name':name,'command':cmd,'started':time.time()};report['checks'].append(row);save();print(name,'started',flush=True)
 with (OUT/(name+'.log')).open('w') as log:r=subprocess.run(cmd,cwd=ROOT,env=env| (extra or {}),stdout=log,stderr=subprocess.STDOUT)
 row.update(exit_code=r.returncode,seconds=time.time()-row['started']);save();print(name,r.returncode,flush=True)
 if r.returncode:raise RuntimeError(name+' failed')
 return row
try:
 inputs=json.loads((OUT/'inputs.json').read_text())
 assert all(hashlib.sha256((ROOT/name).read_bytes()).hexdigest()==sha for name,sha in inputs['source_files'].items()),'image source differs from verified snapshot'
 report['source_files_verified']=len(inputs['source_files']);save()
 run('toolchain',['bash','-c','java -version; native-image --version; mvn -version'])
 for engine in ['webflux']:
  name='llmgw-'+engine+'-g1-linux'
  run('build-'+engine,['mvn','-B','-o','-pl','llmgw-app','-am','-Pnative-g1','-DskipTests','-Dllmgw.native.profile='+engine,'-Dllmgw.native.image='+name,'-DjvmArgs=-Xmx10g','clean','package'])
  binary=OUT/name;shutil.copy2(ROOT/'llmgw-app/target'/name,binary)
  report['native'][engine]={'sha256':hashlib.sha256(binary.read_bytes()).hexdigest(),'bytes':binary.stat().st_size};save()
  run('file-'+engine,['file',str(binary)])
  compared=0;mismatches=[]
  with zipfile.ZipFile(OUT/'jvm-default.jar') as outer:
   for name_in_jar in outer.namelist():
    if not name_in_jar.startswith('BOOT-INF/lib/llmgw-'):continue
    module=Path(name_in_jar).name.removesuffix('-0.3.1.jar');dirs=list(ROOT.glob('**/'+module+'/target/classes'));assert len(dirs)==1
    with zipfile.ZipFile(io.BytesIO(outer.read(name_in_jar))) as inner:
     for entry in inner.namelist():
      if entry.endswith('.class'):
       compared+=1
       if (dirs[0]/entry).read_bytes()!=inner.read(entry):mismatches.append(module+':'+entry)
  report['native'][engine].update(compared_jvm_classes=compared,class_mismatches=mismatches);save();assert not mismatches,mismatches
  run('abi-'+engine,['bash','-c','objdump -T '+str(binary)+" | grep -oE 'GLIBC_[0-9.]+' | sort -Vu"])
  run('sdk-'+engine,['python3','bin/smoke-sdk-compatibility.py','--binary',str(binary),'--engine',engine,'--out',str(OUT/('sdk-'+engine))])
  sdk=json.loads((OUT/('sdk-'+engine)/'sdk-results.json').read_text());assert sdk['complete'] and len(sdk['checks'])==26
  native_env={'BINARY':str(binary),'ENGINE':engine}
  run('stream-'+engine,['bash','bin/smoke-stream-errors.sh',engine],native_env)
  run('protocol-'+engine,['bash','bin/smoke-multi-protocol.sh',engine],native_env)
  run('tenant-'+engine,['python3','bin/smoke-tenant-isolation.py'],native_env)
  run('idle-'+engine,['python3','/audit/native-idle-progress.py','--binary',str(binary),'--engine',engine,'--out',str(OUT/('idle-'+engine))])
 for name,cmd in [('module-layout',['bash','bin/check-module-layout.sh']),('native-boundaries',['python3','bin/check-native-boundaries.py']),('native-rule-tests',['python3','-m','unittest','discover','-s','bin','-p','test_native_*.py'])]:run(name,cmd)
 report['memory_events']=Path('/sys/fs/cgroup/memory.events').read_text();report['memory_peak']=Path('/sys/fs/cgroup/memory.peak').read_text().strip()
 report['complete']=True
except BaseException as error:report['failure']=repr(error);raise
finally:save()
