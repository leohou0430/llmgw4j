"""Verify controlled runs and keep diagnostic runtime labels explicit."""
import hashlib,json,re,statistics
from pathlib import Path
root=Path(__file__).resolve().parents[4];out=Path(__file__).resolve().parent
reports={}
for name in ('quiet-retest','young-probe','quiet-confirmation'):
 base=root/'bench/results/native-sse-2026-11-12'/name
 manifest=json.loads((base/'manifest.json').read_text());rows=json.loads((base/'samples.json').read_text())
 assert len(rows)==9
 for r in rows:
  assert r['exit']==0 and r['performance_valid']
  assert r['metrics']['audit_unexpected_failure']['value']==0
  if r['label']=='direct':continue
  successes=r['metrics']['audit_successes']['count'];delta=r['upstream_delta']
  assert delta=={'requests':successes,'active':0,'completed':successes,'cancelled':0},delta
  assert all(int(re.search(r'^'+key+r' (\d+)$',r['resources_after'],re.M)[1])==0 for key in ('oom','oom_kill'))
 summary={}
 for label in ('before-webflux','after-webflux'):
  trials=[r for r in rows if r['label']==label and isinstance(r['round'],int) and r['round']>0];assert len(trials)==3
  p99=[r['metrics']['audit_end_to_end_ms']['p(99)'] for r in trials]
  rates=[r['metrics']['audit_successes']['rate'] for r in trials]
  summary[label]={'p99_ms':p99,'p99_median_ms':statistics.median(p99),'success_per_second':rates,'success_per_second_median':statistics.median(rates)}
 report={'runtime_labels':manifest.get('runtime_labels',{'before':'JVM','after':'Linux native Serial GC'}),'groups':summary,'p99_after_to_before_ratio':summary['after-webflux']['p99_median_ms']/summary['before-webflux']['p99_median_ms'],'success_rate_after_to_before_ratio':summary['after-webflux']['success_per_second_median']/summary['before-webflux']['success_per_second_median'],'artifacts':{'jars':manifest['jars'],'native_binaries':manifest['native_binaries']},'samples':len(rows),'all_load_guards_passed':True,'raw':str(base.relative_to(root))}
 if name=='young-probe':
  for label in summary:
   cid=manifest['containers'][label];text=(base/(cid[:12]+'.log')).read_text();values=[float(x) for x in re.findall(r'Pause .*GC .*? ([\d.]+)ms',text)]
   report['groups'][label]['gc_entire_process']={'count':len(values),'median_ms':statistics.median(values),'max_ms':max(values),'total_ms':sum(values),'includes_startup_warmup_and_trials':True}
 reports[name]=report
(out/'results-summary.json').write_text(json.dumps(reports,indent=2)+'\n')
for name,r in reports.items():print(name,'p99 ratio',round(r['p99_after_to_before_ratio'],3),'success rate ratio',round(r['success_rate_after_to_before_ratio'],3))
