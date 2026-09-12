package io.doindev.codegraph.dba;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit network gate: installs drivers into a disposable directory, never connects databases. */
@EnabledIfSystemProperty(named="dba.driver.integration",matches="true")
class DriverInstallationTest {
    @TempDir Path directory;
    @Test @Timeout(1800) void latestBundlesLoadWithRuntimeDependenciesAndReuseVerifiedCache()throws Exception {
        var bundles=new DriverBundles(directory);
        var selected=java.util.Set.of(System.getProperty("dba.driver.ids","").split(","));var failures=new java.util.ArrayList<String>();var checked=new java.util.HashSet<String>();
        for(var t:DatabaseCatalog.ALL){if(t.id().equals("custom")||!selected.contains("")&&!selected.contains(t.id()))continue;
            if(!checked.add(t.group()+":"+t.artifact()+":"+DatabaseCatalog.classifier(t.id())))continue;
            try{
            var input=Profiles.JSON.createObjectNode().put("templateId",t.id()).put("groupId",t.group()).put("artifactId",t.artifact());
            var status=bundles.status(input,()->false);assertTrue(status.path("latestAvailable").asBoolean(),t.name()+": version lookup unavailable");input.put("version",status.path("latestVersion").asText());
            var installed=bundles.install(input,()->false,p->{});DriverBundles.verify(installed);String driver=t.driver();installed.put("driverClass",driver);
            var command=new java.util.ArrayList<String>(java.util.List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),"--enable-native-access=ALL-UNNAMED","-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),DriverProbe.class.getName(),driver,t.url()));for(var jar:installed.path("jars"))command.add(jar.asText());
            Path output=directory.resolve("probe-"+t.id()+".log");var process=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
            try{assertTrue(process.waitFor(60,java.util.concurrent.TimeUnit.SECONDS),"Driver probe timed out");assertEquals(0,process.exitValue(),t.name()+": "+Files.readString(output));}finally{if(process.isAlive()){process.destroyForcibly();process.waitFor();}}
            var cached=bundles.install(input,()->false,p->{throw new AssertionError("Cache reuse must not download");});assertEquals(installed.path("jars"),cached.path("jars"));
            System.out.println("DRIVER_VERIFIED "+t.name()+" "+input.path("version").asText()+" jars="+installed.path("jars").size()+" class="+driver);
            }catch(Throwable failure){if(failure instanceof ThreadDeath)throw failure;String summary=t.name()+": "+failure.getClass().getSimpleName()+" "+failure.getMessage();System.out.println("DRIVER_FAILED "+summary);failures.add(summary);}
        }
        assertTrue(failures.isEmpty(),String.join("\n",failures));
    }
}
