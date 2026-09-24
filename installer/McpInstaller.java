import java.io.*;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

/** Optional local HTTP MCP registration. JDK-only; never starts a client/server or grants permissions. */
public final class McpInstaller {
    static final String DEFAULT_URL = "http://localhost:3000/mcp";
    static final List<String> CLIENTS = List.of("copilot", "copilot-vscode", "codex", "claude", "windsurf");
    static final Map<String,String> LABELS = Map.of("copilot", "GitHub Copilot CLI", "copilot-vscode", "GitHub Copilot in VS Code (default profile)",
            "codex", "Codex", "claude", "Claude Code", "windsurf", "Windsurf");
    record Result(String client, String status, Path destination, String message) {}
    record Edit(String status, String text, String message) {}

    static List<String> selection(String text) {
        if (text == null || text.isBlank() || text.strip().equalsIgnoreCase("none")) return List.of();
        if (text.strip().equalsIgnoreCase("all")) return CLIENTS;
        Set<String> result = new LinkedHashSet<>();
        for (String token : text.toLowerCase(Locale.ROOT).split(",", -1)) {
            String item = token.strip();
            if (item.matches("[1-5]")) item = CLIENTS.get(Integer.parseInt(item)-1);
            if (!CLIENTS.contains(item) || !result.add(item))
                throw new IllegalArgumentException("Choose all, none, or unique comma-separated clients/numbers: copilot, copilot-vscode, codex, claude, windsurf");
        }
        return List.copyOf(result);
    }
    static List<String> choose(String explicit, boolean unattended, boolean buildOnly, Console console, String url) throws IOException {
        canonicalUrl(url);
        if (explicit != null) {
            if (explicit.isBlank()) throw new IllegalArgumentException("--mcp-clients requires a selection");
            List<String> selected = selection(explicit);
            if (buildOnly && !selected.isEmpty()) throw new IllegalArgumentException("--build-only cannot configure MCP; use --mcp-clients none");
            return selected;
        }
        if (unattended || buildOnly || console == null) return List.of();
        return prompt(new BufferedReader(console.reader()), console.writer(), url);
    }
    static List<String> prompt(BufferedReader input, PrintWriter out, String url) throws IOException {
        out.println("\nOptional MCP connections (current user, local HTTP): " + url);
        out.println("No clients are installed, no server is started, and no tool approvals or trust settings are changed.");
        for (int i=0;i<CLIENTS.size();i++) out.println("  " + (i+1) + ") " + LABELS.get(CLIENTS.get(i)));
        while (true) {
            out.print("Configure MCP [numbers/names separated by commas / all / none] (none): "); out.flush();
            try { return selection(input.readLine()); }
            catch (IllegalArgumentException e) { out.println(e.getMessage()); }
        }
    }
    static String canonicalUrl(String value) {
        try {
            URI uri = URI.create(value); String host=uri.getHost();
            if (host == null || !Set.of("localhost","127.0.0.1","[::1]","::1").contains(host.toLowerCase(Locale.ROOT))
                    || !"http".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo()!=null || uri.getRawQuery()!=null
                    || uri.getRawFragment()!=null || uri.getPort()==0 || uri.getPort()>65535
                    || !Set.of("/mcp","/mcp/").contains(uri.getRawPath())) throw new IllegalArgumentException();
            return "http://loopback:" + (uri.getPort()<0?80:uri.getPort()) + "/mcp";
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("MCP URL must be http://localhost:PORT/mcp (or 127.0.0.1 / [::1]), without credentials, query, or fragment. No remote exposure is configured.");
        }
    }
    static Path envPath(Map<String,String> env, String key, Path fallback) {
        String value=env.get(key); if(value==null||value.isBlank())return fallback;
        Path path=Path.of(value); if(!path.isAbsolute())throw new IllegalArgumentException(key+" must be an absolute path"); return path.normalize();
    }
    static Path destination(String client, Path home, Map<String,String> env, String os) {
        return switch(client) {
            case "codex" -> envPath(env,"CODEX_HOME",home.resolve(".codex")).resolve("config.toml");
            case "copilot" -> envPath(env,"COPILOT_HOME",home.resolve(".copilot")).resolve("mcp-config.json");
            case "claude" -> {
                // Claude's relocated global state file is not documented consistently across versions.
                // Never guess and write a second config when an alternate instance is in use.
                if(env.containsKey("CLAUDE_CONFIG_DIR")&&!env.get("CLAUDE_CONFIG_DIR").isBlank())
                    throw new IllegalArgumentException("CLAUDE_CONFIG_DIR is set. Configure this Claude instance using claude mcp add --transport http --scope user code-graph URL; automatic registration is skipped to avoid a duplicate configuration.");
                yield home.resolve(".claude.json");
            }
            case "windsurf" -> home.resolve(".codeium/windsurf/mcp_config.json");
            case "copilot-vscode" -> os.startsWith("Windows")
                    ? envPath(env,"APPDATA",home.resolve("AppData/Roaming")).resolve("Code/User/mcp.json")
                    : os.startsWith("Mac") ? home.resolve("Library/Application Support/Code/User/mcp.json")
                    : envPath(env,"XDG_CONFIG_HOME",home.resolve(".config")).resolve("Code/User/mcp.json");
            default -> throw new IllegalArgumentException("Unknown MCP client");
        };
    }
    static Map<String,Object> fields(Object value) throws IOException {
        if (!(value instanceof McpConfigDocument.ObjectValue object)) throw new IOException("Expected an MCP configuration object; original file preserved");
        return object.fields();
    }
    static boolean matching(Map<String,Object> entry, String url) {
        for (String key : List.of("url","serverUrl")) if (entry.get(key) instanceof String existing) {
            try { if(canonicalUrl(existing).equals(canonicalUrl(url))) return true; } catch(IllegalArgumentException ignored) {}
        }
        return false;
    }
    static boolean ownName(String name) { return name.replace("-", "").replace("_", "").equalsIgnoreCase("codegraph"); }
    static boolean knownStdio(Map<String,Object> server) {
        if(!(server.get("command") instanceof String command) || !(server.get("args") instanceof List<?> args))return false;
        String executable=command.replace('\\','/');executable=executable.substring(executable.lastIndexOf('/')+1);
        return Set.of("java","java.exe").contains(executable.toLowerCase(Locale.ROOT))
                && args.contains("io.doindev.codegraph.mcp.Main");
    }
    static Edit inspectServers(Map<String,Map<String,Object>> servers, String url, String original) {
        // Check names first: do not hide a conflict just because another alias points at this URL.
        for (var entry:servers.entrySet()) if(ownName(entry.getKey())&&!matching(entry.getValue(),url)&&!knownStdio(entry.getValue()))
            return new Edit("conflict", original,"An existing code-graph entry has a different transport/endpoint. Preserved; review it in the client before installing another.");
        if(servers.values().stream().anyMatch(server->matching(server,url)||knownStdio(server)))
            return new Edit("already-configured",original,"Matching endpoint or known code-graph stdio launcher already configured (possibly under another name). Existing enabled/disabled state, transport, headers and permissions preserved.");
        return null;
    }
    static Edit edit(String client, String original, String url) throws IOException {
        canonicalUrl(url);
        if(client.equals("codex")) {
            var servers=McpConfigDocument.tomlServers(original);
            Edit existing=inspectServers(servers,url,original); if(existing!=null)return existing;
            String nl=original.contains("\r\n")?"\r\n":"\n";
            String updated=original+nl+"[mcp_servers.code-graph]"+nl+"url = "+McpConfigDocument.quote(url)+nl
                    +"supports_parallel_tool_calls = true"+nl;
            McpConfigDocument.tomlServers(updated);
            return new Edit("configured",updated,"Added user-level HTTP connection; restart/reload the client if needed.");
        }
        String text=original.isBlank()?"{}\n":original;
        boolean jsonc=client.equals("copilot-vscode");
        var parser=new McpConfigDocument(text,jsonc); var root=parser.json();
        String key=client.equals("copilot-vscode")?"servers":"mcpServers";
        Object value=root.fields().get(key); Map<String,Map<String,Object>> servers=new LinkedHashMap<>();
        if(root.fields().containsKey(key)) for(var entry:fields(value).entrySet()) servers.put(entry.getKey(),fields(entry.getValue()));
        Edit existing=inspectServers(servers,url,original); if(existing!=null)return existing;
        // Claude private project entries may shadow global entries. Do not add another endpoint silently.
        if(client.equals("claude") && root.fields().get("projects") instanceof McpConfigDocument.ObjectValue projects) {
            for(Object project:projects.fields().values()) {
                if(project instanceof McpConfigDocument.ObjectValue object && object.fields().get("mcpServers") instanceof McpConfigDocument.ObjectValue local) {
                    for(var entry:local.fields().entrySet()) if(ownName(entry.getKey())||matching(fields(entry.getValue()),url))
                        return new Edit("scoped-existing",original,"A project-scoped code-graph connection already exists. Preserved; use Claude's MCP editor to choose its scope. No second global connection added.");
                }
            }
        }
        String server=client.equals("windsurf") ? "{\"serverUrl\": "+McpConfigDocument.quote(url)+"}"
                : "{\"type\": \"http\", \"url\": "+McpConfigDocument.quote(url)+(client.equals("copilot")?", \"tools\": [\"*\"]":"")+"}";
        String updated=value==null?parser.insert(root,key,"{\"code-graph\": "+server+"}")
                :parser.insert((McpConfigDocument.ObjectValue)value,"code-graph",server);
        new McpConfigDocument(updated,jsonc).json();
        return new Edit("configured",updated,"Added user-level HTTP connection; restart/reload the client if needed.");
    }
    static byte[] read(Path file) throws IOException {
        if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS))return null;
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(file)||!file.toRealPath().equals(file.toAbsolutePath().normalize()))
            throw new IOException("Linked/nonregular configuration file; use the client's MCP editor");
        try(var in=Files.newInputStream(file)) {
            byte[] bytes=in.readNBytes(McpConfigDocument.MAX_BYTES+1);
            if(bytes.length>McpConfigDocument.MAX_BYTES)throw new IOException("Configuration exceeds 2 MiB safety limit; use the client's MCP editor");return bytes;
        }
    }
    static Edit legacyVsCode(String client, Path file, String url) throws IOException {
        if(!client.equals("copilot-vscode"))return null;
        byte[] bytes=read(file.resolveSibling("settings.json"));if(bytes==null)return null;
        var root=new McpConfigDocument(decode(bytes)).json();
        if(!root.fields().containsKey("mcp"))return null;
        Map<String,Object> mcp=fields(root.fields().get("mcp"));if(!mcp.containsKey("servers"))return null;
        Map<String,Map<String,Object>> servers=new LinkedHashMap<>();
        for(var entry:fields(mcp.get("servers")).entrySet())servers.put(entry.getKey(),fields(entry.getValue()));
        Edit found=inspectServers(servers,url,"");
        return found==null?null:new Edit(found.status,"",found.message+" Located in legacy VS Code settings.json; no duplicate added to mcp.json.");
    }
    static String decode(byte[] bytes) throws IOException {
        if(bytes==null)return "";
        try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}
        catch(CharacterCodingException e){throw new IOException("Configuration must be UTF-8; original file preserved");}
    }
    static void directories(Path directory) throws IOException {
        Path at=directory.toAbsolutePath().normalize().getRoot();
        for(Path part:directory.toAbsolutePath().normalize()) {
            at=at.resolve(part);
            if(Files.exists(at,LinkOption.NOFOLLOW_LINKS)&&(!Files.isDirectory(at,LinkOption.NOFOLLOW_LINKS)||!at.toRealPath().equals(at)))
                throw new IOException("Linked/non-directory configuration path; use the client's MCP editor");
        }
    }
    static void permissions(Path original, Path temporary) throws IOException {
        var posix=Files.getFileAttributeView(temporary,PosixFileAttributeView.class);
        if(posix!=null)posix.setPermissions(original==null?PosixFilePermissions.fromString("rw-------"):Files.getPosixFilePermissions(original));
        if(original!=null) {
            var acl=Files.getFileAttributeView(original,AclFileAttributeView.class);
            if(acl!=null)Files.getFileAttributeView(temporary,AclFileAttributeView.class).setAcl(acl.getAcl());
        }
    }
    static Path replace(Path file, byte[] before, byte[] after) throws IOException {
        if(after.length>McpConfigDocument.MAX_BYTES)throw new IOException("Updated configuration exceeds 2 MiB safety limit");
        Path temp=Files.createTempFile(file.getParent(),".cgraph-mcp-",".tmp"); Path backup=null;
        try {
            permissions(before==null?null:file,temp); Files.write(temp,after);
            if(!Arrays.equals(before,read(file)))throw new IOException("Configuration changed concurrently; rerun after the client finishes saving. Nothing overwritten.");
            if(before!=null) {
                backup=file.resolveSibling(file.getFileName()+".cgraph-backup-"+UUID.randomUUID());
                Files.copy(file,backup,StandardCopyOption.COPY_ATTRIBUTES); permissions(file,backup);
            }
            directories(file.getParent());
            if(!Arrays.equals(before,read(file)))throw new IOException("Configuration changed concurrently; original preserved");
            // Fail closed where atomic replacement is unavailable; no delete/rewrite fallback.
            Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            return backup;
        } finally { Files.deleteIfExists(temp); }
    }
    static Result configure(String client, Path file, String url) {
        Path lock=null;
        try {
            directories(file.getParent()); byte[] before=read(file);
            Edit edit=edit(client,decode(before),url);
            if(!edit.status.equals("configured"))return new Result(client,edit.status,file,edit.message);
            Edit legacy=legacyVsCode(client,file,url);
            if(legacy!=null)return new Result(client,legacy.status,file,legacy.message);
            Files.createDirectories(file.getParent()); directories(file.getParent());
            Path lockPath=file.resolveSibling(file.getFileName()+".cgraph-mcp.lock");
            Files.writeString(lockPath,"code-graph MCP installer",StandardOpenOption.CREATE_NEW); lock=lockPath;
            if(!Arrays.equals(before,read(file)))throw new IOException("Configuration changed concurrently; rerun. Original preserved.");
            Path backup=replace(file,before,edit.text.getBytes(StandardCharsets.UTF_8));
            return new Result(client,"configured",file,edit.message+(backup==null?"":" Backup: "+backup));
        } catch(IOException|IllegalArgumentException e) {
            // Filesystem errors may contain paths, never file contents or secret values.
            return new Result(client,"failed",file,e instanceof FileAlreadyExistsException?"Another installer owns the MCP config lock; retry after it finishes.":e.getMessage());
        } finally {if(lock!=null)try{Files.delete(lock);}catch(IOException ignored){}}
    }
    static List<Result> install(List<String> selected, Path home, Map<String,String> env, String os, String url) {
        List<Result> results=new ArrayList<>();
        for(String client:selected)try {results.add(configure(client,destination(client,home,env,os),url));}
        catch(IllegalArgumentException e){results.add(new Result(client,"failed",null,e.getMessage()));}
        return results;
    }
    static List<String> skillClients(Path home, Map<String,String> env, String os, String url, PrintStream out) {
        Set<String> result=new LinkedHashSet<>();
        for(String client:CLIENTS)try {
            Path file=destination(client,home,env,os); directories(file.getParent()); byte[] data=read(file);
            Edit found=data==null?null:edit(client,decode(data),url);
            if(found==null||found.status.equals("configured"))found=legacyVsCode(client,file,url);
            if(found!=null && Set.of("already-configured","scoped-existing").contains(found.status))result.add(client.equals("copilot-vscode")?"copilot":client);
        } catch(IOException|IllegalArgumentException e){out.println("MCP detection for "+LABELS.get(client)+": "+e.getMessage());}
        return List.copyOf(result);
    }
    public static void main(String[] args) throws Exception {
        Map<String,String> options=new LinkedHashMap<>();
        for(int i=0;i<args.length;i++) {
            if(args[i].equals("--help")){System.out.println("java installer/McpInstaller.java [--clients all|none|codex,claude,copilot,copilot-vscode,windsurf] [--url http://localhost:3000/mcp]\nOmitting --clients prompts only on an interactive console. No permissions or skills are changed.");return;}
            if(!Set.of("--clients","--url").contains(args[i])||i+1==args.length||options.putIfAbsent(args[i],args[++i])!=null)throw new IllegalArgumentException("Invalid or duplicate MCP installer option");
        }
        String url=options.getOrDefault("--url",DEFAULT_URL);
        var selected=choose(options.get("--clients"),false,false,System.console(),url);
        var results=install(selected,Path.of(System.getProperty("user.home")).toRealPath(),System.getenv(),System.getProperty("os.name"),url);
        results.forEach(r->System.out.println(r.client+": "+r.status+" — "+r.destination+" — "+r.message));
        if(results.stream().anyMatch(r->!Set.of("configured","already-configured","scoped-existing").contains(r.status)))System.exit(2);
    }
}
