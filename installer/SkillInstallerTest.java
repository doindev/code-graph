import java.io.*;
import java.nio.file.*;
import java.util.*;

/** All writes are confined to owned temporary user profiles, never real client configuration. */
public class SkillInstallerTest {
    static int checks;
    static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
    interface Checked { void run() throws Exception; }
    static void rejects(Checked action) throws Exception {
        boolean rejected = false;
        try { action.run(); } catch (IOException | IllegalArgumentException expected) { rejected = true; }
        check(rejected, "Invalid input accepted");
    }
    static void same(Path left, Path right) throws Exception {
        var a = SkillInstaller.inventory(left); var b = SkillInstaller.inventory(right);
        check(a.keySet().equals(b.keySet()) && a.entrySet().stream().allMatch(e -> Arrays.equals(e.getValue(), b.get(e.getKey()))),
                "Whole skill and references must match");
    }
    public static void main(String[] args) throws Exception {
        SkillInstaller installer = new SkillInstaller(Path.of("").toAbsolutePath());
        check(installer.selection("all").equals(List.of("codex","copilot","claude","windsurf")), "All clients");
        check(installer.selection("none").isEmpty(), "Explicit opt-out");
        check(installer.selection("Codex, Claude").equals(List.of("codex","claude")), "Subset");
        rejects(() -> installer.selection("all,claude"));
        rejects(() -> installer.selection("claude,claude"));
        rejects(() -> installer.selection("codex,"));
        rejects(() -> installer.selection("unknown"));
        check(installer.choose(null,true,false,null).isEmpty(), "Unattended default is none");
        check(installer.choose(null,false,false,null).isEmpty(), "No console default is none");
        check(installer.choose(null,false,true,null).isEmpty(), "Build-only skips");
        check(installer.choose("all",true,false,null).size()==4, "Explicit unattended selection");
        rejects(() -> installer.choose("all",true,true,null));
        rejects(() -> installer.choose("",true,false,null));
        check(installer.choose("none",true,true,null).isEmpty(), "Build-only explicit none");
        StringWriter prompt = new StringWriter();
        check(installer.promptSelection(new BufferedReader(new StringReader("invalid\ncodex,claude\n")),new PrintWriter(prompt))
                .equals(List.of("codex","claude")), "Invalid prompt input retries");
        check(prompt.toString().contains("Skills must be"), "Prompt validation feedback");
        check(installer.promptSelection(new BufferedReader(new StringReader("\n")),new PrintWriter(prompt)).isEmpty(), "Default opt-out");
        check(installer.promptSelection(new BufferedReader(new StringReader("")),new PrintWriter(prompt)).isEmpty(), "EOF opt-out");
        Path promptTarget=Path.of("chosen skills/code-graph");
        for(String answer:List.of("", "\n", "n\n", "NO\n"))
            check(!SkillInstaller.promptOverwrite(new BufferedReader(new StringReader(answer)),new PrintWriter(prompt),"codex",promptTarget),"Replacement default/No/EOF keeps existing");
        check(SkillInstaller.promptOverwrite(new BufferedReader(new StringReader("invalid\n YES \n")),new PrintWriter(prompt),"codex",promptTarget),"Explicit yes after validation approves");
        check(prompt.toString().contains(promptTarget.toString()) && prompt.toString().contains("SKILL.md and all references")
                && prompt.toString().contains("[y/N]"),"Replacement explains exact target, backup and default");
        check(!SkillInstaller.confirmation(true,null).approve("codex",promptTarget),"Non-interactive never approves replacement");
        check(!SkillInstaller.confirmation(false,null).approve("codex",promptTarget),"No console never approves replacement");
        check(CgraphInstaller.parse(new String[]{"--skills","all"}).get("--skills").equals("all"), "Native forwarding");
        rejects(() -> CgraphInstaller.parse(new String[]{"--skills","--no-path"}));
        rejects(() -> CgraphInstaller.parse(new String[]{"--skills","all","--skills","none"}));

        Path temp = Files.createTempDirectory("cgraph-build-").toRealPath();
        String owner = UUID.randomUUID().toString();
        Files.writeString(temp.resolve(".cgraph-build-owner"),owner);
        try {
            Path home = Files.createDirectory(temp.resolve("fake user home"));
            var expected = Map.of("codex",".agents","copilot",".copilot","claude",".claude","windsurf",".codeium/windsurf");
            check(installer.install(List.of(),home,Map.of()).isEmpty(), "No selection writes nothing");
            try(var children=Files.list(home)){check(children.findAny().isEmpty(),"No global folders when skipped");}
            var first=installer.install(installer.clients,home,Map.of());
            for(var result:first){
                check(result.status().equals("installed"), "Initial installation: "+result);
                check(result.destination().equals(home.resolve(expected.get(result.client())+"/skills/code-graph")), "Official global path");
                same(installer.source,result.destination());
                check(!Files.exists(result.destination().getParent().getParent().resolve("mcp.json")), "No MCP configuration");
            }
            check(installer.install(installer.clients,home,Map.of()).stream().allMatch(r->r.status().equals("already-installed")), "Idempotent");
            Path customized=first.getFirst().destination().resolve("references/code-navigation.md");
            Files.writeString(customized,"user-customized reference");
            Path newConfig=temp.resolve("alternate claude config");
            var partial=installer.install(List.of("codex","claude"),home,Map.of("CLAUDE_CONFIG_DIR",newConfig.toString()));
            check(partial.get(0).status().equals("skipped-existing") && partial.get(1).status().equals("installed"), "Keeping existing does not fail or block other selected clients");
            check(Files.readString(customized).equals("user-customized reference"), "Custom references preserved");
            same(installer.source,newConfig.resolve("skills/code-graph"));
            for(var result:first) Files.writeString(result.destination().resolve("custom.md"),"custom "+result.client());
            Map<String,Map<String,byte[]>> before=new HashMap<>();
            for(var result:first) before.put(result.client(),SkillInstaller.inventory(result.destination()));
            List<String> prompted=new ArrayList<>();
            var updates=installer.install(installer.clients,home,Map.of(),(client,target)->{prompted.add(client);return !client.equals("claude");});
            check(prompted.equals(installer.clients),"Each differing client has its own decision");
            for(var result:updates){
                if(result.client().equals("claude")){
                    check(result.status().equals("skipped-existing") && result.backup()==null,"Decline skips with no backup");
                    check(SkillInstaller.same(before.get("claude"),SkillInstaller.inventory(result.destination())),"Declined contents untouched");
                } else {
                    check(result.status().equals("updated"),"Approved replacement succeeded: "+result);
                    same(installer.source,result.destination());
                    check(SkillInstaller.same(before.get(result.client()),SkillInstaller.inventory(result.backup())),"Backup includes every customized file");
                    check(!result.backup().startsWith(result.destination().getParent()),"Backup outside skill discovery");
                    check(installer.install(List.of(result.client()),home,Map.of(),(client,target)->{throw new AssertionError("Identical copy prompted");})
                            .getFirst().status().equals("already-installed"),"Updated copy idempotent");
                }
            }
            Path existingSkill=first.getFirst().destination().resolve("SKILL.md");
            Files.writeString(existingSkill,"before prompt");
            var concurrent=installer.install(List.of("codex"),home,Map.of(),(client,target)->{
                Files.writeString(existingSkill,"changed during prompt");return true;
            }).getFirst();
            check(concurrent.status().equals("failed") && concurrent.error().contains("changed after confirmation"),"Concurrent edit invalidates approval");
            check(Files.readString(existingSkill).equals("changed during prompt"),"Concurrent edit preserved");
            Path blockedBackup=first.get(2).destination().getParent().getParent().resolve(".code-graph-skill-backups");
            Files.writeString(blockedBackup,"not a directory");
            var blockedUpdate=installer.install(List.of("claude"),home,Map.of(),(client,target)->true).getFirst();
            check(blockedUpdate.status().equals("failed"),"Unsafe backup destination rejected");
            check(SkillInstaller.same(before.get("claude"),SkillInstaller.inventory(blockedUpdate.destination())),"Backup failure leaves old skill intact");
            Path copilot=temp.resolve("copilot override");
            check(installer.install(List.of("copilot"),home,Map.of("COPILOT_HOME",copilot.toString())).getFirst().status().equals("installed"), "Copilot home override");
            rejects(() -> installer.destination("claude",home,Map.of("CLAUDE_CONFIG_DIR","relative")));
            check(installer.destination("codex",home,Map.of("CODEX_HOME",temp.resolve("not-skills").toString()))
                    .equals(home.resolve(".agents/skills/code-graph")), "Codex uses official shared USER skills directory");
            Path blocked=Files.createDirectory(temp.resolve("blocked user"));
            Files.writeString(blocked.resolve(".agents"),"not a directory");
            check(installer.install(List.of("codex"),blocked,Map.of()).getFirst().status().equals("failed"), "Non-directory refused");
            Path linkedHome=Files.createDirectory(temp.resolve("linked user"));
            Path outside=Files.createDirectory(temp.resolve("outside"));
            Path link=linkedHome.resolve(".agents");
            if (CgraphInstaller.WINDOWS) {
                // Native PowerShell junction creation; no string-built shell or recursive operations.
                ProcessBuilder builder=new ProcessBuilder("powershell.exe","-NoProfile","-Command",
                        "New-Item -ItemType Junction -Path $env:CGRAPH_TEST_LINK -Target $env:CGRAPH_TEST_TARGET | Out-Null");
                builder.environment().put("CGRAPH_TEST_LINK",link.toString());builder.environment().put("CGRAPH_TEST_TARGET",outside.toString());
                check(builder.start().waitFor()==0,"Create owned test junction");
            } else Files.createSymbolicLink(link,outside);
            try {
                check(installer.install(List.of("codex"),linkedHome,Map.of()).getFirst().status().equals("failed"),"Redirecting link refused");
                try(var files=Files.list(outside)){check(files.findAny().isEmpty(),"Linked destination untouched");}
            } finally { Files.delete(link); }
            System.out.println("Skill installer checks passed: "+checks);
        } finally { CgraphInstaller.removeOwnedWork(temp,owner); }
    }
}
