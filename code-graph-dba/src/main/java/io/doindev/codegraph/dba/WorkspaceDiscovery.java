package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import io.doindev.codegraph.query.GenerationCursor;
import java.util.List;

/** Authorized summaries only; no database or driver access and no activity renewal. */
final class WorkspaceDiscovery {
    private final GenerationCursor cursors = new GenerationCursor();

    ObjectNode page(String principal,JsonNode args,JsonNode projects,JsonNode connections,JsonNode bindings) {
        int limit=args.path("limit").asInt(20);
        if(limit<1||limit>100)throw new IllegalArgumentException("limit must be 1..100");
        var entries=Profiles.JSON.createArrayNode();
        for(JsonNode project:projects) add(entries,"project",project,List.of("id","name","generation","state","files","symbols","dirtyPending"));
        for(JsonNode connection:connections) add(entries,"connection",connection,List.of("id","name"));
        for(JsonNode binding:bindings.path("bindings")) add(entries,"binding",binding,List.of(
                "id","projectId","projectName","connectionId","connectionName","environment","role","purpose",
                "database","schema","enabled","state","generation","effectivePermissions"));
        String fingerprint=CatalogScanner.hash(entries.toString());
        long generation=Long.parseUnsignedLong(fingerprint.substring(0,16),16);
        var position=cursors.read(args.path("cursor").asText(""),principal,generation);
        var out=Profiles.JSON.createObjectNode().put("dbaEnabled",true).put("state","complete")
                .put("generation",fingerprint).put("inventoryComplete",true).put("total",entries.size())
                .put("permissionNote","effectivePermissions summarizes legacy read grants; inspect dba_get_my_permissions for scoped exact/category/session policies. Discovery never grants access.");
        var page=out.putArray("entries");int start=Math.toIntExact(position.seen()),end=Math.min(entries.size(),start+limit);
        for(int i=start;i<end;i++)page.add(entries.get(i));
        out.put("truncated",end<entries.size());
        if(end<entries.size())out.put("nextCursor",cursors.issue(principal,generation,new GenerationCursor.Position("",end,position.expiresAt())));
        return out;
    }
    private static void add(ArrayNode rows,String kind,JsonNode source,List<String> fields) {
        ObjectNode row=rows.addObject().put("kind",kind);
        for(String field:fields)if(source.has(field))row.set(field,source.path(field).deepCopy());
    }
}
