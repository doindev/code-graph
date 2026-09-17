package io.doindev.codegraph.mcp.http;

import java.util.*;

/** Friendly defaults for the installed native launcher; the existing HTTP/stdio entry points are unchanged. */
public final class CgraphMain {
    private CgraphMain() {}

    public static void main(String[] args) throws Exception {
        if (Arrays.asList(args).contains("--help") || Arrays.asList(args).contains("-h")) {
            System.out.println(HELP); return;
        }
        if (Arrays.asList(args).contains("--version")) {
            System.out.println("cgraph 0.0.1 (Java " + Runtime.version() + ")"); return;
        }
        String[] effective = effectiveArguments(args);
        if (Arrays.asList(args).contains("--print-config")) {
            System.out.println(String.join("\n", effective)); return;
        }
        HttpMain.main(effective);
    }

    static String[] effectiveArguments(String[] args) {
        List<String> normalized = new ArrayList<>();
        for (String arg : args) {
            int equals = arg.startsWith("--") ? arg.indexOf('=') : -1;
            if (equals > 0) { normalized.add(arg.substring(0, equals)); normalized.add(arg.substring(equals + 1)); }
            else normalized.add(arg);
        }
        boolean defaults = !normalized.contains("--no-defaults");
        boolean ui = !normalized.contains("--no-ui");
        boolean admin = !normalized.contains("--no-admin");
        boolean dba = !normalized.contains("--no-dba");
        if (!ui && normalized.contains("--viz")) throw new IllegalArgumentException("Choose --no-ui or --viz, not both");
        if (!admin && normalized.contains("--viz-admin")) throw new IllegalArgumentException("Choose --no-admin or --viz-admin, not both");
        if (!dba && normalized.stream().anyMatch(a -> a.equals("--dba") || a.startsWith("--dba-")))
            throw new IllegalArgumentException("DBA settings cannot be used with --no-dba");
        List<String> result = new ArrayList<>();
        for (String arg : normalized) if (!Set.of("--no-defaults", "--no-ui", "--no-admin", "--no-dba", "--print-config").contains(arg)) result.add(arg);
        // Never supply a duplicate default: the underlying parsers have different first/last-value semantics.
        if (defaults) {
            defaultValue(result, "--port", "3000");
            defaultValue(result, "--graph-storage", "hybrid");
            defaultValue(result, "--graph-memory", "1g");
            if (ui) { defaultValue(result, "--viz", "8137"); if (admin && !result.contains("--viz-admin")) result.add("--viz-admin"); }
            if (dba) { if (!result.contains("--dba")) result.add("--dba"); defaultValue(result, "--dba-approval-mode", "desktop"); }
        }
        return result.toArray(String[]::new);
    }

    private static void defaultValue(List<String> args, String key, String value) {
        if (!args.contains(key)) { args.add(key); args.add(value); }
    }

    static final String HELP = """
        Usage: cgraph [server options]

        Defaults: local MCP :3000, admin UI/DBA :8137, desktop approvals,
                  hybrid graph storage, shared 1 GiB graph/cache budget.
        No directory is onboarded unless --root, --workspace or CODE_GRAPH_ROOT specifies one.
        Keep this terminal open; Ctrl+C stops the server. No service is installed.

          --port N                   MCP port (3000)
          --viz N                    Web UI port (8137)
          --graph-memory 512m|1g|2g   Graph/cache budget, NOT a total RAM or JVM heap cap
          --graph-storage MODE       hybrid (default) or memory
          --dba-approval-mode MODE   desktop (default), auto, browser, none
          --dba-dir PATH             DBA profiles/settings directory
          --root PATH                Explicitly onboard a project; repeatable
          --project-ttl 10m|1h        Idle project lifetime
          --no-ui                    Do not start the web UI
          --no-admin                 Read-only graph UI
          --no-dba                   Disable DBA and its default approval setting
          --no-defaults              Pass only supplied options to the HTTP server
          --print-config             Print effective arguments without starting anything
          --version                  Print application and bundled Java versions
          --help                     Show this help; remaining HTTP server options pass through

        Headless example: cgraph --no-ui --dba-approval-mode none
        UI: http://localhost:8137/dba    MCP: http://localhost:3000/mcp
        Desktop approval mode requires an interactive graphical session; it does not silently fall back.
        Native launcher JVM options can also be supplied through the standard JAVA_TOOL_OPTIONS environment variable.
        """;
}
