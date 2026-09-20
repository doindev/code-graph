package io.doindev.codegraph.parse;

import org.treesitter.TSNode;
import java.util.*;

/** Lexical, literal ancestry only. No evaluation of decorators, imports or metaprogramming. */
final class DynamicMethodEvidence {
    static final Set<String> LANGUAGES=Set.of("py","rb");
    static Map<String,String> extract(String language,TSNode declaration,Src src,String owner,boolean method){
        var out=new HashMap<String,String>();out.put("dynamic.owner",owner);out.put("dynamic.syntax",declaration.getType());
        boolean uncertain=present(declaration.getParent())&&declaration.getParent().getType().equals("decorated_definition");
        if(method){
            uncertain|=declaration.getType().equals("singleton_method");
            out.put("dynamic.method","true");
        }else{
            var parents=new ArrayList<String>();
            if(language.equals("py")){
                TSNode bases=field(declaration,"superclasses");
                if(bases!=null)for(int i=0;i<bases.getNamedChildCount();i++){
                    TSNode base=bases.getNamedChild(i);String text=src.text(base);
                    if(!base.getType().equals("identifier"))uncertain=true;
                    else if(!text.equals("object"))parents.add(text);
                }
            }else{
                TSNode body=field(declaration,"body");
                if(body!=null)for(int i=0;i<body.getNamedChildCount();i++){
                    TSNode item=body.getNamedChild(i);
                    if(!item.getType().equals("call"))continue;
                    TSNode name=field(item,"method"),args=field(item,"arguments");String operation=name==null?"":src.text(name);
                    if(operation.equals("include")&&field(item,"receiver")==null&&args!=null){
                        var group=new ArrayList<String>();
                        for(int j=0;j<args.getNamedChildCount();j++){
                            TSNode arg=args.getNamedChild(j);if(!arg.getType().equals("constant"))uncertain=true;else group.add(src.text(arg));
                        }
                        parents.addAll(0,group); // Later include calls take precedence; argument order is retained.
                    }else if(!Set.of("public","protected","private").contains(operation))uncertain=true;
                }
                TSNode base=field(declaration,"superclass");
                if(base!=null){
                    if(base.getNamedChildCount()!=1||!base.getNamedChild(0).getType().equals("constant"))uncertain=true;
                    else parents.add(src.text(base.getNamedChild(0)));
                }
            }
            out.put("dynamic.parents",parents.size()>32?"?":String.join("\t",parents));
        }
        out.put("dynamic.uncertain",Boolean.toString(uncertain));return Map.copyOf(out);
    }
    private static TSNode field(TSNode node,String field){TSNode value=node.getChildByFieldName(field);return present(value)?value:null;}
    private static boolean present(TSNode node){return node!=null&&!node.isNull();}
}
