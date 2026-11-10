import io.github.leohou0430.llmgw.core.protocol.*;
import java.lang.management.ManagementFactory;

/** Real SSE parsing, CPU time and thread allocation; first round is warmup. */
class SsePayloadMicrobench {
    static volatile long consumed;
    public static void main(String[] args) {
        int chars=args.length>0?Integer.parseInt(args[0]):64;
        String[] frames=new String[256];
        for(int i=0;i<frames.length;i++)frames[i]="data: {\"id\":\""+i+"\",\"choices\":[{\"delta\":{\"content\":\""+"x".repeat(chars)+"\"}}]}\n";
        var bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true);bean.setThreadCpuTimeEnabled(true);
        long thread=Thread.currentThread().threadId();int count=30000;
        double lastBytes=0;
        for(int round=0;round<6;round++) {
            long cpu=bean.getCurrentThreadCpuTime(),bytes=bean.getThreadAllocatedBytes(thread),sum=0;
            for(int i=0;i<count;i++)sum+=ChunkParser.parse(frames[i&255]).delta().length();
            long elapsed=bean.getCurrentThreadCpuTime()-cpu,allocated=bean.getThreadAllocatedBytes(thread)-bytes;
            consumed=sum;lastBytes=(double)allocated/count;
            if(round>0)System.out.printf("%d,%d,%.2f,%.2f%n",chars,round,(double)elapsed/count,lastBytes);
        }
        if(args.length>1 && lastBytes>Double.parseDouble(args[1]))throw new AssertionError("SSE allocation budget exceeded: "+lastBytes);
    }
}
