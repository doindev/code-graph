package io.doindev.codegraph.mcp;

import io.doindev.codegraph.tools.*;
import java.util.*;

/** Generate public reference text from the same schemas advertised by the server. No runtime startup. */
public final class ToolReference {
    private ToolReference() {}
    public static String markdown() {
        var out=new StringBuilder("# Generated MCP tool reference\n\nGenerated from tool definitions; do not edit tool entries manually.\n\n")
                .append("Availability depends on DBA, approval and browser-editor configuration. Definitions never authorize execution.\n\n")
                .append("Use `requestId` when submitting an idempotent proposal, returned `approvalId` to poll/cancel it, and `jobId` for execution. Legacy aliases remain supported.\n\n");
        try(var workspace=CodeGraphTools.workspace(List.of(),ignored->{})) {
            var tools=new TreeMap<String,GraphTool>();
            workspace.tools(path->{throw new UnsupportedOperationException("Reference generation cannot onboard projects");})
                    .forEach(tool->tools.put(tool.spec().name(),tool));
            DbaMcpTools.definitions().forEach(tool->tools.put(tool.spec().name(),tool));
            for(var tool:tools.values()) {
                var spec=tool.spec();
                out.append("## `").append(spec.name()).append("`\n\n").append(spec.description()).append("\n\n")
                        .append("```json\n").append(spec.inputSchemaJson()).append("\n```\n\n");
            }
        }
        return out.toString();
    }
    public static void main(String[] args)throws Exception {
        String text=markdown();
        if(args.length==0)System.out.print(text);
        else if(args.length==1)java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]),text,java.nio.charset.StandardCharsets.UTF_8);
        else throw new IllegalArgumentException("Usage: ToolReference [output.md]");
    }
}
