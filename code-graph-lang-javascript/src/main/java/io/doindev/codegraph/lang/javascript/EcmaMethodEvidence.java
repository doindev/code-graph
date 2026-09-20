package io.doindev.codegraph.lang.javascript;

import io.doindev.codegraph.parse.Src;
import org.treesitter.TSNode;
import java.util.*;

/** Short-lived structural method evidence. No source bodies are retained in the symbol lookup. */
final class EcmaMethodEvidence {
    private static final Set<String> TYPES=Set.of("class_declaration","abstract_class_declaration","interface_declaration");
    private static final Set<String> METHODS=Set.of("method_definition","method_signature","abstract_method_signature");
    private EcmaMethodEvidence(){}
    static Map<String,String> declaration(TSNode node,Src src){
        boolean type=TYPES.contains(node.getType()),method=METHODS.contains(node.getType());
        if(!type&&!method)return Map.of();
        var attrs=new HashMap<String,String>();var owners=new ArrayDeque<String>();
        for(TSNode p=node.getParent();present(p);p=p.getParent())if(TYPES.contains(p.getType()))owners.addFirst(text(p,"name",src));
        attrs.put("ecma.owner",String.join(".",owners));
        var span=src.span(node);attrs.put("ecma.line",""+span.startLine());attrs.put("ecma.column",""+span.startCol());
        attrs.put("ecma.generic",""+present(node.getChildByFieldName("type_parameters")));
        if(type){
            attrs.put("ecma.interface",""+node.getType().equals("interface_declaration"));
            List<String> parents=new ArrayList<>();for(int i=0;i<node.getNamedChildCount();i++){
                TSNode c=node.getNamedChild(i);
                if(Set.of("class_heritage","extends_type_clause","extends_clause","implements_clause").contains(c.getType()))parents(c,src,parents);
            }
            attrs.put("ecma.parents",parents.size()<=32?String.join("\t",parents):"?");
        }else{
            attrs.put("ecma.method","true");attrs.put("ecma.return",type(text(node,"return_type",src)));
            Set<String> modifiers=new HashSet<>();for(int i=0;i<node.getChildCount();i++){
                TSNode c=node.getChild(i);if(!Set.of("statement_block","formal_parameters","type_annotation").contains(c.getType()))modifiers.add(src.text(c));
            }
            String name=text(node,"name",src);
            attrs.put("ecma.excluded",""+(name.equals("constructor")||name.startsWith("#")||modifiers.contains("static")||modifiers.contains("private")||modifiers.contains("get")||modifiers.contains("set")));
            var params=new ArrayList<String>();TSNode parameters=node.getChildByFieldName("parameters");
            if(present(parameters))for(int i=0;i<parameters.getNamedChildCount();i++){
                TSNode parameter=parameters.getNamedChild(i);
                String value=type(text(parameter,"type",src));
                // Optional/rest/destructured signatures need variance/overload analysis, not name matching.
                String pattern=text(parameter,"pattern",src);
                if(!parameter.getType().equals("required_parameter")||!pattern.matches("[A-Za-z_$][A-Za-z0-9_$]*"))value="?";
                params.add(value.isBlank()?"?":value);
            }
            attrs.put("ecma.params",params.size()<=64?String.join("\t",params):"?");
        }
        return Map.copyOf(attrs);
    }
    private static void parents(TSNode node,Src src,List<String> into){
        if(Set.of("identifier","type_identifier","nested_type_identifier","member_expression","generic_type").contains(node.getType())){into.add(src.text(node));return;}
        if(!Set.of("class_heritage","extends_type_clause","extends_clause","implements_clause").contains(node.getType())){into.add("?");return;}
        for(int i=0;i<node.getNamedChildCount();i++)parents(node.getNamedChild(i),src,into);
    }
    private static String type(String text){return text.replaceFirst("^:\\s*","").trim();}
    private static boolean present(TSNode node){return node!=null&&!node.isNull();}
    private static String text(TSNode node,String field,Src src){TSNode value=node.getChildByFieldName(field);return present(value)?src.text(value):"";}
}
