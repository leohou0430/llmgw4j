import org.springframework.http.HttpHeaders;
import org.springframework.http.support.Netty4HeadersAdapter;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import java.lang.management.ManagementFactory;
import java.util.Map;

/** Copy actual Netty-backed Spring headers, checking first-value and casing behavior. */
class HeaderCopyMicrobench {
    static volatile int consumed;
    public static void main(String[] args) throws Exception {
        var raw = new DefaultHttpHeaders();
        raw.add("Host","gateway");raw.add("Authorization","Bearer test");
        raw.add("Content-Type","application/json");raw.add("X-Duplicate","first");
        raw.add("x-duplicate","second");raw.add("X-Empty","");
        for(int i=0;i<8;i++)raw.add("X-Custom-"+i,"value-"+i);
        var headers=HttpHeaders.readOnlyHttpHeaders(new HttpHeaders(new Netty4HeadersAdapter(raw)));
        var type=Class.forName("io.github.leohou0430.llmgw.engine.webflux.WebfluxGatewayController");
        var copy=type.getDeclaredMethod("firstValues",HttpHeaders.class);copy.setAccessible(true);
        @SuppressWarnings("unchecked") var result=(Map<String,String>)copy.invoke(null,headers);
        if(result.size()!=13 || !"first".equals(result.get("X-Duplicate")) || !"".equals(result.get("X-Empty")) || headers.get("X-Duplicate").size()!=2)throw new AssertionError(result);
        var bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true);bean.setThreadCpuTimeEnabled(true);
        long thread=Thread.currentThread().threadId();int count=50000;
        for(int round=0;round<6;round++) {
            long cpu=bean.getCurrentThreadCpuTime(),bytes=bean.getThreadAllocatedBytes(thread);int n=0;
            for(int i=0;i<count;i++)n+=((Map<?,?>)copy.invoke(null,headers)).size();
            long time=bean.getCurrentThreadCpuTime()-cpu,allocated=bean.getThreadAllocatedBytes(thread)-bytes;
            consumed=n;if(round>0)System.out.printf("%d,%.2f,%.2f%n",round,(double)time/count,(double)allocated/count);
        }
    }
}
