package io.doindev.codegraph.lang.java;

import io.doindev.codegraph.parse.*;
import org.treesitter.TSNode;
import java.util.*;

/** Lexical, bounded Java evidence extracted once per file. Does not retain tree-sitter nodes. */
final class JavaSemanticHints implements SemanticHints {
    private static final Set<String> TYPES = Set.of("class_declaration", "interface_declaration",
            "enum_declaration", "record_declaration", "annotation_type_declaration");
    private static final Set<String> METHODS = Set.of("method_declaration", "constructor_declaration");
    private final Map<Integer,Map<String,String>> declarations = new HashMap<>();
    private final Map<Long,CallContext> calls = new HashMap<>();
    private final Src src;
    private final String packageName;
    private final List<String> imports;

    JavaSemanticHints(TSNode root, Src src, String packageName, List<String> imports) {
        this.src = src; this.packageName = packageName; this.imports = imports;
        visit(root, new Scope(null, ""));
    }
    @Override public Map<String,String> declaration(TSNode node) {
        return declarations.getOrDefault(node.getStartByte(), Map.of());
    }
    @Override public CallContext call(TSNode node) { return calls.get(callKey(node)); }
    private static long callKey(TSNode node) {return ((long)node.getStartByte()<<32)|(node.getEndByte()&0xffffffffL);}

