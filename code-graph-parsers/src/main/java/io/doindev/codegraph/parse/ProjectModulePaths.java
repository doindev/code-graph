package io.doindev.codegraph.parse;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;

/** Bounded subset of Node/TypeScript paths. Unsupported conditions/configuration fail closed. */
public final class ProjectModulePaths {
    private final SymbolLookup lookup;
    private final Function<String,ModuleFile> probe;
    private int configurationReads;
    private boolean nodeConditions = true;
    private ProjectModulePaths(SymbolLookup lookup,Function<String,ModuleFile> probe) {this.lookup=lookup;this.probe=probe;}
    public static String resolve(SymbolLookup lookup,String source,String specifier,String kind,Function<String,ModuleFile> probe) {
        return new ProjectModulePaths(lookup,probe).resolve(source,specifier,kind);
    }
    private JsonNode config(String path) {
        if(++configurationReads>256)throw new IllegalArgumentException("Module configuration probe budget exhausted");
        JsonNode result=lookup.configuration(path);
        if(result!=null&&result.path("invalidModuleConfiguration").asBoolean())throw new IllegalArgumentException("Invalid or oversized module configuration");
        return result;
    }
    private String nearest(String source,String filename) {
        String dir=directory(source);
        for(int hops=0;hops<32;hops++) {
            String path=dir+filename;if(config(path)!=null)return path;
            if(dir.isEmpty())return null;dir=directory(dir.substring(0,dir.length()-1));
        }
        throw new IllegalArgumentException("Configuration parent traversal exceeds 32 hops");
    }
    private record Options(String mode,String base,JsonNode paths,String pathsBase,boolean typescript) {}
    private Options options(String file,Set<String> visited,int depth) {
        if(depth>=32||!visited.add(file))throw new IllegalArgumentException("Cyclic or excessive configuration inheritance");
        JsonNode json=config(file);
        if(json==null)throw new IllegalArgumentException("Missing local inherited configuration");
        Options inherited=new Options("","",null,directory(file),false);
        if(json.has("extends")) {
            JsonNode parent=json.get("extends");
            if(!parent.isTextual()||!parent.asText().startsWith("."))throw new IllegalArgumentException("Only local configuration inheritance is supported");
            String target=relative(file,parent.asText());
            if(target==null)throw new IllegalArgumentException("Configuration outside indexed root");
            if(!target.endsWith(".json"))target+=".json";
            inherited=options(target,visited,depth+1);
        }
        JsonNode compiler=json.path("compilerOptions");
        for(String unsupported:List.of("rootDirs","moduleSuffixes","customConditions"))
            if(compiler.has(unsupported))throw new IllegalArgumentException("Unsupported module configuration: "+unsupported);
        String mode=compiler.path("moduleResolution").asText(inherited.mode()).toLowerCase(Locale.ROOT);
        if(mode.equals("node"))mode="node10";
        if(mode.isEmpty()) {
            String module=compiler.path("module").asText("").toLowerCase(Locale.ROOT);
            mode=switch(module){case "node16","node18","node20","nodenext"->"nodenext";case "commonjs"->"node10";default->"unconfigured";};
        }
        String base=compiler.has("baseUrl")?normalize(directory(file)+compiler.path("baseUrl").asText()):inherited.base();
        if(compiler.has("baseUrl")&&base.isEmpty())base=".";
        JsonNode paths=compiler.has("paths")?compiler.get("paths"):inherited.paths();
        String pathsBase=compiler.has("paths")?(base.isEmpty()?directory(file):base+"/"):inherited.pathsBase();
        return new Options(mode,base,paths,pathsBase,file.endsWith("tsconfig.json"));
    }
    private String resolve(String source,String specifier,String kind) {
        if(specifier.isBlank()||specifier.contains("\\")||specifier.contains("?")||specifier.contains("#")&&!specifier.startsWith("#"))return null;
        String ts=nearest(source,"tsconfig.json"),js=nearest(source,"jsconfig.json");
        String selected=ts==null?js:js==null?ts:directory(ts).length()>=directory(js).length()?ts:js;
        Options options=selected==null?new Options("node","",null,"",false):options(selected,new HashSet<>(),0);
        nodeConditions=!options.mode().equals("bundler");
        if(!Set.of("node","node10","node16","nodenext","bundler","unconfigured").contains(options.mode()))
            throw new IllegalArgumentException("Unsupported moduleResolution");
        String owner=nearest(source,"package.json");
        boolean common=kind.startsWith("commonjs")||source.endsWith(".cts")||source.endsWith(".cjs");
        if((options.mode().equals("node16")||options.mode().equals("nodenext"))&&!source.endsWith(".mts")&&!source.endsWith(".mjs"))
            common |= owner==null||!config(owner).path("type").asText().equals("module");
        boolean implicit=common||options.mode().equals("bundler")||options.mode().equals("node10");
        boolean tsSource=source.endsWith(".ts")||source.endsWith(".tsx")||source.endsWith(".mts")||source.endsWith(".cts")||selected!=null;
        String relative=relative(source,specifier);
        if(relative!=null)return file(relative,implicit,tsSource);
        if(specifier.startsWith("."))return null;
        if(specifier.startsWith("#")) {
            if(owner==null)return null;
            String target=mapping(config(owner).path("imports"),specifier,common,0);
            if(!packageTarget(target))return null;
            return file(relative(owner,target),false,tsSource);
        }
        if(options.paths()!=null) {
            String pattern=pattern(options.paths(),specifier);
            if(pattern!=null) {
                JsonNode targets=options.paths().get(pattern);
                if(!targets.isArray())throw new IllegalArgumentException("paths mappings must be arrays");
                String capture=capture(pattern,specifier);
                for(JsonNode target:targets) {
                    if(!target.isTextual())throw new IllegalArgumentException("Invalid paths target");
                    String path=normalize(options.pathsBase()+target.asText().replace("*",capture));
                    String match=file(path,implicit,tsSource);if(match!=null)return match;
                }
                return null;
            }
        }
        if(!options.base().isEmpty()) {
            String match=file(normalize(options.base()+"/"+specifier),implicit,tsSource);if(match!=null)return match;
        }
        String[] parts=specifier.split("/");
        String name=specifier.startsWith("@")&&parts.length>1?parts[0]+"/"+parts[1]:parts[0];
        String sub=specifier.length()==name.length()?".":"."+specifier.substring(name.length());
        List<String> packages=owner!=null&&config(owner).path("name").asText().equals(name)?List.of(owner):lookup.packageConfigurations(name);
        if(packages.size()!=1)return null;
        String pkg=packages.getFirst();
        if(!pkg.equals(owner)&&!workspacePackage(directory(pkg)))return null;
        JsonNode metadata=config(pkg);
        if(metadata==null)return null;
        if(metadata.has("exports")) {
            String target=mapping(metadata.get("exports"),sub,common,0);
            return !packageTarget(target)?null:file(relative(pkg,target),false,tsSource);
        }
        if(!sub.equals("."))return file(relative(pkg,sub),implicit,tsSource);
        String entry=metadata.path("main").asText("./index.js");
        if(!entry.startsWith("."))entry="./"+entry;
        return file(relative(pkg,entry),true,tsSource);
    }
    private boolean workspacePackage(String directory) {
        JsonNode root=config("package.json");if(root==null)return false;
        JsonNode workspaces=root.path("workspaces");if(workspaces.isObject())workspaces=workspaces.path("packages");
        if(!workspaces.isArray())return false;
        String path=directory.endsWith("/")?directory.substring(0,directory.length()-1):directory;
        for(JsonNode glob:workspaces)if(glob.isTextual()&&io.doindev.codegraph.util.Globs.matchesAny(List.of(glob.asText()),path))return true;
        return false;
    }
    private String file(String path,boolean implicit,boolean typescript) {
        if(path==null)return null;
        if(typescript)for(String[] extension:List.of(new String[]{".js",".ts",".tsx"},new String[]{".mjs",".mts"},new String[]{".cjs",".cts"},new String[]{".jsx",".tsx"})) {
            if(path.endsWith(extension[0]))for(int i=1;i<extension.length;i++) {
                String alternate=path.substring(0,path.length()-extension[0].length())+extension[i];
                if(probe.apply(alternate)!=null)return alternate;
            }
        }
        if(probe.apply(path)!=null)return path;
        if(implicit) {
            for(String suffix:typescript?List.of(".ts",".tsx",".js",".jsx"):List.of(".js"))
                if(probe.apply(path+suffix)!=null)return path+suffix;
            JsonNode pkg=config(path+"/package.json");
            if(pkg!=null&&pkg.has("main")) {
                String target=relative(path+"/package.json","./"+pkg.path("main").asText());
                if(target!=null&&probe.apply(target)!=null)return target;
            }
            for(String suffix:typescript?List.of("/index.ts","/index.tsx","/index.js"):List.of("/index.js"))
                if(probe.apply(path+suffix)!=null)return path+suffix;
        }
        return null;
    }
    private String mapping(JsonNode node,String key,boolean common,int depth) {
        if(depth>=32)throw new IllegalArgumentException("Export conditions exceed 32 hops");
        if(node==null||node.isNull()||node.isMissingNode())return null;
        if(node.isTextual())return key.equals(".")?node.asText():null;
        if(!node.isObject())throw new IllegalArgumentException("Unsupported export array or syntax");
        boolean subpaths=false,conditionsPresent=false;
        for(var fields=node.fieldNames();fields.hasNext();) {
            String field=fields.next();
            if(field.startsWith(".")||field.startsWith("#"))subpaths=true;else conditionsPresent=true;
        }
        if(subpaths&&conditionsPresent)throw new IllegalArgumentException("Mixed export subpaths and conditions");
        String pattern=pattern(node,key);
        if(pattern!=null) {
            String target=condition(node.get(pattern),common,depth+1);
            return target==null?null:target.replace("*",capture(pattern,key));
        }
        boolean conditions=true;for(var fields=node.fieldNames();fields.hasNext();)if(fields.next().startsWith("."))conditions=false;
        return conditions&&key.equals(".")?condition(node,common,depth+1):null;
    }
    private String condition(JsonNode node,boolean common,int depth) {
        if(depth>=32)throw new IllegalArgumentException("Export conditions exceed 32 hops");
        if(node.isTextual())return node.asText();
        if(node.isNull())return null;
        if(!node.isObject())throw new IllegalArgumentException("Unsupported export condition");
        for(var fields=node.fields();fields.hasNext();) {
            var field=fields.next();String key=field.getKey();
            if(key.equals("types"))continue;
            if(!Set.of("import","require","node","default").contains(key))throw new IllegalArgumentException("Unsupported export condition: "+key);
            if(key.equals("default")||key.equals("node")&&nodeConditions||key.equals(common?"require":"import")) {
                // Preserve blocked/null nested branches. Do not guess a fallback when a
                // selected nested condition cannot identify an implementation.
                return condition(field.getValue(),common,depth+1);
            }
        }
        return null;
    }
    private static String pattern(JsonNode object,String name) {
        if(!object.isObject())return null;if(object.has(name))return name;
        String best=null;for(var keys=object.fieldNames();keys.hasNext();) {
            String key=keys.next();int star=key.indexOf('*');
            if(star<0||key.indexOf('*',star+1)>=0)continue;
            if(name.startsWith(key.substring(0,star))&&name.endsWith(key.substring(star+1))&&name.length()>=key.length()-1
                    &&(best==null||star>best.indexOf('*')||star==best.indexOf('*')&&key.length()>best.length()))best=key;
        }return best;
    }
    private static String capture(String pattern,String value) {
        int star=pattern.indexOf('*');return star<0?"":value.substring(star,value.length()-(pattern.length()-star-1));
    }
    private static boolean packageTarget(String target) {
        return target!=null&&target.startsWith("./")&&!target.contains("\\")&&!target.contains("%")
                &&!target.contains("?")&&!target.contains("#")&&!Arrays.asList(target.substring(2).split("/")).contains("..")
                &&!Arrays.asList(target.split("/")).contains("node_modules");
    }
    private static String directory(String path) {int at=path.lastIndexOf('/');return at<0?"":path.substring(0,at+1);}
    private static String relative(String source,String path) {return path.startsWith(".")?normalize(directory(source)+path):null;}
    private static String normalize(String path) {
        String result=Path.of(path).normalize().toString().replace('\\','/');
        if(result.startsWith("../")||result.equals("..")||result.startsWith("/")||result.contains(":"))throw new IllegalArgumentException("Target outside indexed root");
        return result;
    }
}
