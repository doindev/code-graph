package io.doindev.codegraph.dba;

import java.nio.file.Path;
import java.util.*;

/** Explicit opt-in. Limits govern retained application records, not driver/native allocations. */
public record DbaConfig(Path directory, long memoryBytes, int concurrency, int uiRows,
                        int agentRows, int timeoutSeconds, int decisionTimeoutSeconds, String approvalMode, boolean yolo, DriverDownloadConfig driverDownloads, int compareTimeoutSeconds) {
    public static final int DEFAULT_COMPARE_TIMEOUT_SECONDS=900;
    public DbaConfig(Path directory,long memoryBytes,int concurrency,int uiRows,int agentRows,int timeoutSeconds,int decisionTimeoutSeconds,String approvalMode,boolean yolo,DriverDownloadConfig driverDownloads){this(directory,memoryBytes,concurrency,uiRows,agentRows,timeoutSeconds,decisionTimeoutSeconds,approvalMode,yolo,driverDownloads,DEFAULT_COMPARE_TIMEOUT_SECONDS);}
    public DbaConfig(Path directory,long memoryBytes,int concurrency,int uiRows,int agentRows,int timeoutSeconds,int decisionTimeoutSeconds,String approvalMode,boolean yolo){this(directory,memoryBytes,concurrency,uiRows,agentRows,timeoutSeconds,decisionTimeoutSeconds,approvalMode,yolo,DriverDownloadConfig.embedded());}
    public DbaConfig(Path directory,long memoryBytes,int concurrency,int uiRows,int agentRows,int timeoutSeconds,int decisionTimeoutSeconds,String approvalMode){this(directory,memoryBytes,concurrency,uiRows,agentRows,timeoutSeconds,decisionTimeoutSeconds,approvalMode,false);}
    public DbaConfig(Path directory,long memoryBytes,int concurrency,int uiRows,int agentRows,int timeoutSeconds,int decisionTimeoutSeconds){this(directory,memoryBytes,concurrency,uiRows,agentRows,timeoutSeconds,decisionTimeoutSeconds,"auto");}
    public DbaConfig(Path directory,long memoryBytes,int concurrency,int uiRows,int agentRows,int timeoutSeconds){this(directory,memoryBytes,concurrency,uiRows,agentRows,timeoutSeconds,60);}
    public DbaConfig {
        directory = directory.toAbsolutePath().normalize();
        if (!Set.of("auto","browser","desktop","none").contains(approvalMode)) throw new IllegalArgumentException("DBA approval mode must be auto, browser, desktop, or none");
        if (memoryBytes < 32L * 1024 * 1024 || concurrency < 1 || concurrency > 16
                || uiRows < 1 || uiRows > 10_000 || agentRows < 1 || agentRows > 1000
                || timeoutSeconds < 1 || timeoutSeconds > 300 || decisionTimeoutSeconds < 10 || decisionTimeoutSeconds > 600 || compareTimeoutSeconds < 30 || compareTimeoutSeconds > 3600)
            throw new IllegalArgumentException("Invalid DBA limits (memory >=32m, jobs 1..16, UI rows 1..10000, agent rows 1..1000, timeout 1..300s, decision timeout 10..600s, comparison timeout 30..3600s)");
    }
    public static Optional<DbaConfig> parse(String[] args) {
        validateYoloFlag(args);
        boolean enabled = Arrays.asList(args).contains("--dba");
        boolean yolo = Arrays.asList(args).contains("--yolo");
        if(yolo && (!enabled || Arrays.asList(args).contains("--no-dba"))) throw new IllegalArgumentException("--yolo requires --dba and cannot be combined with --no-dba");
        Map<String,String> values = new HashMap<>();
        for (int i=0; i<args.length; i++) if (args[i].startsWith("--dba-")) {
            String key=args[i];
            if (!Set.of("--dba-dir","--dba-memory","--dba-concurrency","--dba-ui-rows","--dba-agent-rows","--dba-timeout","--dba-compare-timeout","--dba-decision-timeout","--dba-approval-mode","--dba-driver-download","--dba-maven-command","--dba-maven-settings","--dba-maven-cert","--dba-maven-insecure-tls").contains(key))
                throw new IllegalArgumentException("Unknown DBA argument: " + key);
            if (++i == args.length || args[i].startsWith("--")) throw new IllegalArgumentException("Missing value for " + key);
            values.put(key,args[i]);
        }
        if (!enabled) {
            if (!values.isEmpty()) throw new IllegalArgumentException("DBA settings require --dba");
            return Optional.empty();
        }
        if(yolo && values.containsKey("--dba-approval-mode")) System.err.println("WARNING: --dba-approval-mode is ignored while --yolo is active");
        return Optional.of(new DbaConfig(Path.of(values.getOrDefault("--dba-dir", Path.of(System.getProperty("user.home"),".code-graph","dba").toString())),
                budget(values.getOrDefault("--dba-memory","256m")),
                Integer.parseInt(values.getOrDefault("--dba-concurrency","4")),
                Integer.parseInt(values.getOrDefault("--dba-ui-rows","1000")),
                Integer.parseInt(values.getOrDefault("--dba-agent-rows","100")),
                Integer.parseInt(values.getOrDefault("--dba-timeout","30")),
                Integer.parseInt(values.getOrDefault("--dba-decision-timeout","60")),values.getOrDefault("--dba-approval-mode","auto"),yolo,DriverDownloadConfig.parse(values),Integer.parseInt(values.getOrDefault("--dba-compare-timeout","900"))));
    }
    public static void validateYoloFlag(String[] args) {
        for(int i=0;i<args.length;i++) {
            String arg=args[i];
            if(arg.toLowerCase(Locale.ROOT).startsWith("--yolo") && !arg.equals("--yolo")) throw new IllegalArgumentException("Use the case-sensitive standalone --yolo flag, without a value");
            if(arg.equals("--yolo") && i+1<args.length && !args[i+1].startsWith("--")) throw new IllegalArgumentException("--yolo does not accept a value");
        }
    }
    public static long budget(String value) {
        if (!value.matches("(?i)[0-9]+[mg]")) throw new IllegalArgumentException("Use whole MiB/GiB, e.g. 256m");
        return Math.multiplyExact(Long.parseLong(value.substring(0,value.length()-1)),
                value.toLowerCase(Locale.ROOT).endsWith("g") ? 1L<<30 : 1L<<20);
    }
}
