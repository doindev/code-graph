package io.doindev.codegraph.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.doindev.codegraph.query.GraphQuery;

/** Optional compare-before-read, performed inside the same published read scope as execution. */
final class GenerationGuard implements GraphTool {
    private final GraphTool delegate;
    private final GraphQuery graph;
    private final String instance;
    GenerationGuard(GraphTool delegate,GraphQuery graph,String instance){
        this.delegate=delegate;this.graph=graph;this.instance=instance;
    }
    public ToolSpec spec(){
        ToolSpec source=delegate.spec();
        try {
            ObjectNode schema=(ObjectNode)ToolSupport.JSON.readTree(source.inputSchemaJson());
            var props=schema.withObject("properties");
            props.putObject("expectedIndexInstance").put("type","string").put("format","uuid")
                    .put("description","Index instance returned by index_status; protects against removal/re-onboarding.");
            props.putObject("expectedGeneration").put("type","integer").put("minimum",0)
                    .put("description","Require this exact published generation. Requires expectedIndexInstance; mismatches never execute.");
            schema.withObject("dependentRequired").putArray("expectedGeneration").add("expectedIndexInstance");
            return new ToolSpec(source.name(),source.description(),schema.toString());
        }catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException(e);}
    }
    public ToolResponse call(JsonNode args){
        try {
            return graph.read(()->{
                if(args.has("expectedIndexInstance")&&(!args.path("expectedIndexInstance").isTextual()||
                        !instance.equals(args.path("expectedIndexInstance").asText())))
                    return ToolResponse.fail("stale_index_instance: obtain index_status for the current project instance");
                if(args.has("expectedGeneration")){
                    var expected=args.path("expectedGeneration");
                    if(!expected.isIntegralNumber()||!expected.canConvertToLong()||expected.asLong()<0||!args.has("expectedIndexInstance"))
                        return ToolResponse.fail("expectedGeneration requires a non-negative integer and expectedIndexInstance");
                    if(graph.status().generation()!=expected.asLong())
                        return ToolResponse.fail("stale_generation: index changed; obtain fresh evidence before retrying");
                }
                return delegate.call(args);
            });
        }catch(RuntimeException e){return ToolResponse.fail(e.getMessage());}
    }
}

