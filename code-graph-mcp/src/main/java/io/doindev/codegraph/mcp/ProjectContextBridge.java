package io.doindev.codegraph.mcp;

import io.doindev.codegraph.dba.*;
import io.doindev.codegraph.index.Workspace;
import io.doindev.codegraph.tools.*;
import io.doindev.codegraph.store.DocumentStore;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.util.*;

/** Composition-only bridge; database documents never enter anonymous graph responses. */
public final class ProjectContextBridge implements ProjectContextHost {
    private static final ObjectMapper JSON=new ObjectMapper();
    private final Workspace workspace;private final WorkspaceTools registry;
    private ProjectContextBridge(Workspace workspace,WorkspaceTools registry){this.workspace=workspace;this.registry=registry;}
    public static void attach(DbaRuntime dba,Workspace workspace,WorkspaceTools registry,List<GraphTool> tools){
        dba.attachProjectContext(new ProjectContextBridge(workspace,registry));
        // The authenticated AgentTool supplies the combined context; never expose DBA summaries
        // through the anonymous graph-only tool.
        tools.removeIf(tool->tool.spec().name().equals("get_workspace_context"));
        Set<String> reads=Set.of("search_symbols","get_symbol_context","get_symbol","get_call_graph","get_impact_radius","get_blast_score","find_dead_code","find_code_smells","compare_architectural_drift","get_file_outline","resolve_symbol_at_position","find_references","find_implementations","analyze_change","find_affected_tests");
        for(int i=0;i<tools.size();i++){GraphTool delegate=tools.get(i);if(!reads.contains(delegate.spec().name()))continue;tools.set(i,new GraphTool(){
            public ToolSpec spec(){return delegate.spec();}
            public ToolResponse call(JsonNode args){String project=args.path("project").asText(workspace.defaultProject()==null?"":workspace.defaultProject().name());ToolResponse response=delegate.call(args);if(!response.error())dba.projectQueried(project);return response;}
        });}
    }
    public JsonNode projects(){ArrayNode out=JSON.createArrayNode();for(var p:workspace.projects()){var status=p.graph().status();out.addObject().put("id",DbaRuntime.projectContextId(p.root().toString())).put("name",p.name()).put("generation",status.generation()).put("state",status.state()).put("files",status.filesIndexed()).put("symbols",status.symbolCount()).put("dirtyPending",status.dirtyPending());}return out;}
    public DocumentStore documents(){return workspace.contextDocuments();}
    public AutoCloseable hold(String id){for(var p:workspace.projects())if(DbaRuntime.projectContextId(p.root().toString()).equals(id)){var lease=registry.lifecycle().use(p.name());return lease==null?()->{}:lease;}return ()->{};}
    public JsonNode references(String id,String schema,String object){
        ArrayNode out=JSON.createArrayNode();for(var p:workspace.projects())if(DbaRuntime.projectContextId(p.root().toString()).equals(id)){
            try(var lease=registry.lifecycle().use(p.name())){if(lease==null)return out;
                p.graph().read(()->{
                    long generation=p.graph().status().generation();
                    p.graph().scanNodes(Set.of(io.doindev.codegraph.model.NodeKind.DATABASE_MAPPING),node->{
                        if(out.size()>=100||!object.equalsIgnoreCase(node.attrs().getOrDefault("table","")))return;
                        String mappedSchema=node.attrs().getOrDefault("schema","");
                        if(!mappedSchema.isBlank()&&!schema.isBlank()&&!mappedSchema.equalsIgnoreCase(schema))return;
                        var row=out.addObject().put("id",node.id().value()).put("path",node.relPath()).put("line",node.span().startLine()).put("column",node.span().startCol()).put("generation",generation);
                        row.put("kind",node.attrs().getOrDefault("relationship","references")).put("framework",node.attrs().getOrDefault("framework","unknown"));
                        row.put("confidence",Double.parseDouble(node.attrs().getOrDefault("confidence","0")));
                        for(String key:List.of("schema","table","column","field","model","alias","sourceType","databaseType","nameResolution","uncertainty","coverage","transport","ttlPolicy","required","nativeEvidence"))if(node.attrs().containsKey(key))row.put(key.equals("column")?"databaseColumn":key,node.attrs().get(key));
                        row.put("evidence","Incrementally indexed static mapping; no source or live-database read performed");
                    });
                    if(generation!=p.graph().status().generation())throw new IllegalArgumentException("Code generation changed during mapping lookup; retry");
                    return null;
                });
            }
        }return out;
    }
    public JsonNode mappings(String id){
        ArrayNode out=JSON.createArrayNode();for(var p:workspace.projects())if(DbaRuntime.projectContextId(p.root().toString()).equals(id)){
            try(var lease=registry.lifecycle().use(p.name())){if(lease==null)return out;p.graph().read(()->{
                long generation=p.graph().status().generation();p.graph().scanNodes(Set.of(io.doindev.codegraph.model.NodeKind.DATABASE_MAPPING),node->{
                    if(out.size()>=100)return;var row=out.addObject().put("id",node.id().value()).put("path",node.relPath())
                            .put("line",node.span().startLine()).put("column",node.span().startCol()).put("generation",generation)
                            .put("confidence",Double.parseDouble(node.attrs().getOrDefault("confidence","0")));
                    for(String key:List.of("schema","table","column","field","model","framework","relationship","sourceType","databaseType","nameResolution","uncertainty","coverage","transport","ttlPolicy","required","nativeEvidence"))if(node.attrs().containsKey(key))row.put(key,node.attrs().get(key));
                });if(generation!=p.graph().status().generation())throw new IllegalArgumentException("Code generation changed during contract mapping inventory; retry");return null;});
            }
        }return out;
    }
}
