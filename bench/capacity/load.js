import http from 'k6/http';
import {sleep} from 'k6';
import {Counter, Rate, Trend} from 'k6/metrics';
const okCount=new Counter('audit_successes');
const rejected=new Counter('audit_rejected');
const backoffs=new Counter('audit_backoffs');
const backoffMs=new Counter('audit_backoff_ms');
const RESPECT_RETRY=__ENV.RESPECT_RETRY_AFTER==='true';
const statuses=new Counter('audit_status');
let loggedFailure=false;
const unexpected=new Rate('audit_unexpected_failure');
const endToEnd=new Trend('audit_end_to_end_ms',true);
const residual=new Trend('audit_duration_minus_mock_ms',true);
const preflight=new Trend('audit_preflight_ms',true);
const STREAM=__ENV.MODE==='sse';
const TAG=`${Date.now()}-${Math.random()}`;
const RATE=Number(__ENV.ARRIVAL_RATE||0);
const load=RATE>0
    ? {executor:'constant-arrival-rate',rate:RATE,timeUnit:'1s',preAllocatedVUs:Number(__ENV.VUS),maxVUs:Number(__ENV.VUS),duration:__ENV.DURATION,gracefulStop:'40s'}
    : {executor:'constant-vus',vus:Number(__ENV.VUS),duration:__ENV.DURATION,gracefulStop:'40s'};
export const options={scenarios:{load},thresholds:{audit_unexpected_failure:['rate==0'],...(RATE>0?{dropped_iterations:['count==0']}:{})}};
export default function(){
    const body=JSON.stringify({model:'qwen-plus',messages:[{role:'user',content:`audit ${TAG} ${__VU} ${__ITER}`}],stream:STREAM});
    const response=http.post(`${__ENV.BASE_URL}/v1/chat/completions`,body,{headers:{'Content-Type':'application/json','Authorization':'Bearer audit-client'},timeout:'65s'});
    statuses.add(1,{status:String(response.status)});
    if(response.status===503&&response.body.includes('gateway request capacity exceeded')){
        rejected.add(1);unexpected.add(false);
        if(RESPECT_RETRY){
            let delay=0;
            for(const name of Object.keys(response.headers))if(name.toLowerCase()==='retry-after')delay=Number(response.headers[name]);
            if(Number.isFinite(delay)&&delay>0){const bounded=Math.min(delay,5);backoffs.add(1);backoffMs.add(bounded*1000);sleep(bounded);}
        }
        return;
    }
    let success=response.status===200,work;
    try {
        if(STREAM){
            success=success&&response.body.includes('data: [DONE]')&&!response.body.includes('event: error');
            success=success&&Array.from({length:8},(_,i)=>`tok${i}`).every(t=>response.body.includes(t));
            const match=response.body.match(/"audit_upstream_ms":([0-9.]+)/);work=match?Number(match[1]):undefined;
        }else {const data=response.json();success=success&&data.choices[0].message.content==='ok';work=data.audit_upstream_ms;}
    }catch(_){success=false;}
    if(!success&&!loggedFailure){console.warn(`AUDIT_FAILURE status=${response.status} body=${response.body.slice(0,1000)}`);loggedFailure=true;}
    unexpected.add(!success);
    if(success){
        okCount.add(1);endToEnd.add(response.timings.duration);
        if(Number.isFinite(work))residual.add(response.timings.duration-work);
        for(const key of Object.keys(response.headers))if(key.toLowerCase()==='x-llmgw-overhead-ms')preflight.add(Number(response.headers[key]));
    }
}
