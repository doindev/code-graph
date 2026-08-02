package io.doindev.codegraph.lang.sql;

import io.doindev.codegraph.parse.Src;
import io.doindev.codegraph.parse.TreeWalkAnalyzer;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TreeSitterSql;

import java.util.Set;

/**
 * SQL extraction over tree-sitter-sql (grammar {@code io.github.bonede:tree-sitter-sql}). Produces a
 * per-file inventory of the schema objects a {@code .sql} file declares:
 * {@code CREATE TABLE}/{@code CREATE VIEW} become {@code TYPE} nodes (named by the relation) and
 * {@code CREATE FUNCTION}/{@code CREATE PROCEDURE} become {@code FUNCTION} nodes.
 *
 * <p><strong>Honest limitations.</strong> DDL has no cross-symbol call graph to chase, so
 * {@link #callTypes()} is empty and there are no CALLS/EXTENDS edges — the value is the inventory,
 * not a call graph. The grammar (probed against core {@code 0.26.6}) parses {@code CREATE TABLE},
 * {@code CREATE VIEW} and {@code CREATE FUNCTION} cleanly; {@code CREATE PROCEDURE} with a
 * {@code BEGIN … END} body is not reliably parsed by this grammar and may be dropped. Names live
 * under a child {@code object_reference} rather than a {@code name} field, so {@link #nameOf} and
 * {@link #arityOf} are overridden accordingly.
 */
public final class SqlAnalyzer extends TreeWalkAnalyzer {

    @Override
    public String languageId() {
        return "sql";
    }

    @Override
    public Set<String> fileExtensions() {
        return Set.of("sql");
    }

    @Override
    protected TSLanguage newLanguage() {
        return new TreeSitterSql();
    }

    @Override
    protected Set<String> typeDeclarationTypes() {
        return Set.of("create_table", "create_view");
    }

    @Override
    protected Set<String> functionDeclarationTypes() {
        return Set.of("create_function", "create_procedure");
    }

    @Override
    protected Set<String> callTypes() {
        return Set.of();
    }

    /**
     * The declared object's name: tree-sitter-sql attaches it as a direct {@code object_reference}
     * child (schema-qualified for {@code schema.table}), not via a {@code name} field.
     */
    @Override
    protected String nameOf(TSNode decl, Src src) {
        TSNode ref = firstChildOfType(decl, "object_reference");
        if (ref == null) {
            return null;
        }
        return src.text(ref).strip();
    }

    /** Counts {@code function_argument} entries inside the routine's {@code function_arguments} list. */
    @Override
    protected int arityOf(TSNode decl, Src src) {
        TSNode args = firstChildOfType(decl, "function_arguments");
        if (args == null) {
            return 0;
        }
        int arity = 0;
        int count = args.getNamedChildCount();
        for (int i = 0; i < count; i++) {
            if (args.getNamedChild(i).getType().equals("function_argument")) {
                arity++;
            }
        }
        return arity;
    }

    @Override
    protected String signatureOf(TSNode decl, String name, Src src) {
        return name + "/" + arityOf(decl, src);
    }

    private static TSNode firstChildOfType(TSNode node, String type) {
        int count = node.getNamedChildCount();
        for (int i = 0; i < count; i++) {
            TSNode child = node.getNamedChild(i);
            if (child.getType().equals(type)) {
                return child;
            }
        }
        return null;
    }
}
