import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Disposable profiles only: never changes real client configuration, PATH, or server state. */
public class McpInstallerTest {
    static int checks;
    static void check(boolean ok, String why) { checks++; if(!ok)throw new AssertionError(why); }
    interface Checked { void run() throws Exception; }
    static void rejects(Checked action) throws Exception {
        boolean rejected=false;try{action.run();}catch(IOException|IllegalArgumentException expected){rejected=true;}
        check(rejected,"Unsafe input accepted");
    }
    static Path put(Path home,String relative,String content)throws Exception {
        Path path=home.resolve(relative);Files.createDirectories(path.getParent());Files.writeString(path,content);return path;
    }
    static void preserved(String client,Path file,String text,String status)throws Exception {
        Files.writeString(file,text);var result=McpInstaller.configure(client,file,McpInstaller.DEFAULT_URL);
        check(result.status().equals(status),result.toString());
        check(Files.readString(file).equals(text),"Existing bytes changed: "+status);
    }
    public static void main(String[] args)throws Exception {
        String url=McpInstaller.DEFAULT_URL;
        check(McpInstaller.selection("all").size()==5,"All client surfaces");
        check(McpInstaller.selection("3, 4").equals(List.of("codex","claude")),"Number multi-selection");
        check(McpInstaller.selection("Codex, WindSurf").equals(List.of("codex","windsurf")),"Name multi-selection");
        for(String invalid:List.of("all,codex","codex,codex","3,codex","none,claude","codex,","unknown"))rejects(()->McpInstaller.selection(invalid));
        check(McpInstaller.choose(null,true,false,null,url).isEmpty(),"Unattended opt-out");
        check(McpInstaller.choose(null,false,false,null,url).isEmpty(),"No console opt-out");
        check(McpInstaller.choose("all",true,false,null,url).size()==5,"Explicit unattended choice");
        rejects(()->McpInstaller.choose("all",true,true,null,url));
        check(McpInstaller.choose(null,false,true,null,url).isEmpty(),"Build-only opt-out");
        StringWriter screen=new StringWriter();
        check(McpInstaller.prompt(new BufferedReader(new StringReader("invalid\n1,3\n")),new PrintWriter(screen),url).equals(List.of("copilot","codex")),"Prompt retries and multi-selects");
        check(screen.toString().contains("Claude Code")&&screen.toString().contains("default profile"),"Unambiguous client surfaces");
        check(McpInstaller.prompt(new BufferedReader(new StringReader("")),new PrintWriter(screen),url).isEmpty(),"Prompt EOF skips");
        check(McpInstaller.prompt(new BufferedReader(new StringReader("\n")),new PrintWriter(screen),url).isEmpty(),"Prompt blank skips");
        for(String equivalent:List.of("http://127.0.0.1:3000/mcp/","http://[::1]:3000/mcp","http://LOCALHOST:3000/mcp"))
            check(McpInstaller.canonicalUrl(equivalent).equals(McpInstaller.canonicalUrl(url)),"Loopback duplicate recognition");
        for(String invalid:List.of("https://localhost:3000/mcp","http://example.com:3000/mcp","http://localhost:0/mcp","http://localhost:65536/mcp",
                "http://user:secret@localhost:3000/mcp","http://localhost:3000/mcp?token=secret","http://localhost:3000/mcp#secret","http://localhost:3000/other"))
            rejects(()->McpInstaller.canonicalUrl(invalid));
        Path root=Files.createTempDirectory("cgraph-build-").toRealPath();String owner=UUID.randomUUID().toString();Files.writeString(root.resolve(".cgraph-build-owner"),owner);
        try {
            Path home=Files.createDirectory(root.resolve("user with spaces"));
            var env=Map.<String,String>of();
            check(McpInstaller.destination("codex",home,env,"Linux").equals(home.resolve(".codex/config.toml")),"Codex path");
            check(McpInstaller.destination("claude",home,env,"Windows").equals(home.resolve(".claude.json")),"Claude Code global path (not settings.json)");
            check(McpInstaller.destination("copilot-vscode",home,env,"Windows").equals(home.resolve("AppData/Roaming/Code/User/mcp.json")),"Windows VS Code");
            check(McpInstaller.destination("copilot-vscode",home,env,"Mac OS X").equals(home.resolve("Library/Application Support/Code/User/mcp.json")),"macOS VS Code");
            check(McpInstaller.destination("copilot-vscode",home,env,"Linux").equals(home.resolve(".config/Code/User/mcp.json")),"Linux VS Code");
            for(String client:List.of("codex","copilot","copilot-vscode")) {
                String key=client.equals("codex")?"CODEX_HOME":client.equals("copilot")?"COPILOT_HOME":"XDG_CONFIG_HOME";
                check(McpInstaller.destination(client,home,Map.of(key,root.resolve("override").toString()),"Linux").startsWith(root.resolve("override")),"Config directory override");
                rejects(()->McpInstaller.destination(client,home,Map.of(key,"relative"),"Linux"));
            }
            rejects(()->McpInstaller.destination("claude",home,Map.of("CLAUDE_CONFIG_DIR",root.resolve("alternate").toString()),"Linux"));
            check(McpInstaller.install(List.of(),home,env,"Linux",url).isEmpty(),"No selected writes");
            try(var files=Files.list(home)){check(files.findAny().isEmpty(),"Skip leaves home untouched");}
            var results=McpInstaller.install(McpInstaller.CLIENTS,home,env,"Linux",url);
            for(var result:results) {
                check(result.status().equals("configured"),result.toString());
                String content=Files.readString(result.destination());
                check(content.contains(url),"Configured endpoint");
                if(result.client().equals("copilot"))check(content.contains("\"tools\": [\"*\"]"),"Copilot discovery schema");
                if(result.client().equals("windsurf"))check(content.contains("serverUrl"),"Windsurf HTTP schema");
                check(!content.contains("approval_mode")&&!content.contains("autoApprove"),"No permission broadening");
                var repeat=McpInstaller.configure(result.client(),result.destination(),url);
                check(repeat.status().equals("already-configured"),"Idempotent repeated install");
                check(Files.readString(result.destination()).equals(content),"Duplicate run leaves bytes unchanged");
            }
            check(new HashSet<>(McpInstaller.skillClients(home,env,"Linux",url,System.out)).equals(Set.of("codex","claude","copilot","windsurf")),"Skills deduplicated across Copilot surfaces");
            Path legacyHome=Files.createDirectory(root.resolve("legacy user"));
            Path legacy=put(legacyHome,".config/Code/User/settings.json","{\"mcp\":{\"servers\":{\"renamed\":{\"type\":\"http\",\"url\":\"http://localhost:3000/mcp\"}}}}");
            check(McpInstaller.install(List.of("copilot-vscode"),legacyHome,env,"Linux",url).getFirst().status().equals("already-configured"),"Legacy VS Code settings detected");
            check(!Files.exists(legacy.resolveSibling("mcp.json")),"No second modern config for a legacy endpoint");
            check(McpInstaller.skillClients(legacyHome,env,"Linux",url,System.out).equals(List.of("copilot")),"Legacy connection qualifies for skill offer");
            Path json=put(home,"checks/mcp.json","{}");
            String other="\ufeff{\r\n // user comment\r\n \"settings\": {\"token\": \"private-fixture\"},\r\n \"servers\": {\"other\": {\"url\": \"https://example.test\"} // keep me\r\n }\r\n}\r\n";
            Files.writeString(json,other);var merged=McpInstaller.configure("copilot-vscode",json,url);
            check(merged.status().equals("configured"),merged.toString());
            check(Files.readString(json).contains("// keep me")&&Files.readString(json).contains("private-fixture"),"JSONC comments and unrelated secret fields preserved");
            check(!merged.message().contains("private-fixture"),"No secrets in result");
            try(var paths=Files.list(json.getParent())) {
                var backups=paths.filter(p->p.getFileName().toString().contains(".cgraph-backup-")).toList();
                check(backups.size()==1&&Files.readString(backups.getFirst()).equals(other),"Exact original backup");
            }
            preserved("windsurf",json,"{\"mcpServers\":{\"renamed\":{\"serverUrl\":\"http://127.0.0.1:3000/mcp/\",\"disabled\":true}}}","already-configured");
            preserved("claude",json,"{\"mcpServers\":{\"code-graph\":{\"url\":\"http://localhost:3001/mcp\"}}}","conflict");
            preserved("claude",json,"{\"mcpServers\":{\"code_graph\":{\"command\":\"custom-command\"}}}","conflict");
            preserved("claude",json,"{\"mcpServers\":{\"stdio-alias\":{\"command\":\"java\",\"args\":[\"-cp\",\"lib/*\",\"io.doindev.codegraph.mcp.Main\"]}}}","already-configured");
            preserved("claude",json,"{\"projects\":{\"/project\":{\"mcpServers\":{\"alias\":{\"url\":\"http://localhost:3000/mcp\"}}}}}","scoped-existing");
            for(String malformed:List.of("[]","{\"mcpServers\":null}","{\"mcpServers\":[]}","{\"mcpServers\":{\"x\":null}}","{\"x\":1,\"x\":2}","{\"token\":\"private-fixture\",","/* unterminated"))
                preserved("claude",json,malformed,"failed");
            check(!McpInstaller.configure("claude",json,url).message().contains("private-fixture"),"Parse errors sanitized");
            String trailing="{\"servers\": {\"unrelated\": {}, /* trailing */}, /* end */}";
            Files.writeString(json,trailing);check(McpInstaller.configure("copilot-vscode",json,url).status().equals("configured"),"JSONC trailing commas preserved");
            preserved("claude",json,"{\"mcpServers\":{},}","failed");
            preserved("claude",json,"{/* invalid for strict JSON */}","failed");
            preserved("claude",json," ".repeat(McpConfigDocument.MAX_BYTES+1),"failed");
            Files.write(json,new byte[]{(byte)0xff});check(McpInstaller.configure("claude",json,url).status().equals("failed"),"Invalid UTF-8 refused");
            Files.writeString(json,"{}");Path lock=json.resolveSibling("mcp.json.cgraph-mcp.lock");Files.writeString(lock,"another owner");
            check(McpInstaller.configure("claude",json,url).status().equals("failed"),"Concurrent installer lock");
            check(Files.readString(lock).equals("another owner")&&Files.readString(json).equals("{}"),"Other owner's lock/data preserved");Files.delete(lock);
            byte[] old="{}".getBytes(StandardCharsets.UTF_8);Files.writeString(json,"{\"changed\":true}");
            rejects(()->McpInstaller.replace(json,old,old));check(Files.readString(json).contains("changed"),"Concurrent client changes preserved");
            Path toml=put(home,"checks/config.toml","");
            String tomlOther="# retained\nmodel = 'example'\nnotes = '''\n[mcp_servers.fake]\nurl = \"http://localhost:3000/mcp\"\n'''\n[projects.\"C:\\\\workspace\"]\ntrust_level = \"trusted\"\n";
            Files.writeString(toml,tomlOther);check(McpInstaller.configure("codex",toml,url).status().equals("configured"),"Strings never treated as tables");
            check(Files.readString(toml).startsWith(tomlOther),"TOML unrelated content byte-preserved");
            preserved("codex",toml,"[mcp_servers.'alias.with.dots']\nurl = 'http://[::1]:3000/mcp' # retained\nenabled = false\n[mcp_servers.'alias.with.dots'.http_headers]\nAuthorization = 'private-fixture'\n","already-configured");
            preserved("codex",toml,"[mcp_servers.\"code-graph\"]\ncommand = 'different'\n","conflict");
            preserved("codex",toml,"[mcp_servers.alias]\ncommand = 'java'\nargs = [\"-cp\", \"lib/*\", \"io.doindev.codegraph.mcp.Main\"]\n","already-configured");
            for(String malformed:List.of("[mcp_servers] local = {url='http://localhost:3000/mcp'}","mcp_servers = {alias = {url='http://localhost:3000/mcp'}}",
                    "[mcp_servers]\ncodegraph.url = 'http://localhost:3000/mcp'","[mcp_servers.a]\nurl = 'x'\nurl = 'y'","x = \"unfinished","x = [1,2","[mcp_servers.a]\nurl = 42"))
                preserved("codex",toml,malformed,"failed");
            // The new orchestration checks actual MCP first; all means all eligible skills, not every client.
            SkillInstaller skills=new SkillInstaller(Path.of("").toAbsolutePath());
            check(skills.configuredSelection("all",List.of("codex")).equals(List.of("codex")),"All skills gated by configured clients");
            rejects(()->skills.configuredSelection("claude",List.of("codex")));
            check(skills.chooseConfigured(null,true,false,null,List.of("codex")).isEmpty(),"No unattended skill opt-in");
            StringWriter skillPrompt=new StringWriter();
            check(skills.promptConfigured(new BufferedReader(new StringReader("claude\nall\n")),new PrintWriter(skillPrompt),List.of("codex")).equals(List.of("codex")),"Only eligible skill choices accepted");
            check(!skillPrompt.toString().contains("claude,windsurf"),"No unconfigured clients offered");
            String originalHome=System.getProperty("user.home");Path flowHome=Files.createDirectory(root.resolve("flow home"));
            try {
                System.setProperty("user.home",flowHome.toString());
                CgraphInstaller installer=new CgraphInstaller(new String[]{"--source",Path.of("").toAbsolutePath().toString(),"--install-dir",root.resolve("unused install").toString(),"--skills","all","--non-interactive"});
                installer.environment.clear();installer.finishOptionalSetup(Path.of(""),List.of("codex","windsurf"),url);
                check(Files.isRegularFile(flowHome.resolve(".agents/skills/code-graph/SKILL.md")),"MCP-first flow installs matching Codex skill");
                check(Files.isRegularFile(flowHome.resolve(".codeium/windsurf/skills/code-graph/SKILL.md")),"MCP-first flow installs matching Windsurf skill");
                check(!Files.exists(flowHome.resolve(".claude"))&&!Files.exists(flowHome.resolve(".copilot")),"Unselected/unconfigured clients untouched");
                installer.finishOptionalSetup(Path.of(""),List.of(),url); // Existing connections eligible on later installs.
            } finally {System.setProperty("user.home",originalHome);}
            Path external=Files.createDirectory(root.resolve("external"));Path link=home.resolve("redirect");
            if(CgraphInstaller.WINDOWS) {
                var process=new ProcessBuilder("powershell.exe","-NoProfile","-Command","New-Item -ItemType Junction -Path $env:CGRAPH_TEST_LINK -Target $env:CGRAPH_TEST_TARGET | Out-Null");
                process.environment().put("CGRAPH_TEST_LINK",link.toString());process.environment().put("CGRAPH_TEST_TARGET",external.toString());
                check(process.start().waitFor()==0,"Owned junction created");
            } else Files.createSymbolicLink(link,external);
            try {
                check(McpInstaller.configure("claude",link.resolve("mcp.json"),url).status().equals("failed"),"Linked destination refused");
                try(var entries=Files.list(external)){check(entries.findAny().isEmpty(),"Linked target untouched");}
            } finally {Files.delete(link);}
        } finally {CgraphInstaller.removeOwnedWork(root,owner);}
        check(!Files.exists(root),"Owned fixture cleanup");
        System.out.println("MCP installer checks passed: "+checks);
    }
}
