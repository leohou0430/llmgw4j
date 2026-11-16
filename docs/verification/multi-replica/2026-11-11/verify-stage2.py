"""Reproduce this round's sequential source and frozen-JAR contracts."""
import hashlib,json,os,socket,subprocess,tempfile,time,zipfile,io
import xml.etree.ElementTree as ET
from pathlib import Path
ROOT=Path(__file__).resolve().parents[4]
import argparse
parser=argparse.ArgumentParser()
parser.add_argument('--jar',type=Path,required=True)
parser.add_argument('--out',type=Path,required=True)
parser.add_argument('--stage',choices=['profiles','contracts'],required=True)
args=parser.parse_args()
OUT=args.out.resolve();OUT.mkdir(parents=True,exist_ok=True)
JAR=args.jar.resolve()
env=os.environ.copy();env['JAVA_HOME']='/Users/leo/Library/Java/JavaVirtualMachines/graalvm-jdk-25/Contents/Home';env['PATH']=env['JAVA_HOME']+'/bin:'+env['PATH'];env['LLMGW_TEST_JAR']=str(JAR)
report={'frozen_jar_sha256':hashlib.sha256(JAR.read_bytes()).hexdigest(),'stage':args.stage,'checks':[]}
def save(): (OUT/'final-verification.json').write_text(json.dumps(report,indent=2)+'\n')
def run(name,cmd):
    row={'name':name,'command':cmd,'started':time.time()};report['checks'].append(row);save();print(name,'started',flush=True)
    with (OUT/(name+'.log')).open('w') as log:r=subprocess.run(cmd,cwd=ROOT,env=env,stdout=log,stderr=subprocess.STDOUT)
    row.update(exit_code=r.returncode,seconds=time.time()-row['started']);save();print(name,r.returncode,flush=True)
    if r.returncode:raise RuntimeError(name+' failed; inspect its log')
def classes(path):
    result={}
    with zipfile.ZipFile(path) as outer:
        for name in outer.namelist():
            if name.startswith('BOOT-INF/lib/llmgw-') and any(x in name for x in ('core-','engine-api-','engine-webflux-','engine-vt-','provider-http-jdk-','governance-api-','governance-default-','governance-redis-')):
                h=hashlib.sha256()
                with zipfile.ZipFile(io.BytesIO(outer.read(name))) as inner:
                    for item in sorted(n for n in inner.namelist() if n.endswith('.class')):h.update(item.encode());h.update(inner.read(item))
                result[name]=h.hexdigest()
    return result
try:
    report['frozen_module_classes']=classes(JAR)
    save()
    if args.stage=='profiles':
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
                        if f'process_id:{redis.pid}' in r.stdout.splitlines():identity=True;break
                        time.sleep(.05)
                    if not identity:raise RuntimeError('Redis identity not verified')
                    report['redis']={'pid':redis.pid,'port':port,'process_identity_verified':True};save();env['LLMGW_TEST_REDIS_PORT']=str(port)
                    for name,profile in [('default',None),('redis','redis'),('combined','redis,jtokkit,otel')]:
                        cmd=['mvn','-B']+(['-P'+profile] if profile else [])+['clean','verify']
                        run('final-'+name,cmd)
                        counts={key:0 for key in ('tests','failures','errors','skipped')}
                        for xml in ROOT.glob('**/target/surefire-reports/TEST-*.xml'):
                            if xml.stat().st_mtime < next(c for c in report['checks'] if c['name']=='final-'+name)['started']:continue
                            suite=ET.parse(xml).getroot()
                            for key in counts:counts[key]+=int(suite.attrib.get(key,0))
                        row=next(c for c in report['checks'] if c['name']=='final-'+name)
                        if 'junit' not in row:row['junit']=counts
                        actual=classes(ROOT/'llmgw-app/target/llmgw-app-0.3.1.jar')
                        row['module_classes']=actual
                        row['matches_frozen_modules']=actual=={k:v for k,v in report['frozen_module_classes'].items() if profile or 'governance-redis-' not in k};save()
                        if not row['matches_frozen_modules']:raise RuntimeError('profile class mismatch')
                finally:
                    if redis.poll() is None:redis.terminate();redis.wait(timeout=10)
                    report.setdefault('redis',{})['cleanup_exit_code']=redis.returncode;save()
    if args.stage=='contracts':
        for name in ['smoke-heartbeat-deadline.py','smoke-sse-line-limit.py','smoke-stream-errors.sh','smoke-v13-extensions.sh','smoke-v13-hardening.sh','smoke-multi-protocol.sh','smoke-tenant-isolation.py','smoke-admin-auth.py','smoke-jwt-auth.py']:
            cmd=['bash' if name.endswith('.sh') else 'python3','bin/'+name]
            if name in ('smoke-admin-auth.py','smoke-jwt-auth.py'):cmd+=['--jar',str(JAR)]
            run('final-'+name.replace('.py','').replace('.sh',''),cmd)
    report['complete']=True
except BaseException as error:
    report['failure']=repr(error);raise
finally:save()
