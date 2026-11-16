import json, os, socket, subprocess, random
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
ROOT=Path('/Users/leo/WorkBuddy/2026-11-06-01-50-12/llm-gateway')
OUT=Path('/tmp/llmgw-audit-20261116')
PY=str(OUT/'venv/bin/python')
def port():
    for _ in range(100):
        p=random.randrange(21000,29000)
        try:
            with socket.socket() as s: s.bind(('127.0.0.1',p))
            return p
        except OSError: pass
    raise RuntimeError('no audit port')
commands=[
 ('stream-errors',['bash',str(ROOT/'bin/smoke-stream-errors.sh')]),
 ('multi-protocol',['bash',str(ROOT/'bin/smoke-multi-protocol.sh')]),
 ('extensions',['bash',str(ROOT/'bin/smoke-v13-extensions.sh')]),
 ('hardening',['bash',str(ROOT/'bin/smoke-v13-hardening.sh')]),
 ('tenant-isolation',[PY,str(ROOT/'bin/smoke-tenant-isolation.py')]),
 ('admin-auth',[PY,str(ROOT/'bin/smoke-admin-auth.py')]),
 ('jwt-auth',[PY,str(ROOT/'bin/smoke-jwt-auth.py')]),
 ('conversion-sdk',[PY,str(ROOT/'bin/smoke-conversion-sdk.py'),'--jar',str(OUT/'audited.jar'),'--out',str(OUT/'conversion-sdk')]),
]
results=[]
def run(item):
    name,cmd=item
    folder=OUT/('suite-'+name); folder.mkdir(exist_ok=True)
    env={k:v for k,v in os.environ.items() if not k.startswith(('LLMGW_','SPRING_','GATEWAY_','DASHSCOPE_'))}
    mock=port()
    url=f'http://127.0.0.1:{mock}/mock/v1'
    env.update(LLMGW_TEST_JAR=str(OUT/'audited.jar'), LLMGW_CONTROL_DB=str(folder/'control.db'),
               PORT=str(port()), MOCK_PORT=str(mock), NO_PROXY='*',
               JAVA_TOOL_OPTIONS=f'-Dgateway.mock.port={mock} -Dgateway.upstream.base-url={url} -Dgateway.routes[0].target-url={url}')
    with open(OUT/(name+'.log'),'w') as log:
        try:
            proc=subprocess.run(cmd,cwd=folder,env=env,stdout=log,stderr=subprocess.STDOUT,timeout=900)
            row={'suite':name,'exit':proc.returncode}
        except subprocess.TimeoutExpired:
            row={'suite':name,'timeout':True}
    return row
if __name__=='__main__':
    with ThreadPoolExecutor(max_workers=2) as pool:
        for future in as_completed([pool.submit(run,item) for item in commands]):
            row=future.result(); results.append(row)
            (OUT/'contract-results.json').write_text(json.dumps(results,indent=2))
            print(json.dumps(row),flush=True)
