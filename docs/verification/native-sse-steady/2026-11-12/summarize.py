"""Validate steady SSE work, load guards and fixed artifacts before reporting ratios."""
import hashlib,json,re,statistics
from pathlib import Path
root=Path(__file__).resolve().parents[4];out=Path(__file__).resolve().parent;base=root/'bench/results/native-sse-steady-2026-11-12/serial-vs-jvm-stable-window'
manifest=json.loads((base/'manifest.json').read_text());rows=json.loads((base/'samples.json').read_text())
assert manifest['duration']=='60s' and manifest['warmup']=='60s' and manifest['max_host_load_ratio']==0.75
assert manifest['cpu']==2 and manifest['memory_bytes']==2147483648 and len(rows)==9
for record in [*manifest['jars'].values(),*manifest['native_binaries'].values()]:assert hashlib.sha256(Path(record['path']).read_bytes()).hexdigest()==record['sha256']
def value(raw,key):return int(re.search(r'^'+key+r' (\d+)$',raw,re.M)[1])
for r in rows:
 assert r['exit']==0 and r['performance_valid'] and r['metrics']['audit_unexpected_failure']['value']==0
 if r['label']=='direct':continue
 count=r['metrics']['audit_successes']['count']
 assert r['upstream_delta']=={'requests':count,'active':0,'completed':count,'cancelled':0}
 assert r['metrics'].get('audit_backoffs',{}).get('count',0)==r['metrics'].get('audit_rejected',{}).get('count',0)
 assert value(r['resources_after'],'oom')==value(r['resources_after'],'oom_kill')==0
summary={}
for label in ('before-webflux','after-webflux'):
 trials=[r for r in rows if r['label']==label and isinstance(r['round'],int) and r['round']>0];assert len(trials)==3
 cpu=[(value(r['resources_after'],'usage_usec')-value(r['resources_before'],'usage_usec'))/1000/r['metrics']['audit_successes']['count'] for r in trials]
 p99=[r['metrics']['audit_end_to_end_ms']['p(99)'] for r in trials];rates=[r['metrics']['audit_successes']['rate'] for r in trials]
 summary[label]={'runtime':'JVM' if label.startswith('before') else 'native Serial','p99_ms':p99,'p99_median_ms':statistics.median(p99),'success_rates':rates,'success_rate_median':statistics.median(rates),'container_cpu_ms_per_success':cpu,'container_cpu_ms_per_success_median':statistics.median(cpu),'process_cgroup_peak_bytes':max(int(r['resources_after'].splitlines()[1]) for r in trials),'capacity_rejections':sum(r['metrics'].get('audit_rejected',{}).get('count',0) for r in trials),'success_count':sum(r['metrics']['audit_successes']['count'] for r in trials),'throttled_periods':[value(r['resources_after'],'nr_throttled')-value(r['resources_before'],'nr_throttled') for r in trials]}
before,after=summary['before-webflux'],summary['after-webflux']
report={'complete':True,'groups':summary,'p99_native_to_jvm_ratio':after['p99_median_ms']/before['p99_median_ms'],'throughput_native_to_jvm_ratio':after['success_rate_median']/before['success_rate_median'],'artifacts':{'jars':manifest['jars'],'native_binaries':manifest['native_binaries']},'samples':len(rows),'duration':'60s','warmup':'60s','raw_path':str(base.relative_to(root)),'limitations':['Local controlled upstream, closed-loop 64 VUs, not production/vendor maximum QPS','p99 is median of per-trial p99s, not a pooled percentile','cgroup memory.peak accumulates across the lifetime of each process','Host load guard is a coarse filter and does not isolate thermal/scheduling effects']}
(out/'results-summary.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps({'p99_ratio':report['p99_native_to_jvm_ratio'],'throughput_ratio':report['throughput_native_to_jvm_ratio'],'groups':summary},indent=2))
