package io.doindev.codegraph.parse;

import io.doindev.codegraph.model.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NameResolverLanguageTest {
    static FileFragment declaration(String lang,String path,String name) {
        String simple=name.substring(name.lastIndexOf('.')+1);
        var node=new Node(new SymbolId(lang,path,name,1),NodeKind.FUNCTION,simple,simple+"(x)",
                new SourceSpan(path,1,1,1,8),Metrics.NONE,Map.of());
        return new FileFragment(new FileId(path),lang,"hash",List.of(node),List.of(),List.of(),List.of());
    }
    static FileFragment caller(String lang,String path,List<String> imports) {
        return new FileFragment(new FileId(path),lang,"hash",List.of(),List.of(),
                List.of(new RawRef(new FileId(path),RefKind.CALL,"run",null,1,
                        new SourceSpan(path,2,1,2,6))),imports);
    }
    static List<Edge> resolve(FileFragment source,FileFragment... declarations) {
        return NameResolver.resolve(source,SymbolTable.of(List.of(declarations)),new ArrayList<>());
    }
    @Test void unrelatedGlobalNamesRemainPendingIncludingFileLevelCalls() {
        for(String source:List.of("js","ts","py","c","cpp","unknown")) {
            var pending=new ArrayList<RawRef>();
            var edges=NameResolver.resolve(caller(source,"client/use.txt",List.of()),
                    SymbolTable.of(List.of(declaration("java","server/Service.java","Service.run"))),pending);
            assertTrue(edges.isEmpty(),source);
            assertEquals(1,pending.size(),source);
        }
    }
    @Test void qualifiedImportCannotBindAnUnrelatedLanguage() {
        assertTrue(resolve(caller("js","ui/use.js",List.of("Service.run")),
                declaration("java","server/Service.java","Service.run")).isEmpty());
    }
    @Test void sameDirectoryDoesNotOutrankCompatibleLanguageElsewhere() {
        var good=declaration("js","lib/good.js","run");
        assertEquals(good.declarations().getFirst().id(),resolve(caller("js","ui/use.js",List.of()),
                declaration("java","ui/Wrong.java","Wrong.run"),good).getFirst().to());
    }
    @Test void samePathDoesNotPermitForeignSymbolsFromMixedLanguageFragments() {
        assertTrue(resolve(caller("js","mixed/page",List.of()),
                declaration("java","mixed/page","Wrong.run")).isEmpty());
    }
    @Test void javascriptAndTypescriptShareAResolutionFamilyInBothDirections() {
        for(var pair:List.of(List.of("js","ts"),List.of("ts","js"))) {
            var good=declaration(pair.get(1),"lib/helper."+pair.get(1),"run");
            var edges=resolve(caller(pair.get(0),"ui/use."+pair.get(0),List.of()),good);
            assertEquals(1,edges.size());
            assertEquals(good.declarations().getFirst().id(),edges.getFirst().to());
            assertEquals(.8f,edges.getFirst().confidence());
        }
    }
    @Test void familyAmbiguityIsNotHiddenByOneSameLanguageCandidateOrForeignCandidateLimit() {
        var declarations=new ArrayList<FileFragment>();
        for(int i=0;i<20;i++)declarations.add(declaration("java","foreign/"+i+".java","Foreign"+i+".run"));
        declarations.add(declaration("js","lib/one.js","One.run"));
        declarations.add(declaration("ts","lib/two.ts","Two.run"));
        var edges=NameResolver.resolve(caller("js","ui/use.js",List.of()),SymbolTable.of(declarations),new ArrayList<>());
        assertEquals(2,edges.size());
        assertTrue(edges.stream().allMatch(e->Set.of("js","ts").contains(((SymbolId)e.to()).lang())));
        assertTrue(edges.stream().allMatch(e->e.confidence()<.5f));
    }
    @Test void unknownLanguageIdsAreIsolatedAndNeverBecomeWildcardScopes() {
        var good=declaration("custom","lib/good.custom","run");
        assertEquals(1,resolve(caller("custom","use.custom",List.of()),good).size());
        assertTrue(resolve(caller("other","use.other",List.of()),good).isEmpty());
        assertTrue(resolve(caller("shared:js-ts","use.custom",List.of()),
                declaration("js","lib/good.js","run")).isEmpty());
    }
    @Test void legacyNameOnlyImportsCannotEstablishExactModuleBindings() {
        var js=declaration("js","lib/a.js","Library.run");
        var ts=declaration("ts","lib/b.ts","Library.run");
        assertTrue(resolve(caller("js","ui/u.js",List.of("Library.run")),ts).isEmpty());
        assertTrue(resolve(caller("js","ui/u.js",List.of("Library.run")),js,ts).isEmpty());
    }
}
