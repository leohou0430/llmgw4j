#!/usr/bin/env python3
"""Reproducible Docker-only gateway proxy comparison; no paid upstreams."""
import argparse
import csv
import hashlib
import json
import os
import platform
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timezone
from pathlib import Path
from bench_analysis import parse_summary, summarize

ROOT = Path(__file__).resolve().parents[2]
IMAGES = {
    'base': 'llmgw-audit:redis',
    'litellm': 'ghcr.io/berriai/litellm:v1.108.3',
    'bifrost': 'maximhq/bifrost:v2.5.0',
    'portkey': 'portkeyai/gateway:1.15.2',
    'nginx': 'nginx:stable-alpine',
}
CLIENT_KEY, UPSTREAM_KEY = 'benchmark-client', 'benchmark-upstream'


def http(port, path='/v1/chat/completions', body=None, key=CLIENT_KEY, method=None, timeout=10):
    data = body if isinstance(body, bytes) else None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(f'http://127.0.0.1:{port}' + path, data=data, method=method,
        headers={'Content-Type': 'application/json', 'Authorization': 'Bearer ' + key})
    try:
        with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(req, timeout=timeout) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as error:
        return error.code, error.read()


def round_order(arms, rnd):
    offset = (rnd-1) % len(arms)
    order = arms[offset:] + arms[:offset]
    if len(arms)>2 and ((rnd-1)//len(arms))%2:
        order.reverse()
    return order


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--jar', type=Path, default=ROOT / 'llmgw-app/target/llmgw-app-0.3.0.jar')
    parser.add_argument('--before-jar', type=Path, help='Optional immutable artifact before performance changes')
    parser.add_argument('--rounds', type=int, default=3)
    parser.add_argument('--duration', default='10s')
    parser.add_argument('--warmup', default='3s')
    parser.add_argument('--vus', type=int, default=50)
    parser.add_argument('--profiles', default='wire,inference')
    parser.add_argument('--modes', default='json,sse')
    parser.add_argument('--governance', choices=['on','off'], default='off')
    parser.add_argument('--upstream-threads', type=int, help='Explicit llmgw HTTP executor size for tuning')
    parser.add_argument('--before-upstream-threads', type=int, help='Executor size for the before arm')
    parser.add_argument('--jfr', action='store_true', help='Profile llmgw containers; not a throughput comparison')
    parser.add_argument('--arms', default='direct,llmgw-webflux-proxy,llmgw-vt-proxy,litellm,bifrost,portkey')
    parser.add_argument('--preflight-only', action='store_true')
    args = parser.parse_args()
    if args.before_jar:
        args.arms += ',llmgw-webflux-before'
    if args.governance == 'on' or args.jfr:
        if any(a.strip() not in ('direct','llmgw-webflux-proxy','llmgw-vt-proxy','llmgw-vt-before','llmgw-webflux-before') for a in args.arms.split(',')):
            parser.error('governance/JFR lanes support only llmgw and direct control')
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    raw = output / 'raw'
    raw.mkdir(exist_ok=True)
    ids, ports, rows = [], {}, []
    addresses = {}
    profiled = set()
    suffix = uuid.uuid4().hex[:10]
    network = 'llmgw-compare-' + suffix
    frontend_network = network + '-front'
    manifest = dict(started_at=datetime.now(timezone.utc).isoformat(),
        host=dict(platform=platform.platform(), cpu_count=os.cpu_count()),
        cpu_per_gateway=4, memory_per_gateway='2g', proxy_cpu=2, proxy_memory='128m',
        upstream_cpu=4, upstream_memory='1g', vus=args.vus, rounds=args.rounds,
        duration=args.duration, warmup=args.warmup, governance=args.governance, profiling=args.jfr, payload_bytes=int(os.environ.get('PAYLOAD_BYTES','0')),
        upstream_threads=dict(candidate=args.upstream_threads,before=args.before_upstream_threads),
        lane='common static bearer auth + '+('local in-memory governance' if args.governance=='on' else 'proxy only'),
        jar_sha256=hashlib.sha256(args.jar.read_bytes()).hexdigest(),
        before_jar_sha256=hashlib.sha256(args.before_jar.read_bytes()).hexdigest() if args.before_jar else None,
        source_sha256={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest()
            for p in [Path(__file__).resolve(),ROOT/'bench/cross-gateway/bench_analysis.py',ROOT/'bench/mock/StandaloneMockUpstream.java',ROOT/'bench/k6-json.js',ROOT/'bench/k6-sse.js']},
        containers={}, images={}, preflight=[], setup_errors=[], warmup_failures=[], profiles={})

    def docker(*parts):
        return subprocess.check_output(['docker', *map(str, parts)], text=True, stderr=subprocess.STDOUT).strip()

    def save():
        (output / 'manifest.json').write_text(json.dumps(manifest, indent=2))
        (output / 'samples.json').write_text(json.dumps(rows, indent=2))
        ranking = summarize(rows, args.rounds)
        ranking['excluded'] += manifest['warmup_failures']
        (output / 'ranking.json').write_text(json.dumps(ranking, indent=2))

    def container(alias, image, port=None, env=None, volumes=None, command=None, entry=None,
                  cpu=4, memory='2g'):
        cmd = ['create', '--name', alias + '-' + suffix, '--network', frontend_network if port else network,
               '--network-alias', alias, '--cpus', str(cpu), '--memory', memory, '--no-healthcheck']
        if port:
            cmd += ['-p', f'127.0.0.1::{port}']
        for key, value in (env or {}).items():
            cmd += ['-e', key + '=' + str(value)]
        if entry:
            cmd += ['--entrypoint', entry]
        cid = docker(*cmd, image, *(command or []))
        ids.append(cid)
        manifest['containers'][alias] = cid
        if args.jfr and alias.startswith('llmgw-'): profiled.add(cid)
        if port:
            docker('network','connect','--alias',alias,network,cid)
        for source, target in (volumes or []):
            source = Path(source)
            docker('cp', str(source) + ('/.' if source.is_dir() else ''), cid + ':' + target)
        docker('start', cid)
        addresses[alias] = json.loads(docker('inspect', cid))[0]['NetworkSettings']['Networks'][network]['IPAddress']
        host_port = int(docker('port', cid, f'{port}/tcp').split(':')[-1]) if port else None
        return cid, host_port

    def config_file(path, text):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.parent.chmod(0o755)
        path.write_text(text)
        path.chmod(0o644)
        return path

    def k6(arm, port, profile, mode, rnd, warm=False):
        tag = f'{profile}-{mode}-{arm}-' + ('warm' if warm else f'r{rnd}')
        summary, log = raw / (tag + '.json'), raw / (tag + '.log')
        env = {**os.environ, 'BASE_URL': f'http://127.0.0.1:{port}', 'API_KEY': CLIENT_KEY,
            'ENGINE': arm, 'GOVERNANCE': args.governance, 'RUNTIME': 'docker', 'VUS': str(args.vus),
            'DURATION': args.warmup if warm else args.duration, 'NO_PROXY': '*', 'no_proxy': '*'}
        before = json.loads(http(mock_port, '/__benchmark/stats', key=UPSTREAM_KEY)[1])['requests']
        with log.open('w') as stream:
            run = subprocess.run(['k6', 'run', '--quiet', '--no-color',
                '--summary-trend-stats=avg,p(50),p(95),p(99),max', '--summary-export=' + str(summary),
                str(ROOT / f'bench/k6-{mode}.js')], env=env, stdout=stream, stderr=subprocess.STDOUT,
                timeout=120)
        measurement = parse_summary(json.loads(summary.read_text()))
        after = json.loads(http(mock_port, '/__benchmark/stats', key=UPSTREAM_KEY)[1])['requests']
        measurement.update(arm=arm, profile=profile, mode=mode, round=rnd, exit_code=run.returncode,
            upstream_requests=after-before, loadavg=list(os.getloadavg()))
        if after-before != measurement['requests']:
            measurement['invalid_reason'] = 'upstream count differs: caching/retry/loss must be investigated'
        if warm:
            if run.returncode or measurement['error_rate'] or measurement.get('invalid_reason'):
                manifest['warmup_failures'].append(dict(arm=arm,profile=profile,mode=mode,reason='warmup failed',measurement=measurement))
                save()
                print('WARMUP EXCLUDED '+json.dumps(measurement),flush=True)
        else:
            rows.append(measurement)
            save()
            print(json.dumps(measurement), flush=True)
        return measurement

    try:
        for name, image in IMAGES.items():
            info = json.loads(docker('image', 'inspect', image))[0]
            manifest['images'][name] = dict(reference=image, id=info['Id'],
                digests=info.get('RepoDigests'), created=info['Created'], architecture=info['Architecture'])
        manifest['docker'] = json.loads(docker('info', '--format', '{{json .}}'))
        # Avoid storing daemon-local paths, proxy environment or unrelated container inventory.
        manifest['docker'] = {k: manifest['docker'].get(k) for k in ['ServerVersion','NCPU','MemTotal','OperatingSystem','Architecture']}
        docker('network', 'create', '--internal', network)
        docker('network', 'create', frontend_network)
        with tempfile.TemporaryDirectory(prefix='llmgw-compare-') as folder:
            work = Path(folder)
            (work / 'mock').mkdir()
            subprocess.run(['javac', '-d', str(work / 'mock'),
                            str(ROOT / 'bench/mock/StandaloneMockUpstream.java')], check=True)
            mock_id, _ = container('mock', IMAGES['base'], cpu=4, memory='1g',
                entry='java', volumes=[(work / 'mock','/mock')],
                command=['-Xms128m','-Xmx512m','-Dsun.net.httpserver.maxIdleConnections=10000',
                    '-Dsun.net.httpserver.idleInterval=1800','-Dsun.net.httpserver.nodelay=true','-cp','/mock','StandaloneMockUpstream','9099','0','0',UPSTREAM_KEY])
            control_config = config_file(work/'control/nginx.conf', '''events {}
http { access_log off; server { listen 8080;
location / { proxy_http_version 1.1; proxy_pass http://mock:9099; }
} }
''')
            _, mock_port = container('control',IMAGES['nginx'],port=8080,cpu=1,memory='128m',
                volumes=[(control_config,'/etc/nginx/nginx.conf')])
            for _ in range(120):
                try:
                    if http(mock_port, '/v1/models', key=UPSTREAM_KEY)[0] == 200: break
                except OSError: pass
                time.sleep(.25)
            else: raise RuntimeError(docker('logs', mock_id))
            common_env = {'HTTP_PROXY':'','HTTPS_PROXY':'','ALL_PROXY':'','NO_PROXY':'*'}
            for arm in args.arms.split(','):
                arm = arm.strip()
                try:
                    route = '/v1/chat/completions'
                    extra_headers = ''
                    if arm == 'direct':
                        alias, port = 'mock', 9099
                    elif arm in ('llmgw-webflux-proxy','llmgw-vt-proxy','llmgw-vt-before','llmgw-webflux-before'):
                        engine = 'vt' if arm.startswith('llmgw-vt') else 'webflux'
                        alias, port = arm, 8080
                        container(alias, IMAGES['base'], env={**common_env,
                            'SPRING_PROFILES_ACTIVE':engine,
                            'JAVA_OPTS':'-Xms256m -Xmx1024m -XX:MaxGCPauseMillis=10'+
                                (' -XX:StartFlightRecording=filename=/tmp/gateway.jfr,settings=profile,dumponexit=true' if args.jfr else ''),
                            'JAVA_TOOL_OPTIONS':' '.join([
                                *(['-Dgateway.upstream.executor-threads='+str(args.before_upstream_threads if arm.endswith('-before') and args.before_upstream_threads is not None else args.upstream_threads)]
                                  if (args.upstream_threads is not None or (arm.endswith('-before') and args.before_upstream_threads is not None)) else []),
                                '-Dgateway.mock.enabled=false',
                                '-Dgateway.governance-enabled='+str(args.governance=='on').lower(),
                                '-Dgateway.api-keys[0].id=benchmark',
                                '-Dgateway.api-keys[0].token='+UPSTREAM_KEY,
                                '-Dgateway.api-keys[0].quota-tokens=1000000000000',
                                '-Dgateway.governance.rate-limit-tokens-per-second=2000000000',
                                '-Dgateway.governance.rate-limit-burst=2000000000',
                                '-Dgateway.routes[0].target-url=http://mock:9099/v1',
                                '-Dgateway.routes[0].api-key='+UPSTREAM_KEY])},
                            volumes=[((args.before_jar if arm.endswith('-before') else args.jar).resolve(),'/app/llmgw.jar')])
                    elif arm == 'litellm':
                        alias, port = arm, 4000
                        cfg = config_file(work/'litellm/config.yaml', f'''model_list:
  - model_name: qwen-plus
    litellm_params:
      model: openai/qwen-plus
      api_base: http://mock:9099/v1
      api_key: {UPSTREAM_KEY}
litellm_settings:
  drop_params: false
  num_retries: 0
  request_timeout: 60
''')
                        container(alias, IMAGES[arm], env={**common_env,'LITELLM_LOG':'ERROR',
                            'LITELLM_LOCAL_MODEL_COST_MAP':'True','LITELLM_TELEMETRY':'False',
                            'LITELLM_DANGEROUSLY_PERMIT_WEAK_OR_UNSET_MASTER_KEY':'true'},
                            volumes=[(cfg,'/app/bench-config.yaml')],
                            command=['--config','/app/bench-config.yaml','--host','0.0.0.0','--port','4000','--num_workers','4'])
                    elif arm == 'bifrost':
                        alias, port, route = arm, 8080, '/openai/v1/chat/completions'
                        cfg = dict(framework=dict(pricing=dict(pricing_url='file:///app/data/pricing.json',
                            model_parameters_url='file:///app/data/model-parameters.json',mcp_library_sync_interval=0)),
                            client=dict(enable_logging=False, enable_governance=False),
                            config_store=dict(enabled=False), logs_store=dict(enabled=False),
                            providers=dict(openai=dict(keys=[dict(name='mock',value=UPSTREAM_KEY,models=['*'],weight=1)],
                                network_config=dict(base_url='http://mock:9099',allow_private_network=True,max_retries=0))))
                        config_file(work/'bifrost/config.json', json.dumps(cfg))
                        config_file(work/'bifrost/pricing.json', '{}')
                        config_file(work/'bifrost/model-parameters.json', '{}')
                        container(alias, IMAGES[arm], env={**common_env,'APP_DIR':'/app/data',
                            'APP_HOST':'0.0.0.0','APP_PORT':'8080','LOG_LEVEL':'error'},
                            volumes=[(work/'bifrost','/app/data')])
                    elif arm == 'portkey':
                        alias, port = arm, 8787
                        container(alias, IMAGES[arm], env={**common_env,'LOG_LEVEL':'error'})
                        extra_headers = ('proxy_set_header x-portkey-provider openai;\n'
                            'proxy_set_header x-portkey-custom-host http://mock:9099/v1;')
                    else:
                        raise ValueError('unknown arm '+arm)
                    nginx = config_file(work/f'{arm}/nginx.conf', f'''worker_processes 2;
events {{ worker_connections 4096; }}
http {{
  access_log off;
  error_log /dev/stderr warn;
  upstream backend {{ server {addresses[alias]}:{port} max_fails=0; keepalive 64; }}
  server {{ listen 8080;
    location = /v1/chat/completions {{
      if ($http_authorization != "Bearer {CLIENT_KEY}") {{ return 401; }}
      proxy_http_version 1.1;
      proxy_set_header Connection "";
      proxy_set_header Authorization "Bearer {UPSTREAM_KEY}";
      {extra_headers}
      proxy_buffering off;
      proxy_read_timeout 60s;
      proxy_pass http://backend{route};
    }}
    location / {{ return 404; }}
  }}
}}
''')
                    _, front_port = container('front-'+arm, IMAGES['nginx'], port=8080, cpu=2,memory='128m',
                        volumes=[(nginx,'/etc/nginx/nginx.conf')])
                    payload = dict(model='qwen-plus',messages=[dict(role='user',content='preflight')],stream=False)
                    last = None
                    deadline=time.monotonic()+90
                    while time.monotonic()<deadline:
                        try:
                            last = http(front_port, body=payload, timeout=1)
                            if last[0] == 200 and json.loads(last[1]).get('object') == 'chat.completion': break
                        except (OSError,ValueError): pass
                        time.sleep(.25)
                    else: raise RuntimeError(f'JSON preflight failed: {last}')
                    assert http(front_port,body=payload,key='invalid')[0] == 401
                    status, body = http(front_port,body={**payload,'stream':True})
                    text = body.decode()
                    assert status == 200 and 'data: [DONE]' in text and 'event: error' not in text, text[:1000]
                    ports[arm] = front_port
                    manifest['preflight'].append(dict(arm=arm,json='PASS',sse='PASS',invalid_auth=401))
                    print('PREFLIGHT PASS '+arm,flush=True)
                except Exception as error:
                    manifest['setup_errors'].append(dict(arm=arm,error=str(error)))
                    print('PREFLIGHT FAIL '+arm+': '+str(error),flush=True)
                    for cid in ids:
                        try: (raw/(cid[:12]+'.container.log')).write_text(docker('logs',cid))
                        except subprocess.CalledProcessError: pass
                save()
            if args.preflight_only:
                return 1 if manifest['setup_errors'] else 0
            for profile in args.profiles.split(','):
                timings = {'wire':(0,0),'inference':(20,10)}[profile]
                manifest['profiles'][profile] = dict(delay_ms=timings[0],chunk_interval_ms=timings[1])
                assert http(mock_port,'/__benchmark/control',body=(','.join(map(str,timings))).encode(),key=UPSTREAM_KEY)[0] == 200
                for mode in args.modes.split(','):
                    # Rotate and reverse order across rounds; no selective removal of bad rounds.
                    arms = list(ports)
                    for arm in list(arms):
                        measurement=k6(arm,ports[arm],profile,mode,0,warm=True)
                        if measurement['error_rate'] or measurement['exit_code'] or measurement.get('invalid_reason'):
                            arms.remove(arm)
                    for rnd in range(1,args.rounds+1):
                        order=round_order(arms,rnd)
                        for arm in order:
                            k6(arm,ports[arm],profile,mode,rnd)
            return 0
    finally:
        for cid in reversed(ids):
            try:
                if cid in profiled:
                    docker('stop','--time','15',cid)
                    docker('cp',cid+':/tmp/gateway.jfr',str(raw/(cid[:12]+'.jfr')))
                (raw/(cid[:12]+'.container.log')).write_text(docker('logs',cid))
            except subprocess.CalledProcessError: pass
            finally:
                try: docker('rm','-f',cid)
                except subprocess.CalledProcessError: pass
        for name in [frontend_network,network]:
            try: docker('network','rm',name)
            except subprocess.CalledProcessError: pass
        manifest['finished_at']=datetime.now(timezone.utc).isoformat()
        save()


if __name__ == '__main__':
    raise SystemExit(main())
