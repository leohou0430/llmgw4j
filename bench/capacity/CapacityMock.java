import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/** Isolated capacity fixture. No vendor network, credentials or shared persistent state. */
public class CapacityMock {
    static final AtomicLong requests=new AtomicLong(),active=new AtomicLong(),completed=new AtomicLong(),cancelled=new AtomicLong();
    static volatile int delay=20,interval=10,chunks=8,padding=0;
    static final Pattern STREAM=Pattern.compile("\"stream\"\\s*:\\s*true");
    public static void main(String[] args) throws Exception {
        var server=HttpServer.create(new InetSocketAddress("0.0.0.0",9099),1024);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/v1/chat/completions",CapacityMock::chat);
        server.createContext("/__audit/stats",e->json(e,200,"{\"requests\":"+requests.get()+",\"active\":"+active.get()+",\"completed\":"+completed.get()+",\"cancelled\":"+cancelled.get()+"}"));
        server.createContext("/__audit/control",e->{
            if(active.get()!=0){json(e,409,"{}");return;}
            try {
                String[] values=new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8).split(",");
                int d=Integer.parseInt(values[0]),i=Integer.parseInt(values[1]),c=Integer.parseInt(values[2]),p=Integer.parseInt(values[3]);
                if(d<0||d>30000||i<0||i>5000||c<1||c>20000||p<0||p>1048512)throw new IllegalArgumentException();
                delay=d;interval=i;chunks=c;padding=p;requests.set(0);completed.set(0);cancelled.set(0);json(e,200,"{}");
            } catch(RuntimeException invalid){json(e,400,"{}");}
        });server.start();System.out.println("Capacity mock on 9099");
    }
    static void chat(HttpExchange e) throws java.io.IOException {
        requests.incrementAndGet();active.incrementAndGet();long start=System.nanoTime();
        int d=delay,i=interval,c=chunks,p=padding;
        try {
            String request=new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
            Thread.sleep(d);
            if(!STREAM.matcher(request).find()) {
                json(e,200,"{\"object\":\"chat.completion\",\"choices\":[{\"message\":{\"content\":\"ok\"}}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1},\"audit_upstream_ms\":"+elapsed(start)+"}");
            } else {
                e.getResponseHeaders().set("Content-Type","text/event-stream");e.sendResponseHeaders(200,0);
                try(var out=e.getResponseBody()) {
                    String pad="x".repeat(p);
                    for(int n=0;n<c;n++) {
                        out.write(("data: {\"choices\":[{\"delta\":{\"content\":\"tok"+n+pad+"\"}}]}\n\n").getBytes(StandardCharsets.UTF_8));out.flush();Thread.sleep(i);
                    }
                    out.write(("data: {\"choices\":[],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":"+c+"},\"audit_upstream_ms\":"+elapsed(start)+"}\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
                }
            }
            completed.incrementAndGet();
        } catch(java.io.IOException disconnected){cancelled.incrementAndGet();}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();cancelled.incrementAndGet();}
        finally {active.decrementAndGet();e.close();}
    }
    static double elapsed(long start){return (System.nanoTime()-start)/1e6;}
    static void json(HttpExchange e,int status,String body) throws java.io.IOException {
        byte[] bytes=body.getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(status,bytes.length);
        try(var out=e.getResponseBody()){out.write(bytes);}finally{e.close();}
    }
}
