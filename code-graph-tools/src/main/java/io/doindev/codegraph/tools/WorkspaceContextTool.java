package io.doindev.codegraph.tools;

import com.fasterxml.jackson.databind.JsonNode;
import io.doindev.codegraph.query.GenerationCursor;
import io.doindev.codegraph.query.GraphQuery;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Cheap graph-only workspace discovery. The DBA composition replaces this with an authenticated tool. */
final class WorkspaceContextTool implements GraphTool {
    private final Map<String,GraphQuery> graphs;
    private final GenerationCursor cursors = new GenerationCursor();
    WorkspaceContextTool(Map<String,GraphQuery> graphs) { this.graphs = graphs; }
    public ToolSpec spec() { return new ToolSpec("get_workspace_context",
            "Bounded project and freshness summary; does not onboard, index or renew project activity. DBA information is absent when DBA is disabled.",
            """
            {"type":"object","additionalProperties":false,"properties":{
              "limit":{"type":"integer","minimum":1,"maximum":100,"default":20},
              "cursor":{"type":"string","maxLength":16384}}}
            """); }
    public ToolResponse call(JsonNode args) {
        try {
            int limit = args.path("limit").asInt(20);
            if(limit<1||limit>100)return ToolResponse.fail("limit must be 1..100");
            var rows=ToolSupport.JSON.createArrayNode();
            synchronized(graphs) { graphs.forEach((name,graph)->{
                var status=graph.status(); rows.addObject().put("kind","project").put("name",name)
                        .put("generation",status.generation()).put("state",status.state())
                        .put("files",status.filesIndexed()).put("symbols",status.symbolCount()).put("dirtyPending",status.dirtyPending());
            }); }
            byte[] digest=MessageDigest.getInstance("SHA-256").digest(rows.toString().getBytes(StandardCharsets.UTF_8));
            String fingerprint=HexFormat.of().formatHex(digest);
            long generation=java.nio.ByteBuffer.wrap(digest).getLong();
            var position=cursors.read(args.path("cursor").asText(""),"graph-workspace",generation);
            var out=ToolSupport.JSON.createObjectNode().put("dbaEnabled",false).put("state","complete")
                    .put("generation",fingerprint).put("inventoryComplete",true).put("total",rows.size());
            var entries=out.putArray("entries");
            int start=Math.toIntExact(position.seen()),end=Math.min(rows.size(),start+limit);
            for(int i=start;i<end;i++)entries.add(rows.get(i));
            out.put("truncated",end<rows.size());
            if(end<rows.size())out.put("nextCursor",cursors.issue("graph-workspace",generation,new GenerationCursor.Position("",end,position.expiresAt())));
            return ToolResponse.ok(out.toString());
        } catch(IllegalArgumentException e) { return ToolResponse.fail(e.getMessage()); }
        catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
