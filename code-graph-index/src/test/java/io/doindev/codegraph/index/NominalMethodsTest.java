package io.doindev.codegraph.index;

import io.doindev.codegraph.model.*;
import io.doindev.codegraph.parse.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NominalMethodsTest {
    FileFragment parse(String file,String source){var analyzer=Analyzers.discover().forPath(file);return analyzer.extract(new SourceFile(file,analyzer.languageId(),source));}
    List<Edge> edges(FileFragment file){return NameResolver.resolve(file,SymbolTable.of(List.of(file)),new ArrayList<>()).stream().filter(e->e.kind()==EdgeKind.OVERRIDES).toList();}
    void verify(String file,String source,int count){
        var fragment=parse(file,source);var found=edges(fragment);
        assertEquals(count,found.size(),()->file+" "+found+"\n"+fragment.declarations().stream().map(n->n.name()+" "+n.attrs()).toList());
        assertTrue(found.stream().allMatch(e->"resolved".equals(e.attrs().get("resolutionStatus"))));
    }
    @Test void swiftUsesProtocolAndOverrideSignaturesIncludingArgumentLabels(){
        verify("test.swift","""
            protocol P { func run(_ value: Int) -> Int }
            class Base { func run(_ value: Int) -> Int { return value } }
            class Child: Base, P { override func run(_ value: Int) -> Int { return value } }
            class Wrong: P { func run(label value: Int) -> Int { return value } }
            """,2);
    }
    @Test void dartInterfacesExcludeStaticMembers(){
        verify("test.dart","""
            abstract class P { int run(int value); }
            class Child implements P { int run(int value) {return value;} }
            class Wrong implements P { static int run(int value) {return value;} }
            """,1);
    }
    @Test void rustOnlyExplicitTraitImplementationsWithMatchingSelfModes(){
        verify("test.rs","""
            trait I { fn run(&self, value:i32)->i32; }
            struct T; impl I for T {fn run(&self, value:i32)->i32 {value}}
            struct Other; impl Other {fn run(&self, value:i32)->i32 {value}}
            """,1);
    }
    @Test void objectiveCSelectorsAndInstanceClassScopeRemainDistinct(){
        verify("test.m","""
            @protocol P
            - (int)run:(int)value;
            @end
            @interface Base
            - (int)run:(int)value;
            @end
            @interface Child : Base <P>
            - (int)run:(int)value;
            @end
            @interface Other : Base
            + (int)run:(int)value;
            @end
            """,2);
    }
    @Test void pythonC3SelectsTheFirstDefiningAncestorAndRejectsUnknownBases(){
        var fragment=parse("test.py","""
            class A:
                def run(self, value): return value
            class B(A): pass
            class C(A):
                def run(self, value): return value
            class D(B,C):
                def run(self): return 1
            class Unknown(factory(A)):
                def run(self, value): return value
            """);
        var found=edges(fragment);assertEquals(2,found.size(),found.toString());
        assertTrue(found.stream().anyMatch(e->e.from().value().contains("#D.run")&&e.to().value().contains("#C.run")));
        assertFalse(found.stream().anyMatch(e->e.from().value().contains("#Unknown.")));
    }
    @Test void rubyLiteralIncludesUseLastIncludedModuleAndSkipPrepend(){
        var fragment=parse("test.rb","""
            module A
              def run(x); x; end
            end
            module B
              def run(x); x; end
            end
            class C
              include A
              include B
              def run(x); x; end
            end
            class Unknown
              prepend A
              def run(x); x; end
            end
            """);
        var found=edges(fragment);assertEquals(1,found.size(),found.toString());
        assertTrue(found.getFirst().to().value().contains("#B.run"));
    }
    @Test void goRequiresTheWholeInterfaceAndReportsPointerMethodSets(){
        var fragment=parse("test.go","""
            package p
            type I interface { Run(value int) int; Stop() }
            type Complete struct{}
            func (c *Complete) Run(value int) int { return value }
            func (c Complete) Stop() {}
            type Partial struct{}
            func (c Partial) Run(value int) int { return value }
            type Wrong struct{}
            func (c Wrong) Run(value string) int { return 0 }
            func (c Wrong) Stop() {}
            """);
        var found=edges(fragment);assertEquals(2,found.size(),found.toString());
        assertTrue(found.stream().allMatch(e->e.attrs().get("declaringType").equals("*p.Complete")));
    }
    @Test void phpLiteralTraitAndInterfaceMethodsExcludePrivateAndAdaptedUses(){
        verify("test.php","""
            <?php
            trait T { public function run(int $v): int {return $v;} }
            interface I { public function run(int $v): int; }
            class B implements I { use T; public function run(int $v): int {return $v;} }
            class Unrelated { public function run(int $v): int {return $v;} }
            class Hidden { use T; private function run(int $v): int {return $v;} }
            """,2);
    }
    @Test void csharpOverridesAndInterfaceMethodsExcludeHidingAndWrongSignatures(){
        verify("test.cs","""
            namespace Test {
              interface I { int Run(int value); }
              class Base { public virtual int Run(int value){return value;} public int Plain(){return 0;} }
              class Child : Base { public override int Run(int value){return value;} public new int Plain(){return 0;} }
              class Impl : I { public int Run(int value){return value;} }
              class Wrong : I { public int Run(string value){return 0;} }
            }
            """,2);
    }
    @Test void kotlinRequiresOverrideAndAReplaceableBase(){
        verify("test.kt","""
            interface I { fun run(value: Int): Int }
            open class Base { open fun run(value: Int): Int { return value } }
            class Child : Base() { override fun run(value: Int): Int { return value } }
            class Impl : I { override fun run(value: Int): Int { return value } }
            class Other { fun run(value: Int): Int { return value } }
            """,2);
    }
    @Test void scalaAbstractTraitsAndConcreteOverrides(){
        verify("test.scala","""
            trait I { def run(value: Int): Int }
            class Impl extends I { def run(value: Int): Int = value }
            class Base { def run(value: Int): Int = value }
            class Child extends Base { override def run(value: Int): Int = value }
            """,2);
    }
    @Test void cppVirtualPrototypesAndBodiesExcludeNonVirtualHiding(){
        verify("test.cpp","""
            class Base { public: virtual int run(int value)=0; int hidden(int value){return value;} };
            class Child : public Base { public: int run(int value) override {return value;} int hidden(int value){return value;} };
            """,1);
    }
}
