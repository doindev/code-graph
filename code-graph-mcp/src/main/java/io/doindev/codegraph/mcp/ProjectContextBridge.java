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
        Set<String> reads=Set.of("search_symbols","get_symbol","get_call_graph","get_impact_radius","get_blast_score","find_dead_code","find_code_smells","compare_architectural_drift");
        for(int i=0;i<tools.size();i++){GraphTool delegate=tools.get(i);if(!reads.contains(delegate.spec().name()))continue;tools.set(i,new GraphTool(){
            public ToolSpec spec(){return delegate.spec();}
            public ToolResponse call(JsonNode args){String project=args.path("project").asText(workspace.defaultProject()==null?"":workspace.defaultProject().name());ToolResponse response=delegate.call(args);if(!response.error())dba.projectQueried(project);return response;}
        });}
    }
    public JsonNode projects(){ArrayNode out=JSON.createArrayNode();for(var p:workspace.projects())out.addObject().put("id",DbaRuntime.projectContextId(p.root().toString())).put("name",p.name());return out;}
    public DocumentStore documents(){return workspace.contextDocuments();}
    public AutoCloseable hold(String id){for(var p:workspace.projects())if(DbaRuntime.projectContextId(p.root().toString()).equals(id)){var lease=registry.lifecycle().use(p.name());return lease==null?()->{}:lease;}return ()->{};}
    public JsonNode references(String id,String schema,String object){
        ArrayNode out=JSON.createArrayNode();long[] examined={0};long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);for(var p:workspace.projects())if(DbaRuntime.projectContextId(p.root().toString()).equals(id)){
            try(var lease=registry.lifecycle().use(p.name())){if(lease==null)return out;
                // Inspect only files already admitted to the code graph, with the existing file-size bound.
                p.graph().read(()->{p.graph().scanNodes(Set.of(io.doindev.codegraph.model.NodeKind.FILE),node->{
                    if(out.size()>=100||node.relPath()==null||examined[0]>=32L<<20||System.nanoTime()>deadline)return;Path path=p.root().resolve(node.relPath()).normalize();
                    try{if(!path.startsWith(p.root())||!path.toRealPath().startsWith(p.root())||Files.size(path)>2L<<20)return;examined[0]+=Files.size(path);String text=Files.readString(path);String[] lines=text.split("\\R");
                        java.util.regex.Pattern name=java.util.regex.Pattern.compile("(?i)(?<![A-Za-z0-9_])"+java.util.regex.Pattern.quote(object)+"(?![A-Za-z0-9_])");
                        for(int i=0;i<lines.length&&out.size()<100;i++)if(name.matcher(lines[i]).find()&&lines[i].matches("(?is).*(select|from|join|insert|update|delete|create|alter|table|entity|collection|schema|query).*"))out.addObject().put("path",node.relPath()).put("line",i+1).put("kind",lines[i].matches("(?is).*(create|alter|drop).* ".strip())?"declares_or_changes":"references").put("confidence",lines[i].contains(schema+"."+object)?0.85:0.55).put("evidence","SQL or mapping text contains the object name; verify resolution");
                    }catch(Exception ignored){}
                });return null;});
            }
        }return out;
    }
}
