package io.doindev.codegraph.parse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TSQuery;
import org.treesitter.TSQueryCursor;
import org.treesitter.TSQueryMatch;
import org.treesitter.TSTree;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Phase-0 ABI gate: every bundled grammar jar (built against tree-sitter 0.23.x) must load and
 * parse against the core binding in use. If this test fails on a language, either pin the core
 * binding back (0.25.3 is the documented fallback) or drop/upgrade that grammar — nothing else
 * in the project may be built on top of a failing combination.
 *
 * <p>Grammar classes are resolved reflectively because artifact naming varies
 * (e.g. {@code TreeSitterCSharp} vs {@code TreeSitterCsharp}); the failure message reports the
 * candidates tried so the fix is mechanical.
 */
class GrammarAbiSmokeTest {

    private record Lang(String id, List<String> candidateClasses, String snippet) {
    }

    static Stream<Lang> languages() {
        return Stream.of(
                new Lang("java", List.of("TreeSitterJava"),
                        "class A { void m(int x) { if (x > 0) { m(x - 1); } } }"),
                new Lang("javascript", List.of("TreeSitterJavascript", "TreeSitterJavaScript"),
                        "function f(a) { return a + 1; }\nconst g = () => f(2);"),
                new Lang("typescript", List.of("TreeSitterTypescript", "TreeSitterTypeScript"),
                        "interface P { name: string }\nfunction f(p: P): string { return p.name; }"),
                new Lang("tsx", List.of("TreeSitterTsx", "TreeSitterTSX"),
                        "const C = (p: {t: string}) => <div>{p.t}</div>;"),
                new Lang("python", List.of("TreeSitterPython"),
                        "def f(x):\n    return x + 1\n\nclass A:\n    def m(self):\n        return f(1)\n"),
                new Lang("csharp", List.of("TreeSitterCSharp", "TreeSitterCsharp"),
                        "class A { int M(int x) => x + 1; }"),
                new Lang("go", List.of("TreeSitterGo"),
                        "package main\n\nfunc f(x int) int {\n\treturn x + 1\n}\n"),
                new Lang("rust", List.of("TreeSitterRust"),
                        "fn f(x: i32) -> i32 { x + 1 }\npub struct A { v: i32 }"),
                new Lang("c", List.of("TreeSitterC"),
                        "int f(int x) { return x + 1; }"),
                new Lang("cpp", List.of("TreeSitterCpp", "TreeSitterCPP"),
                        "namespace acme { class A { public: int f(int x); }; }"),
                new Lang("php", List.of("TreeSitterPhp", "TreeSitterPHP"),
                        "<?php\nfunction f($x) { return $x + 1; }\nclass A { public function m() { return f(1); } }\n"),
                new Lang("ruby", List.of("TreeSitterRuby"),
                        "class A\n  def m(x)\n    x + 1\n  end\nend\n"),
                // Known limitation: the kotlin grammar (0.3.8.1) flags expression-body
                // functions ("fun m() = ...") as errors; block bodies parse fine.
                new Lang("kotlin", List.of("TreeSitterKotlin"),
                        "class A {\n    fun m(x: Int): Int {\n        return x + 1\n    }\n}\n"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("languages")
    void grammarLoadsAndParses(Lang lang) {
        TSLanguage language = loadLanguage(lang);
        TSParser parser = new TSParser();
        parser.setLanguage(language);
        TSTree tree = parser.parseString(null, lang.snippet());
        TSNode root = tree.getRootNode();
        assertFalse(root.isNull(), lang.id() + ": root node is null");
        assertTrue(root.getChildCount() > 0, lang.id() + ": empty parse tree");
        assertFalse(root.hasError(), lang.id() + ": parse error in known-good snippet\n" + lang.snippet());
    }

    @Test
    @DisplayName("TSQuery capture extraction works (java)")
    void queryApiWorks() {
        TSLanguage java = loadLanguage(new Lang("java", List.of("TreeSitterJava"), ""));
        TSParser parser = new TSParser();
        parser.setLanguage(java);
        TSTree tree = parser.parseString(null, "class Account { void close() {} }");
        TSQuery query = new TSQuery(java, "(class_declaration name: (identifier) @name)");
        TSQueryCursor cursor = new TSQueryCursor();
        cursor.exec(query, tree.getRootNode());
        TSQueryMatch match = new TSQueryMatch();
        assertTrue(cursor.nextMatch(match), "query produced no match");
        assertTrue(match.getCaptures().length > 0, "match has no captures");
    }

    private static TSLanguage loadLanguage(Lang lang) {
        for (String simpleName : lang.candidateClasses()) {
            try {
                Class<?> cls = Class.forName("org.treesitter." + simpleName);
                return (TSLanguage) cls.getDeclaredConstructor().newInstance();
            } catch (ClassNotFoundException e) {
                // try next candidate
            } catch (ReflectiveOperationException e) {
                fail(lang.id() + ": failed to instantiate org.treesitter." + simpleName + ": " + e);
            }
        }
        return fail(lang.id() + ": no grammar class found; tried org.treesitter."
                + String.join(", org.treesitter.", lang.candidateClasses()));
    }
}
