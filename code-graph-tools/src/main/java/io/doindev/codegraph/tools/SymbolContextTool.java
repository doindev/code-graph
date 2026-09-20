package io.doindev.codegraph.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.doindev.codegraph.analysis.BlastScore;
import io.doindev.codegraph.config.CodeGraphConfig;
import io.doindev.codegraph.model.*;
import io.doindev.codegraph.query.*;
import java.util.*;
import static io.doindev.codegraph.tools.NavigationEvidence.*;

/** One generation and one shared byte/work allowance for opt-in, multi-symbol evidence. */
final class SymbolContextTool implements GraphTool {
    private static final List<String> SECTIONS=List.of("declaration","references","callers","callees","implementations");
    private static final Set<EdgeKind> REFERENCES=EnumSet.of(EdgeKind.CALLS,EdgeKind.REFERENCES,EdgeKind.READS,EdgeKind.WRITES,EdgeKind.IMPORTS);
    private static final int WORK_LIMIT=100_000;
    private final GraphQuery graph;
    private final CodeGraphConfig config;
    private final GenerationCursor cursors=new GenerationCursor();
    SymbolContextTool(GraphQuery graph,CodeGraphConfig config){this.graph=graph;this.config=config;}

    public ToolSpec spec(){return new ToolSpec("get_symbol_context",
            "Get declarations and optional references, distinct callers/callees, or implementations for 1..20 known symbols in one published generation. Defaults to declarations plus coverage; no source bodies. Use detail=locations when IDs and source locations suffice; it omits display name/kind/signature, never relationship confidence or coverage. One shared result/byte/work budget, not one per symbol. Follow the cursor unchanged; incomplete counts are lower bounds.","""
            {"type":"object","additionalProperties":false,"required":["symbol_ids"],"properties":{
              "symbol_ids":{"type":"array","minItems":1,"maxItems":20,"uniqueItems":true,"items":{"type":"string","minLength":1,"maxLength":4096}},
              "include":{"type":"array","minItems":1,"maxItems":5,"uniqueItems":true,"default":["declaration"],"items":{"type":"string","enum":["declaration","references","callers","callees","implementations"]}},
              "detail":{"type":"string","enum":["full","locations"],"default":"full"},
              "minConfidence":{"type":"number","minimum":0,"maximum":1,"default":0},
              "limit":{"type":"integer","minimum":1,"maximum":100,"default":20},
              "cursor":{"type":"string","maxLength":16384}}}
            """);}
    public ToolResponse call(JsonNode args){
        try{return graph.read(()->query(args));}
        catch(IllegalArgumentException e){return ToolResponse.fail(e.getMessage());}
    }
    private ToolResponse query(JsonNode args){
        List<String> ids=strings(args.path("symbol_ids"),20,"symbol_ids");
        List<String> requested=args.has("include")?strings(args.path("include"),5,"include"):List.of("declaration");
        if(!SECTIONS.containsAll(requested))throw new IllegalArgumentException("Unsupported symbol-context section");
        List<String> sections=SECTIONS.stream().filter(requested::contains).toList();
        if(args.has("minConfidence")&&!args.path("minConfidence").isNumber())throw new IllegalArgumentException("minConfidence must be numeric");
        double confidence=args.path("minConfidence").asDouble(0);
        if(!Double.isFinite(confidence)||confidence<0||confidence>1)throw new IllegalArgumentException("minConfidence must be between zero and one");
        int limit=ToolSupport.intArg(args,"limit",20,1,Math.min(100,config.limits().maxResults()));
        String detail=args.path("detail").asText("full");
        if(!Set.of("full","locations").contains(detail))throw new IllegalArgumentException("detail must be full or locations");
        var nodes=new LinkedHashMap<NodeId,Optional<Node>>(128,.75f,true){
            protected boolean removeEldestEntry(Map.Entry<NodeId,Optional<Node>> entry){return size()>128;}
        };
        java.util.function.Function<NodeId,Node> lookup=id->nodes.computeIfAbsent(id,graph::node).orElse(null);
        long generation=graph.status().generation();
        String scope=ToolSupport.JSON.createArrayNode().add("get_symbol_context").add(ToolSupport.JSON.valueToTree(ids)).add(ToolSupport.JSON.valueToTree(sections)).add(confidence).add(detail).toString();
        var position=cursors.read(args.path("cursor").asText(""),scope,generation);
        var page=new BoundedPage(limit,position.key(),config.limits().maxResponseBytes(),row->{
            if(detail.equals("locations"))row.remove(List.of("name","kind","signature"));
        });
        var work=new Work();boolean[] further={false};
        for(int index=0;index<ids.size();index++) {
            String raw=ids.get(index),prefix=String.format(Locale.ROOT,"%02d/",index);
            Node node;
            try {NodeId id=ToolSupport.target(raw);if(!(id instanceof SymbolId))throw new IllegalArgumentException("A symbol ID is required");node=lookup.apply(id);}
            catch(IllegalArgumentException invalid){add(page,position,prefix+"0",error(raw,"invalid_symbol",invalid.getMessage()));continue;}
            if(node==null){add(page,position,prefix+"0",error(raw,"unknown_symbol","Symbol is absent in this generation"));continue;}
            for(String section:sections) {
                String base=prefix+SECTIONS.indexOf(section)+"/";
                if(section.equals("declaration")) {
                    String key=base+node.id().value();
                    if(key.compareTo(position.key())<=0)continue;
                    ObjectNode row=symbol(node).put("targetId",raw).put("section",section);
                    risk(row,node.id(),work);add(page,position,key,row);continue;
                }
                if(work.exhausted)continue;
                if(section.equals("implementations")&&node.kind()!=NodeKind.TYPE&&node.kind()!=NodeKind.FUNCTION) {
                    add(page,position,base,error(raw,"unsupported_implementation_target","Implementation discovery requires a type or method").put("section",section));continue;
                }
                if(section.equals("callers")||section.equals("callees")) {
                    neighbors(node,section,base,position,page,work,confidence,limit,further,lookup);continue;
                }
                long[] ordinal={0};
                try {graph.scanEdges(node.id(),Direction.IN,section.equals("references")?REFERENCES:node.kind()==NodeKind.FUNCTION?EnumSet.of(EdgeKind.OVERRIDES):EnumSet.of(EdgeKind.EXTENDS,EdgeKind.IMPLEMENTS),edge->{
                    work.visit();long order=ordinal[0]++;
                    if(edge.confidence()<confidence)return;
                    String key=base+String.format(Locale.ROOT,"%012d",order);
                    if(key.compareTo(position.key())<=0)return;
                    Node source=lookup.apply(edge.from());if(source==null)return;
                    ObjectNode row=relationship(source,edge,node.id()).put("section",section);
                    add(page,position,key,row);
                });}catch(WorkLimit exhausted){/* Explicit partial coverage, never a guessed complete answer. */}
            }
        }
        if(graph.status().generation()!=generation)throw new IllegalArgumentException("stale_cursor: graph changed during context acquisition");
        ObjectNode out=ToolSupport.JSON.createObjectNode().put("generation",generation).put("state","complete")
                .put("inventoryComplete",false).put("referenceCompleteness","not_guaranteed")
                .put("coverage","Indexed evidence only; external, dynamic and unresolved relationships may be absent. Callers/callees are distinct relationships; references preserve individual occurrences.")
                .put("edgeVisits",work.visits).put("edgeVisitLimit",WORK_LIMIT).put("workLimitReached",work.exhausted);
        return page.finishSuffix(out,cursors,scope,generation,position,config,further[0]);
    }
    private void neighbors(Node target,String section,String prefix,GenerationCursor.Position position,BoundedPage page,
                           Work work,double confidence,int limit,boolean[] further,java.util.function.Function<NodeId,Node> lookup){
        Direction direction=section.equals("callers")?Direction.IN:Direction.OUT;
        var selected=new TreeMap<String,Neighbor>();
        boolean[] omitted={false};
        try {graph.scanEdges(target.id(),direction,Set.of(EdgeKind.CALLS),edge->{
            work.visit();if(edge.confidence()<confidence)return;
            NodeId id=direction==Direction.IN?edge.from():edge.to();String key=prefix+id.value();
            if(key.compareTo(position.key())<=0)return;
            Neighbor neighbor=selected.get(key);
            if(neighbor!=null){neighbor.count++;neighbor.confidence=Math.min(neighbor.confidence,edge.confidence());return;}
            if(selected.size()>=limit&&key.compareTo(selected.lastKey())>=0){omitted[0]=true;return;}
            Node node=lookup.apply(id);if(node==null)return;
            selected.put(key,new Neighbor(node,edge));
            if(selected.size()>limit){selected.pollLastEntry();omitted[0]=true;}
        });}catch(WorkLimit exhausted){/* Returned occurrence counts are lower bounds. */}
        further[0]|=omitted[0];
        selected.forEach((key,value)->{
            ObjectNode row=symbol(value.node).put("targetId",target.id().value()).put("section",section)
                    .put("relationship","calls").put("confidence",value.confidence).put("occurrences",value.count)
                    .put("countsComplete",!work.exhausted).put("locationPrecision","declaration");
            resolutionEvidence(row,value.first);
            row.put("resolutionEvidenceScope","representative_site; use references for individual occurrence evidence");
            add(page,position,key,row);
        });
    }
    private void risk(ObjectNode row,NodeId target,Work work){
        if(!config.gating().attachRiskReport())return;
        try {
            if(work.exhausted)throw new WorkLimit();
            var scored=new BlastScore(graph,config).computeBounded(target,work::visit);
            if(scored.score()>=config.gating().threshold()) {
                var risk=row.putObject("risk");GetBlastScoreTool.write(risk,scored,config);
                risk.put("note","MANDATORY RISK REVIEW: review factors and dependents before changing this symbol.");
            }
        }catch(WorkLimit exhausted){row.putObject("risk").put("gate","review_required").put("completeness","unavailable_work_limit");}
    }
    private static List<String> strings(JsonNode value,int max,String label){
        if(!value.isArray()||value.isEmpty()||value.size()>max)throw new IllegalArgumentException(label+" requires 1.."+max+" entries");
        var values=new LinkedHashSet<String>();
        for(JsonNode item:value){if(!item.isTextual()||item.asText().isBlank()||item.asText().length()>4096||!values.add(item.asText()))throw new IllegalArgumentException("Invalid or duplicate "+label);}
        return List.copyOf(values);
    }
    private static ObjectNode error(String id,String code,String message){return ToolSupport.JSON.createObjectNode().put("targetId",id).put("section","error").put("code",code).put("message",message);}
    private static void add(BoundedPage page,GenerationCursor.Position position,String key,ObjectNode row){if(key.compareTo(position.key())>0)page.accept(key,row);}
    private static final class Neighbor {final Node node;final Edge first;long count=1;float confidence;Neighbor(Node node,Edge edge){this.node=node;first=edge;confidence=edge.confidence();}}
    private static final class Work {int visits;boolean exhausted;void visit(){if(Thread.currentThread().isInterrupted())throw new IllegalArgumentException("Navigation cancelled");if(visits==WORK_LIMIT){exhausted=true;throw new WorkLimit();}visits++;}}
    private static final class WorkLimit extends RuntimeException {WorkLimit(){super(null,null,false,false);}}
}
