package io.doindev.codegraph.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.doindev.codegraph.config.CodeGraphConfig;
import io.doindev.codegraph.model.*;
import io.doindev.codegraph.query.*;
import java.util.*;

/** Shared bounded navigation, with explicit distinction between occurrences and enclosing symbols. */
final class CodeNavigationTool implements GraphTool {
    enum Operation {
        OUTLINE("get_file_outline","List indexed declarations in one relative file, with source spans and stable symbol IDs."),
        POSITION("resolve_symbol_at_position","Resolve indexed reference-expression candidates at a 1-based position, or return containing declarations when no reference is indexed. Confidence and range precision are explicit; this is not a token-level language server."),
        REFERENCES("find_references","Find indexed incoming call/reference/read/write/import relationships, with confidence and exact-versus-containing location precision. Not a complete textual occurrence search."),
        IMPLEMENTATIONS("find_implementations","Find direct indexed EXTENDS/IMPLEMENTS relationships for a type. Method overrides and runtime dispatch are not inferred; follow returned types for indirect descendants.");
        final String name,description;
        Operation(String name,String description){this.name=name;this.description=description;}
    }
    private static final Set<NodeKind> SYMBOLS=Set.of(NodeKind.TYPE,NodeKind.FUNCTION,NodeKind.VARIABLE);
    private static final Set<EdgeKind> REFERENCES=EnumSet.of(EdgeKind.CALLS,EdgeKind.REFERENCES,EdgeKind.READS,EdgeKind.WRITES,EdgeKind.IMPORTS);
    private final GraphQuery graph;
    private final CodeGraphConfig config;
    private final Operation operation;
    private final GenerationCursor cursors=new GenerationCursor();
    CodeNavigationTool(GraphQuery graph,CodeGraphConfig config,Operation operation){this.graph=graph;this.config=config;this.operation=operation;}

