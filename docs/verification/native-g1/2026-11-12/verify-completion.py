"""Recheck bounded G1 evidence and exact owned container cleanup; no supplier calls."""
import json,re,subprocess,sys
from pathlib import Path
root=Path(__file__).resolve().parents[4];out=Path(__file__).resolve().parent;art=root/'bench/results/native-g1-2026-11-12'
subprocess.run([sys.executable,str(out/'summarize.py')],check=True,stdout=subprocess.DEVNULL)
build=json.loads((art/'evidence/status.json').read_text());run=json.loads((out/'run.json').read_text());post=json.loads((out/'post-results.json').read_text());completion=json.loads((out/'completion.json').read_text());summary=json.loads((out/'results-summary.json').read_text());sha=summary['artifacts']['native_binaries']['after-webflux']['sha256']
assert build['complete'] and post['complete'] and completion['complete']
assert build['source_files_verified']==249 and build['native']['webflux']['compared_jvm_classes']==174
assert not build['native']['webflux']['class_mismatches'] and build['native']['webflux']['sha256']==sha
assert all(c['exit_code']==0 for c in build['checks']+post['checks'])
assert len(json.loads((art/'evidence/sdk-webflux/sdk-results.json').read_text())['checks'])==26
for name,count in [('stream-webflux.log',34),('protocol-webflux.log',28)]:assert f'PASS={count} FAIL=0' in (art/'evidence'/name).read_text()
for p in [art/'slow-webflux-h0/slow-clients.json',art/'slow-webflux-h50/slow-clients.json']:
 d=json.loads(p.read_text());r=d['results'][0];assert d['native_binaries']['webflux']['sha256']==sha
 assert len(r['clients_completed'])==64 and not r['client_errors'] and max(v['headers_ms'] for v in r['clients_completed'])<=10000
 assert r['stats_after_cancel']=={'requests':64,'active':0,'completed':0,'cancelled':64}
 assert r['stats_final']=={'requests':1,'active':0,'completed':1,'cancelled':0} and r['recovery']=='200 ok'
 assert r['resources_after'].splitlines()[:3]==['200000 100000','2147483648','0']
 assert all(int(re.search(r'^'+key+r' (\d+)$',r['resources_after'],re.M)[1])==0 for key in ('oom','oom_kill'))
 assert not r['container_state']['OOMKilled'] and r['out_of_memory_errors']==0
tls=json.loads((art/'tls/results.json').read_text());assert tls['complete'] and tls['sha256']==sha and tls['external_requests']==0 and len(tls['checks'])==4
for r in tls['checks']:
 assert r['status']==r['expected']
 if r['certificate']=='wrong-host':assert 'subject alternative DNS name' in r['body']
 elif not r['custom_trust']:assert 'certification path' in r['body']
assert not completion['default_runtime_changed'] and completion['new_supplier_requests']==0
subprocess.run(['docker','info','--format','{{.ServerVersion}}'],check=True,stdout=subprocess.DEVNULL)
cids=[run['builder'],post['tls_container'],*json.loads((art/'threeway/manifest.json').read_text())['containers'].values()]
assert run['cleaned_builder']==run['builder'] and post['tls_container_cleaned']
for cid in cids:assert subprocess.run(['docker','inspect',cid],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode!=0,cid
print('PASS G1 evidence: 13 performance samples, SDK/protocols, 2 slow-reader cases, 4 TLS cases, exact owned containers removed')
