package io.doindev.codegraph.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.doindev.codegraph.model.*;
import io.doindev.codegraph.query.GraphQuery;
import java.util.*;

/** Shared streaming code-impact evidence. No indexing, file edits, command execution or database access. */
final class ChangeEvidence {
    private static final Set<NodeKind> DECLARATIONS=Set.of(NodeKind.TYPE,NodeKind.FUNCTION,NodeKind.VARIABLE);
    private static final Set<EdgeKind> DEPENDENCIES=Set.of(EdgeKind.CALLS,EdgeKind.REFERENCES,EdgeKind.READS,EdgeKind.WRITES,EdgeKind.IMPORTS,EdgeKind.EXTENDS,EdgeKind.IMPLEMENTS);
    private static final int MAX_VISITED=1000,MAX_SEEDS=200;
    record Reach(Node node,int depth,float confidence,List<String> path,String relationship){}
    static ObjectNode analyze(GraphQuery graph,JsonNode args){return graph.read(()->compute(graph,args));}
    private static ObjectNode compute(GraphQuery graph,JsonNode args){
        long generation=graph.status().generation(),deadline=System.nanoTime()+5_000_000_000L;
        Set<String> files=strings(args,"files"),symbols=strings(args,"symbols");
        JsonNode changes=args.path("databaseChanges");
        if(!changes.isMissingNode()&&(!changes.isArray()||changes.size()>100))throw new IllegalArgumentException("databaseChanges must be an array of at most 100 explicit changes");
        if(files.isEmpty()&&symbols.isEmpty()&&changes.isEmpty())throw new IllegalArgumentException("Supply explicit files, symbols or databaseChanges");
        for(String file:files)relative(file);
        for(JsonNode change:changes)if(!change.path("table").isTextual()||change.path("table").asText().isBlank()||change.toString().length()>4096)throw new IllegalArgumentException("Each bounded database change requires an explicit table name");
        double minimum=args.path("minConfidence").asDouble(0);if(!Double.isFinite(minimum)||minimum<0||minimum>1)throw new IllegalArgumentException("minConfidence must be 0..1");
        int maxDepth=ToolSupport.intArg(args,"depth",5,1,10),limit=ToolSupport.intArg(args,"limit",50,1,100);
        var out=ToolSupport.JSON.createObjectNode().put("generation",generation).put("state","complete").put("analysisBasis","current_index").put("inventoryComplete",false);
        var target=out.putObject("target");target.set("files",ToolSupport.JSON.valueToTree(files));target.set("symbols",ToolSupport.JSON.valueToTree(symbols));
        var limitations=out.putArray("limitations");limitations.add("Indexed relationships are incomplete; absence does not prove non-use or test coverage");
        var missing=out.putArray("unresolvedTargets");var mappings=out.putArray("databaseMappings");
        var seeds=new LinkedHashMap<NodeId,Reach>();boolean[] bounded={false};
        for(String symbol:symbols){var node=graph.node(NodeId.parse(symbol)).orElse(null);if(node==null)missing.addObject().put("symbolId",symbol).put("reason","Missing from current index; deleted or historical definitions are not available");else seeds.put(node.id(),new Reach(node,0,1,List.of(symbol),"explicit_target"));}
        Set<String> matchedFiles=new HashSet<>(),mappedFiles=new HashSet<>();
        graph.scanNodes(Set.of(NodeKind.DATABASE_MAPPING),node->{
            check(deadline);boolean match=files.contains(node.relPath());
            for(JsonNode change:changes){
                boolean table=change.path("table").asText().equalsIgnoreCase(node.attrs().getOrDefault("table",""));
                String schema=change.path("schema").asText(),column=change.path("column").asText();
                if(table&&(schema.isBlank()||node.attrs().getOrDefault("schema","").isBlank()||schema.equalsIgnoreCase(node.attrs().get("schema")))&&(column.isBlank()||node.attrs().getOrDefault("column","").isBlank()||column.equalsIgnoreCase(node.attrs().get("column"))))match=true;
            }
            if(!match)return;
            if(mappedFiles.size()<MAX_SEEDS)mappedFiles.add(node.relPath());else bounded[0]=true;
            if(mappings.size()<limit){var row=location(node).put("confidence",Double.parseDouble(node.attrs().getOrDefault("confidence","0")));row.set("mapping",ToolSupport.JSON.valueToTree(node.attrs()));mappings.add(row);}else bounded[0]=true;
        });
        graph.scanNodes(DECLARATIONS,node->{
            check(deadline);if(!files.contains(node.relPath())&&!mappedFiles.contains(node.relPath()))return;matchedFiles.add(node.relPath());
            if(seeds.size()<MAX_SEEDS)seeds.putIfAbsent(node.id(),new Reach(node,0,mappedFiles.contains(node.relPath())?.5f:1,List.of(node.id().value()),mappedFiles.contains(node.relPath())?"same_file_as_database_mapping":"file_target"));else bounded[0]=true;
        });
        for(String file:files)if(!matchedFiles.contains(file))missing.addObject().put("path",file).put("reason","No indexed declarations; deleted, unsupported or unindexed file is not proof of no impact");
        var queue=new ArrayDeque<>(seeds.values());var visited=new LinkedHashMap<>(seeds);
        while(!queue.isEmpty()){
            check(deadline);Reach current=queue.removeFirst();if(current.depth()>=maxDepth)continue;
            graph.scanEdges(current.node().id(),Direction.IN,DEPENDENCIES,edge->{
                check(deadline);float confidence=Math.min(current.confidence(),edge.confidence());if(confidence<minimum||visited.containsKey(edge.from()))return;
                if(visited.size()>=MAX_VISITED){bounded[0]=true;return;}
                Node node=graph.node(edge.from()).orElse(null);if(node==null)return;
                var path=new ArrayList<>(current.path());path.add(node.id().value());var reach=new Reach(node,current.depth()+1,confidence,List.copyOf(path),edge.kind().name().toLowerCase(Locale.ROOT));visited.put(node.id(),reach);queue.addLast(reach);
            });
        }
        var affected=out.putArray("affectedSymbols");var tests=out.putArray("candidateTests");var migrations=out.putArray("migrationReferences");
        for(Reach reach:visited.values()){
            var row=location(reach.node()).put("depth",reach.depth()).put("confidence",reach.confidence()).put("relationship",reach.relationship());row.set("evidencePath",ToolSupport.JSON.valueToTree(reach.path()));
            if(affected.size()<limit)affected.add(row);else bounded[0]=true;
            String path=Objects.toString(reach.node().relPath(),"").toLowerCase(Locale.ROOT);
            if(isTest(path)&&tests.size()<limit)tests.add(row.deepCopy().put("recommendationBasis","Dependency evidence plus test-path convention; not executed or complete coverage"));
        }
        for(JsonNode mapping:mappings){String path=mapping.path("path").asText().toLowerCase(Locale.ROOT);if((path.contains("migration")||path.matches(".*[/\\\\]v[0-9].*__.*\\.sql"))&&migrations.size()<limit)migrations.add(mapping);}
        if(graph.status().generation()!=generation)throw new IllegalArgumentException("stale_generation: index changed during analysis; retry");
        out.put("visited",visited.size()).put("truncated",bounded[0]);
        out.putObject("databaseDependencies").put("state","not_observed").put("reason","This code analysis does not access database catalogs or grant database permissions");
        if(!changes.isEmpty())limitations.add("Database change matching uses source names, not an authorized live schema; compare catalogs separately");
        if(bounded[0])limitations.add("A result, seed or 1000-node traversal bound was reached; narrow the requested targets");
        limitations.add("Traversal is bounded to depth "+maxDepth+"; test recommendations do not run commands");
        return out;
    }
    static boolean isTest(String path){return path.contains("/test/")||path.contains("/tests/")||path.startsWith("test/")||path.startsWith("tests/")||path.contains(".test.")||path.contains(".spec.")||path.endsWith("test.java")||path.endsWith("tests.java")||path.contains("/__tests__/");}
    private static Set<String> strings(JsonNode args,String key){var out=new LinkedHashSet<String>();JsonNode values=args.path(key);if(values.isMissingNode())return out;if(!values.isArray()||values.size()>100)throw new IllegalArgumentException(key+" must be an array of at most 100 strings");for(JsonNode value:values){if(!value.isTextual()||value.asText().isBlank()||value.asText().length()>4096)throw new IllegalArgumentException("Invalid "+key+" target");out.add(value.asText().replace('\\','/'));}return out;}
    static void relative(String path){if(path.startsWith("/")||path.contains(":")||Arrays.asList(path.split("/",-1)).contains(".."))throw new IllegalArgumentException("Use project-relative paths without parent traversal");}
    private static void check(long deadline){if(Thread.currentThread().isInterrupted()||System.nanoTime()>deadline)throw new IllegalArgumentException("Analysis interrupted or exceeded five-second work bound; narrow the targets");}
    static ObjectNode location(Node node){var out=ToolSupport.JSON.createObjectNode().put("id",node.id().value()).put("name",node.name()).put("path",node.relPath());if(node.span()!=null)out.putObject("span").put("startLine",node.span().startLine()).put("startColumn",node.span().startCol()).put("endLine",node.span().endLine()).put("endColumn",node.span().endCol());return out;}
}
