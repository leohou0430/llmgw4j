#!/usr/bin/env python3
"""Cancel slow socket readers against an isolated 2C2G gateway and check recovery."""
import argparse, concurrent.futures, hashlib, json, socket, subprocess, tempfile, time, urllib.request, uuid
import urllib.error
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--jar',type=Path,required=True);parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--native-dir',type=Path);parser.add_argument('--clients',type=int,default=8);parser.add_argument('--padding',type=int,default=4096);parser.add_argument('--chunks',type=int,default=4096)
    parser.add_argument('--hold-seconds',type=float,default=3);parser.add_argument('--heartbeat-ms',type=int,default=0);parser.add_argument('--engine',choices=['webflux','vt'])
    parser.add_argument('--header-timeout',type=float,default=10);parser.add_argument('--jfr',action='store_true')
    args=parser.parse_args()
    if not 1<=args.clients<=64 or not 0<=args.padding<=1048512 or not 1<=args.chunks<=20000 or not 0<args.hold_seconds<=300 or not 0<args.header_timeout<=120 or args.heartbeat_ms<0:parser.error('invalid bounded slow-reader scenario')
    if args.native_dir and args.jfr:parser.error('JFR is only available for JVM runs')
    out=args.output.resolve();out.mkdir(parents=True,exist_ok=True);ids=[];net='llmgw-slow-'+uuid.uuid4().hex[:10]
    report={'jar_sha256':hashlib.sha256(args.jar.read_bytes()).hexdigest(),'parameters':vars(args).copy(),'sources':{str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in [Path(__file__),ROOT/'bench/capacity/CapacityMock.java']},'results':[]}
    report['parameters']['native_dir']=str(args.native_dir.resolve()) if args.native_dir else None
    report['parameters']['jar']=str(args.jar.resolve());report['parameters']['output']=str(out)
    def docker(*cmd):return subprocess.check_output(['docker',*map(str,cmd)],text=True,stderr=subprocess.STDOUT).strip()
    def http(port,path,body=None):
        req=urllib.request.Request(f'http://127.0.0.1:{port}{path}',data=body,headers={'Content-Type':'application/json','Authorization':'Bearer audit-client'})
        try:
            with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(req,timeout=10) as r:return json.load(r)
        except urllib.error.HTTPError as error:
            detail={'status':error.code,'headers':dict(error.headers),'body':error.read(16384).decode(errors='replace')}
            raise RuntimeError(json.dumps(detail)) from error
    def start(alias,port,source,target,cmd):
        if args.jfr and alias!='mock':cmd.insert(2,'-XX:StartFlightRecording=filename=/tmp/slow.jfr,settings=profile,dumponexit=true,maxsize=32m')
        entrypoint='java'
        if args.native_dir and alias!='mock':
            source=str((args.native_dir/('llmgw-'+alias+'-linux')).resolve());target='/app/llmgw-native';entrypoint=target;cmd=cmd[:2]+cmd[4:]
            report.setdefault('native_binaries',{})[alias]={'path':source,'sha256':hashlib.sha256(Path(source).read_bytes()).hexdigest()}
        cid=docker('create','--name',net+'-'+alias,'--network',net,'--network-alias',alias,'--cpus','2','--memory','2g','--memory-swap','2g','--no-healthcheck','-p',f'127.0.0.1::{port}','--entrypoint',entrypoint,'llmgw-audit:redis',*cmd)
        ids.append(cid);docker('cp',source,cid+':'+target);docker('start',cid);port=int(docker('port',cid,f'{port}/tcp').split(':')[-1])
        for _ in range(160):
            try:http(port,'/__audit/stats' if alias=='mock' else '/actuator/health');return cid,port
            except Exception:time.sleep(.25)
        raise RuntimeError(docker('logs',cid))
    def resources(cid):
        try:return docker('exec',cid,'sh','-c','cat /sys/fs/cgroup/cpu.max /sys/fs/cgroup/memory.max /sys/fs/cgroup/memory.swap.max /sys/fs/cgroup/memory.current /sys/fs/cgroup/memory.peak /sys/fs/cgroup/memory.events /sys/fs/cgroup/cpu.stat /proc/1/status')
        except Exception as error:return {'unavailable':str(error)}
    def slow(port,n):
        started=time.monotonic()
        body=json.dumps({'model':'qwen-plus','messages':[{'role':'user','content':'slow '+str(n)}],'stream':True}).encode()
        with socket.create_connection(('127.0.0.1',port),timeout=args.header_timeout) as sock:
            sock.setsockopt(socket.SOL_SOCKET,socket.SO_RCVBUF,1024)
            sock.sendall((f'POST /v1/chat/completions HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer audit-client\r\nContent-Type: application/json\r\nContent-Length: {len(body)}\r\n\r\n').encode()+body)
            head=b''
            while b'\r\n\r\n' not in head:
                remaining=args.header_timeout-(time.monotonic()-started)
                if remaining<=0:raise TimeoutError('response header budget exceeded')
                sock.settimeout(remaining)
                try:value=sock.recv(1)
                except TimeoutError as error:raise TimeoutError('response headers not received within budget') from error
                if not value:raise RuntimeError('gateway closed before response headers')
                head+=value
                if len(head)>65536:raise RuntimeError('response headers too large')
            if not head.startswith(b'HTTP/1.1 200'):raise RuntimeError(head.decode())
            header_ms=(time.monotonic()-started)*1000
            sock.settimeout(args.header_timeout)
            end=time.monotonic()+args.hold_seconds
            while time.monotonic()<end:
                try:value=sock.recv(64)
                except TimeoutError as error:raise TimeoutError(f'slow body read timed out after headers at {header_ms:.1f}ms') from error
                if not value:raise RuntimeError(f'gateway closed while slow reader active; headers at {header_ms:.1f}ms')
                time.sleep(.5)
        return {'client':n,'headers_ms':header_ms}
    try:
        docker('network','create',net)
        with tempfile.TemporaryDirectory(prefix='llmgw-slow-') as temp:
            subprocess.run(['javac','-d',temp,str(ROOT/'bench/capacity/CapacityMock.java')],check=True)
            _,mock=start('mock',9099,temp+'/.','/mock',['-Xmx512m','-Dsun.net.httpserver.nodelay=true','-cp','/mock','CapacityMock'])
            for engine in ([args.engine] if args.engine else ['webflux','vt']):
                cid,port=start(engine,8080,str(args.jar.resolve()),'/app/llmgw.jar',['-Xms128m','-Xmx1024m','-jar','/app/llmgw.jar','--spring.profiles.active='+engine,'--gateway.mock.enabled=false','--gateway.upstream.api-key=audit-upstream','--gateway.routes[0].api-key=audit-upstream','--gateway.keys-store-path=/tmp/keys.json','--gateway.governance.snapshot-path=/tmp/snapshot.json','--gateway.api-keys[0].token=audit-client','--gateway.api-keys[0].quota-tokens=1000000000000','--gateway.governance.rate-limit-tokens-per-second=2000000000','--gateway.governance.rate-limit-burst=2000000000','--gateway.routes[0].target-url=http://mock:9099/v1','--gateway.upstream.heartbeat-interval-ms='+str(args.heartbeat_ms)])
                row={'engine':engine,'clients':args.clients,'payload_bytes_per_response':args.chunks*args.padding,'resources_before':resources(cid)}
                report['results'].append(row);starttime=time.monotonic()
                try:
                    http(mock,'/__audit/control',f'0,0,{args.chunks},{args.padding}'.encode())
                    row['clients_completed']=[];row['client_errors']=[]
                    with concurrent.futures.ThreadPoolExecutor(max_workers=args.clients) as workers:
                        futures={workers.submit(slow,port,n):n for n in range(args.clients)}
                        for future in concurrent.futures.as_completed(futures):
                            try:row['clients_completed'].append(future.result())
                            except Exception as error:row['client_errors'].append({'client':futures[future],'error':repr(error)})
                    assert not row['client_errors'],row['client_errors']
                    deadline=time.monotonic()+10
                    while time.monotonic()<deadline:
                        stats=http(mock,'/__audit/stats')
                        if stats['active']==0:break
                        time.sleep(.1)
                    row['stats_after_cancel']=stats
                    assert stats['active']==0,stats
                    assert stats['requests']==args.clients,stats
                    assert stats['cancelled']>0,'fixture completed before slow readers exercised cancellation'
                    http(mock,'/__audit/control',b'20,10,8,0')
                    body=json.dumps({'model':'qwen-plus','messages':[{'role':'user','content':'recovery'}],'stream':False}).encode()
                    assert http(port,'/v1/chat/completions',body)['choices'][0]['message']['content']=='ok'
                    row['recovery']='200 ok'
                except BaseException as error:
                    row['failure']=repr(error)
                    try:docker('kill','--signal=QUIT',cid);time.sleep(.25)
                    except Exception:pass
                    raise
                finally:
                    try:row['stats_final']=http(mock,'/__audit/stats')
                    except Exception as error:row['stats_final_error']=repr(error)
                    row['elapsed_seconds']=time.monotonic()-starttime;row['resources_after']=resources(cid)
                    row['container_state']=json.loads(docker('inspect',cid))[0]['State']
                    if args.jfr:
                        try:docker('stop','-t','15',cid);docker('cp',cid+':/tmp/slow.jfr',out/(engine+'.jfr'))
                        except Exception as error:row['jfr_error']=repr(error)
                    logs=docker('logs',cid);(out/(engine+'.log')).write_text(logs)
                    row['out_of_memory_errors']=logs.count('OutOfMemoryError')
                    (out/'slow-clients.json').write_text(json.dumps(report,indent=2))
                assert not row['container_state']['OOMKilled'] and row['out_of_memory_errors']==0,row
                print(engine,json.dumps(report['results'][-1]),flush=True)
                docker('rm','-f',cid);ids.remove(cid)
    finally:
        for cid in reversed(ids):
            try:docker('rm','-f',cid)
            except Exception:pass
        try:docker('network','rm',net)
        except Exception:pass
        (out/'slow-clients.json').write_text(json.dumps(report,indent=2))
if __name__=='__main__':main()
