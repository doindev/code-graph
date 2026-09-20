package io.doindev.codegraph.parse;

import io.doindev.codegraph.model.Edge;
import java.util.List;

/** Adapter-owned declaration evidence. Does not expand CALLS into possible runtime dispatch. */
final class MethodRelationships {
    private MethodRelationships(){}
    static List<Edge> resolve(FileFragment fragment,SymbolLookup lookup){
        if(ResolutionLanguages.compatible(fragment.lang(),"js"))return new EcmaMethodRelationships(lookup).resolve(fragment);
        if(NominalMethodEvidence.LANGUAGES.contains(fragment.lang()))return new NominalMethodRelationships(lookup,fragment.lang()).resolve(fragment);
        if(DynamicMethodEvidence.LANGUAGES.contains(fragment.lang()))return new DynamicMethodRelationships(lookup,fragment.lang()).resolve(fragment);
        if(fragment.lang().equals("go"))return new GoMethodRelationships(lookup).resolve(fragment);
        if(fragment.lang().equals("java"))return new JavaMethodRelationships(fragment,lookup).resolve(fragment);
        return List.of();
    }
}
