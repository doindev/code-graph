package io.doindev.codegraph.lang.csharp;

import io.doindev.codegraph.model.Node;
import io.doindev.codegraph.model.NodeKind;
import io.doindev.codegraph.model.SymbolId;
import io.doindev.codegraph.parse.FileFragment;
import io.doindev.codegraph.parse.RawRef;
import io.doindev.codegraph.parse.RefKind;
import io.doindev.codegraph.parse.SourceFile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CSharpAnalyzerTest {

    private final CSharpAnalyzer analyzer = new CSharpAnalyzer();

    private static final String FILE_SCOPED = """
            using System;
            using System.Collections.Generic;

            namespace Acme.Store;

            public interface IRepository
            {
            }

            public class OrderService : BaseService, IRepository
            {
                private readonly List<string> items;
                public int Count { get; set; }

                public OrderService(int capacity)
                {
                }

                public void Save(string order, int retries)
                {
                    var repo = new OrderRepository();
                    repo.Persist(order);
                    Validate(order);
                    if (retries > 0)
                    {
                    }
                }
            }
            """;

    private FileFragment extract(String relPath, String content) {
        return analyzer.extract(new SourceFile(relPath, "cs", content));
    }

    private static Node node(FileFragment fragment, NodeKind kind, String qualifiedName) {
        return fragment.declarations().stream()
                .filter(n -> n.kind() == kind && n.id() instanceof SymbolId id
                        && id.qualifiedName().equals(qualifiedName))
                .findFirst().orElse(null);
    }

    private static RawRef call(FileFragment fragment, String name) {
        return fragment.rawRefs().stream()
                .filter(r -> r.kind() == RefKind.CALL && r.name().equals(name))
                .findFirst().orElse(null);
    }

    @Test
    void fileScopedNamespaceQualifiesTypesAndFunctions() {
        FileFragment fragment = extract("src/OrderService.cs", FILE_SCOPED);

        Node type = node(fragment, NodeKind.TYPE, "Acme.Store.OrderService");
        assertNotNull(type, "TYPE node with namespace-qualified name");
        assertEquals("OrderService", type.name());
        assertNotNull(node(fragment, NodeKind.TYPE, "Acme.Store.IRepository"));

        Node save = node(fragment, NodeKind.FUNCTION, "Acme.Store.OrderService.Save");
        assertNotNull(save, "FUNCTION node with qualified name");
        assertEquals(2, ((SymbolId) save.id()).arity());

        Node constructor = node(fragment, NodeKind.FUNCTION, "Acme.Store.OrderService.OrderService");
        assertNotNull(constructor, "constructor declaration");
        assertEquals(1, ((SymbolId) constructor.id()).arity());
    }

    @Test
    void callsCarryNameReceiverAndArity() {
        FileFragment fragment = extract("src/OrderService.cs", FILE_SCOPED);

        RawRef memberCall = call(fragment, "Persist");
        assertNotNull(memberCall, "member invocation repo.Persist(order)");
        assertEquals("repo", memberCall.receiverHint());
        assertEquals(1, memberCall.arity());

        RawRef plainCall = call(fragment, "Validate");
        assertNotNull(plainCall, "unqualified invocation Validate(order)");
        assertEquals(1, plainCall.arity());

        RawRef creation = call(fragment, "OrderRepository");
        assertNotNull(creation, "object creation new OrderRepository()");
        assertEquals(0, creation.arity());
    }

    @Test
    void usingsAreCollected() {
        FileFragment fragment = extract("src/OrderService.cs", FILE_SCOPED);
        assertTrue(fragment.imports().contains("System"), fragment.imports().toString());
        assertTrue(fragment.imports().contains("System.Collections.Generic"),
                fragment.imports().toString());
    }

    @Test
    void allBasesReportedAsExtends() {
        FileFragment fragment = extract("src/OrderService.cs", FILE_SCOPED);
        List<RawRef> supers = fragment.rawRefs().stream()
                .filter(r -> r.kind() == RefKind.EXTENDS
                        && r.from() instanceof SymbolId id
                        && id.qualifiedName().equals("Acme.Store.OrderService"))
                .toList();
        assertEquals(2, supers.size(), supers.toString());
        assertTrue(supers.stream().anyMatch(r -> r.name().equals("BaseService")));
        assertTrue(supers.stream().anyMatch(r -> r.name().equals("IRepository")));
    }

    @Test
    void fieldsAndPropertiesBecomeVariables() {
        FileFragment fragment = extract("src/OrderService.cs", FILE_SCOPED);
        assertNotNull(node(fragment, NodeKind.VARIABLE, "Acme.Store.OrderService.items"),
                "field declaration");
        assertNotNull(node(fragment, NodeKind.VARIABLE, "Acme.Store.OrderService.Count"),
                "property declaration");
    }

    @Test
    void blockNamespaceIsAScopeContainer() {
        String source = """
                namespace Acme.Web
                {
                    public class Handler
                    {
                        public void Run(int x)
                        {
                            obj.Save(x);
                        }
                    }
                }
                """;
        FileFragment fragment = extract("src/Handler.cs", source);

        assertNotNull(node(fragment, NodeKind.TYPE, "Acme.Web"), "namespace scope node");
        assertNotNull(node(fragment, NodeKind.TYPE, "Acme.Web.Handler"));
        Node run = node(fragment, NodeKind.FUNCTION, "Acme.Web.Handler.Run");
        assertNotNull(run, "method qualified through block namespace");
        assertEquals(1, ((SymbolId) run.id()).arity());

        RawRef save = call(fragment, "Save");
        assertNotNull(save);
        assertEquals("obj", save.receiverHint());
        assertEquals(1, save.arity());
    }

    @Test
    void localFunctionsAndGenericInvocationsAreExtracted() {
        String source = """
                namespace Acme.Tools;

                public class Runner
                {
                    public void Main()
                    {
                        Helper<int>(1, 2);

                        void Helper<T>(T a, int b)
                        {
                        }
                    }
                }
                """;
        FileFragment fragment = extract("src/Runner.cs", source);

        Node helper = node(fragment, NodeKind.FUNCTION, "Acme.Tools.Runner.Main.Helper");
        assertNotNull(helper, "local_function_statement");
        assertEquals(2, ((SymbolId) helper.id()).arity());

        RawRef genericCall = call(fragment, "Helper");
        assertNotNull(genericCall, "generic invocation Helper<int>(...) stripped to Helper");
        assertEquals(2, genericCall.arity());
    }
}
