package io.doindev.codegraph.parse;

import org.treesitter.TSNode;
import java.util.*;

/** Stores signatures and whole-interface requirements without changing existing Go symbol IDs. */
final class GoMethodEvidence {
    static Map<String,String> extract(TSNode node,Src src,String owner,String pkg,boolean method){
        var out=new HashMap<String,String>();out.put("go.package",pkg);out.put("go.owner",owner);
        if(!method){
            TSNode type=field(node,"type");
            if(type!=null&&type.getType().equals("interface_type")){
                var names=new ArrayList<String>();boolean known=field(node,"type_parameters")==null;
                for(int i=0;i<type.getNamedChildCount();i++){
                    TSNode item=type.getNamedChild(i);if(item.getType().equals("comment"))continue;
                    TSNode name=field(item,"name");
                    if(!item.getType().equals("method_elem")||name==null)known=false;else names.add(src.text(name));
                }
                out.put("go.requirements",known&&names.size()<=64?String.join("\t",names):"?");
            }
            return Map.copyOf(out);
        }
        out.put("go.signature",parameters(field(node,"parameters"),src)+"\n"+result(field(node,"result"),src));
        out.put("go.contract",Boolean.toString(node.getType().equals("method_elem")));
        TSNode receiver=field(node,"receiver");
        if(receiver!=null&&receiver.getNamedChildCount()==1){
            TSNode type=field(receiver.getNamedChild(0),"type");out.put("go.receiver",type==null?"?":src.text(type));
        }
        return Map.copyOf(out);
    }
    private static String result(TSNode node,Src src){if(node==null)return "";return node.getType().equals("parameter_list")?parameters(node,src):src.text(node);}
    private static String parameters(TSNode node,Src src){
        if(node==null)return "";var types=new ArrayList<String>();
        for(int i=0;i<node.getNamedChildCount();i++){
            TSNode p=node.getNamedChild(i);if(p.getType().equals("comment"))continue;
            TSNode type=field(p,"type");if(type==null||type.getEndByte()-type.getStartByte()>512)return "?";
            int names=0;for(int j=0;j<p.getNamedChildCount();j++)if(p.getNamedChild(j).getType().equals("identifier"))names++;
            for(int j=0;j<Math.max(1,names);j++)types.add((p.getType().startsWith("variadic")?"...":"")+src.text(type));
            if(types.size()>64)return "?";
        }
        return String.join("\t",types);
    }
    private static TSNode field(TSNode node,String key){TSNode value=node.getChildByFieldName(key);return value==null||value.isNull()?null:value;}
}
