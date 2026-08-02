package io.doindev.codegraph.lang.rust;

import io.doindev.codegraph.parse.Src;
import io.doindev.codegraph.parse.TreeWalkAnalyzer;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TreeSitterRust;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Rust extraction over tree-sitter-rust. {@code impl} blocks are named by their target type so
 * methods qualify as {@code Type.method}; method arity excludes a leading {@code self} parameter.
 */
public final class RustAnalyzer extends TreeWalkAnalyzer {

    @Override
    public String languageId() {
        return "rs";
    }

    @Override
    public Set<String> fileExtensions() {
        return Set.of("rs");
    }

    @Override
    protected TSLanguage newLanguage() {
        return new TreeSitterRust();
    }

    @Override
    protected Set<String> typeDeclarationTypes() {
        return Set.of("struct_item", "enum_item", "trait_item", "union_item", "mod_item",
                "impl_item");
    }

    @Override
    protected Set<String> functionDeclarationTypes() {
        return Set.of("function_item", "function_signature_item");
    }

    @Override
    protected Set<String> callTypes() {
        return Set.of("call_expression");
    }

    @Override
    protected Set<String> branchTypes() {
        return Set.of("if_expression", "while_expression", "loop_expression", "for_expression",
                "match_arm");
    }

    @Override
    protected String nameOf(TSNode decl, Src src) {
        if (decl.getType().equals("impl_item")) {
            TSNode type = decl.getChildByFieldName("type");
            return type == null || type.isNull() ? null : src.text(type);
        }
        return super.nameOf(decl, src);
    }

    @Override
    protected int arityOf(TSNode decl, Src src) {
        TSNode params = decl.getChildByFieldName("parameters");
        if (params == null || params.isNull()) {
            return 0;
        }
        int count = params.getNamedChildCount();
        if (count > 0 && params.getNamedChild(0).getType().equals("self_parameter")) {
            return count - 1;
        }
        return count;
    }

    @Override
    protected CallSite callOf(TSNode call, Src src) {
        TSNode function = call.getChildByFieldName("function");
        if (function == null || function.isNull()) {
            return null;
        }
        TSNode args = call.getChildByFieldName("arguments");
        int arity = args == null || args.isNull() ? 0 : args.getNamedChildCount();
        String type = function.getType();
        if (type.equals("field_expression")) {
            TSNode field = function.getChildByFieldName("field");
            if (field == null || field.isNull()) {
                return null;
            }
            TSNode value = function.getChildByFieldName("value");
            String receiverHint = value == null || value.isNull() ? null : src.text(value);
            return new CallSite(src.text(field), receiverHint, arity);
        }
        if (type.equals("scoped_identifier")) {
            TSNode name = function.getChildByFieldName("name");
            if (name == null || name.isNull()) {
                return null;
            }
            TSNode path = function.getChildByFieldName("path");
            String receiverHint = path == null || path.isNull() ? null : src.text(path);
            return new CallSite(src.text(name), receiverHint, arity);
        }
        if (type.equals("identifier")) {
            return new CallSite(src.text(function), null, arity);
        }
        return null;
    }

    @Override
    protected List<String> importsOf(TSNode root, Src src) {
        List<String> imports = new ArrayList<>();
        collectUses(root, src, imports);
        return imports;
    }

    private static void collectUses(TSNode node, Src src, List<String> into) {
        if (node.getType().equals("use_declaration")) {
            TSNode argument = node.getChildByFieldName("argument");
            if (argument != null && !argument.isNull()) {
                into.add(src.text(argument));
            }
            return;
        }
        // uses can appear nested (inside modules and functions)
        int count = node.getNamedChildCount();
        for (int i = 0; i < count; i++) {
            collectUses(node.getNamedChild(i), src, into);
        }
    }
}
