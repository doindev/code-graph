package io.doindev.codegraph.index;

import io.doindev.codegraph.config.CodeGraphConfig;
import io.doindev.codegraph.model.*;
import io.doindev.codegraph.storage.GraphStorage;
import io.doindev.codegraph.query.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MethodPublicationTest {
    @TempDir Path root;
    Set<String> methods(GraphQuery graph){
        Set<String> found=new TreeSet<>();graph.scanNodes(Set.of(NodeKind.FUNCTION),node->graph.scanEdges(node.id(),Direction.OUT,Set.of(EdgeKind.OVERRIDES),
            edge->found.add(edge.from().value()+" -> "+edge.to().value())));return found;
    }
    @Test void allAdditionalAdaptersShareIncrementalMemoryAndHybridPublication()throws Exception{
        var fixtures=Map.ofEntries(
            Map.entry("test.kt","interface I { fun run(x: Int): Int }\nclass C : I { override fun run(x: Int): Int { return x } }"),
            Map.entry("test.scala","trait I { def run(x: Int): Int }\nclass C extends I { def run(x: Int): Int = x }"),
            Map.entry("test.cs","interface I { int run(int x); } class C : I { public int run(int x){return x;} }"),
            Map.entry("test.cpp","class I {public: virtual int run(int x)=0;}; class C: public I {public: int run(int x) override {return x;}};"),
            Map.entry("test.swift","protocol I { func run(_ x: Int) -> Int } class C: I { func run(_ x: Int) -> Int {return x} }"),
            Map.entry("test.dart","abstract class I { int run(int x); } class C implements I { int run(int x){return x;} }"),
            Map.entry("test.m","@protocol I\n- (int)run:(int)x;\n@end\n@interface C <I>\n- (int)run:(int)x;\n@end"),
            Map.entry("test.go","package p\ntype I interface { Run(x int) int }; type C struct{}; func (c C) Run(x int) int {return x}"),
            Map.entry("test.rs","trait I { fn run(&self,x:i32)->i32; } struct C; impl I for C {fn run(&self,x:i32)->i32{x}}"),
            Map.entry("test.py","class I:\n def run(self,x): return x\nclass C(I):\n def run(self,x): return x\n"),
            Map.entry("test.rb","module I\n def run(x); x; end\nend\nclass C\n include I\n def run(x); x; end\nend"),
            Map.entry("test.php","<?php trait I { public function run(int $x): int {return $x;} } class C {use I; public function run(int $x): int {return $x;} }"));
        Set<String> baseline=null;
        for(boolean hybrid:List.of(false,true)){
            for(var fixture:fixtures.entrySet())Files.writeString(root.resolve(fixture.getKey()),fixture.getValue());
            try(var storage=new GraphStorage(hybrid,32L<<20)){
                var graph=storage.create();var indexer=new IncrementalIndexer(root,Analyzers.discover(),CodeGraphConfig.defaults(),graph);
                indexer.fullIndex();var initial=methods(graph);assertEquals(fixtures.size(),initial.size(),initial.toString());
                if(baseline==null)baseline=initial;else assertEquals(baseline,initial);
                for(var fixture:fixtures.entrySet())Files.writeString(root.resolve(fixture.getKey()),fixture.getValue().replaceFirst("run|Run","renamed"));
                indexer.applyChanges(fixtures.keySet());assertTrue(methods(graph).isEmpty(),methods(graph).toString());
                for(var fixture:fixtures.entrySet())Files.writeString(root.resolve(fixture.getKey()),fixture.getValue());
                indexer.applyChanges(fixtures.keySet());assertEquals(initial,methods(graph));
                for(String file:fixtures.keySet())Files.delete(root.resolve(file));
                indexer.applyChanges(fixtures.keySet());assertTrue(methods(graph).isEmpty());
            }
        }
    }
    @Test void memoryAndHybridPublishEditsRemovalAndModuleRebindingTogether()throws Exception{
        Set<String> baseline=null;
        for(boolean hybrid:List.of(false,true)){
            Files.writeString(root.resolve("A.java"),"interface A { void run(int value); } class B implements A { public void run(int value){} }");
            Files.writeString(root.resolve("a.js"),"export class Parent { run(){} }");
            Files.writeString(root.resolve("b.js"),"export class Parent { run(){} }");
            Files.writeString(root.resolve("use.js"),"import {Parent} from './a.js'; class Child extends Parent {run(){}}");
            try(var storage=new GraphStorage(hybrid,32L<<20)){
                var graph=storage.create();var indexer=new IncrementalIndexer(root,Analyzers.discover(),CodeGraphConfig.defaults(),graph);
                indexer.fullIndex();Set<String> first=methods(graph);assertEquals(2,first.size(),first.toString());
                if(baseline==null)baseline=first;else assertEquals(baseline,first);
                long before=graph.generation();
                Files.writeString(root.resolve("use.js"),"import {Parent} from './b.js'; class Child extends Parent {run(){}}");
                Files.writeString(root.resolve("A.java"),"interface A { void run(String value); } class B implements A { public void run(int value){} }");
                indexer.applyChanges(List.of("use.js","A.java"));assertTrue(graph.generation()>before);
                Set<String> updated=methods(graph);assertEquals(1,updated.size(),updated.toString());
                assertTrue(updated.iterator().next().contains("-> js:b.js#Parent.run"));
                Files.delete(root.resolve("b.js"));indexer.applyChanges(List.of("b.js"));assertTrue(methods(graph).isEmpty());
            }
        }
    }
}
