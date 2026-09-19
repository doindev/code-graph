package io.doindev.codegraph.lang.javascript;

import io.doindev.codegraph.parse.*;
import io.doindev.codegraph.model.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ModuleResolutionTest {
    private FileFragment js(String path,String source) {
        return new JavaScriptAnalyzer().extract(new SourceFile(path,"js",source));
    }
    private List<Edge> resolve(FileFragment input,FileFragment...other) {
        var files=new ArrayList<>(List.of(other));files.add(input);
        return NameResolver.resolve(input,SymbolTable.of(files),new ArrayList<>());
    }
    @Test void aliasedImportNeverResolvesToSameNamedUnrelatedExport() {
        var input=js("main.js","import {helper as renamed} from './right.js'; function run(){renamed();}");
        var right=js("right.js","export function helper(){}");
        var wrong=js("wrong.js","export function renamed(){}");
        var edges=resolve(input,right,wrong);
        assertEquals(1,edges.size(),input.modules().toString());
        assertEquals("right.js",((SymbolId)edges.getFirst().to()).relPath());
        assertEquals("helper",((SymbolId)edges.getFirst().to()).qualifiedName());
        assertEquals("resolved",edges.getFirst().attrs().get("resolutionStatus"));
        assertEquals(1f,edges.getFirst().confidence());
    }
    @Test void missingImportsDoNotFallBackAndHavePendingReason() {
        var input=js("main.js","import {helper} from './missing.js'; helper();");
        var pending=new ArrayList<RawRef>();
        var edges=NameResolver.resolve(input,SymbolTable.of(List.of(input,js("wrong.js","export function helper(){}"))),pending);
        assertTrue(edges.isEmpty());assertEquals("external_or_unindexed",pending.getFirst().resolutionStatus());
    }
    @Test void defaultsNamespacesBarrelsAndArrowFunctionsResolve() {
        var target=js("target.js","export const helper = (n) => n; export default function main(){}");
        var barrel=js("barrel.js","export {default as start} from './target.js'; export * from './target.js';");
        var input=js("main.js","import * as api from './barrel.js'; api.helper(1); api.start();");
        var edges=resolve(input,target,barrel);
        assertEquals(2,edges.size(),target.modules()+" / "+barrel.modules());
        assertTrue(edges.stream().allMatch(e->((SymbolId)e.to()).relPath().equals("target.js")));
    }
    @Test void parametersShadowImportsWithoutInventingBindings() {
        var input=js("main.js","import {helper} from './target.js'; function run(helper){helper();}");
        var pending=new ArrayList<RawRef>();
        assertTrue(NameResolver.resolve(input,SymbolTable.of(List.of(input,js("target.js","export function helper(){}"))),pending).isEmpty());
        assertEquals("shadowed_binding",pending.getFirst().resolutionStatus());
    }
    @Test void commonJsNamedAndDestructuredAliasesResolve() {
        var target=js("target.cjs","function helper(){} exports.helper=helper;");
        var input=js("main.cjs","const {helper: renamed}=require('./target.cjs'); renamed();");
        var edges=resolve(input,target);
        assertEquals(1,edges.size(),input.modules().toString());
        assertEquals("target.cjs",((SymbolId)edges.getFirst().to()).relPath());
    }
    @Test void typeOnlyAndCyclicExportsStayUnresolved() {
        var input=new TypeScriptAnalyzer().extract(new SourceFile("main.ts","ts","import type {helper} from './target.ts'; helper();"));
        assertTrue(resolve(input,js("target.ts","export function helper(){}")).isEmpty());
        var cyclic=js("main.js","import {helper} from './a.js'; helper();");
        assertTrue(resolve(cyclic,js("a.js","export * from './b.js';"),js("b.js","export * from './a.js';")).isEmpty());
    }
    @Test void dynamicExportsShadowedRequireAndDerivedImportsCannotAcquireExactBinding() {
        var target=js("target.cjs","function helper(){} if(flag) exports.helper=helper;");
        var input=js("main.cjs","const api=require('./target.cjs'); api.helper();");
        assertTrue(resolve(input,target).isEmpty());
        var shadowed=js("shadow.cjs","function run(require){const {helper}=require('./target.cjs'); helper();}");
        assertTrue(resolve(shadowed,js("target.cjs","function helper(){} exports.helper=helper;")).isEmpty());
        var derived=js("derived.js","const helper=(await import('./target.js')).helper; helper();");
        assertTrue(resolve(derived,js("target.js","export function helper(){}")).isEmpty());
    }
    @Test void unrelatedStarExportsDoNotHideAUniqueExportButDuplicateExportsStayAmbiguous() {
        var input=js("main.js","import {helper} from './barrel.js'; helper();");
        var barrel=js("barrel.js","export * from './one.js'; export * from './two.js';");
        var one=js("one.js","export function helper(){}");
        var unique=resolve(input,barrel,one,js("two.js","export function other(){}"));
        assertEquals(1,unique.size());assertEquals(1f,unique.getFirst().confidence());
        var ambiguous=resolve(input,barrel,one,js("two.js","export function helper(){}"));
        assertEquals(2,ambiguous.size());assertTrue(ambiguous.stream().allMatch(e->e.confidence()<1));
    }
    @Test void singleArrowParameterAndMutableBindingsStayUnresolved() {
        var target=js("target.js","export function helper(){}");
        assertTrue(resolve(js("main.js","import {helper} from './target.js'; const f=helper=>helper();"),target).isEmpty());
        assertTrue(resolve(js("main.cjs","let api=require('./target.cjs'); api=other; api.helper();"),
                js("target.cjs","function helper(){} exports.helper=helper;")).isEmpty());
        for(String body:List.of("exports.helper=make();","exports.helper=helper; Object.assign(exports,other);",
                "module.exports=helper; module.exports=other;","exports.api.helper=helper;")) {
            assertTrue(resolve(js("main.cjs","const api=require('./target.cjs'); api.helper();"),
                    js("target.cjs","function helper(){} "+body)).isEmpty(),body);
        }
    }
    @Test void commonJsVarIsFunctionScopedButOutOfScopeLetCannotFallBack() {
        var target=js("target.cjs","function helper(){} exports.helper=helper;");
        assertEquals(1,resolve(js("main.cjs","function run(){if(flag){var {helper}=require('./target.cjs');} helper();}"),target).size());
        var invalid=js("main.cjs","if(flag){let {helper}=require('./target.cjs');} helper();");
        assertTrue(resolve(invalid,target,js("other.cjs","function helper(){}")).isEmpty());
    }
    @Test void excessiveReexportDepthReportsBudgetRatherThanGuessing() {
        var files=new ArrayList<FileFragment>();
        for(int i=0;i<34;i++)files.add(js("a"+i+".js","export * from './a"+(i+1)+".js';"));
        files.add(js("a34.js","export function helper(){}"));
        var input=js("main.js","import {helper} from './a0.js'; helper();");files.add(input);
        var pending=new ArrayList<RawRef>();
        assertTrue(NameResolver.resolve(input,SymbolTable.of(files),pending).isEmpty());
        assertEquals("work_budget_exhausted",pending.getFirst().resolutionStatus());
    }
}
