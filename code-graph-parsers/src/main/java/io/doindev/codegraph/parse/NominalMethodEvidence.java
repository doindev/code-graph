package io.doindev.codegraph.parse;

import org.treesitter.TSNode;
import java.util.*;

/** Bounded grammar-derived evidence for explicitly nominal method relationships. */
final class NominalMethodEvidence {
    static final Set<String> LANGUAGES=Set.of("kt","scala","cs","cpp","swift","dart","objc","rs","php");
    private NominalMethodEvidence(){}
    static Map<String,String> extract(String language,TSNode decl,Src src,String owner,String pkg,
                                    boolean method,List<TreeWalkAnalyzer.SuperRef> parents,List<String> imports){
        if(!LANGUAGES.contains(language))return Map.of();
        var attrs=new HashMap<String,String>();Set<String> modifiers=new HashSet<>();
        modifiers(decl,src,modifiers,0);
        if(language.equals("dart")&&present(decl.getParent()))modifiers(decl.getParent(),src,modifiers,0);
        attrs.put("nominal.owner",owner);attrs.put("nominal.package",pkg);
        attrs.put("nominal.imports",imports.size()<=64?String.join("\n",imports):"?");
        attrs.put("nominal.syntax",decl.getType());
        attrs.put("nominal.interface",""+((!language.equals("objc")&&decl.getType().contains("interface"))||decl.getType().contains("trait")||decl.getType().contains("protocol")||modifiers.contains("interface")));
        attrs.put("nominal.modifiers",String.join(" ",new TreeSet<>(modifiers)));
        attrs.put("nominal.generic",""+genericHeader(decl,0));
        if(!method){
            var names=new ArrayList<>(parents.stream().map(TreeWalkAnalyzer.SuperRef::name).toList());
            if(language.equals("php")){
                TSNode body=field(decl,"body");
                if(body!=null)for(int i=0;i<body.getNamedChildCount();i++){
                    TSNode use=body.getNamedChild(i);if(!use.getType().equals("use_declaration"))continue;
                    for(int j=0;j<use.getNamedChildCount();j++){
                        TSNode part=use.getNamedChild(j);
                        if(Set.of("name","qualified_name").contains(part.getType()))names.add(src.text(part));
                        else {names.clear();names.add("?");break;}
                    }
                }
            }
            attrs.put("nominal.parents",names.size()<=32?String.join("\t",names):"?");
            return Map.copyOf(attrs);
        }
        attrs.put("nominal.method","true");
        if(language.equals("rs")){
            TSNode container=decl.getParent();for(int i=0;i<6&&present(container);i++,container=container.getParent()){
                if(container.getType().equals("impl_item")){
                    TSNode trait=field(container,"trait");
                    attrs.put("nominal.explicitParents",trait==null?"":src.text(trait));
                    if(genericHeader(container,0))attrs.put("nominal.generic","true");
                    break;
                }
                if(container.getType().equals("trait_item"))break;
            }
        }
        TSNode callable=decl;
        if(language.equals("cpp"))for(int depth=0;depth<8;depth++){
            TSNode next=field(callable,"declarator");if(next==null)break;callable=next;
            if(callable.getType().equals("function_declarator"))break;
        }
        TSNode parameters=field(callable,"parameters");
        if(parameters==null)parameters=child(decl,Set.of("function_value_parameters","parameters","parameter_list","formal_parameter_list","formal_parameters"));
        var params=new ArrayList<String>();var labels=new ArrayList<String>();String receiver="";
        List<TSNode> parameterNodes=new ArrayList<>();
        if(parameters!=null)for(int i=0;i<parameters.getNamedChildCount();i++)parameterNodes.add(parameters.getNamedChild(i));
        if(language.equals("swift"))for(int i=0;i<decl.getNamedChildCount();i++)if(decl.getNamedChild(i).getType().equals("parameter"))parameterNodes.add(decl.getNamedChild(i));
        if(language.equals("objc")){
            TSNode selector=field(decl,"selector");
            if(selector!=null)for(int i=0;i<selector.getNamedChildCount();i++)if(selector.getNamedChild(i).getType().equals("keyword_declarator"))parameterNodes.add(selector.getNamedChild(i));
            TSNode scope=field(decl,"scope");receiver=scope==null?"?":src.text(scope);
        }
        for(TSNode p:parameterNodes){
            if(p.getType().equals("comment"))continue;
            if(language.equals("rs")&&p.getType().equals("self_parameter")){receiver=src.text(p).replaceAll("\\s+","");continue;}
            if(language.equals("swift")){TSNode label=field(p,"external_name");if(label==null)label=field(p,"name");labels.add(label==null?"?":src.text(label));}
            String type=typeOf(p,src);Set<String> mode=new TreeSet<>();modifiers(p,src,mode,0);
            if(mode.stream().anyMatch(Set.of("ref","out","in","params","vararg","this")::contains))type="?";
            TSNode declarator=field(p,"declarator");
            if(declarator!=null&&!Set.of("identifier","field_identifier").contains(declarator.getType()))type="?";
            params.add(type);
        }
        attrs.put("nominal.params",params.size()<=64?String.join("\t",params):"?");
        String result=typeOf(decl,src);
        if(result.equals("?")&&language.equals("kt")){TSNode body=field(decl,"body");if(body==null)body=child(decl,Set.of("function_body"));if(body==null||!src.text(body).stripLeading().startsWith("="))result="Unit";}
        if(result.equals("?")&&language.equals("swift")&&!src.text(decl).split("\\{",2)[0].contains("->"))result="Void";
        if(result.equals("?")&&language.equals("rs"))result="()";
        attrs.put("nominal.return",result);
        attrs.put("nominal.qualifiers",language.equals("cpp")?qualifiers(callable,src):language.equals("swift")?String.join("\t",labels):receiver);
        return Map.copyOf(attrs);
    }
    private static String qualifiers(TSNode node,Src src){
        var result=new ArrayList<String>();for(int i=0;i<node.getNamedChildCount();i++){
            TSNode c=node.getNamedChild(i);if(Set.of("type_qualifier","ref_qualifier").contains(c.getType()))result.add(src.text(c));
        }return String.join(" ",result);
    }
    private static String typeOf(TSNode node,Src src){
        TSNode type=field(node,"type");if(type!=null&&!type.isNamed())type=null;if(type==null)type=field(node,"return_type");
        if(type==null)type=child(node,Set.of("user_type","nullable_type","predefined_type","primitive_type","integral_type","void_type","type_identifier"));
        if(type==null||type.getEndByte()-type.getStartByte()>512)return "?";
        return src.text(type).trim();
    }
    private static boolean genericHeader(TSNode node,int depth){
        if(depth>4)return false;
        if(Set.of("type_parameters","type_parameter_list","template_parameter_list","generic_parameters","type_arguments","type_argument_list").contains(node.getType()))return true;
        if(body(node.getType())||node.getType().contains("parameter"))return false;
        for(int i=0;i<node.getNamedChildCount();i++)if(genericHeader(node.getNamedChild(i),depth+1))return true;
        TSNode parent=node.getParent();return depth==0&&present(parent)&&parent.getType().equals("template_declaration");
    }
    private static void modifiers(TSNode node,Src src,Set<String> into,int depth){
        if(depth>4||body(node.getType()))return;
        for(int i=0;i<node.getChildCount();i++){
            TSNode c=node.getChild(i);String kind=c.getType();
            if(c.getNamedChildCount()==0&&c.getEndByte()-c.getStartByte()<32){
                String text=src.text(c);if(Set.of("private","protected","public","internal","static","class","mutating","nonmutating","final","sealed","abstract","virtual","override","open","new","interface","ref","out","in","params","vararg","this").contains(text))into.add(text);
            }else if(kind.contains("modifier")||kind.equals("storage_class_specifier"))modifiers(c,src,into,depth+1);
        }
    }
    private static boolean body(String kind){return Set.of("class_body","declaration_list","template_body","function_body","block","compound_statement","field_declaration_list","statement_block").contains(kind);}
    private static TSNode child(TSNode node,Set<String> kinds){for(int i=0;i<node.getNamedChildCount();i++){TSNode c=node.getNamedChild(i);if(kinds.contains(c.getType()))return c;}return null;}
    private static TSNode field(TSNode node,String name){TSNode result=node.getChildByFieldName(name);return present(result)?result:null;}
    private static boolean present(TSNode node){return node!=null&&!node.isNull();}
}
