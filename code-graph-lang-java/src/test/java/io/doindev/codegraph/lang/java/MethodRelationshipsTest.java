package io.doindev.codegraph.lang.java;

import io.doindev.codegraph.model.*;
import io.doindev.codegraph.parse.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MethodRelationshipsTest {
    FileFragment parse(String path,String text){return new JavaAnalyzer().extract(new SourceFile(path,"java",text));}
    List<Edge> resolve(FileFragment... files){var lookup=SymbolTable.of(List.of(files));return Arrays.stream(files).flatMap(f->NameResolver.resolve(f,lookup,new ArrayList<>()).stream()).filter(e->e.kind()==EdgeKind.OVERRIDES).toList();}
    @Test void overloadsAndUnrelatedSameNamesDoNotBecomeOverrides(){
        var file=parse("A.java","""
            interface Contract { String use(String value); }
            class Base { public void use(int value) {} public void use(String value) {} }
            class Child extends Base { public void use(int value) {} }
            class Impl implements Contract { public String use(String value) { return value; } }
            class Unrelated { public void use(int value) {} }
            """);
        var edges=resolve(file);assertEquals(2,edges.size(),edges.toString());
        assertTrue(edges.stream().anyMatch(e->e.from().value().contains("Child.use")&&e.to().value().contains("Base.use")));
        assertTrue(edges.stream().anyMatch(e->e.attrs().get("implementationKind").equals("interface_method")));
        assertTrue(edges.stream().allMatch(e->e.confidence()==1f));
    }
    @Test void privateStaticFinalConstructorsAndInaccessibleMembersAreExcluded(){
        var base=parse("p/Base.java","""
            package p; public class Base {
              public Base(){} private void hidden(){} public static void stat(){}
              public final void fixed(){} void local(){} protected void ok(){}
            }
            """);
        var child=parse("q/Child.java","""
            package q; import p.Base; class Child extends Base {
              Child(){} public void hidden(){} public static void stat(){}
              public void fixed(){} public void local(){} protected void ok(){}
            }
            """);
        var edges=resolve(base,child);assertEquals(1,edges.size(),edges.toString());assertTrue(edges.getFirst().from().value().contains("Child.ok"));
    }
    @Test void indirectHierarchyAndCovariantReturnAreDeclarationEvidenceOnly(){
        var file=parse("A.java","""
            class Value {} class Special extends Value {}
            class Base { public Value read(){return null;} }
            class Middle extends Base {}
            class Child extends Middle { public Special read(){return null;} }
            """);
        var edges=resolve(file);assertEquals(1,edges.size());assertEquals("declaration",edges.getFirst().attrs().get("referencePrecision"));
        assertTrue(edges.getFirst().to().value().contains("Base.read"));
    }
    @Test void genericArgumentsFollowEachInheritanceHopWithoutErasure(){
        var generic=parse("A.java","""
            import java.util.List;
            interface Contract<T> { List<T> read(T value); }
            abstract class Middle<U> implements Contract<U> {}
            class Good extends Middle<String> { public List<String> read(String value){return null;} }
            class Wrong extends Middle<String> { public List<Integer> read(String value){return null;} }
            class Base { public void call(){} }
            class Narrow extends Base { protected void call(){} }
            """);
        var found=resolve(generic);assertEquals(1,found.size(),found.toString());
        assertTrue(found.getFirst().from().value().contains("Good.read"));
    }
    @Test void instantiatedGenericsResolveButMethodGenericsRemainUncertain(){
        var generic=parse("A.java","interface A<T> { T read(T value); } class B implements A<String> { public String read(String value){return value;} }");
        assertEquals(1,resolve(generic).size());
        var methodGeneric=parse("G.java","interface A { <T> T read(T value); } class B implements A { public <U> U read(U value){return value;} }");
        assertTrue(resolve(methodGeneric).isEmpty());
        var cycle=parse("cycle.java","class X extends Y { public void x(){} } class Y extends X {}");
        assertTrue(resolve(cycle).isEmpty());
    }
}