    private static final class Scope {
        final Scope parent;
        final String owner;
        final Map<String,ValueHint> variables = new HashMap<>();
        final Map<String,String> typeVariables = new HashMap<>();
        Scope(Scope parent, String owner) { this.parent = parent; this.owner = owner; }
        ValueHint lookup(String name) {
            for (Scope s=this; s!=null; s=s.parent) if(s.variables.containsKey(name)) return s.variables.get(name);
            return null;
        }
    }
    private Map<String,String> attrs(Scope scope) {
        var a = new HashMap<String,String>();
        a.put("java.owner", scope.owner); a.put("java.package", packageName);
        a.put("java.imports", String.join("\n", imports));
        var variables=new TreeMap<String,String>();
        for(Scope s=scope;s!=null;s=s.parent)s.typeVariables.forEach(variables::putIfAbsent);
        a.put("java.typevars",String.join("\t",variables.entrySet().stream().map(e->e.getKey()+"="+e.getValue()).toList()));
        return a;
    }
    private void visit(TSNode node, Scope scope) {
        String kind = node.getType();
        if (TYPES.contains(kind)) {
            String name=text(field(node,"name"));
            String owner=scope.owner.isEmpty() ? qualify(packageName,name) : qualify(scope.owner,name);
            var a=attrs(scope);
            var parents=new ArrayList<String>();
            for(TSNode child:children(node)) {
                if(Set.of("superclass","super_interfaces","extends_interfaces").contains(child.getType()))
                    collectParents(child,parents);
            }
            a.put("java.parents",String.join("\t",parents));
            a.put("java.interface",String.valueOf(kind.equals("interface_declaration")));
            a.put("java.final",String.valueOf(modifier(node,"final")||kind.equals("record_declaration")||kind.equals("enum_declaration")));
            declarations.put(node.getStartByte(),Map.copyOf(a));
            scope=new Scope(scope,owner);
            typeParameters(node,scope);
            // Fields are visible throughout the type, including before their textual declaration.
            TSNode body=field(node,"body");
            for(TSNode child:children(body)) if(child.getType().equals("field_declaration")) variables(child,scope);
            for(TSNode parameter:children(field(node,"parameters"))) parameter(parameter,scope);
        } else if (METHODS.contains(kind)) {
            scope=new Scope(scope,scope.owner);
            typeParameters(node,scope);
            var a=attrs(scope); var types=new ArrayList<String>();
            for(TSNode p:children(field(node,"parameters"))) types.add(parameterType(p));
            a.put("java.params",String.join("\t",types));
            a.put("java.return",text(field(node,"type")));
            a.put("java.constructor",String.valueOf(kind.equals("constructor_declaration")));
            a.put("java.static",String.valueOf(modifier(node,"static")));
            a.put("java.private",String.valueOf(modifier(node,"private")));
            a.put("java.final",String.valueOf(modifier(node,"final")));
            a.put("java.varargs",String.valueOf(children(field(node,"parameters")).stream().anyMatch(p->p.getType().equals("spread_parameter"))));
            declarations.put(node.getStartByte(),Map.copyOf(a));
            for(TSNode p:children(field(node,"parameters"))) parameter(p,scope);
        } else if(Set.of("block","for_statement","enhanced_for_statement","catch_clause","lambda_expression","try_with_resources_statement").contains(kind)) {
            scope=new Scope(scope,scope.owner);
            if(kind.equals("enhanced_for_statement")) {
                String name=text(field(node,"name"));
                scope.variables.put(name,ValueHint.of("type",scopedType(text(field(node,"type")),scope)));
            }
            if(kind.equals("lambda_expression")) {
                TSNode p=field(node,"parameters");
                if(p!=null&&p.getType().equals("identifier")) scope.variables.put(text(p),ValueHint.unknown());
                for(TSNode c:children(p)) {
                    if(c.getType().equals("identifier")) scope.variables.put(text(c),ValueHint.unknown());
                    else parameter(c,scope);
                }
            }
        }
        if(kind.equals("field_declaration")) {
            var a=attrs(scope); a.put("java.return",text(field(node,"type")));
            a.put("java.static",String.valueOf(modifier(node,"static")));
            declarations.put(node.getStartByte(),Map.copyOf(a));
        }
        if(Set.of("local_variable_declaration","resource").contains(kind)) variables(node,scope);
        if(Set.of("formal_parameter","catch_formal_parameter","spread_parameter").contains(kind)) parameter(node,scope);
        if(kind.equals("method_invocation")||kind.equals("object_creation_expression")) {
            ValueHint value=expression(node,scope,0);
            calls.put(callKey(node), new CallContext(scope.owner,
                    kind.equals("object_creation_expression")?ValueHint.of("type",text(field(node,"type"))):value.receiver(),
                    value.arguments(),kind.equals("object_creation_expression")));
        }
        for(TSNode child:children(node)) visit(child,scope);
    }
    private void collectParents(TSNode node,List<String> into) {
        if(Set.of("type_identifier","scoped_type_identifier","generic_type").contains(node.getType())) {
            into.add(text(node)); return;
        }
        for(TSNode c:children(node)) collectParents(c,into);
    }
    private void variables(TSNode declaration,Scope scope) {
        String type=text(field(declaration,"type"));
        for(TSNode variable:children(declaration)) if(variable.getType().equals("variable_declarator")) {
            String name=text(field(variable,"name"));
            String dimensions=text(field(variable,"dimensions"));
            ValueHint hint=type.equals("var")?expression(field(variable,"value"),scope,0):ValueHint.of("type",scopedType(type+dimensions,scope));
            scope.variables.put(name,hint);
        }
        if(declaration.getType().equals("resource"))
            scope.variables.put(text(field(declaration,"name")),ValueHint.of("type",scopedType(type,scope)));
    }
    private void parameter(TSNode node,Scope scope) {
        String name=text(field(node,"name"));
        if(name.isEmpty()) for(TSNode c:children(node)) {
            if(c.getType().equals("variable_declarator")) name=text(field(c,"name"));
        }
        if(!name.isEmpty()) scope.variables.put(name,ValueHint.of("type",scopedType(parameterType(node),scope)));
    }
    private void typeParameters(TSNode node,Scope scope) {
        for(TSNode parameter:children(field(node,"type_parameters"))) {
            String name=text(field(parameter,"name"));String bound="?";
            for(TSNode child:children(parameter)) {
                if(name.isEmpty()&&child.getType().equals("type_identifier"))name=text(child);
                if(child.getType().equals("type_bound")&&children(child).size()==1)bound=text(children(child).getFirst());
            }
            if(!name.isEmpty())scope.typeVariables.put(name,bound);
        }
    }
    private String scopedType(String type,Scope scope) {
        String raw=type.replace("[]","").trim();
        for(Scope s=scope;s!=null;s=s.parent)if(s.typeVariables.containsKey(raw))
            return s.typeVariables.get(raw)+(type.endsWith("[]")?"[]":"");
        return type;
    }
    private String parameterType(TSNode node) {
        String type=text(field(node,"type"));
        if(type.isEmpty()) for(TSNode c:children(node))
            if(c.getType().contains("type")) { type=text(c); break; }
        return type+text(field(node,"dimensions"))+(node.getType().equals("spread_parameter")?"[]":"");
    }
    private ValueHint expression(TSNode node,Scope scope,int depth) {
        if(node==null||depth>8) return ValueHint.unknown();
        String kind=node.getType();
        return switch(kind) {
            case "identifier" -> {
                ValueHint local=scope.lookup(text(node));
                yield local!=null?local:ValueHint.of("name",text(node));
            }
            case "this" -> ValueHint.of("type",scope.owner);
            case "super" -> ValueHint.of("super",scope.owner);
            case "string_literal" -> ValueHint.of("type","java.lang.String");
            case "character_literal" -> ValueHint.of("type","char");
            case "true", "false" -> ValueHint.of("type","boolean");
            case "null_literal" -> ValueHint.of("null","");
            case "decimal_integer_literal","hex_integer_literal","octal_integer_literal","binary_integer_literal" ->
                ValueHint.of("type",text(node).matches("(?s).*[lL]$")?"long":"int");
            case "decimal_floating_point_literal","hex_floating_point_literal" ->
                ValueHint.of("type",text(node).matches("(?s).*[fF]$")?"float":"double");
            case "cast_expression" -> ValueHint.of("type",scopedType(text(field(node,"type")),scope));
            case "parenthesized_expression","unary_expression" -> expression(children(node).isEmpty()?null:children(node).getLast(),scope,depth+1);
            case "object_creation_expression" -> new ValueHint("new",text(field(node,"type")),null,arguments(node,scope,depth));
            case "method_invocation" -> new ValueHint("call",text(field(node,"name")),
                    field(node,"object")==null?ValueHint.of("implicit",scope.owner):expression(field(node,"object"),scope,depth+1),
                    arguments(node,scope,depth));
            case "field_access" -> new ValueHint("field",text(field(node,"field")),expression(field(node,"object"),scope,depth+1),List.of());
            case "array_creation_expression" -> ValueHint.of("type",text(field(node,"type"))+"[]");
            case "array_access" -> new ValueHint("element","",expression(field(node,"array"),scope,depth+1),List.of());
            default -> ValueHint.unknown();
        };
    }
    private List<ValueHint> arguments(TSNode node,Scope scope,int depth) {
        var args=children(field(node,"arguments"));
        if(args.size()>64) return Collections.nCopies(args.size(),ValueHint.unknown());
        return args.stream().map(n->expression(n,scope,depth+1)).toList();
    }
    private boolean modifier(TSNode node,String value) {
        for(TSNode c:children(node)) if(c.getType().equals("modifiers"))
            return Arrays.asList(text(c).split("\\s+")).contains(value);
        return false;
    }
    private static String qualify(String prefix,String name) { return prefix.isEmpty()?name:prefix+"."+name; }
    private String text(TSNode node) { return node==null?"":src.text(node); }
    private static TSNode field(TSNode node,String name) {
        if(node==null)return null; TSNode out=node.getChildByFieldName(name);return out==null||out.isNull()?null:out;
    }
    private static List<TSNode> children(TSNode node) {
        if(node==null||node.isNull())return List.of();
        var out=new ArrayList<TSNode>(node.getNamedChildCount());
        for(int i=0;i<node.getNamedChildCount();i++)out.add(node.getNamedChild(i));
        return out;
    }
}
