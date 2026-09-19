import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.source.tree.*;
import com.sun.source.util.*;
import javax.lang.model.element.*;
import javax.tools.*;
import java.nio.file.*;
import java.util.*;

/** Independent javac ground truth, used only AFTER evidence acquisition to grade navigation.
 * Does not generate class files or participate in the benchmark's stopping decisions.
 */
public class NavigationWorkflowOracle {
    public static void main(String[] args) throws Exception {
        Path root=Path.of(args[0]).toAbsolutePath().normalize();
        Path main=root.resolve("code-graph-dba/src/main/java");
        Set<String> targets=Set.of("NativeRedisArguments.bytes","NativeRedisArguments.validate",
                "NativeResults.add","NativeResults.finish","NativeResults.binary","NativeReadExecutor.key");
        var compiler=ToolProvider.getSystemJavaCompiler();
        var diagnostics=new DiagnosticCollector<JavaFileObject>();
        Map<String,Object> result=new LinkedHashMap<>();
        var references=new TreeMap<String,List<Map<String,Object>>>();
        var declarations=new TreeMap<String,List<Map<String,Object>>>();
        for(String t:targets) {references.put(t,new ArrayList<>());declarations.put(t,new ArrayList<>());}
        try(var manager=compiler.getStandardFileManager(diagnostics,null,null);var files=Files.walk(main)) {
            var sources=manager.getJavaFileObjectsFromPaths(files.filter(p->p.toString().endsWith(".java")).toList());
            var task=(JavacTask)compiler.getTask(null,manager,diagnostics,
                    List.of("-proc:none","-classpath",System.getProperty("java.class.path")),null,sources);
            var units=new ArrayList<CompilationUnitTree>();
            task.parse().forEach(units::add);
            task.analyze();
            var trees=Trees.instance(task);
            for(var unit:units) {
                String path=root.relativize(Path.of(unit.getSourceFile().toUri())).toString().replace('\\','/');
                new TreePathScanner<Void,Void>() {
                    String caller;
                    String key(Element element) {
                        if(!(element instanceof ExecutableElement method))return "";
                        return method.getEnclosingElement().getSimpleName()+"."+method.getSimpleName();
                    }
                    Map<String,Object> position(Tree tree) {
                        long start=trees.getSourcePositions().getStartPosition(unit,tree);
                        long end=trees.getSourcePositions().getEndPosition(unit,tree);
                        var p=new LinkedHashMap<String,Object>();
                        p.put("path",path);p.put("line",unit.getLineMap().getLineNumber(start));
                        p.put("column",unit.getLineMap().getColumnNumber(start));
                        p.put("endLine",unit.getLineMap().getLineNumber(Math.max(start,end-1)));
                        p.put("caller",caller);
                        return p;
                    }
                    @Override public Void visitMethod(MethodTree node,Void unused) {
                        String previous=caller;
                        Element e=trees.getElement(getCurrentPath());caller=key(e);
                        if(targets.contains(caller)) {
                            var p=position(node);p.put("signature",e.toString());
                            declarations.get(caller).add(p);
                        }
                        super.visitMethod(node,unused);caller=previous;return null;
                    }
                    @Override public Void visitMethodInvocation(MethodInvocationTree node,Void unused) {
                        String target=key(trees.getElement(getCurrentPath()));
                        if(targets.contains(target)) {
                            var p=position(node);
                            long selectEnd=trees.getSourcePositions().getEndPosition(unit,node.getMethodSelect());
                            String name=target.substring(target.lastIndexOf('.')+1);
                            long identifier=selectEnd-name.length();
                            p.put("identifierLine",unit.getLineMap().getLineNumber(identifier));
                            p.put("identifierColumn",unit.getLineMap().getColumnNumber(identifier));
                            references.get(target).add(p);
                        }
                        return super.visitMethodInvocation(node,unused);
                    }
                }.scan(unit,null);
            }
        }
        var errors=diagnostics.getDiagnostics().stream().filter(d->d.getKind()==Diagnostic.Kind.ERROR)
                .map(d->d.getMessage(Locale.ROOT)).toList();
        result.put("method","Independent JDK javac semantic attribution; production DBA Java sources only");
        result.put("root",root.toString());result.put("compiler",Runtime.version().toString());
        result.put("errors",errors);result.put("references",references);result.put("declarations",declarations);
        var mapper=new ObjectMapper();
        mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of(args[1]).toFile(),result);
        System.out.println(mapper.writeValueAsString(Map.of("errors",errors.size(),"referenceCounts",
                references.entrySet().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,e->e.getValue().size())))));
        if(!errors.isEmpty())System.exit(1);
    }
}
