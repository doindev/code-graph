package io.doindev.codegraph.dba;

import java.nio.file.Path;
import java.util.*;

/** Explicit opt-in. Limits govern retained application records, not driver/native allocations. */
public record DbaConfig(Path directory, long memoryBytes, int concurrency, int uiRows,
                        int agentRows, int timeoutSeconds, int decisionTimeoutSeconds) {
    public DbaConfig(Path directory,long memoryBytes,int concurrency,int uiRows,int agentRows,int timeoutSeconds){this(directory,memoryBytes,concurrency,uiRows,agentRows,timeoutSeconds,60);}
    public DbaConfig {
        directory = directory.toAbsolutePath().normalize();
        if (memoryBytes < 32L * 1024 * 1024 || concurrency < 1 || concurrency > 16
                || uiRows < 1 || uiRows > 10_000 || agentRows < 1 || agentRows > 1000
                || timeoutSeconds < 1 || timeoutSeconds > 300 || decisionTimeoutSeconds < 10 || decisionTimeoutSeconds > 600)
            throw new IllegalArgumentException("Invalid DBA limits (memory >=32m, jobs 1..16, UI rows 1..10000, agent rows 1..1000, timeout 1..300s, decision timeout 10..600s)");
    }
    public static Optional<DbaConfig> parse(String[] args) {
        boolean enabled = Arrays.asList(args).contains("--dba");
        Map<String,String> values = new HashMap<>();
        for (int i=0; i<args.length; i++) if (args[i].startsWith("--dba-")) {
            String key=args[i];
            if (!Set.of("--dba-dir","--dba-memory","--dba-concurrency","--dba-ui-rows","--dba-agent-rows","--dba-timeout","--dba-decision-timeout").contains(key))
                throw new IllegalArgumentException("Unknown DBA argument: " + key);
            if (++i == args.length || args[i].startsWith("--")) throw new IllegalArgumentException("Missing value for " + key);
            values.put(key,args[i]);
        }
        if (!enabled) {
            if (!values.isEmpty()) throw new IllegalArgumentException("DBA settings require --dba");
            return Optional.empty();
        }
        return Optional.of(new DbaConfig(Path.of(values.getOrDefault("--dba-dir", Path.of(System.getProperty("user.home"),".code-graph","dba").toString())),
                budget(values.getOrDefault("--dba-memory","256m")),
                Integer.parseInt(values.getOrDefault("--dba-concurrency","4")),
                Integer.parseInt(values.getOrDefault("--dba-ui-rows","1000")),
                Integer.parseInt(values.getOrDefault("--dba-agent-rows","100")),
                Integer.parseInt(values.getOrDefault("--dba-timeout","30")),
                Integer.parseInt(values.getOrDefault("--dba-decision-timeout","60"))));
    }
    public static long budget(String value) {
        if (!value.matches("(?i)[0-9]+[mg]")) throw new IllegalArgumentException("Use whole MiB/GiB, e.g. 256m");
        return Math.multiplyExact(Long.parseLong(value.substring(0,value.length()-1)),
                value.toLowerCase(Locale.ROOT).endsWith("g") ? 1L<<30 : 1L<<20);
    }
}
