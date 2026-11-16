"""Summarize accepted work separately from capacity refusals; keep raw trials unchanged."""
import argparse,json,re,statistics
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--input',type=Path,required=True);p.add_argument('--output',type=Path,required=True);args=p.parse_args()
rows=json.loads((args.input/'samples.json').read_text());manifest=json.loads((args.input/'manifest.json').read_text());groups={};failures=[]
def count(row,name):return row['metrics'].get(name,{}).get('count',0)
def cpu(raw):return int(re.search(r'^usage_usec (\d+)$',raw,re.M)[1])
def check(ok,message):
    if not ok:failures.append(message)
regular=[r for r in rows if isinstance(r['round'],int) and r['round']>0 and r['label']!='direct']
longs=[r for r in rows if r['round']=='long']
check(len(regular)==48,'expected 48 regular trials');check(len(longs)==4,'expected four long-stream trials')
for row in rows:
    label=f"{row['label']}/{row['mode']}/{row['vus']}/{row['round']}"
    check(row['exit']==0,label+' nonzero k6 exit')
    check(row['metrics'].get('audit_unexpected_failure',{}).get('value',1)==0,label+' unexpected failure')
    if row['label']=='direct':continue
    for name in ('oom','oom_kill'):
        value=int(re.search(r'^'+name+r' (\d+)$',row['resources_after'],re.M)[1]);check(value==0,label+' cgroup '+name)
    if row['label'].startswith('after'):
        success=count(row,'audit_successes');delta=row['upstream_delta']
        check(delta['requests']==success,label+' refused request reached upstream or accepted work missing')
        check(delta['active']==0 and delta['cancelled']==0 and delta['completed']==success,label+' normal upstream completion mismatch')
        check(count(row,'audit_backoffs')==count(row,'audit_rejected'),label+' missing valid Retry-After backoff')
        if row['round']=='long':check(success==32,label+' expected 32 completed long streams')
for row in regular:
    key=(row['label'],row['mode'],row['vus']);groups.setdefault(key,[]).append(row)
summary=[]
for (label,mode,vus),trials in sorted(groups.items()):
    success=sum(count(r,'audit_successes') for r in trials);rejected=sum(count(r,'audit_rejected') for r in trials)
    entry={'label':label,'mode':mode,'vus':vus,'rounds':len(trials),'successes':success,'capacity_rejections':rejected,
           'median_success_per_second':statistics.median(r['metrics']['audit_successes']['rate'] for r in trials),
           'median_p99_ms':statistics.median(r['metrics']['audit_end_to_end_ms']['p(99)'] for r in trials),
           'median_container_cpu_ms_per_success':statistics.median((cpu(r['resources_after'])-cpu(r['resources_before']))/1000/count(r,'audit_successes') for r in trials),
           'peak_cgroup_memory_bytes':max(int(r['resources_after'].splitlines()[1]) for r in trials)}
    if label.startswith('after') and vus==96:check(rejected>0,str((label,mode,vus))+' no observed overload refusal')
    summary.append(entry)
comparisons=[]
for engine in ('webflux','vt'):
    for mode in ('json','sse'):
        for vus in (64,96):
            before=next((x for x in summary if x['label']=='before-'+engine and x['mode']==mode and x['vus']==vus),None)
            after=next((x for x in summary if x['label']=='after-'+engine and x['mode']==mode and x['vus']==vus),None)
            if before and after:
                comparisons.append({'engine':engine,'mode':mode,'vus':vus,'success_per_second_percent':(after['median_success_per_second']/before['median_success_per_second']-1)*100,'cpu_per_success_percent':(after['median_container_cpu_ms_per_success']/before['median_container_cpu_ms_per_success']-1)*100})
for log in args.input.glob('*.log'):
    check('OutOfMemoryError' not in log.read_text(errors='replace'),str(log)+' JVM OOM')
report={'jars':manifest['jars'],'trial_count':len(rows),'regular_trial_count':len(regular),'long_trial_count':len(longs),'load':{'rounds':3,'duration':'6s','vus':[64,96],'retry_after_respected':True},'groups':summary,'comparisons':comparisons,'failed_assertions':failures,'limitations':['local controlled upstream; not production/vendor maximum QPS','p99 is median of three per-trial p99s, not a pooled percentile','container CPU/success includes refusals and sampling/drain work; 64 VUs are at the admission ceiling, not a refusal-free SLO','45s near-limit slow-reader matrix is a separate experiment']}
args.output.write_text(json.dumps(report,indent=2)+'\n')
print('trials',len(rows),'failed checks',len(failures))
if failures:print('\n'.join(failures));raise SystemExit(1)