    public ToolSpec spec(){
        var schema=ToolSupport.JSON.createObjectNode().put("type","object").put("additionalProperties",false);
        var props=schema.putObject("properties");var required=schema.putArray("required");
        String target=isFile()?"file":"symbol_id";
        props.putObject(target).put("type","string").put("minLength",1).put("maxLength",4096);required.add(target);
        if(operation==Operation.POSITION){
            props.putObject("line").put("type","integer").put("minimum",1);required.add("line");
            props.putObject("column").put("type","integer").put("minimum",1).put("default",1);
        }
        if(!isFile())props.putObject("minConfidence").put("type","number").put("minimum",0).put("maximum",1).put("default",0);
        props.putObject("limit").put("type","integer").put("minimum",1).put("maximum",100).put("default",20);
        props.putObject("cursor").put("type","string").put("maxLength",16384);
        return new ToolSpec(operation.name,operation.description,schema.toString());
    }
    private boolean isFile(){return operation==Operation.OUTLINE||operation==Operation.POSITION;}
    public ToolResponse call(JsonNode args){
        try{return graph.read(()->query(args));}
        catch(IllegalArgumentException e){return ToolResponse.fail(e.getMessage());}
    }
    private ToolResponse query(JsonNode args){
        String target=ToolSupport.stringArg(args,isFile()?"file":"symbol_id","");
        if(target.isBlank()||target.length()>4096)throw new IllegalArgumentException("A bounded explicit target is required");
        if(isFile()){
            target=target.replace('\\','/');
            if(target.startsWith("/")||target.contains(":")||Arrays.asList(target.split("/",-1)).contains(".."))throw new IllegalArgumentException("file must be relative to the selected project without parent traversal");
            if(target.startsWith("./"))target=target.substring(2);
        }
        int limit=ToolSupport.intArg(args,"limit",20,1,Math.min(100,config.limits().maxResults()));
        int line=args.path("line").asInt(0),column=args.path("column").asInt(1);
        if(operation==Operation.POSITION&&(line<1||column<1))throw new IllegalArgumentException("line and column must be positive 1-based integers");
        double confidence=args.path("minConfidence").asDouble(0);
        if(!Double.isFinite(confidence)||confidence<0||confidence>1)throw new IllegalArgumentException("minConfidence must be between zero and one");
        long generation=graph.status().generation();
        String scope=ToolSupport.JSON.createArrayNode().add(operation.name).add(target).add(line).add(column).add(confidence).toString();
        var position=cursors.read(args.path("cursor").asText(""),scope,generation);
        var collector=new Page(limit,position.key());
        var referencesAtPosition=new Page(limit,position.key());
        boolean[] resolvedReference={false};
        if(isFile()){
            String file=target;
            graph.scanNodes(SYMBOLS,node->{
                if(!file.equals(node.relPath())||node.span()==null)return;
                if(operation==Operation.POSITION&&!contains(node.span(),line,column))return;
                ObjectNode row=symbol(node);
                if(operation==Operation.POSITION)row.put("resolution","containing_symbol_only");
                collector.accept(key(node),row);
                if(operation==Operation.POSITION)graph.scanEdges(node.id(),Direction.OUT,REFERENCES,edge->{
                    var attrs=edge.attrs();
                    if(!Set.of("reference_expression","identifier_token").contains(attrs.getOrDefault("referencePrecision",""))||!file.equals(attrs.get("referencePath")))return;
                    try{
                        var span=new SourceSpan(file,Integer.parseInt(attrs.get("referenceStartLine")),Integer.parseInt(attrs.get("referenceStartColumn")),Integer.parseInt(attrs.get("referenceEndLine")),Integer.parseInt(attrs.get("referenceEndColumn")));
                        if(!contains(span,line,column))return;
                        Node definition=graph.node(edge.to()).orElse(null);if(definition==null)return;
                        var candidate=symbol(definition).put("resolution","reference_expression_candidate").put("locationPrecision",attrs.get("referencePrecision")).put("confidence",edge.confidence()).put("relationship",edge.kind().name().toLowerCase(Locale.ROOT)).put("viaSymbolId",node.id().value());
                        candidate.putObject("occurrence").put("path",file).put("startLine",span.startLine()).put("startColumn",span.startCol()).put("endLine",span.endLine()).put("endColumn",span.endCol()).put("rangeConvention","1-based UTF-16; inclusive end");
                        resolvedReference[0]=true;referencesAtPosition.accept(String.format(Locale.ROOT,"%010d/%010d/",span.startLine(),span.startCol())+definition.id().value(),candidate);
                    }catch(NumberFormatException ignored){}
                });
            });
        }else{
            NodeId id=ToolSupport.target(target);
            Node selected=graph.node(id).orElseThrow(()->new IllegalArgumentException("Unknown symbol; use search_symbols first"));
            if(operation==Operation.IMPLEMENTATIONS&&selected.kind()!=NodeKind.TYPE)throw new IllegalArgumentException("Implementation discovery currently requires a type symbol; method override resolution is not indexed");
            long[] ordinal={0};
            graph.scanEdges(id,Direction.IN,operation==Operation.REFERENCES?REFERENCES:EnumSet.of(EdgeKind.EXTENDS,EdgeKind.IMPLEMENTS),edge->{
                long index=ordinal[0]++;
                if(edge.confidence()<confidence)return;
                Node source=graph.node(edge.from()).orElse(null);
                if(source==null)return;
                ObjectNode row=symbol(source).put("relationship",edge.kind().name().toLowerCase(Locale.ROOT)).put("confidence",edge.confidence());
                row.put("targetId",id.value()).put("locationPrecision","containing_symbol");
                String callLine=edge.attrs().getOrDefault("referenceStartLine",edge.attrs().getOrDefault("callSiteLine",edge.attrs().get("site")));
                if(callLine!=null&&source.relPath()!=null)try{
                    int at=Integer.parseInt(callLine);
                    if(at>0){
                        row.put("locationPrecision","line_only");
                        var occurrence=row.putObject("occurrence").put("path",source.relPath()).put("line",at);
                        if(edge.attrs().containsKey("referencePrecision")) {
                            row.put("locationPrecision",edge.attrs().get("referencePrecision"));
                            var span=occurrence.putObject("span");
                            for(String part:List.of("StartLine","StartColumn","EndLine","EndColumn"))
                                span.put(Character.toLowerCase(part.charAt(0))+part.substring(1),Integer.parseInt(edge.attrs().get("reference"+part)));
                            occurrence.put("rangeConvention","1-based UTF-16; inclusive end");
                        }
                    }
                }catch(NumberFormatException ignored){}
                collector.accept(key(source)+"/"+edge.kind().name()+"/"+String.format(Locale.ROOT,"%012d",index),row);
            });
        }
        if(graph.status().generation()!=generation)throw new IllegalArgumentException("stale_cursor: index changed during navigation; restart without cursor");
        var out=ToolSupport.JSON.createObjectNode().put("generation",generation).put("state","complete");
        out.putObject("target").put(isFile()?"file":"symbolId",target);
        Page chosen=resolvedReference[0]?referencesAtPosition:collector;
        var rows=out.putArray("symbols");var page=chosen.rows.stream().sorted(Comparator.comparing(Item::key)).toList();
        page.forEach(item->rows.add(item.value()));
        long seen=position.seen()+page.size(),remaining=Math.max(0,chosen.total-seen);
        out.put("total",chosen.total).put("truncated",remaining>0).put("omitted",remaining).put("inventoryComplete",false);
        out.put("coverage",operation==Operation.IMPLEMENTATIONS?"Direct indexed type relationships only; indirect implementations and method overrides require additional analysis":"Indexed declarations/relationships only; dynamic, unresolved and unindexed occurrences may be absent");
        if(operation==Operation.POSITION)out.put("resolution",resolvedReference[0]?"reference_expression_candidates":"containing_symbol_only").put("exactIdentifierResolved",false);
        if(remaining>0&&!page.isEmpty())out.put("nextCursor",cursors.issue(scope,generation,new GenerationCursor.Position(page.getLast().key(),seen,position.expiresAt())));
        return ToolSupport.finish(out,config);
    }
    private static boolean contains(SourceSpan span,int line,int column){
        return (line>span.startLine()||line==span.startLine()&&column>=span.startCol())
                &&(line<span.endLine()||line==span.endLine()&&column<=span.endCol());
    }
    private static String key(Node node){
        var span=node.span();return String.format(Locale.ROOT,"%010d/%010d/",span==null?0:span.startLine(),span==null?0:span.startCol())+node.id().value();
    }
    private static ObjectNode symbol(Node node){
        var row=ToolSupport.JSON.createObjectNode().put("id",node.id().value()).put("name",node.name()).put("kind",node.kind().name().toLowerCase(Locale.ROOT)).put("signature",node.displaySignature());
        if(node.relPath()!=null)row.put("path",node.relPath());
        if(node.span()!=null){var span=node.span();row.putObject("declarationSpan").put("startLine",span.startLine()).put("startColumn",span.startCol()).put("endLine",span.endLine()).put("endColumn",span.endCol());}
        return row;
    }
    private record Item(String key,ObjectNode value,long bytes){}
    private final class Page {
        final int limit;final String after;long total,bytes;
        final PriorityQueue<Item> rows=new PriorityQueue<>(Comparator.comparing(Item::key).reversed());
        Page(int limit,String after){this.limit=limit;this.after=after;}
        void accept(String key,ObjectNode row){
            if(Thread.currentThread().isInterrupted())throw new IllegalArgumentException("Navigation cancelled");
            total++;
            if(key.compareTo(after)<=0||rows.size()==limit&&key.compareTo(rows.peek().key())>=0)return;
            long size=row.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length+2L*key.length()+128;
            if(rows.size()==limit)bytes-=rows.remove().bytes();
            rows.add(new Item(key,row,size));bytes+=size;
            if(bytes>config.limits().maxResponseBytes())throw new IllegalArgumentException("Navigation page exceeds memory bound; lower limit or narrow the target");
        }
    }
}
