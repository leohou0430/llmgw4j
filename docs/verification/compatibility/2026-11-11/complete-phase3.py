"""Check the bounded compatibility audit against actual frozen artifacts."""
import hashlib,json,subprocess
from pathlib import Path
ROOT=Path(__file__).resolve().parents[4];OUT=Path(__file__).resolve().parent
read=lambda p:json.loads(p.read_text())
profiles=read(OUT/'final-profiles/final-verification.json');contracts=read(OUT/'contracts-final/final-verification.json');sdk=read(OUT/'sdk-ci/sdk-results.json');extra=read(OUT/'additional-contracts.json');manifest=read(OUT/'source-manifest.json')
validation=[]
for name,cmd in [('openapi-ci-yaml',['/usr/local/bin/python3.14','bin/check-openapi.py','docs/openapi.yaml','.github/workflows/ci.yml','.github/workflows/release.yml','docker-compose.yml','docker-compose.redis.yml']),('git-diff-check',['git','diff','--check']),('shell-syntax',['bash','-n','bin/smoke-stream-errors.sh'])]:
    with (OUT/(name+'-final.log')).open('w') as log:result=subprocess.run(cmd,cwd=ROOT,stdout=log,stderr=subprocess.STDOUT)
    validation.append({'name':name,'command':cmd,'exit_code':result.returncode})
(OUT/'final-static-checks.json').write_text(json.dumps(validation,indent=2)+'\n')
def unchanged(p,sha):return hashlib.sha256((ROOT/p).read_bytes()).hexdigest()==sha
sdk_hash=hashlib.sha256((ROOT/'bin/smoke-sdk-compatibility.py').read_bytes()).hexdigest()
default_hash=manifest['artifacts']['bench/results/compatibility-2026-11-11/sdk-final-default.jar']
gates={
 'three_clean_profiles':profiles.get('complete') is True and len([c for c in profiles['checks'] if c['name'].startswith('final-') and c.get('exit_code')==0 and c.get('matches_frozen_modules') and c['junit']['failures']==c['junit']['errors']==c['junit']['skipped']==0])==3,
 'foundation_and_schema_boundaries':all(c.get('exit_code')==0 for c in profiles['checks'] if not c['name'].startswith('final-')),
 'official_sdk_52_checks':sdk.get('complete') is True and len(sdk['checks'])==52 and all(c.get('passed') for c in sdk['checks']) and sdk['jar_sha256']==default_hash,
 'sdk_harness_matches':sdk['harness_sha256']==sdk_hash and hashlib.sha256((OUT/'sdk-ci/harness.py').read_bytes()).hexdigest()==sdk_hash,
 'nine_http_contracts':contracts.get('complete') is True and len(contracts['checks'])==9 and all(c.get('exit_code')==0 for c in contracts['checks']) and contracts['frozen_jar_sha256']==default_hash,
 'provider_and_redis_recovery':len(extra['checks'])==2 and all(c['exit_code']==0 for c in extra['checks']),
 'vt_small_body_rechecked':len(read(OUT/'vt-413-recheck.json'))==3 and all(c['status']==413 and c['ready_seconds']<60 for c in read(OUT/'vt-413-recheck.json')),
 'source_and_artifact_integrity':all(unchanged(p,sha) for p,sha in (manifest['sources']|manifest['artifacts']).items()),
 'ci_yaml_and_syntax':all(c['exit_code']==0 for c in validation),
 'sdk_lock_consistency':(ROOT/'bin/requirements-sdk.txt').read_bytes()==(OUT/'requirements.txt').read_bytes() and 'No broken requirements found' in (OUT/'sdk-dependency-check.log').read_text(),
 'scope_documented':(OUT/'README.md').exists()}
report={'complete':all(gates.values()),'gates':gates,'artifacts':manifest['artifacts'],'sdk_versions':sdk['versions'],'initial_stream_run':'contracts/ retained 66/67 with one startup-not-ready; cause not reproduced, not claimed fixed','contract_reuse':contracts.get('reused_verified_checks',[]),'not_covered':['live suppliers and account permissions','new native artifact and performance for phase-3 fixes','other language SDKs and all supplier-specific methods','financial durability or a distributed control plane'],'remote_ci_triggered':False,'committed_pushed_released':False}
(OUT/'phase-3-completion.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report,indent=2))
if not report['complete']:raise SystemExit(1)
