package io.doindev.codegraph.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.doindev.codegraph.config.CodeGraphConfig;
import io.doindev.codegraph.query.GraphQuery;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

/** Explicit-target analysis only; historical Git/catalog composition must not be implied. */
final class ChangeAnalysisTool implements GraphTool {
    private final GraphQuery graph;private final CodeGraphConfig config;private final Path root;private final boolean testsOnly;
    ChangeAnalysisTool(GraphQuery graph,CodeGraphConfig config,Path root,boolean testsOnly){this.graph=graph;this.config=config;this.root=root;this.testsOnly=testsOnly;}
    public ToolSpec spec(){
        var schema=ToolSupport.JSON.createObjectNode().put("type","object").put("additionalProperties",false);var p=schema.putObject("properties");
        for(String key:Set.of("files","symbols"))p.putObject(key).put("type","array").put("maxItems",100).putObject("items").put("type","string").put("minLength",1).put("maxLength",4096);
        var changes=p.putObject("databaseChanges").put("type","array").put("maxItems",100).putObject("items").put("type","object").put("additionalProperties",false);
        changes.putArray("required").add("table");var fields=changes.putObject("properties");for(String key:Set.of("schema","table","column"))fields.putObject(key).put("type","string").put("maxLength",128);
        fields.putObject("operation").put("type","string").putArray("enum").add("add").add("alter").add("remove").add("rename");
        fields.putObject("newName").put("type","string").put("maxLength",128);
        var git=p.putObject("git").put("type","object").put("additionalProperties",false);var gp=git.putObject("properties");
        gp.putObject("kind").put("type","string").putArray("enum").add("working_tree").add("revisions");
        for(String key:Set.of("base","head"))gp.putObject(key).put("type","string").put("minLength",1).put("maxLength",200);
        p.putObject("depth").put("type","integer").put("minimum",1).put("maximum",10).put("default",5);
        p.putObject("limit").put("type","integer").put("minimum",1).put("maximum",100).put("default",50);
        p.putObject("minConfidence").put("type","number").put("minimum",0).put("maximum",1).put("default",0);
        return new ToolSpec(testsOnly?"find_affected_tests":"analyze_change",testsOnly
                ?"Recommend candidate tests from explicit file/symbol/database-name changes using bounded indexed dependency paths and confidence. Does not execute tests or claim complete test coverage."
                :"Analyze explicit files, symbol IDs, an exact Git working-tree/revision selection, or proposed database-name changes against the current code index. Returns bounded dependencies, static ORM/SQL mappings, migration references, candidate tests and uncertainty. Git evidence records resolved commits; results still describe the current index and do not access live database catalogs.",schema.toString());
    }
    public ToolResponse call(JsonNode args){
        try{
            if(args==null||!args.isObject()||args.toString().length()>65536)throw new IllegalArgumentException("Bounded JSON object required");
            args.fieldNames().forEachRemaining(key->{if(!Set.of("project","files","symbols","databaseChanges","git","depth","limit","minConfidence").contains(key))throw new IllegalArgumentException("Unsupported analysis input: "+key);});
            ObjectNode effective=args.deepCopy();int cap=Math.min(100,config.limits().maxResults());effective.put("limit",ToolSupport.intArg(args,"limit",Math.min(50,cap),1,cap));
            GitChangeSelection.Selection selection=null;
            if(args.has("git")){
                selection=GitChangeSelection.resolve(root,args.get("git"));
                LinkedHashSet<String> files=new LinkedHashSet<>();if(args.has("files"))args.path("files").forEach(file->files.add(file.asText()));files.addAll(selection.paths());
                if(files.size()>100)throw new IllegalArgumentException("Combined explicit and Git selection exceeds the 100-path analysis limit");
                var merged=effective.putArray("files");files.forEach(merged::add);effective.remove("git");
            }
            ObjectNode result=ChangeEvidence.analyze(graph,effective);
            if(selection!=null){
                result.put("analysisBasis","git_selection_against_current_index").set("git",selection.evidence());
                result.withArray("limitations").add("Git changes were resolved exactly, but dependency evidence comes from the current published graph generation rather than a historical index.");
            }
            if(testsOnly)result.remove(java.util.List.of("affectedSymbols","databaseMappings","migrationReferences"));
            return ToolSupport.finish(result,config);
        }catch(IllegalArgumentException invalid){return ToolResponse.fail(invalid.getMessage());}
    }
}
