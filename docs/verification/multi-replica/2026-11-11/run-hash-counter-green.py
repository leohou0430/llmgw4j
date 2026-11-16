import os,socket,subprocess,tempfile,time,json
from pathlib import Path
root=Path(__file__).resolve().parents[4];out=Path(__file__).resolve().parent;report={};env=os.environ.copy();env['JAVA_HOME']='/Users/leo/Library/Java/JavaVirtualMachines/graalvm-jdk-25/Contents/Home';env['PATH']=env['JAVA_HOME']+'/bin:'+env['PATH']
try:
 with tempfile.TemporaryDirectory(prefix='llmgw-stage2-redis-') as temp:
  with socket.socket() as s:s.bind(('127.0.0.1',0));port=s.getsockname()[1]
  with (out/'redis.log').open('w') as log:
   server=subprocess.Popen(['redis-server','--bind','127.0.0.1','--port',str(port),'--save','','--appendonly','no','--dir',temp],stdout=log,stderr=subprocess.STDOUT)
   try:
    for _ in range(100):
     if server.poll() is not None:raise RuntimeError('owned Redis exited')
     info=subprocess.run(['redis-cli','-p',str(port),'INFO','server'],text=True,capture_output=True)
     if f'process_id:{server.pid}' in info.stdout.splitlines():break
     time.sleep(.05)
    else:raise RuntimeError('owned Redis PID not verified')
    report.update(pid=server.pid,port=port,process_identity_verified=True);env['LLMGW_TEST_REDIS_PORT']=str(port)
    cmd=['mvn','-B','-pl','llmgw-governance/llmgw-governance-redis','-am','-Dtest=RedisConsistencyTest,RedisTokenLimiterTest','-Dsurefire.failIfNoSpecifiedTests=false','test'];report['command']=cmd
    with (out/'hash-counter-green.log').open('w') as result:r=subprocess.run(cmd,cwd=root,env=env,stdout=result,stderr=subprocess.STDOUT)
    report['test_exit_code']=r.returncode
   finally:
    if server.poll() is None:server.terminate();server.wait(timeout=10)
    report['redis_cleanup_exit_code']=server.returncode
finally:(out/'hash-counter-green-run.json').write_text(json.dumps(report,indent=2)+'\n')

if report.get("test_exit_code",1):raise SystemExit(report["test_exit_code"])
