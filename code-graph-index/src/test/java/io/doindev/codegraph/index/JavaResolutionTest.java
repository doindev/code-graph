package io.doindev.codegraph.index;

import io.doindev.codegraph.config.CodeGraphConfig;
import io.doindev.codegraph.graph.InMemoryCodeGraph;
import io.doindev.codegraph.model.*;
import io.doindev.codegraph.parse.*;
import io.doindev.codegraph.storage.RecordCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class JavaResolutionTest {
    @TempDir Path root;
    final InMemoryCodeGraph graph=new InMemoryCodeGraph();
    void file(String name,String source)throws Exception {
        Path path=root.resolve(name);Files.createDirectories(path.getParent());Files.writeString(path,source);
    }
    IncrementalIndexer index() {
        var indexer=new IncrementalIndexer(root,Analyzers.discover(),CodeGraphConfig.defaults(),graph);
        assertTrue(indexer.fullIndex().failedFiles().isEmpty());return indexer;
    }
    List<Edge> calls(String caller) {
        var nodes=graph.findSymbols(caller,Set.of(NodeKind.FUNCTION),"java",30).stream()
                .filter(n->((SymbolId)n.id()).qualifiedName().equals(caller)).toList();
        assertEquals(1,nodes.size(),caller);
        return graph.edges(nodes.getFirst().id(),Direction.OUT,Set.of(EdgeKind.CALLS));
    }
    Set<String> targets(String caller) {
        var names=new HashSet<String>();
        calls(caller).forEach(e->names.add(((SymbolId)e.to()).qualifiedName()));return names;
    }
    @Test void qualifiedReceiverCannotResolveToLocalOrSameDirectoryNames()throws Exception {
        file("p/Target.java","package p; class Target { static byte[] bytes(Object c,int i){ return null; } static void validate(Object x){} }");
        file("p/Caller.java","""
                package p;
                class Caller {
                  static byte[] bytes(Object c,int i){ return Target.bytes(c,i); }
                  static void validate(Object c){ Target.validate(c); }
                  void external(java.util.Map x){ x.get("a"); }
                  Object get(String key){return null;}
                }
                """);
        index();
        assertEquals(Set.of("p.Target.bytes"),targets("p.Caller.bytes"));
        assertEquals(Set.of("p.Target.validate"),targets("p.Caller.validate"));
        assertTrue(targets("p.Caller.external").isEmpty(),"external receivers must not become local calls");
    }
    @Test void importsPackagesStaticImportsAndNestedTypesAreRespected()throws Exception {
        file("a/Service.java","package a; public class Service { public static void run(){} public static class Nested { public static void work(){} } }");
        file("b/Service.java","package b; class Service { static void run(){} }");
        file("c/C.java","""
                package c; import a.Service; import static a.Service.run;
                class C { void first(){ Service.run(); } void second(){ run(); }
                  void third(){ Service.Nested.work(); } }
                """);
        file("c/W.java","package c; import a.*; class W {void invoke(){ Service.run(); }}");
        index();
        assertEquals(Set.of("a.Service.run"),targets("c.C.first"));
        assertEquals(Set.of("a.Service.run"),targets("c.C.second"));
        assertEquals(Set.of("a.Service.Nested.work"),targets("c.C.third"));
        assertEquals(Set.of("a.Service.run"),targets("c.W.invoke"));
    }
    @Test void localsParametersFieldsCastsAndReturnChainsResolve()throws Exception {
        file("Types.java","""
                class Service { void run(){} Service next(){return this;} static Service create(){return null;} }
                class Other { void run(){} }
                class Caller {
                  Service field;
                  void parameter(Service s){s.run();}
                  void local(){Service s=new Service();s.run();}
                  void inferred(){var s=Service.create();s.run();}
                  void chain(){field.next().run();}
                  void cast(Object x){((Service)x).run();}
                  void shadow(Other field){field.run();this.field.run();}
                  void scope(){Service x=null;{Other x=null;x.run();}x.run();}
                }
                """);
        index();
        assertEquals(Set.of("Service.run"),targets("Caller.parameter"));
        assertTrue(targets("Caller.local").contains("Service.run"));
        assertEquals(Set.of("Service.create","Service.run"),targets("Caller.inferred"));
        assertEquals(Set.of("Service.next","Service.run"),targets("Caller.chain"));
        assertEquals(Set.of("Service.run"),targets("Caller.cast"));
        assertEquals(Set.of("Service.run","Other.run"),targets("Caller.shadow"));
        assertEquals(Set.of("Service.run","Other.run"),targets("Caller.scope"));
    }
    @Test void inheritanceOverridesAndOverloadsRetainHonestEvidence()throws Exception {
        file("Inheritance.java","""
                interface Contract { void execute(); }
                class Parent { void inherited(){} void changed(){} }
                class Child extends Parent implements Contract {
                  public void execute(){} void changed(){}
                  void call(){ inherited();this.changed();super.changed(); }
                }
                class Use {void call(Contract c){c.execute();}}
                class Overloads {
                  void pick(int n){} void pick(String s){} void pick(long n){}
                  void exact(){pick(1);} void text(){pick("a");}
                  void unknown(){pick(mystery());}
                }
                """);
        index();
        assertEquals(Set.of("Parent.inherited","Parent.changed","Child.changed"),targets("Child.call"));
        assertEquals(Set.of("Contract.execute"),targets("Use.call"));
        assertTrue(calls("Use.call").getFirst().attrs().get("dispatch").contains("runtime"));
        var exact=calls("Overloads.exact");assertEquals(1,exact.size());
        assertTrue(graph.node(exact.getFirst().to()).orElseThrow().displaySignature().contains("int n"));
        var text=calls("Overloads.text");assertEquals(1,text.size());
        assertTrue(graph.node(text.getFirst().to()).orElseThrow().displaySignature().contains("String s"));
        assertEquals(3,calls("Overloads.unknown").size());
        assertTrue(calls("Overloads.unknown").stream().allMatch(e->e.attrs().get("resolutionStatus").equals("candidate")));
    }
    @Test void constructorsVarargsNullAndBoxingAreBounded()throws Exception {
        file("Args.java","""
                class Built {Built(int n){} Built(String s){}}
                class Args {
                  void many(String... v){} void boxed(Integer n){} void nullable(int n){} void nullable(String s){}
                  void call(){new Built(1);many("a","b");boxed(1);nullable(null);}
                }
                """);
        index();
        assertEquals(4,calls("Args.call").size());
        assertTrue(calls("Args.call").stream().filter(e->((SymbolId)e.to()).qualifiedName().equals("Built.Built"))
                .allMatch(e->graph.node(e.to()).orElseThrow().displaySignature().contains("int n")));
    }
    @Test void signatureChangesInvalidateTransitiveTypeConsumersAndNewOverloads()throws Exception {
        file("Models.java","class First {void run(){}} class Second{void run(){}}");
        file("Factory.java","class Factory {static First create(){return null;}}");
        file("Use.java","class Use {void work(){Factory.create().run();}}");
        var indexer=index();
        assertTrue(targets("Use.work").contains("First.run"));
        file("Factory.java","class Factory {static Second create(){return null;}}");
        indexer.applyChanges(List.of("Factory.java"));
        assertTrue(targets("Use.work").contains("Second.run"));
        assertFalse(targets("Use.work").contains("First.run"));
        file("Second.java","class Second{void run(){}}"); // duplicate identity must not be silently selected
        indexer.applyChanges(List.of("Second.java"));
        var incremental=new HashSet<>(calls("Use.work"));
        indexer.fullIndex();
        assertEquals(incremental,new HashSet<>(calls("Use.work")));
    }
    @Test void fullHybridAndCodecAgree()throws Exception {
        file("Model.java","class Base {void go(){}} class Model extends Base {static Model make(){return null;}}");
        file("Caller.java","class Caller {void go(){Model.make().go();} void local(Model x){x.go();}}");
        index();
        try(var workspace=Workspace.open(List.of(root),Analyzers.discover(),p->CodeGraphConfig.defaults(),true,32L<<20)) {
            workspace.fullIndexAll();var disk=workspace.defaultProject().graph();
            for(Node node:graph.allNodes(null))
                assertEquals(new HashSet<>(graph.edges(node.id(),Direction.OUT,null)),new HashSet<>(disk.edges(node.id(),Direction.OUT,null)));
        }
        var fragment=new io.doindev.codegraph.lang.java.JavaAnalyzer().extract(new SourceFile("C.java","java","class C{void go(C c){c.go(c);}}"));
        assertEquals(fragment,RecordCodec.decode(RecordCodec.encode(new RecordCodec.F(fragment)),RecordCodec.F.class).fragment());
    }
    @Test void inheritedFieldsAndGenericBoundsDoNotBindToUnrelatedTypeNames()throws Exception {
        file("Generic.java","""
                class Service {void run(){}}
                class Parent {Service service;}
                class Child extends Parent {void call(){service.run();}}
                class T {void run(){}}
                class Generic<T> {void unknown(T item){item.run();}}
                class Bounded<T extends Service> {void call(T item){item.run();}}
                """);
        index();
        assertEquals(Set.of("Service.run"),targets("Child.call"));
        assertTrue(targets("Generic.unknown").isEmpty());
        assertEquals(Set.of("Service.run"),targets("Bounded.call"));
    }
    @Test void wildcardAmbiguityAndUnknownReceiverNeverPickFirstGlobalCandidate()throws Exception {
        file("a/S.java","package a; public class S {public static void get(){}}");
        file("b/S.java","package b; public class S {public static void get(){}}");
        file("c/C.java","package c; import a.*; import b.*; class C {void call(){S.get();missing.get();} void get(){}}");
        index();assertTrue(targets("c.C.call").isEmpty());
    }
    @Test void lexicalTypesAndExplicitImportsBeatUnrelatedDefaultPackageTypes()throws Exception {
        file("Service.java","class Service {static void go(){}}");
        file("a/Service.java","package a; public class Service {public static void go(){}}");
        file("p/C.java","""
                package p; import a.Service;
                class C {void call(){Service.go();}}
                class Outer {static class Service {static void go(){}} void call(){Service.go();}}
                """);
        index();
        assertEquals(Set.of("a.Service.go"),targets("p.C.call"));
        assertEquals(Set.of("p.Outer.Service.go"),targets("p.Outer.call"));
    }
    @Test void boxedAndStringOverloadsAndUnboxingWideningAreDistinguished()throws Exception {
        file("Boxed.java","""
                class Boxed {
                  void choose(String s){} void choose(Integer n){} void widen(long n){}
                  void call(){choose("yes");} void number(Integer n){widen(n);}
                }
                """);
        index();
        assertEquals(1,calls("Boxed.call").size());
        assertTrue(graph.node(calls("Boxed.call").getFirst().to()).orElseThrow().displaySignature().contains("String s"));
        assertEquals(Set.of("Boxed.widen"),targets("Boxed.number"));
    }
    @Test void sameNameStressKeepsOnlyQualifiedTargetsAndParityAfterDeletion()throws Exception {
        for(int i=0;i<120;i++)file("p/T"+i+".java","package p; class T"+i+" {static void validate(int n){} void call(){T"+((i+1)%120)+".validate(1);}}");
        var indexer=index();
        for(int i=0;i<120;i++)assertEquals(Set.of("p.T"+((i+1)%120)+".validate"),targets("p.T"+i+".call"));
        Files.delete(root.resolve("p/T60.java"));indexer.applyChanges(List.of("p/T60.java"));
        assertTrue(targets("p.T59.call").isEmpty());
        assertTrue(graph.findSymbols("p.T60.call",null,"java",10).isEmpty());
        assertEquals(Set.of("p.T62.validate"),targets("p.T61.call"));
    }
}
