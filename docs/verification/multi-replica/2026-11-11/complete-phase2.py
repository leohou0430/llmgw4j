"""Audit phase-2 evidence without overwriting source measurements."""
import hashlib,json,re
from pathlib import Path
ROOT=Path(__file__).resolve().parents[4];OUT=Path(__file__).resolve().parent
read=lambda p:json.loads(p.read_text())
profiles=read(OUT/'final-profiles/final-verification.json');contracts=read(OUT/'contracts/final-verification.json');distributed=read(OUT/'distributed-final.json');capacity=read(OUT/'capacity-summary.json');sources=read(OUT/'source-manifest.json')
gates={
 'module_and_native_boundaries':all(c.get('exit_code')==0 for c in profiles['checks'] if not c['name'].startswith('final-')),
 'three_clean_profiles':profiles.get('complete') is True and len([c for c in profiles['checks'] if c['name'].startswith('final-') and c.get('exit_code')==0 and c.get('matches_frozen_modules') and c['junit']['failures']==c['junit']['errors']==c['junit']['skipped']==0])==3,
 'redis_consistency':read(OUT/'breaker-green-run.json')['test_exit_code']==0 and 'Tests run: 26, Failures: 0, Errors: 0, Skipped: 0' in (OUT/'breaker-green.log').read_text(),
 'dual_engine_http_contracts':contracts.get('complete') is True and len(contracts['checks'])==9 and all(c.get('exit_code')==0 for c in contracts['checks']),
 'contract_classes_apply_to_final':read(OUT/'checkpoint-to-final.json')['common_http_modules_identical'] is True,
 'distributed_quota_and_recovery':len(distributed['checks'])==2 and all(c['exit_code']==0 for c in distributed['checks']),
 'capacity_and_resource_checks':read(OUT/'capacity-exit.json')['exit_code']==0 and capacity['trial_count']==34 and not capacity['failed_assertions'],
 'latency_recheck_recorded':(OUT/'warm-json-summary.json').exists() and not read(OUT/'warm-json-summary.json')['failed_assertions'],
 'sources_unchanged':all(hashlib.sha256((ROOT/p).read_bytes()).hexdigest()==sha for p,sha in sources['sources'].items()),
 'artifacts_unchanged':all(hashlib.sha256((ROOT/p).read_bytes()).hexdigest()==sha for p,sha in distributed['jars'].items())}
report={'complete':all(gates.values()),'gates':gates,'artifacts':distributed['jars'],'native_scope':'no new phase-2 native build; no AOT changes; phase-1 native remains historical','unsupported_scope':['financial durability and crash reconciliation','versioned distributed control plane','Redis Cluster/Sentinel','live supplier compatibility']}
(OUT/'phase-2-completion.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report,indent=2))
if not report['complete']:raise SystemExit(1)
