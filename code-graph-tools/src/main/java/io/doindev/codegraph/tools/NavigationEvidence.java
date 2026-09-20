package io.doindev.codegraph.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.doindev.codegraph.model.*;
import java.util.*;

/** Transport-neutral formatting shared by single-target and bundled navigation. */
final class NavigationEvidence {
    private NavigationEvidence() {}
    static void resolutionEvidence(ObjectNode row,Edge edge) {
        var evidence=row.putObject("resolutionEvidence");
        for(String key:List.of("resolution","resolutionStatus","dispatch","candidateCount","omittedCandidates","moduleSpecifier","modulePath","exportedName","localAlias","importKind","implementationKind","declaringType","baseType"))
            if(edge.attrs().containsKey(key))evidence.put(key,edge.attrs().get(key));
        if(!evidence.has("resolutionStatus"))evidence.put("resolutionStatus","heuristic");
    }
    static ObjectNode symbol(Node node){
        var row=ToolSupport.JSON.createObjectNode().put("id",node.id().value()).put("name",node.name()).put("kind",node.kind().name().toLowerCase(Locale.ROOT)).put("signature",node.displaySignature());
        if(node.relPath()!=null)row.put("path",node.relPath());
        if(node.span()!=null){var span=node.span();row.putObject("declarationSpan").put("startLine",span.startLine()).put("startColumn",span.startCol()).put("endLine",span.endLine()).put("endColumn",span.endCol());}
        return row;
    }
    static ObjectNode relationship(Node source,Edge edge,NodeId target){
        ObjectNode row=symbol(source).put("targetId",target.value())
                .put("relationship",edge.kind().name().toLowerCase(Locale.ROOT)).put("confidence",edge.confidence())
                .put("locationPrecision",edge.kind()==EdgeKind.OVERRIDES?"declaration":"containing_symbol");
        resolutionEvidence(row,edge);
        var attrs=edge.attrs();String line=attrs.getOrDefault("referenceStartLine",attrs.getOrDefault("callSiteLine",attrs.get("site")));
        if(line!=null&&source.relPath()!=null)try {
            int at=Integer.parseInt(line);if(at>0){
                row.put("locationPrecision","line_only");var occurrence=row.putObject("occurrence").put("path",attrs.getOrDefault("referencePath",source.relPath())).put("line",at);
                if(attrs.containsKey("referencePrecision")){
                    row.put("locationPrecision",attrs.get("referencePrecision"));var span=occurrence.putObject("span");
                    for(String part:List.of("StartLine","StartColumn","EndLine","EndColumn"))span.put(Character.toLowerCase(part.charAt(0))+part.substring(1),Integer.parseInt(attrs.get("reference"+part)));
                    occurrence.put("rangeConvention","1-based UTF-16; inclusive end");
                }
            }
        }catch(NumberFormatException ignored){}
        return row;
    }
}
