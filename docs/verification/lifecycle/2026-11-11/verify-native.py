"""Fresh WebFlux AOT build and contracts, without overwriting bin/native."""
import hashlib,json,os,shutil,subprocess,time
from pathlib import Path
ROOT=Path(__file__).resolve().parents[4];OUT=Path(__file__).resolve().parent
RESULT=ROOT/'bench/results/lifecycle-2026-11-11';BINARY=RESULT/'llmgw-webflux-native'
env=os.environ.copy();env['JAVA_HOME']='/Users/leo/Library/Java/JavaVirtualMachines/graalvm-jdk-25/Contents/Home';env['PATH']=env['JAVA_HOME']+'/bin:'+env['PATH'];env['ENGINE']='webflux'
report={'checks':[]}
def save():(OUT/'final-native.json').write_text(json.dumps(report,indent=2)+'\n')
def run(name,cmd):
    row={'name':name,'command':cmd,'started':time.time()};report['checks'].append(row);save();print(name,'started',flush=True)
    with (OUT/(name+'.log')).open('w') as log:r=subprocess.run(cmd,cwd=ROOT,env=env,stdout=log,stderr=subprocess.STDOUT)
    row.update(exit_code=r.returncode,seconds=time.time()-row['started']);save();print(name,r.returncode,flush=True)
    if r.returncode:raise RuntimeError(name+' failed')
try:
    source={}
    for module in ('llmgw-core','llmgw-provider/llmgw-provider-http-jdk','llmgw-engine/llmgw-engine-api','llmgw-engine/llmgw-engine-webflux','llmgw-app'):
        for p in (ROOT/module/'src/main').rglob('*'):
            if p.is_file():source[str(p.relative_to(ROOT))]=hashlib.sha256(p.read_bytes()).hexdigest()
    report['sources']=source;save()
    run('final-native-build',['mvn','-B','-pl','llmgw-app','-am','-DskipTests','-Pnative','-Dllmgw.native.profile=webflux','-Dllmgw.native.image=llmgw-lifecycle-webflux','clean','package'])
    shutil.copy2(ROOT/'llmgw-app/target/llmgw-lifecycle-webflux',BINARY)
    report['binary']={'path':str(BINARY),'sha256':hashlib.sha256(BINARY.read_bytes()).hexdigest()};save()
    wrapper=RESULT/'native-contract-wrapper.py'
    wrapper.write_text('#!/usr/bin/env python3\nimport os,sys\nargs=sys.argv[1:]\nvalues={arg.split("=",1)[0]:arg.split("=",1)[1] for arg in args if arg.startswith("--") and "=" in arg}\nport=values.get("--gateway.mock.port",os.environ.get("MOCK_PORT","9090"))\nurl="http://127.0.0.1:"+port+"/mock/v1"\nfor key,value in {"--gateway.mock.port":port,"--gateway.upstream.base-url":url,"--gateway.routes[0].target-url":url}.items():\n    if key not in values:args.append(key+"="+value)\nbinary='+repr(str(BINARY))+'\nos.execv(binary,[binary,*args])\n')
    wrapper.chmod(0o755);env['BINARY']=str(wrapper)
    for name in ('smoke-stream-errors.sh','smoke-multi-protocol.sh','smoke-tenant-isolation.py'):
        cmd=['bash' if name.endswith('.sh') else 'python3','bin/'+name]+(['webflux'] if name.endswith('.sh') else [])
        run('native-'+name.replace('.sh','').replace('.py',''),cmd)
    run('native-idle-progress',['python3',str(OUT/'native-idle-progress.py'),'--binary',str(BINARY),'--out',str(OUT)])
    report['complete']=True
except BaseException as error:report['failure']=repr(error);raise
finally:save()
