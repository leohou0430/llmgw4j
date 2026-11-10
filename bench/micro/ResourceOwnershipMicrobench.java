import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.Locale;
import io.github.leohou0430.llmgw.core.protocol.*;
import io.github.leohou0430.llmgw.engine.EngineRoutes;

/** CPU/allocation cost of file-reference authorization on ordinary text requests. */
public class ResourceOwnershipMicrobench {
    private static volatile int consumed;
    public static void main(String[] args) {
        int bytes = args.length > 0 ? Integer.parseInt(args[0]) : 4096;
        var requests = new ParsedRequest[256];
        for (int i=0;i<requests.length;i++) requests[i]=ProtocolCodec.parse(ProtocolKind.CHAT,
                "{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\""+"x".repeat(bytes)+i+"\"}]}");
        var bean=(ThreadMXBean)ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true);
        long thread=Thread.currentThread().threadId();
        for (int round=0;round<8;round++) {
            int count=50000,yes=0;
            long allocated=bean.getThreadAllocatedBytes(thread), cpu=bean.getCurrentThreadCpuTime();
            for (int i=0;i<count;i++) if(EngineRoutes.ownsReferencedFiles(requests[i%requests.length],"tenant",null,null))yes++;
            long nanos=bean.getCurrentThreadCpuTime()-cpu, memory=bean.getThreadAllocatedBytes(thread)-allocated;
            consumed=yes;
            if(yes!=count)throw new AssertionError("ordinary text requests must remain authorized");
            if(round>0)System.out.printf(Locale.ROOT,"%d,%d,%.2f,%.2f%n",bytes,round,(double)nanos/count,(double)memory/count);
        }
    }
}
