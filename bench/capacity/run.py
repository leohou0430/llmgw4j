#!/usr/bin/env python3
"""Isolated 2 CPU / 2 GiB gateway capacity experiment; uses only owned containers."""
import argparse, hashlib, json, os, platform, subprocess, tempfile, time, urllib.request, uuid
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]

def host_load(max_ratio):
    load=os.getloadavg()
    return {'loadavg':load,'logical_cpus':os.cpu_count(),
            'acceptable':max_ratio==0 or load[0]<=max_ratio*(os.cpu_count() or 1)}

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--before',type=Path,required=True);p.add_argument('--after',type=Path,required=True)
    p.add_argument('--native-dir',type=Path,help='Use Linux llmgw-{engine}-linux binaries for after labels');p.add_argument('--output',type=Path,required=True);p.add_argument('--rounds',type=int,default=3)
    p.add_argument('--duration',default='8s');p.add_argument('--warmup',default='3s');p.add_argument('--warmup-vus',type=int,default=8);p.add_argument('--arrival-rate',type=int,default=0);p.add_argument('--respect-retry-after',action='store_true');p.add_argument('--jfr',action='store_true');p.add_argument('--vus',default='8,32,64,96');p.add_argument('--modes',default='json,sse');p.add_argument('--labels',default='before-webflux,before-vt,after-webflux,after-vt');p.add_argument('--skip-long',action='store_true');p.add_argument('--keep-going',action='store_true')
    p.add_argument('--max-host-load-ratio',type=float,default=1.5,help='Abort noisy performance trials above 1-minute host load/logical CPUs; 0 explicitly disables this diagnostic guard')
    args=p.parse_args()
    if args.native_dir and args.jfr:p.error('JFR is only available for JVM runs')
    if args.max_host_load_ratio<0:p.error('--max-host-load-ratio must be nonnegative')
    initial_load=host_load(args.max_host_load_ratio)
    if not initial_load['acceptable']:p.error('host load exceeds performance guard: '+str(initial_load))
    out=args.output.resolve();out.mkdir(parents=True,exist_ok=True)
    ids=[];profiled=set();network='llmgw-capacity-'+uuid.uuid4().hex[:10];rows=[]
    manifest={'host':{'platform':platform.platform(),'cpu_count':os.cpu_count()},'started':time.strftime('%Y-%m-%dT%H:%M:%S%z'),'cpu':2,'memory_bytes':2147483648,'heap_max':'1024m','max_host_load_ratio':args.max_host_load_ratio,'initial_host_load':initial_load,'rounds':args.rounds,'duration':args.duration,'warmup':args.warmup,'warmup_vus':args.warmup_vus,'profiling':args.jfr,'arrival_rate':args.arrival_rate,'respect_retry_after':args.respect_retry_after,'vus':args.vus,'modes':args.modes,'labels':args.labels,'keep_going':args.keep_going,'mock_idle_policy':{'maxIdleConnections':10000,'idleIntervalSeconds':1800},'jars':{},'containers':{}}
    def docker(*cmd):return subprocess.check_output(['docker',*map(str,cmd)],text=True,stderr=subprocess.STDOUT).strip()
    def http(port,path,body=None):
        req=urllib.request.Request(f'http://127.0.0.1:{port}{path}',data=body)
        with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(req,timeout=5) as r:return json.load(r)
    def save():
        (out/'manifest.json').write_text(json.dumps(manifest,indent=2));(out/'samples.json').write_text(json.dumps(rows,indent=2))
    def start(alias,port,copies,cmd,cpu=2,memory='2g',entrypoint='java'):
        cid=docker('create','--name',network+'-'+alias,'--network',network,'--network-alias',alias,'--cpus',cpu,'--memory',memory,'--memory-swap',memory,'--no-healthcheck','-p',f'127.0.0.1::{port}','--entrypoint',entrypoint,'llmgw-audit:redis',*cmd)
        ids.append(cid);manifest['containers'][alias]=cid
        for source,target in copies:docker('cp',source,cid+':'+target)
        docker('start',cid);host=int(docker('port',cid,f'{port}/tcp').split(':')[-1])
        for _ in range(160):
            try:http(host,'/__audit/stats' if alias=='mock' else '/actuator/health');return cid,host
            except Exception:time.sleep(.25)
        raise RuntimeError(docker('logs',cid))
    def sample(cid):
        raw=docker('exec',cid,'sh','-c','cat /sys/fs/cgroup/memory.current /sys/fs/cgroup/memory.peak /sys/fs/cgroup/memory.events /sys/fs/cgroup/cpu.stat /proc/1/status')
        return raw
    def trial(label,port,mode,vus,rnd,cid=None,duration=None):
        name=f'{label}-{mode}-v{vus}-r{rnd}';summary=out/(name+'.json')
        load_before_check=host_load(args.max_host_load_ratio)
        if not load_before_check['acceptable']:raise RuntimeError('host load exceeds performance guard before '+name+': '+str(load_before_check))
        started=time.strftime('%Y-%m-%dT%H:%M:%S%z');load_before=os.getloadavg()
        baseline=http(mockport,'/__audit/stats')
        env={**os.environ,'BASE_URL':f'http://127.0.0.1:{port}','MODE':mode,'VUS':str(vus),'DURATION':duration or args.duration,'ARRIVAL_RATE':str(args.arrival_rate if cid and isinstance(rnd,int) and rnd>0 else 0),'RESPECT_RETRY_AFTER':str(args.respect_retry_after).lower(),'NO_PROXY':'*','no_proxy':'*'}
        before=sample(cid) if cid else None
        with (out/(name+'.log')).open('w') as log:
            result=subprocess.run(['k6','run','--quiet','--no-color','--summary-trend-stats=avg,p(50),p(95),p(99),max','--summary-export='+str(summary),str(ROOT/'bench/capacity/load.js')],env=env,stdout=log,stderr=subprocess.STDOUT,timeout=120)
        for _ in range(100):
            stats=http(mockport,'/__audit/stats')
            if stats['active']==0:break
            time.sleep(.1)
        metrics=json.loads(summary.read_text())['metrics']
        row={'started':started,'ended':time.strftime('%Y-%m-%dT%H:%M:%S%z'),'host_loadavg_before':load_before,'host_loadavg_after':os.getloadavg(),'label':label,'mode':mode,'vus':vus,'round':rnd,'arrival_rate':int(env['ARRIVAL_RATE']),'exit':result.returncode,'metrics':metrics,'upstream_delta':{k:stats[k]-baseline[k] for k in baseline},'resources_before':before,'resources_after':sample(cid) if cid else None}
        load_after_check=host_load(args.max_host_load_ratio)
        row['host_load_guard']={'before':load_before_check,'after':load_after_check,'enabled':args.max_host_load_ratio!=0}
        row['performance_valid']=load_before_check['acceptable'] and load_after_check['acceptable'] and args.max_host_load_ratio!=0
        rows.append(row);save();print(name,'exit',result.returncode,flush=True)
        if not load_after_check['acceptable']:raise RuntimeError('host load exceeded performance guard during '+name+'; sample retained but performance_valid=false')
        if result.returncode and not args.keep_going:raise RuntimeError('load contract failed: '+name)
    try:
        manifest['image']=json.loads(docker('image','inspect','llmgw-audit:redis'))[0]['Id']
        manifest['docker']=json.loads(docker('info','--format','{{json .}}'))
        manifest['docker']={k:manifest['docker'].get(k) for k in ('ServerVersion','NCPU','MemTotal','Architecture')}
        manifest['sources']={str(f.relative_to(ROOT)):hashlib.sha256(f.read_bytes()).hexdigest() for f in (Path(__file__),ROOT/'bench/capacity/CapacityMock.java',ROOT/'bench/capacity/load.js')}
        for source in (Path(__file__),ROOT/'bench/capacity/CapacityMock.java',ROOT/'bench/capacity/load.js'):(out/source.name).write_bytes(source.read_bytes())
        docker('network','create',network)
        with tempfile.TemporaryDirectory(prefix='llmgw-capacity-') as temp:
            subprocess.run(['javac','-d',temp,str(ROOT/'bench/capacity/CapacityMock.java')],check=True)
            _,mockport=start('mock',9099,[(temp+'/.','/mock')],['-Xmx512m','-Dsun.net.httpserver.maxIdleConnections=10000','-Dsun.net.httpserver.idleInterval=1800','-Dsun.net.httpserver.nodelay=true','-cp','/mock','CapacityMock'],4,'768m')
            gateways={}
            for version,jar in [('before',args.before),('after',args.after)]:
                manifest['jars'][version]={'path':str(jar.resolve()),'sha256':hashlib.sha256(jar.read_bytes()).hexdigest()}
                for engine in ['webflux','vt']:
                    label=version+'-'+engine
                    if label not in args.labels.split(','):continue
                    cmd=['-Xms128m','-Xmx1024m','-jar','/app/llmgw.jar','--spring.profiles.active='+engine,'--gateway.mock.enabled=false','--gateway.governance-enabled=true','--gateway.admin-token=audit-admin','--gateway.keys-store-path=/tmp/keys.json','--gateway.governance.snapshot-path=/tmp/snapshot.json','--gateway.api-keys[0].id=audit','--gateway.api-keys[0].token=audit-client','--gateway.api-keys[0].quota-tokens=1000000000000','--gateway.governance.rate-limit-tokens-per-second=2000000000','--gateway.governance.rate-limit-burst=2000000000','--gateway.routes[0].target-url=http://mock:9099/v1','--gateway.routes[0].api-key=audit-upstream']
                    if args.jfr:cmd.insert(2,'-XX:StartFlightRecording=filename=/tmp/gateway.jfr,settings=profile,dumponexit=true,maxsize=64m')
                    copies=[(str(jar.resolve()),'/app/llmgw.jar')];entrypoint='java'
                    if version=='after' and args.native_dir:
                        binary=(args.native_dir/('llmgw-'+engine+'-linux')).resolve()
                        manifest.setdefault('native_binaries',{})[engine]={'path':str(binary),'sha256':hashlib.sha256(binary.read_bytes()).hexdigest()}
                        copies=[(str(binary),'/app/llmgw-native')];entrypoint='/app/llmgw-native';cmd=cmd[:2]+cmd[4:]
                    cid,port=start(label,8080,copies,cmd,entrypoint=entrypoint)
                    gateways[label]=(cid,port)
                    if args.jfr:profiled.add(cid)
                    manifest[label+'-limits']=docker('exec',cid,'sh','-c','cat /sys/fs/cgroup/cpu.max /sys/fs/cgroup/memory.max /sys/fs/cgroup/memory.swap.max')
            save()
            for mode in args.modes.split(','):
                http(mockport,'/__audit/control',b'20,10,8,0')
                trial('direct',mockport,mode,64,0,duration='4s')
                for label,(cid,port) in gateways.items():trial(label,port,mode,args.warmup_vus,'warm',cid,duration=args.warmup)
                for vus in map(int,args.vus.split(',')):
                    for rnd in range(1,args.rounds+1):
                        labels=list(gateways)
                        if rnd%2==0:labels.reverse()
                        for label in labels:
                            cid,port=gateways[label];trial(label,port,mode,vus,rnd,cid)
            if not args.skip_long:
                http(mockport,'/__audit/control',b'50,3750,8,0')
                for label,(cid,port) in gateways.items():trial(label,port,'sse',32,'long',cid,duration='1s')
    finally:
        for cid in reversed(ids):
            try:
                if cid in profiled:
                    docker('stop','-t','15',cid)
                    label=next(name for name,value in manifest['containers'].items() if value==cid)
                    recording=out/(label+'.jfr');docker('cp',cid+':/tmp/gateway.jfr',recording)
                    manifest.setdefault('jfr',{})[label]={'sha256':hashlib.sha256(recording.read_bytes()).hexdigest(),'bytes':recording.stat().st_size}
                (out/(cid[:12]+'.log')).write_text(docker('logs',cid))
            except Exception as e:print('cleanup',cid,str(e),flush=True)
            finally:
                try:docker('rm','-f',cid)
                except Exception as e:print('container cleanup',cid,str(e),flush=True)
        try:docker('network','rm',network)
        except Exception:pass
        save()
if __name__=='__main__':main()
