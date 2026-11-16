#!/usr/bin/env python3
"""Preserve each capacity trial and reject invalid rounds before aggregating."""
import argparse, hashlib, json, re, statistics
from collections import defaultdict
from pathlib import Path

def resources(raw):
    if not raw:return None
    lines=raw.splitlines();result={'memory_current':int(lines[0]),'memory_peak_lifetime':int(lines[1])}
    for key in ['oom','oom_kill','usage_usec','nr_periods','nr_throttled','throttled_usec']:
        found=re.search(r'^'+key+r' (\d+)$',raw,re.M);result[key]=int(found[1]) if found else None
    for key in ['VmRSS','VmHWM']:
        found=re.search(r'^'+key+r':\s*(\d+) kB$',raw,re.M)
        if found:result[key+'_bytes']=int(found[1])*1024
    return result

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('input',type=Path);parser.add_argument('output',type=Path);args=parser.parse_args()
    args.output.mkdir(parents=True,exist_ok=True);rows=json.loads((args.input/'samples.json').read_text());trials=[];groups=defaultdict(list)
    for row in rows:
        m=row['metrics'];success=m.get('audit_successes',{});rejected=m.get('audit_rejected',{}).get('count',0);unexpected=m['audit_unexpected_failure']['passes'];count=success.get('count',0)
        invalid=[]
        if row['exit']:invalid.append('load exit '+str(row['exit']))
        if unexpected:invalid.append('unexpected responses '+str(unexpected))
        if not count:invalid.append('no successful responses')
        if m.get('dropped_iterations',{}).get('count',0):invalid.append('load driver dropped scheduled iterations')
        if count!=row['upstream_delta']['requests']:invalid.append('upstream count mismatch')
        if row['upstream_delta']['active']:invalid.append('upstream still active')
        entry={k:row[k] for k in ['label','mode','vus','round','exit','upstream_delta']}
        entry.update({k:row[k] for k in ['started','ended','host_loadavg_before','host_loadavg_after','arrival_rate'] if k in row})
        entry.update(client_backoffs=m.get('audit_backoffs',{}).get('count',0),client_backoff_ms=m.get('audit_backoff_ms',{}).get('count',0),iteration_latency=m.get('iteration_duration'),http_requests=m.get('http_reqs',{}).get('count',0),dropped_iterations=m.get('dropped_iterations',{}).get('count',0))
        entry.update(success=count,success_rps=success.get('rate',0),rejected=rejected,unexpected=unexpected,invalid=invalid,latency=m.get('audit_end_to_end_ms'),duration_minus_mock=m.get('audit_duration_minus_mock_ms'),preflight=m.get('audit_preflight_ms'),resources_before=resources(row.get('resources_before')),resources_after=resources(row.get('resources_after')))
        before,after=entry['resources_before'],entry['resources_after']
        if before and after:entry['cpu_delta']={key:after[key]-before[key] for key in ['usage_usec','nr_periods','nr_throttled','throttled_usec']}
        trials.append(entry)
        if isinstance(row['round'],int) and row['round']>0:groups[row['label'],row['mode'],row['vus']].append(entry)
    report={'manifest':json.loads((args.input/'manifest.json').read_text()),'trials':trials,'raw_sha256':{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(args.input.iterdir()) if p.is_file()}}
    (args.output/'matrix.json').write_text(json.dumps(report,indent=2))
    lines=['| 产物/引擎 | 模式 | VU | 有效轮/总轮 | 成功 RPS 中位数 [范围] | p95 ms 中位数 | p99 ms 中位数 | 容量拒绝数 | 非预期错误数 |','|---|---|---:|---:|---:|---:|---:|---:|---:|']
    for (label,mode,vus),items in sorted(groups.items()):
        good=[x for x in items if not x['invalid']];rps=[x['success_rps'] for x in good]
        if good:
            rate=f'{statistics.median(rps):.1f} [{min(rps):.1f}, {max(rps):.1f}]'
            p95=f"{statistics.median(x['latency']['p(95)'] for x in good):.2f}";p99=f"{statistics.median(x['latency']['p(99)'] for x in good):.2f}"
        else:rate=p95=p99='无有效轮'
        lines.append(f"| {label} | {mode} | {vus} | {len(good)}/{len(items)} | {rate} | {p95} | {p99} | {sum(x['rejected'] for x in items)} | {sum(x['unexpected'] for x in items)} |")
    (args.output/'matrix.md').write_text('\n'.join(lines)+'\n')
    print('trials',len(trials),'successful',sum(x['success'] for x in trials),'rejected',sum(x['rejected'] for x in trials),'unexpected',sum(x['unexpected'] for x in trials),'invalid',sum(bool(x['invalid']) for x in trials))
if __name__=='__main__':main()
