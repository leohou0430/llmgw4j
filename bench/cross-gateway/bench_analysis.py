"""Read k6 evidence and rank complete, zero-error runs within one profile/mode."""
import math
import statistics
from collections import defaultdict


def parse_summary(document):
    try:
        metrics = document['metrics']
        def metric(name):
            value = metrics[name]
            return value.get('values', value)
        requests = metric('gateway_requests')
        errors = metric('gateway_failure_rate')
        latency = metric('gateway_latency_ms')
        result = dict(requests=requests['count'], rps=requests['rate'],
            error_rate=errors.get('value', errors.get('rate')),
            p50_ms=latency['p(50)'], p95_ms=latency['p(95)'], p99_ms=latency['p(99)'])
        if any(value is None or not math.isfinite(value) or value < 0 for value in result.values()):
            raise ValueError('invalid k6 measurement')
        if result['requests'] <= 0 or result['rps'] <= 0 or result['error_rate'] > 1:
            raise ValueError('empty or invalid k6 measurement')
        return result
    except (KeyError, TypeError) as error:
        raise ValueError('missing required k6 evidence') from error


def summarize(rows, rounds):
    groups = defaultdict(list)
    for row in rows:
        groups[(row['profile'], row['mode'], row['arm'])].append(row)
    ranked, excluded = [], []
    for (profile, mode, arm), samples in groups.items():
        reason = None
        if len(samples) != rounds or len({s['round'] for s in samples}) != rounds:
            reason = 'incomplete rounds'
        elif any(s.get('exit_code') != 0 or s.get('error_rate') != 0 or s.get('invalid_reason') for s in samples):
            reason = 'failed requests, invalid evidence or k6 failure'
        if reason:
            excluded.append(dict(profile=profile, mode=mode, arm=arm, reason=reason))
            continue
        item = dict(profile=profile, mode=mode, arm=arm)
        for field in ['rps', 'p50_ms', 'p95_ms', 'p99_ms']:
            item[field + '_median'] = statistics.median(s[field] for s in samples)
        item['rps_min'] = min(s['rps'] for s in samples)
        item['rps_max'] = max(s['rps'] for s in samples)
        ranked.append(item)
    ranked.sort(key=lambda r: (r['profile'], r['mode'], -r['rps_median']))
    return dict(rankings=ranked, excluded=excluded)
