import jdk.jfr.consumer.*;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/** Aggregate JFR samples directly; avoids expanding recordings into multi-GB JSON. */
class AnalyzeRecording {
    static void top(String label, Map<String,Long> counts, long total) {
        System.out.println("\n"+label+" total="+total);
        counts.entrySet().stream().sorted(Map.Entry.<String,Long>comparingByValue().reversed())
            .limit(25).forEach(e -> System.out.printf(Locale.ROOT,"%7.2f%%\t%d\t%s%n",100.0*e.getValue()/Math.max(1,total),e.getValue(),e.getKey()));
    }
    public static void main(String[] args) throws Exception {
        var cpu = new HashMap<String,Long>();var app = new HashMap<String,Long>();
        var allocations = new HashMap<String,Long>();var sites = new HashMap<String,Long>();
        long samples=0, bytes=0;Instant first=null;
        try(var file = new RecordingFile(Path.of(args[0]))) {
            while(file.hasMoreEvents()) {
                var event=file.readEvent();
                if(first==null)first=event.getStartTime();
                if(event.getStartTime().isBefore(first.plusSeconds(15)))continue;
                String type=event.getEventType().getName();
                if(!type.equals("jdk.ExecutionSample")&&!type.equals("jdk.ObjectAllocationSample"))continue;
                List<String> frames=new ArrayList<>();
                if(event.getStackTrace()!=null)for(var frame:event.getStackTrace().getFrames()) {
                    var m=frame.getMethod();frames.add(m.getType().getName().replace('/','.')+"."+m.getName());
                }
                String own=frames.stream().filter(f->f.startsWith("io.github.leohou0430")).findFirst().orElse("<framework-only stack>");
                if(type.equals("jdk.ExecutionSample")) {
                    samples++;
                    for(String frame:new HashSet<>(frames))cpu.merge(frame,1L,Long::sum);
                    app.merge(own,1L,Long::sum);
                } else {
                    long weight=event.getLong("weight");bytes+=weight;
                    allocations.merge(event.getClass("objectClass").getName(),weight,Long::sum);
                    String site=own+" :: "+String.join(" <- ",frames.subList(0,Math.min(frames.size(),5)));
                    sites.merge(site,weight,Long::sum);
                }
            }
        }
        top("CPU inclusive",cpu,samples);top("CPU first application frame",app,samples);
        top("Allocation sampled bytes by class",allocations,bytes);top("Allocation sampled bytes by site",sites,bytes);
    }
}
