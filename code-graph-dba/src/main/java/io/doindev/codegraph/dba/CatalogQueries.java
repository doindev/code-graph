package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import java.util.function.*;
import static io.doindev.codegraph.dba.ProjectContexts.json;
import static io.doindev.codegraph.dba.ProjectContexts.number;

/** Bounded query rendering shared by project-bound and standalone catalog observations. */
final class CatalogQueries {
    private final io.doindev.codegraph.query.GenerationCursor catalogCursors=new io.doindev.codegraph.query.GenerationCursor();
    private static final int MAX_OFFSET=4<<20;
    private final LongSupplier clock;
    private volatile QueryJobs accounting;
    void accounting(QueryJobs jobs){accounting=jobs;}
    /** Reserve room for scope, authorization and continuation metadata added by callers. */
    private static final int PAGE_BYTES=(1<<20)-(16<<10);
    static final class Page {
        final ArrayNode rows; final int limit; int bytes=0; boolean stopped;
        Page(ArrayNode rows,int limit){this.rows=rows;this.limit=limit;}
        void add(JsonNode row){
            if(stopped||rows.size()>=limit)return;
            if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("Catalog read cancelled");
            int size=row.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length+1;
            if(bytes+size>PAGE_BYTES){
                if(rows.isEmpty())throw new IllegalArgumentException("item_too_large: catalog record exceeds the 1 MiB response allowance; request a narrower section");
                stopped=true;return;
            }
            rows.add(row);bytes+=size;
        }
        void metadata(ObjectNode out){out.put("requestedCount",limit).put("returnedCount",rows.size())
            .put("pageEndReason",stopped?"byte_limit":out.path("truncated").asBoolean()?"item_limit":"complete");}
    }
    CatalogQueries(LongSupplier clock){this.clock=clock;}
    JsonNode read(CatalogCache.Scope scope,String principal,String targetKey,String authorizationIdentity,
                  String operation,JsonNode args,Function<JsonNode,JsonNode> references){
        QueryJobs budget=accounting;QueryJobs.RetainedReservation reservation=budget==null?null:budget.retainAllowance(2L<<20);
        try{return scope.read(published->{
            JsonNode snapshotMetadata=published.metadata;ObjectNode out=Profiles.JSON.createObjectNode().put("generation",snapshotMetadata.path("generation").asLong()).put("scannedAt",snapshotMetadata.path("finishedAt").asLong()).put("state",scope.state).put("stale",clock.getAsLong()>scope.nextScan||scope.state.equals("stale"));out.set("version",snapshotMetadata.path("version"));
            if(operation.equals("dba_search_objects")){
                String query=args.path("query").asText("").toLowerCase(Locale.ROOT),kind=args.path("kind").asText("");
                String cursorScope=Profiles.JSON.createArrayNode().add(principal).add(targetKey).add(scope.instanceId).add(authorizationIdentity).add(query).add(kind).add(args.path("searchDefinitions").asBoolean()).toString();
                if(args.has("cursor")&&args.has("offset"))throw new IllegalArgumentException("Use cursor or legacy offset, not both");
                var position=catalogCursors.read(args.path("cursor").asText(""),cursorScope,out.path("generation").asLong());
                int offset=args.has("cursor")?Math.toIntExact(position.seen()):number(args,"offset",0,0,50000),limit=number(args,"limit",30,1,100);int[] matched={0};ArrayNode objects=out.putArray("objects");Page page=new Page(objects,limit);
                published.store.scan("i/",(key,value)->{JsonNode row=json(value);boolean matches=(kind.isBlank()||kind.equals(row.path("kind").asText()))&&(row.path("name").asText().toLowerCase(Locale.ROOT).contains(query)||row.path("schema").asText().toLowerCase(Locale.ROOT).contains(query));if(!matches&&args.path("searchDefinitions").asBoolean()&&query.length()>=3&&(kind.isBlank()||kind.equals(row.path("kind").asText())))matches=json(published.store.get("o/"+row.path("id").asText())).path("ddl").asText().toLowerCase(Locale.ROOT).contains(query);if(matches){if(matched[0]++>=offset)page.add(row);}});
                int next=offset+objects.size();out.put("total",matched[0]).put("nextOffset",next).put("truncated",matched[0]>next);page.metadata(out);
                out.put("inventoryComplete",snapshotMetadata.path("inventoryComplete").asBoolean(false)).put("coverage",snapshotMetadata.path("coverage").asText("unknown"));
                if(matched[0]>next)out.put("nextCursor",catalogCursors.issue(cursorScope,out.path("generation").asLong(),new io.doindev.codegraph.query.GenerationCursor.Position("",next,position.expiresAt())));
                return out;
            }
            String objectId=Profiles.text(args,"objectId",64);JsonNode object=json(published.store.get("o/"+objectId));if(object==null)throw new IllegalArgumentException("Unknown object in this snapshot");
            if(operation.equals("dba_get_indexed_properties")){String section=args.path("section").asText("columns");if(!Set.of("columns","indexes","primaryKeys","foreignKeys","privileges","fieldObservations","nativeColumns","nativeKeys","nativeIndexes","constraints").contains(section))throw new IllegalArgumentException("Unknown metadata section");int offset=number(args,"offset",0,0,10000),limit=number(args,"limit",50,1,100);JsonNode rows=object.path(section);ArrayNode values=out.putArray("properties");Page page=new Page(values,limit);for(int i=offset;i<Math.min(rows.size(),offset+limit);i++)page.add(rows.get(i));out.put("section",section).put("nextOffset",offset+values.size()).put("truncated",offset+values.size()<rows.size());page.metadata(out);return out;}
            if(operation.equals("dba_get_indexed_ddl")){String ddl=object.path("ddl").asText();int offset=number(args,"offset",0,0,MAX_OFFSET),length=number(args,"length",32000,1,64000);if(offset>ddl.length())throw new IllegalArgumentException("DDL offset exceeds its length");int end=Math.min(ddl.length(),offset+length);out.set("object",json(published.store.get("i/"+objectId)));out.put("ddl",ddl.substring(offset,end)).put("nextOffset",end).put("truncated",end<ddl.length());return out;}
            if(operation.equals("dba_get_database_dependencies")){ArrayNode edges=out.putArray("dependencies");int offset=number(args,"offset",0,0,50000),limit=number(args,"limit",100,1,100);int[] total={0};Page page=new Page(edges,limit);published.store.scan("e/",(k,v)->{JsonNode e=json(v);if(e.path("schema").equals(object.path("schema"))&&e.path("name").equals(object.path("name"))||e.path("targetSchema").equals(object.path("schema"))&&e.path("target").equals(object.path("name"))){if(total[0]++>=offset)page.add(e);}});out.put("truncated",total[0]>offset+edges.size()).put("nextOffset",offset+edges.size());page.metadata(out);return out;}
            if(operation.equals("dba_find_code_references")){JsonNode candidates=references.apply(object);ArrayNode rows=out.putArray("references");Page page=new Page(rows,100);for(JsonNode reference:candidates)page.add(reference);out.put("resolution","Indexed static SQL and ORM evidence; candidates require review. Runtime SQL and naming strategies may remain unresolved.").put("inventoryComplete",false).put("truncated",page.stopped||candidates.size()>=100);page.metadata(out);return out;}
            throw new IllegalArgumentException("Unknown catalog operation");
        });}finally{if(reservation!=null)reservation.close();}
    }
}
