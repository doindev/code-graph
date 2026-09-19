package io.doindev.codegraph.index;

import io.doindev.codegraph.config.CodeGraphConfig;
import io.doindev.codegraph.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ModuleConfigurationIntegrationTest {
    @TempDir Path root;
    private void write(String path,String source)throws Exception {
        Path file=root.resolve(path);Files.createDirectories(file.getParent());Files.writeString(file,source);
    }
    private List<Edge> calls(io.doindev.codegraph.store.ManagedGraph graph,String file) {
        return graph.edges(new FileId(file),Direction.OUT,Set.of(EdgeKind.CALLS));
    }
    @Test void aliasesPackagesAndConfigOnlyChangesAgreeAcrossStorageModes()throws Exception {
        for(boolean disk:List.of(false,true)) {
            write("package.json","{\"workspaces\":[\"packages/*\"]}");
            write("base.json","{\"compilerOptions\":{\"moduleResolution\":\"bundler\",\"baseUrl\":\".\",\"paths\":{\"@lib/*\":[\"lib/*\"]}}}");
            write("tsconfig.json","{\"extends\":\"./base.json\"}");
            write("main.ts","import {helper as renamed} from '@lib/right.js'; renamed();");
            write("lib/right.ts","export const helper = () => 1;");
            write("other/right.ts","export const helper = () => 2;");
            write("packages/tools/package.json","{\"name\":\"@local/tools\",\"exports\":{\".\":{\"import\":\"./api.js\",\"require\":\"./api.cjs\"}}}");
            write("packages/tools/api.js","export function go(){}");
            write("packages/tools/api.cjs","function go(){} exports.go=go;");
            write("package-use.js","import {go} from '@local/tools'; go();");
            write("require-use.cjs","const {go}=require('@local/tools'); go();");
            try(var workspace=Workspace.open(List.of(root),Analyzers.discover(),p->CodeGraphConfig.defaults(),disk,32L<<20)) {
                workspace.fullIndexAll();var project=workspace.defaultProject();var graph=project.graph();
                assertEquals("lib/right.ts",((SymbolId)calls(graph,"main.ts").getFirst().to()).relPath());
                assertEquals("packages/tools/api.js",((SymbolId)calls(graph,"package-use.js").getFirst().to()).relPath());
                assertEquals("packages/tools/api.cjs",((SymbolId)calls(graph,"require-use.cjs").getFirst().to()).relPath());
                long before=graph.status().generation();
                write("base.json","{\"compilerOptions\":{\"moduleResolution\":\"bundler\",\"baseUrl\":\".\",\"paths\":{\"@lib/*\":[\"other/*\"]}}}");
                project.indexer().applyChanges(List.of("base.json"));
                assertTrue(graph.status().generation()>before);
                assertEquals("other/right.ts",((SymbolId)calls(graph,"main.ts").getFirst().to()).relPath());
                write("packages/tools/package.json","{\"name\":\"@local/tools\",\"exports\":{\".\":{\"browser\":\"./api.js\",\"default\":\"./api.cjs\"}}}");
                project.indexer().applyChanges(List.of("packages/tools/package.json"));
                assertTrue(calls(graph,"package-use.js").isEmpty(),"Unknown conditions cannot guess");
                write("other/right.ts","export function notHelper(){}");
                project.indexer().applyChanges(List.of("other/right.ts"));
                assertTrue(calls(graph,"main.ts").isEmpty());
            }
        }
    }
    @Test void nearestConfigOverridesOuterAndNodeEsmDoesNotGuessExtensionlessPaths()throws Exception {
        write("package.json","{\"type\":\"module\"}");
        write("tsconfig.json","{\"compilerOptions\":{\"moduleResolution\":\"nodenext\"}}");
        write("strict.ts","import {helper} from './lib/right'; helper();");
        write("lib/right.ts","export function helper(){}");
        write("app/tsconfig.json","{\"compilerOptions\":{\"moduleResolution\":\"bundler\"}}");
        write("app/use.ts","import {helper} from '../lib/right'; helper();");
        try(var workspace=Workspace.open(List.of(root),Analyzers.discover(),p->CodeGraphConfig.defaults())) {
            workspace.fullIndexAll();var graph=workspace.defaultProject().graph();
            assertTrue(calls(graph,"strict.ts").isEmpty());
            assertEquals(1,calls(graph,"app/use.ts").size());
        }
    }
    @Test void fragmentCodecPreservesModuleAndPendingEvidence() {
        var fragment=new io.doindev.codegraph.lang.javascript.JavaScriptAnalyzer().extract(
                new io.doindev.codegraph.parse.SourceFile("main.js","js","import {x as y} from './x.js'; y();"));
        assertEquals(fragment,io.doindev.codegraph.storage.RecordCodec.decode(
                io.doindev.codegraph.storage.RecordCodec.encode(new io.doindev.codegraph.storage.RecordCodec.F(fragment)),
                io.doindev.codegraph.storage.RecordCodec.F.class).fragment());
    }
    @Test void configurationBoundsCyclesAndFileMovesStayGenerationConsistent()throws Exception {
        for(boolean disk:List.of(false,true)) {
            write("tsconfig.json","{\"compilerOptions\":{\"moduleResolution\":\"bundler\",\"paths\":{\"@api\":[\"./target.ts\"]}}}");
            write("main.ts","import {helper} from '@api'; helper();");
            write("target.ts","export function helper(){}");
            try(var workspace=Workspace.open(List.of(root),Analyzers.discover(),p->CodeGraphConfig.defaults(),disk,32L<<20)) {
                workspace.fullIndexAll();var project=workspace.defaultProject();var graph=project.graph();
                assertEquals(1,calls(graph,"main.ts").size());
                Files.move(root.resolve("target.ts"),root.resolve("moved.ts"),StandardCopyOption.REPLACE_EXISTING);
                project.indexer().applyChanges(List.of("target.ts","moved.ts"));
                assertTrue(calls(graph,"main.ts").isEmpty());
                write("tsconfig.json","{\"compilerOptions\":{\"moduleResolution\":\"bundler\",\"paths\":{\"@api\":[\"./moved.ts\"]}}}");
                project.indexer().applyChanges(List.of("tsconfig.json"));
                assertEquals("moved.ts",((SymbolId)calls(graph,"main.ts").getFirst().to()).relPath());
                write("tsconfig.json","{\"extends\":\"./cycle.json\"}");
                write("cycle.json","{\"extends\":\"./tsconfig.json\"}");
                project.indexer().applyChanges(List.of("cycle.json","tsconfig.json"));
                assertTrue(calls(graph,"main.ts").isEmpty());
                write("tsconfig.json"," ".repeat((1<<20)+1));
                project.indexer().applyChanges(List.of("tsconfig.json"));
                assertTrue(calls(graph,"main.ts").isEmpty());
            }
        }
    }
    @Test void runtimeConditionsAndCommonJsExtensionsDoNotBorrowBundlerRules()throws Exception {
        write("package.json","{\"name\":\"self\",\"exports\":{\".\":{\"node\":\"./node.js\",\"default\":\"./browser.js\"}}}");
        write("node.js","export function helper(){}");write("browser.js","export function helper(){}");
        write("tsconfig.json","{\"compilerOptions\":{\"moduleResolution\":\"bundler\"}}");
        write("main.ts","import {helper} from 'self'; helper();");
        write("nested/package.json","{}");
        write("nested/jsconfig.json","{\"compilerOptions\":{\"moduleResolution\":\"node\"}}");
        write("nested/main.cjs","const {helper}=require('./only'); helper();");
        write("nested/only.cjs","function helper(){} exports.helper=helper;");
        try(var workspace=Workspace.open(List.of(root),Analyzers.discover(),p->CodeGraphConfig.defaults())) {
            workspace.fullIndexAll();var graph=workspace.defaultProject().graph();
            assertEquals("browser.js",((SymbolId)calls(graph,"main.ts").getFirst().to()).relPath());
            assertTrue(calls(graph,"nested/main.cjs").isEmpty());
        }
    }
    @Test void blockedNestedExportsAndInvalidMixedMappingsCannotSelectFallbacks()throws Exception {
        write("main.js","import {helper} from 'self'; helper();");
        write("target.js","export function helper(){}");
        for(String exports:List.of("{\"import\":{\"default\":null},\"default\":\"./target.js\"}",
                "{\".\":\"./target.js\",\"default\":\"./target.js\"}")) {
            write("package.json","{\"name\":\"self\",\"exports\":"+exports+"}");
            try(var workspace=Workspace.open(List.of(root),Analyzers.discover(),p->CodeGraphConfig.defaults())) {
                workspace.fullIndexAll();assertTrue(calls(workspace.defaultProject().graph(),"main.js").isEmpty());
            }
        }
    }
    @Test void packageImportsAndCandidateProbeBudgetAgreeAcrossStores()throws Exception {
        for(boolean disk:List.of(false,true)) {
            write("package.json","{\"imports\":{\"#tools/*\":\"./lib/*.js\"}}");
            write("lib/right.js","export function helper(){}");
            write("main.js","import {helper as call} from '#tools/right'; call();");
            write("tsconfig.json","{\"compilerOptions\":{\"moduleResolution\":\"bundler\"}}");
            try(var workspace=Workspace.open(List.of(root),Analyzers.discover(),p->CodeGraphConfig.defaults(),disk,32L<<20)) {
                workspace.fullIndexAll();var project=workspace.defaultProject();var graph=project.graph();
                assertEquals("lib/right.js",((SymbolId)calls(graph,"main.js").getFirst().to()).relPath());
                var targets=new ArrayList<String>();for(int i=0;i<80;i++)targets.add("\"./missing"+i+"\"");
                write("tsconfig.json","{\"compilerOptions\":{\"moduleResolution\":\"bundler\",\"paths\":{\"@api\":["+String.join(",",targets)+"]}}}");
                write("main.js","import {helper} from '@api'; helper();");
                project.indexer().applyChanges(List.of("tsconfig.json","main.js"));
                assertTrue(calls(graph,"main.js").isEmpty());
                assertTrue(graph.node(new FileId("main.js")).orElseThrow().attrs().toString().contains("work_budget_exhausted"));
            }
        }
    }
}
