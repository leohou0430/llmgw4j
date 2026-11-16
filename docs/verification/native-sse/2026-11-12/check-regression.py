"""Red when native SSE p99 exceeds same-source JVM by more than 25%."""
import json,statistics,sys
from pathlib import Path
rows=json.loads((Path(sys.argv[1])/'samples.json').read_text())
groups={label:[r for r in rows if r['label']==label and r['mode']=='sse' and r['vus']==64 and isinstance(r['round'],int) and r['round']>0] for label in ('before-webflux','after-webflux')}
assert all(len(rs)==3 for rs in groups.values())
assert all(r['exit']==0 and r['metrics']['audit_unexpected_failure']['value']==0 for rs in groups.values() for r in rs)
p99={label:statistics.median(r['metrics']['audit_end_to_end_ms']['p(99)'] for r in rs) for label,rs in groups.items()}
ratio=p99['after-webflux']/p99['before-webflux']
print(json.dumps({'p99_ms':p99,'native_to_jvm_ratio':ratio,'threshold':1.25}))
if ratio>1.25:sys.exit(1)
