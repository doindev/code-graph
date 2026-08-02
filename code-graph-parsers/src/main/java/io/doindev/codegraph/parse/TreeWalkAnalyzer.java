package io.doindev.codegraph.parse;

import io.doindev.codegraph.model.Edge;
import io.doindev.codegraph.model.EdgeKind;
import io.doindev.codegraph.model.FileId;
import io.doindev.codegraph.model.Metrics;
import io.doindev.codegraph.model.Node;
import io.doindev.codegraph.model.NodeId;
import io.doindev.codegraph.model.NodeKind;
import io.doindev.codegraph.model.SymbolId;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TSTree;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Table-driven pass-1 extraction shared by all language analyzers: a single tree walk that
 * collects type/function/field declarations (with stable {@link SymbolId}s and AST metrics),
 * CONTAINS edges, imports, and unresolved call/supertype references. Subclasses supply the
 * grammar and node-type tables; hooks with language-specific shapes (call names, field
 * declarators, package prefixes) have overridable defaults.
 *
 * <p>Thread-safety: {@link #extract} creates a fresh {@link TSParser} per call; instances hold
 * no mutable state.
 */
public abstract class TreeWalkAnalyzer implements LanguageAnalyzer {

    // ---- grammar tables ----

    protected abstract TSLanguage newLanguage();

    /** Node types declaring a named type (class/interface/enum/struct/trait...). */
    protected abstract Set<String> typeDeclarationTypes();

    /** Node types declaring a named function/method/constructor. */
    protected abstract Set<String> functionDeclarationTypes();

    /** Node types that are call sites. */
    protected abstract Set<String> callTypes();

    /** Node types counted for the cyclomatic approximation (if/for/while/case/catch/&&/||...). */
    protected Set<String> branchTypes() {
        return Set.of();
    }

    /** Node types declaring fields/properties inside a type. */
    protected Set<String> fieldDeclarationTypes() {
        return Set.of();
    }

    // ---- language-specific hooks with common defaults ----

    /** Declared name of a type/function node; default reads the {@code name} field. */
    protected String nameOf(TSNode decl, Src src) {
        TSNode name = decl.getChildByFieldName("name");
        return name == null || name.isNull() ? null : src.text(name);
    }

    /** Parameter count of a function node; default counts named children of the {@code parameters} field. */
    protected int arityOf(TSNode decl, Src src) {
        TSNode params = decl.getChildByFieldName("parameters");
        return params == null || params.isNull() ? 0 : params.getNamedChildCount();
    }

    /** Call name / receiver hint / arg count for a call node; {@code null} name = skip. */
    protected CallSite callOf(TSNode call, Src src) {
        TSNode function = call.getChildByFieldName("function");
        if (function == null || function.isNull()) {
            return null;
        }
        TSNode args = call.getChildByFieldName("arguments");
        int arity = args == null || args.isNull() ? 0 : args.getNamedChildCount();
        // identifier() or receiver.property()
        if (function.getNamedChildCount() == 0) {
            return new CallSite(src.text(function), null, arity);
        }
        TSNode property = function.getChildByFieldName("property");
        if (property == null || property.isNull()) {
            property = function.getChildByFieldName("attribute");
        }
        if (property == null || property.isNull()) {
            property = function.getChildByFieldName("name");
        }
        if (property == null || property.isNull()) {
            return new CallSite(src.text(function), null, arity);
        }
        TSNode receiver = function.getChildByFieldName("object");
        if (receiver == null || receiver.isNull()) {
            receiver = function.getChildByFieldName("value");
        }
        String receiverHint = receiver == null || receiver.isNull() ? null : src.text(receiver);
        return new CallSite(src.text(property), receiverHint, arity);
    }

    /** Import strings as written; default none. */
    protected List<String> importsOf(TSNode root, Src src) {
        return List.of();
    }

    /** Supertype references of a type declaration; default none. */
    protected List<SuperRef> supertypesOf(TSNode typeDecl, Src src) {
        return List.of();
    }

    /** Qualified-name prefix for top-level declarations (java package); default empty. */
    protected String packagePrefix(TSNode root, Src src) {
        return "";
    }

    /** Declared names inside a field declaration node; default none (fields skipped). */
    protected List<String> fieldNamesOf(TSNode fieldDecl, Src src) {
        return List.of();
    }

    public record CallSite(String name, String receiverHint, int arity) {
    }

    public record SuperRef(String name, RefKind kind) {
    }

    // ---- pass 1 ----

    @Override
    public FileFragment extract(SourceFile file) {
        TSParser parser = new TSParser();
        parser.setLanguage(newLanguage());
        TSTree tree = parser.parseString(null, file.content());
        Src src = new Src(file.relPath(), file.content());
        TSNode root = tree.getRootNode();

        Walk walk = new Walk(file, src);
        FileId fileId = new FileId(file.relPath());
        String fileName = file.relPath().substring(file.relPath().lastIndexOf('/') + 1);
        walk.declarations.add(new Node(fileId, NodeKind.FILE, fileName, fileName, src.span(root),
                new Metrics(src.lineCount(root), 0, 0, 0, 0, 0, Float.NaN),
                Map.of("lang", languageId())));
        walk.packagePrefix = packagePrefix(root, src);
        walk.visit(root, new Scope(fileId, "", null));

        return new FileFragment(fileId, languageId(), sha256(file.content()),
                walk.declarations, walk.localEdges, walk.rawRefs, importsOf(root, src));
    }

    /** Enclosing lexical scope: the owning node id and the qualified-name prefix. */
    private record Scope(NodeId owner, String qualifiedName, Scope parent) {
        String childQualifiedName(String name, String packagePrefix) {
            if (!qualifiedName.isEmpty()) {
                return qualifiedName + "." + name;
            }
            return packagePrefix.isEmpty() ? name : packagePrefix + "." + name;
        }
    }

    private final class Walk {
        final SourceFile file;
        final Src src;
        final List<Node> declarations = new ArrayList<>();
        final List<Edge> localEdges = new ArrayList<>();
        final List<RawRef> rawRefs = new ArrayList<>();
        final Set<String> seenIds = new HashSet<>();
        final Map<NodeId, int[]> typeCounters = new HashMap<>(); // [methodCount, fieldCount]
        String packagePrefix = "";

        Walk(SourceFile file, Src src) {
            this.file = file;
            this.src = src;
        }

        void visit(TSNode node, Scope scope) {
            int count = node.getNamedChildCount();
            for (int i = 0; i < count; i++) {
                TSNode child = node.getNamedChild(i);
                String type = child.getType();
                if (typeDeclarationTypes().contains(type)) {
                    visitTypeDeclaration(child, scope);
                } else if (functionDeclarationTypes().contains(type)) {
                    visitFunctionDeclaration(child, scope);
                } else if (fieldDeclarationTypes().contains(type)) {
                    visitFieldDeclaration(child, scope);
                    visit(child, scope); // initializers may contain calls
                } else if (callTypes().contains(type)) {
                    visitCall(child, scope);
                    visit(child, scope); // nested calls in arguments
                } else {
                    visit(child, scope);
                }
            }
        }

        private void visitTypeDeclaration(TSNode decl, Scope scope) {
            String name = nameOf(decl, src);
            if (name == null || name.isBlank()) {
                visit(decl, scope);
                return;
            }
            String qualifiedName = scope.childQualifiedName(name, packagePrefix);
            SymbolId id = uniqueId(qualifiedName, 0, decl);
            localEdges.add(new Edge(scope.owner(), id, EdgeKind.CONTAINS));
            for (SuperRef superRef : supertypesOf(decl, src)) {
                rawRefs.add(new RawRef(id, superRef.kind(), superRef.name(), null, -1, src.span(decl)));
            }
            int[] counters = new int[2];
            typeCounters.put(id, counters);
            visit(decl, new Scope(id, qualifiedName, scope));
            declarations.add(new Node(id, NodeKind.TYPE, name, name, src.span(decl),
                    new Metrics(src.lineCount(decl), counters[0], counters[1], 0,
                            0, 0, Float.NaN),
                    Map.of()));
        }

        private void visitFunctionDeclaration(TSNode decl, Scope scope) {
            String name = nameOf(decl, src);
            if (name == null || name.isBlank()) {
                visit(decl, scope);
                return;
            }
            int arity = arityOf(decl, src);
            String qualifiedName = scope.childQualifiedName(name, packagePrefix);
            SymbolId id = uniqueId(qualifiedName, arity, decl);
            localEdges.add(new Edge(scope.owner(), id, EdgeKind.CONTAINS));
            int[] counters = typeCounters.get(scope.owner());
            if (counters != null) {
                counters[0]++;
            }
            visit(decl, new Scope(id, qualifiedName, scope));
            declarations.add(new Node(id, NodeKind.FUNCTION, name, signatureOf(decl, name, src),
                    src.span(decl),
                    new Metrics(src.lineCount(decl), 0, 0, arity,
                            nestingDepth(decl, 0), cyclomatic(decl), Float.NaN),
                    Map.of()));
        }

        private void visitFieldDeclaration(TSNode decl, Scope scope) {
            int[] counters = typeCounters.get(scope.owner());
            for (String name : fieldNamesOf(decl, src)) {
                String qualifiedName = scope.childQualifiedName(name, packagePrefix);
                SymbolId id = uniqueId(qualifiedName, 0, decl);
                localEdges.add(new Edge(scope.owner(), id, EdgeKind.CONTAINS));
                declarations.add(new Node(id, NodeKind.VARIABLE, name, name, src.span(decl),
                        new Metrics(src.lineCount(decl), 0, 0, 0, 0, 0, Float.NaN), Map.of()));
                if (counters != null) {
                    counters[1]++;
                }
            }
        }

        private void visitCall(TSNode call, Scope scope) {
            CallSite site = callOf(call, src);
            if (site != null && site.name() != null && !site.name().isBlank()) {
                rawRefs.add(new RawRef(scope.owner(), RefKind.CALL, site.name(),
                        site.receiverHint(), site.arity(), src.span(call)));
            }
        }

        /** Same-arity overload collisions get a {@code ~hash4} suffix over the declaration header text. */
        private SymbolId uniqueId(String qualifiedName, int arity, TSNode decl) {
            SymbolId id = new SymbolId(languageId(), file.relPath(), qualifiedName, arity);
            if (seenIds.add(id.value())) {
                return id;
            }
            String header = src.text(decl);
            header = header.substring(0, Math.min(header.length(), 200));
            String hash = sha256(header).substring(0, 4);
            SymbolId collided = new SymbolId(languageId(), file.relPath(), qualifiedName, arity, hash);
            int ordinal = 2;
            while (!seenIds.add(collided.value())) {
                collided = new SymbolId(languageId(), file.relPath(), qualifiedName, arity,
                        hash + "-" + ordinal++);
            }
            return collided;
        }
    }

    /** One-line display signature; default {@code name(paramText)} truncated. */
    protected String signatureOf(TSNode decl, String name, Src src) {
        TSNode params = decl.getChildByFieldName("parameters");
        String paramText = params == null || params.isNull() ? "()" : src.text(params);
        if (paramText.length() > 80) {
            paramText = paramText.substring(0, 77) + "...";
        }
        return name + paramText.replaceAll("\\s+", " ");
    }

    private int nestingDepth(TSNode node, int depth) {
        int max = depth;
        int count = node.getNamedChildCount();
        for (int i = 0; i < count; i++) {
            TSNode child = node.getNamedChild(i);
            int childDepth = branchTypes().contains(child.getType()) ? depth + 1 : depth;
            max = Math.max(max, nestingDepth(child, childDepth));
        }
        return max;
    }

    private int cyclomatic(TSNode node) {
        int count = 1;
        ArrayList<TSNode> stack = new ArrayList<>();
        stack.add(node);
        while (!stack.isEmpty()) {
            TSNode current = stack.remove(stack.size() - 1);
            int children = current.getNamedChildCount();
            for (int i = 0; i < children; i++) {
                TSNode child = current.getNamedChild(i);
                if (branchTypes().contains(child.getType())) {
                    count++;
                }
                stack.add(child);
            }
        }
        return count;
    }

    static String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
