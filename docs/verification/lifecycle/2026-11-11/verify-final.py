"""Reproduce this round's sequential source and frozen-JAR contracts."""
import hashlib,json,os,socket,subprocess,tempfile,time,zipfile,io
import xml.etree.ElementTree as ET
from pathlib import Path
ROOT=Path(__file__).resolve().parents[4]
OUT=Path(__file__).resolve().parent
JAR=ROOT/'bench/results/lifecycle-2026-11-11/bounded-output.jar'
env=os.environ.copy();env['JAVA_HOME']='/Users/leo/Library/Java/JavaVirtualMachines/graalvm-jdk-25/Contents/Home';env['PATH']=env['JAVA_HOME']+'/bin:'+env['PATH'];env['LLMGW_TEST_JAR']=str(JAR)
first=json.loads((OUT/'final-verification-first.json').read_text())
report={'frozen_jar_sha256':hashlib.sha256(JAR.read_bytes()).hexdigest(),'checks':[],
        'corrected_harness':'first run mistakenly selected older after.jar; preserved final-verification-first.json; current default classes match bounded-output.jar'}
for prior in first['checks']:
    if prior.get('exit_code')==0:
        prior['reused_from']='final-verification-first.json'
        report['checks'].append(prior)
def save(): (OUT/'final-verification.json').write_text(json.dumps(report,indent=2)+'\n')
def run(name,cmd):
    if any(c['name']==name and c.get('exit_code')==0 for c in report['checks']):
        print(name,'reusing verified unchanged source result',flush=True);return
    row={'name':name,'command':cmd,'started':time.time()};report['checks'].append(row);save();print(name,'started',flush=True)
    with (OUT/(name+'.log')).open('w') as log:r=subprocess.run(cmd,cwd=ROOT,env=env,stdout=log,stderr=subprocess.STDOUT)
    row.update(exit_code=r.returncode,seconds=time.time()-row['started']);save();print(name,r.returncode,flush=True)
    if r.returncode:raise RuntimeError(name+' failed; inspect its log')
def classes(path):
    result={}
    with zipfile.ZipFile(path) as outer:
        for name in outer.namelist():
            if name.startswith('BOOT-INF/lib/llmgw-') and any(x in name for x in ('core-','engine-api-','engine-webflux-','engine-vt-','provider-http-jdk-')):
                h=hashlib.sha256()
                with zipfile.ZipFile(io.BytesIO(outer.read(name))) as inner:
                    for item in sorted(n for n in inner.namelist() if n.endswith('.class')):h.update(item.encode());h.update(inner.read(item))
                result[name]=h.hexdigest()
    return result
try:
    report['frozen_module_classes']=classes(JAR)
    for prior in report['checks']:
        if prior['name']=='final-default':
            prior['matches_frozen_modules']=prior['module_classes']==report['frozen_module_classes']
            if not prior['matches_frozen_modules']:raise RuntimeError('corrected default class mismatch')
    save()
    run('module-layout',['bash','bin/check-module-layout.sh'])
    run('native-boundaries',['python3','bin/check-native-boundaries.py'])
    run('native-boundary-tests',['python3','-m','unittest','discover','-s','bin','-p','test_native_*.py'])
    run('openapi',['/usr/local/bin/python3.14','bin/check-openapi.py'])
    with tempfile.TemporaryDirectory(prefix='llmgw-lifecycle-redis-') as temp:
        with socket.socket() as s:s.bind(('127.0.0.1',0));port=s.getsockname()[1]
        with open(Path(temp)/'redis.log','w') as log:
            redis=subprocess.Popen(['redis-server','--bind','127.0.0.1','--port',str(port),'--save','','--appendonly','no','--dir',temp],stdout=log,stderr=subprocess.STDOUT)
            try:
                identity=False
                for _ in range(100):
                    if redis.poll() is not None:raise RuntimeError('owned Redis exited')
                    r=subprocess.run(['redis-cli','-p',str(port),'INFO','server'],capture_output=True,text=True)
                    if f'process_id:{redis.pid}' in r.stdout:identity=True;break
                    time.sleep(.05)
                if not identity:raise RuntimeError('Redis identity not verified')
                report['redis']={'pid':redis.pid,'port':port,'process_identity_verified':True};save();env['LLMGW_TEST_REDIS_PORT']=str(port)
                for name,profile in [('default',None),('redis','redis'),('combined','redis,jtokkit,otel')]:
                    cmd=['mvn','-B']+(['-P'+profile] if profile else [])+['verify']
                    run('final-'+name,cmd)
                    counts={key:0 for key in ('tests','failures','errors','skipped')}
                    for xml in ROOT.glob('**/target/surefire-reports/TEST-*.xml'):
                        suite=ET.parse(xml).getroot()
                        for key in counts:counts[key]+=int(suite.attrib.get(key,0))
                    row=next(c for c in report['checks'] if c['name']=='final-'+name)
                    if 'junit' not in row:row['junit']=counts
                    actual=classes(ROOT/'llmgw-app/target/llmgw-app-0.3.1.jar')
                    row['module_classes']=actual
                    row['matches_frozen_modules']=actual==report['frozen_module_classes'];save()
                    if actual!=report['frozen_module_classes']:raise RuntimeError('profile class mismatch')
            finally:
                if redis.poll() is None:redis.terminate();redis.wait(timeout=10)
                report.setdefault('redis',{})['cleanup_exit_code']=redis.returncode;save()
    for name in ['smoke-heartbeat-deadline.py','smoke-sse-line-limit.py','smoke-stream-errors.sh','smoke-v13-extensions.sh','smoke-v13-hardening.sh','smoke-multi-protocol.sh','smoke-tenant-isolation.py','smoke-admin-auth.py','smoke-jwt-auth.py']:
        cmd=['bash' if name.endswith('.sh') else 'python3','bin/'+name]
        if name in ('smoke-admin-auth.py','smoke-jwt-auth.py'):cmd+=['--jar',str(JAR)]
        run('final-'+name.replace('.py','').replace('.sh',''),cmd)
    report['complete']=True
except BaseException as error:
    report['failure']=repr(error);raise
finally:save()
