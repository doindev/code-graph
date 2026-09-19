import java.nio.file.*;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;

/** No package managers, network, PATH changes or running application are touched by these checks. */
public class CgraphInstallerTest {
    static int checks;
    static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    interface Checked{void run()throws Exception;}
    static void rejects(Checked operation)throws Exception{boolean rejected=false;try{operation.run();}catch(IllegalArgumentException|java.io.IOException expected){rejected=true;}check(rejected,"Unsafe input was accepted");}
    public static void main(String[] args)throws Exception{
        check(CgraphInstaller.parse(new String[]{"--source","a b","--install-dir","c d","--no-path"}).get("--source").equals("a b"),"Space-containing arguments");
        rejects(()->CgraphInstaller.parse(new String[]{"--unknown"}));rejects(()->CgraphInstaller.parse(new String[]{"--source"}));
        check(CgraphInstaller.executableRelative(true,false).equals("cgraph.exe"),"Windows executable");
        check(CgraphInstaller.executableRelative(false,true).equals("Contents/MacOS/cgraph"),"macOS executable");
        check(CgraphInstaller.executableRelative(false,false).equals("bin/cgraph"),"Linux executable");
        check(CgraphInstaller.nonProxyHosts("localhost,.example.org,*.internal").equals("localhost|*.example.org|example.org|*.internal"),"NO_PROXY conversion");
        rejects(()->CgraphInstaller.nonProxyHosts("10.0.0.0/8"));
        rejects(()->CgraphInstaller.proxySettings("http://user:secret@proxy:8080",null));
        rejects(()->CgraphInstaller.proxySettings("socks://proxy:1080",null));
        rejects(()->CgraphInstaller.proxySettings("http://proxy:0",null));
        rejects(()->CgraphInstaller.proxySettings("http://proxy:70000",null));
        rejects(()->CgraphInstaller.proxySettings("http://proxy/path?token=secret",null));
        try { CgraphInstaller.proxySettings("http://user:private value@host",null); throw new AssertionError("Malformed URL accepted"); }
        catch(IllegalArgumentException e) { check(!e.getMessage().contains("private value"),"Malformed URL redacted"); }
        String xml=CgraphInstaller.proxySettings("http://proxy.example:8080","localhost,.corp");
        var doc=DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new java.io.ByteArrayInputStream(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        check(doc.getElementsByTagName("host").item(0).getTextContent().equals("proxy.example"),"Maven proxy host");
        check(doc.getElementsByTagName("port").item(0).getTextContent().equals("8080"),"Maven proxy port");
        check(!xml.contains("secret"),"No literal proxy secrets in settings");
        check(CgraphInstaller.shellQuote("space and 'quote").equals("'space and '\"'\"'quote'"),"Shell PATH quoting");
        Path temp=Files.createTempDirectory("cgraph-build-");String owner=UUID.randomUUID().toString();Files.writeString(temp.resolve(".cgraph-build-owner"),owner);
        try{
            Path source=temp.resolve("source");Files.createDirectories(source.resolve("target"));Files.createDirectories(source.resolve(".git"));
            Path existingSettings=temp.resolve("settings.xml");Files.writeString(existingSettings,"corporate settings");
            rejects(()->CgraphInstaller.requireExplicitExistingSettings(existingSettings));
            check(Files.readString(existingSettings).equals("corporate settings"),"Existing Maven settings never overwritten");
            Files.writeString(source.resolve("pom.xml"),"source");Files.writeString(source.resolve("target/skip"),"build");
            CgraphInstaller.copyTree(source,temp.resolve("copy"),true);
            check(Files.isRegularFile(temp.resolve("copy/pom.xml")),"Source copied");check(!Files.exists(temp.resolve("copy/target")),"Generated artifacts excluded");check(!Files.exists(temp.resolve("copy/.git")),"Git internals excluded");
            rejects(()->CgraphInstaller.validateDestination(source,source.resolve("nested"),temp.resolve("home")));
            rejects(()->CgraphInstaller.validateDestination(source,temp,temp.resolve("home")));
            Path unrelated=temp.resolve("unrelated");Files.createDirectories(unrelated);Files.writeString(unrelated.resolve("keep"),"user data");
            rejects(()->CgraphInstaller.validateDestination(source,unrelated,temp.resolve("home")));
            check(Files.readString(unrelated.resolve("keep")).equals("user data"),"Existing data preserved");
            Path base=temp.resolve("installation");Files.createDirectories(base.resolve("releases/one/cgraph"));Path exe=base.resolve("releases/one/cgraph/cgraph.exe");Files.writeString(exe,"native fixture");
            for(String name:List.of("uninstall.ps1","uninstall.sh"))Files.writeString(source.resolve(name),"# cgraph-managed-uninstaller-v1\nfixture\n");
            CgraphInstaller.installUninstallers(source,base);
            for(String name:List.of("uninstall.ps1","uninstall.sh"))check(Files.readString(base.resolve(name)).equals(Files.readString(source.resolve(name))),"Uninstaller copied: "+name);
            CgraphInstaller.installUninstallers(source,base);
            Files.writeString(base.resolve("uninstall.sh"),"customized script");
            CgraphInstaller.installUninstallers(source,base);
            check(Files.readString(base.resolve("uninstall.sh")).equals("customized script"),"Customized uninstaller preserved");
            Files.writeString(source.resolve("uninstall.ps1"),"unverified source");
            rejects(()->CgraphInstaller.installUninstallers(source,temp.resolve("unverified")));
            CgraphInstaller.installShim(base,exe,true);String shim=Files.readString(base.resolve("bin/cgraph.cmd"));
            check(shim.contains("%~dp0")&&shim.contains("%*"),"Windows shim preserves arguments and relative paths");
            CgraphInstaller.installShim(base,exe,true);check(Files.readString(base.resolve("bin/cgraph.cmd")).equals(shim),"Reinstall updates only owned shim");
            Files.writeString(base.resolve("bin/cgraph.cmd"),"unrelated command");rejects(()->CgraphInstaller.installShim(base,exe,true));
            check(Files.readString(base.resolve("bin/cgraph.cmd")).equals("unrelated command"),"Unrelated command preserved");
            rejects(()->CgraphInstaller.removeOwnedWork(temp,"wrong-owner"));check(Files.exists(temp),"Cleanup ownership checked");
            if(CgraphInstaller.WINDOWS){
                Path readOnly=temp.resolve("read-only-native.exe");Files.writeString(readOnly,"owned native fixture");
                Files.setAttribute(readOnly,"dos:readonly",true);
                check(Files.readAttributes(readOnly,java.nio.file.attribute.DosFileAttributes.class).isReadOnly(),"Read-only jpackage cleanup fixture");
            }
        }finally{CgraphInstaller.removeOwnedWork(temp,owner);}
        check(!Files.exists(temp),"Only the owned test directory was removed");
        System.out.println("Installer checks passed: "+checks);
    }
}
