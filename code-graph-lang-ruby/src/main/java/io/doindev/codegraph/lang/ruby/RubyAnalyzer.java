package io.doindev.codegraph.lang.ruby;

import io.doindev.codegraph.parse.RefKind;
import io.doindev.codegraph.parse.Src;
import io.doindev.codegraph.parse.TreeWalkAnalyzer;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TreeSitterRuby;

import java.util.List;
import java.util.Set;

/**
 * Ruby extraction over tree-sitter-ruby. Nested {@code module}/{@code class} scopes join with
 * {@code '.'} in qualified names (so {@code Billing::Invoice} declared by nesting becomes
 * {@code Billing.Invoice}); a class declared with an inline scoped name keeps its literal
 * {@code ::} text.
 */
public final class RubyAnalyzer extends TreeWalkAnalyzer {

    @Override
    public String languageId() {
        return "rb";
    }

    @Override
    public Set<String> fileExtensions() {
        return Set.of("rb");
    }

    @Override
    protected TSLanguage newLanguage() {
        return new TreeSitterRuby();
    }

    @Override
    protected Set<String> typeDeclarationTypes() {
        return Set.of("class", "module");
    }

    @Override
    protected Set<String> functionDeclarationTypes() {
        return Set.of("method", "singleton_method");
    }

    @Override
    protected Set<String> callTypes() {
        return Set.of("call");
    }

    @Override
    protected Set<String> branchTypes() {
        return Set.of("if", "elsif", "unless", "while", "until", "for", "when", "rescue",
                "conditional");
    }

    @Override
    protected CallSite callOf(TSNode call, Src src) {
        TSNode method = call.getChildByFieldName("method");
        if (method == null || method.isNull()) {
            return null;
        }
        TSNode receiver = call.getChildByFieldName("receiver");
        String receiverHint = receiver == null || receiver.isNull() ? null : src.text(receiver);
        TSNode args = call.getChildByFieldName("arguments");
        int arity = args == null || args.isNull() ? 0 : args.getNamedChildCount();
        return new CallSite(src.text(method), receiverHint, arity);
    }

    /** Ruby has no statically meaningful imports: {@code require} takes runtime strings. */
    @Override
    protected List<String> importsOf(TSNode root, Src src) {
        return List.of();
    }

    @Override
    protected List<SuperRef> supertypesOf(TSNode typeDecl, Src src) {
        TSNode superclass = typeDecl.getChildByFieldName("superclass");
        if (superclass == null || superclass.isNull()) {
            return List.of();
        }
        // the superclass node spans "< Const"; drill to the constant itself
        int count = superclass.getNamedChildCount();
        for (int i = 0; i < count; i++) {
            TSNode child = superclass.getNamedChild(i);
            if (child.getType().equals("constant")) {
                return List.of(new SuperRef(src.text(child), RefKind.EXTENDS));
            }
            if (child.getType().equals("scope_resolution")) {
                TSNode name = child.getChildByFieldName("name");
                String text = name == null || name.isNull() ? src.text(child) : src.text(name);
                return List.of(new SuperRef(text, RefKind.EXTENDS));
            }
        }
        String text = src.text(superclass);
        if (text.startsWith("<")) {
            text = text.substring(1).strip();
        }
        return text.isBlank() ? List.of() : List.of(new SuperRef(text, RefKind.EXTENDS));
    }
}
