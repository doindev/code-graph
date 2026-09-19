import com.fasterxml.jackson.databind.ObjectMapper;
import jdk.jfr.consumer.*;
import java.nio.file.*;
import java.util.*;

/** Bounded aggregation of an owned diagnostic recording; does not read application data. */
public class SummarizeHybridRecording {
    static String application(RecordedEvent event) {
        var stack=event.getStackTrace();
        if(stack!=null)for(var frame:stack.getFrames()) {
            var method=frame.getMethod();String type=method.getType().getName();
            if(type.startsWith("io.doindev."))return type+"."+method.getName();
        }
        return "(no application frame)";
    }
    static String caller(RecordedEvent event) {
        var stack=event.getStackTrace();
        if(stack!=null)for(var frame:stack.getFrames()) {
            var method=frame.getMethod();String type=method.getType().getName();
            if(type.startsWith("org.h2.")||type.startsWith("com.fasterxml."))
                return type+"."+method.getName();
        }
        return application(event);
    }
    static List<Map<String,Object>> top(Map<String,Long> values) {
        return values.entrySet().stream().sorted(Map.Entry.<String,Long>comparingByValue().reversed())
            .limit(30).map(e->Map.<String,Object>of("frame",e.getKey(),"value",e.getValue())).toList();
    }
    static void count(Map<String,Long> map,String key,long value) {
        if(map.size()<4095||map.containsKey(key))map.merge(key,value,Long::sum);
        else map.merge("(other bounded)",value,Long::sum);
    }
    public static void main(String[] args)throws Exception {
        var samples=new HashMap<String,Long>();var app=new HashMap<String,Long>();
        var parks=new HashMap<String,Long>();var allocation=new HashMap<String,Long>();long events=0;
        try(var file=new RecordingFile(Path.of(args[0]))) {
            while(file.hasMoreEvents()) {
                var event=file.readEvent();events++;String type=event.getEventType().getName();
                if(type.equals("jdk.ExecutionSample")||type.equals("jdk.NativeMethodSample")) {
                    count(samples,caller(event),1);count(app,application(event),1);
                } else if(type.equals("jdk.ThreadPark")) {
                    count(parks,application(event)+" :: "+caller(event),event.getDuration().toNanos());
                } else if(type.equals("jdk.ObjectAllocationSample")) {
                    count(allocation,application(event),event.getLong("weight"));
                }
            }
        }
        System.out.println(new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(
            Map.of("events",events,"cpuSamples",top(samples),"applicationSamples",top(app),
                "parkNanos",top(parks),"sampledAllocationWeightBytes",top(allocation),
                "note","Sampling diagnostics, not exact call counts, exclusive costs or retained memory; map cardinality bounded to 4096.")));
    }
}
