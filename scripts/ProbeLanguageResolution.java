import io.doindev.codegraph.config.CodeGraphConfig;
import io.doindev.codegraph.index.Analyzers;
import io.doindev.codegraph.index.Workspace;
import io.doindev.codegraph.model.*;
import java.nio.file.*;
import java.util.*;

/** Reproduce unresolved module/alias coverage separately from cross-language isolation.
 * Source-launch with the packaged server's lib/* classpath. Uses only owned temp fixtures. */
class ProbeLanguageResolution {
    public static void main(String[] args) throws Exception {
        Path root=Files.createTempDirectory("cgraph-module-probe-");
        try {
            Files.createDirectories(root.resolve("lib"));
            Files.createDirectories(root.resolve("ui"));
            Files.writeString(root.resolve("lib/right.js"),"export function helper(n){}");
            Files.writeString(root.resolve("lib/wrong.js"),"export function helper(n){} export function renamed(n){}");
            Files.writeString(root.resolve("ui/use.js"),
                    "import { helper } from '../lib/right'; function caller(){helper(1);}");
            Files.writeString(root.resolve("ui/alias.js"),
                    "import { helper as renamed } from '../lib/right'; function aliased(){renamed(1);}");
            try(var workspace=Workspace.open(List.of(root),Analyzers.discover(),p->CodeGraphConfig.defaults())) {
                workspace.fullIndexAll();
                var graph=workspace.defaultProject().graph();
                for(var caller:List.of(new SymbolId("js","ui/use.js","caller",0),
                        new SymbolId("js","ui/alias.js","aliased",0))) {
                    System.out.println("source="+caller.value()+" expected=js:lib/right.js#helper/1");
                    for(var edge:graph.edges(caller,Direction.OUT,Set.of(EdgeKind.CALLS)))
                        System.out.println("actual="+edge.to().value()+" confidence="+edge.confidence()+" evidence="+edge.attrs());
                }
            }
        } finally {
            // root was created by this process; no user source/cache paths are removed.
            try(var paths=Files.walk(root)) {
                for(var p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);
            }
        }
    }
}
