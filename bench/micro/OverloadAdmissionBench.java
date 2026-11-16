package io.github.leohou0430.llmgw.engine.webflux;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.springframework.http.HttpStatus;

/** Actual DispatcherHandler rejection path; transport is mocked, so these are not end-to-end costs. */
public class OverloadAdmissionBench {
    public static void main(String[] args) throws Exception {
        int warm=10000,count=20000;
        var bean=(ThreadMXBean)ManagementFactory.getThreadMXBean();
        if(!bean.isThreadAllocatedMemorySupported()||!bean.isCurrentThreadCpuTimeSupported())
            throw new IllegalStateException("thread allocation/CPU accounting is required");
        bean.setThreadAllocatedMemoryEnabled(true);bean.setThreadCpuTimeEnabled(true);
        long thread=Thread.currentThread().threadId();
        try(var fixture=new CapacityAdmissionTest.Fixture()) {
            var active=fixture.start();
            if(!fixture.provider.started.await(2,TimeUnit.SECONDS))throw new AssertionError("first request did not hold capacity");
            try {
                reject(fixture,warm);
                long allocated=bean.getThreadAllocatedBytes(thread),cpu=bean.getCurrentThreadCpuTime();
                reject(fixture,count);
                long cpuUsed=bean.getCurrentThreadCpuTime()-cpu,bytes=bean.getThreadAllocatedBytes(thread)-allocated;
                if(fixture.provider.calls.get()!=1)throw new AssertionError("rejections must not reach upstream");
                System.out.printf(Locale.ROOT,"{\"operations\":%d,\"warmup\":%d,\"bytes_per_rejection\":%.2f,\"cpu_ns_per_rejection\":%.2f,\"adapter_calls\":%d}%n",
                        count,warm,(double)bytes/count,(double)cpuUsed/count,fixture.adapter().calls.get());
            }finally{active.cancel(true);}
        }
    }
    private static void reject(CapacityAdmissionTest.Fixture fixture,int count) {
        for(int i=0;i<count;i++)if(fixture.request("/v1/chat/completions",CapacityAdmissionTest.BODY).getStatusCode()!=HttpStatus.SERVICE_UNAVAILABLE)
            throw new AssertionError("request escaped the capacity boundary");
    }
}
