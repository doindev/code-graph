package io.doindev.codegraph.lang.javascript;

import io.doindev.codegraph.model.*;
import io.doindev.codegraph.parse.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EcmaMethodRelationshipsTest {
    FileFragment parse(String path,String source){
        LanguageAnalyzer analyzer=path.endsWith(".ts")?new TypeScriptAnalyzer():new JavaScriptAnalyzer();
        return analyzer.extract(new SourceFile(path,analyzer.languageId(),source));
    }
    List<Edge> resolve(FileFragment... files){
        var lookup=SymbolTable.of(List.of(files));return Arrays.stream(files).flatMap(f->NameResolver.resolve(f,lookup,new ArrayList<>()).stream()).filter(e->e.kind()==EdgeKind.OVERRIDES).toList();
    }
    @Test void explicitClassAncestryExcludesStaticPrivateAccessorsAndUnrelatedNames(){
        var file=parse("test.js","""
            class Base { run(value){} static stat(){} #private(){} get prop(){return 1;} }
            class Mid extends Base {}
            class Child extends Mid { run(value, extra){} static stat(){} #private(){} get prop(){return 2;} }
            class Other { run(value){} }
            """);
        var edges=resolve(file);assertEquals(1,edges.size(),edges.toString());
        assertTrue(edges.getFirst().from().value().contains("Child.run"));
        assertTrue(edges.getFirst().to().value().contains("Base.run"));assertEquals(.9f,edges.getFirst().confidence());
    }
    @Test void typeScriptInterfacesUseDeclaredParameterAndReturnTypes(){
        var file=parse("test.ts","""
            interface I { run(value: string): number; }
            class Correct implements I { run(value: string): number {return 1;} }
            class Wrong implements I { run(value: number): number {return 1;} }
            class Uncertain implements I { run(value: string) {return 1;} }
            """);
        var edges=resolve(file);assertEquals(1,edges.size(),edges.toString());
        assertTrue(edges.getFirst().from().value().contains("Correct.run"));
        assertEquals("interface_method",edges.getFirst().attrs().get("implementationKind"));
    }
    @Test void importedAliasesSelectTheCorrectParentAndNeverFallback(){
        var a=parse("a.js","export class Base { run(){} }");
        var b=parse("b.js","export class Base { run(){} }");
        var use=parse("use.js","import {Base as Parent} from './a.js'; class Child extends Parent { run(){} }");
        var broken=parse("missing.js","import {Base} from './absent.js'; class Missing extends Base { run(){} }");
        var edges=resolve(a,b,use,broken);assertEquals(1,edges.size(),edges.toString());
        assertEquals("a.js",((SymbolId)edges.getFirst().to()).relPath());
    }
    @Test void namespacesGenericArgumentsAndDynamicBasesRemainBounded(){
        var a=parse("a.ts","export interface I { run(value: string): number; }");
        var b=parse("b.ts","import * as ns from './a.ts'; class C implements ns.I {run(value: string): number{return 1;}}");
        var generic=parse("g.ts","interface I<T> {run(value: T): T;} class C implements I<string>{run(value: string): string{return value;}}");
        assertEquals(1,resolve(a,b).size());assertTrue(resolve(generic).isEmpty());
        assertTrue(resolve(parse("d.js","class Base{run(){}} class Child extends factory(Base){run(){}}")).isEmpty());
    }
}
