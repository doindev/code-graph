package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.jar.JarFile;

/** Browser-owned setup operations share admission, deadlines and retention with query jobs. */
final class ConnectionSetup {
    private record Receipt(String owner,String connection,String fingerprint,long expires){}
    private final Map<String,Receipt> receipts=new LinkedHashMap<>();
    private final Map<String,ObjectNode> descriptorCache=new LinkedHashMap<>();
    private final Profiles profiles;
    private final QueryJobs jobs;
    final DriverBundles bundles;
    ConnectionSetup(Profiles profiles,QueryJobs jobs)throws java.io.IOException{this(profiles,jobs,DriverDownloadConfig.embedded());}
    ConnectionSetup(Profiles profiles,QueryJobs jobs,DriverDownloadConfig config)throws java.io.IOException{this.profiles=profiles;this.jobs=jobs;bundles=new DriverBundles(profiles.directory(),config);}
    ObjectNode operation(String owner,String operation,JsonNode input){
        ObjectNode snapshot=input.deepCopy();
        QueryJobs.LocalTask task=job->switch(operation){
            case "driver-status" -> bundles.status(snapshot,()->job.cancelled);
            case "driver-install" -> {var result=bundles.install(snapshot,()->job.cancelled,p->job.progress=p);result.set("classes",classes(result));yield result;}
            case "driver-inspect" -> inspect(snapshot);
            case "properties" -> descriptors(snapshot);
            case "key-validate" -> {ObjectNode key=SnowflakeKeys.validate(Profiles.text(snapshot,"path",4096),snapshot.path("passphrase").asText("").toCharArray());key.remove("contentHash");yield key;}
            case "file-select" -> select(snapshot,job);
            case "draft-test" -> test(owner,snapshot,job);
            default -> throw new IllegalArgumentException("Unsupported connection setup operation");
        };
        return operation.equals("file-select")?jobs.fileSelection(owner,task,snapshot::removeAll):jobs.local(owner,task,snapshot::removeAll);
    }
    ObjectNode test(String owner,ObjectNode input,QueryJobs.Job job)throws Exception {
        String id=input.hasNonNull("connectionId")?input.path("connectionId").asText():null;
        ConnectionDraft draft=profiles.draft(id,input);
        try{
            job.progress="Validating configuration and key file";String fingerprint=draft.fingerprint();
            if(DatabaseTransport.of(draft.profile())!=DatabaseTransport.JDBC){
                jobs.reserveNative(job);
                job.progress="Connecting native client and reading server version";
                if(job.cancelled)throw new java.util.concurrent.CancellationException();
                ObjectNode result=NativeConnections.testDraft(draft);
                if(job.cancelled)throw new java.util.concurrent.CancellationException();
                ConnectionDraft fresh=profiles.draft(id,input);
                try{if(!fingerprint.equals(fresh.fingerprint()))throw new IllegalArgumentException("Native configuration changed during test; test again");}finally{fresh.clear();}
                result.put("receipt",receipt(owner,id,fingerprint)).put("stage","connected")
                    .put("elapsedMillis",System.currentTimeMillis()-job.started);
                return result;
            }
            String url=Profiles.expand(draft.profile().path("url").asText());
            // Driver initialization is arbitrary trusted code; embedded URLs can create files on connect.
            if(!input.path("confirmDriverEffects").asBoolean()&&(url.matches("(?is)jdbc:(h2|hsqldb|sqlite|duckdb):.*")||url.matches("(?is).*(INIT|RUNSCRIPT)=.*")||draft.properties().stringPropertyNames().stream().anyMatch(n->n.toLowerCase(Locale.ROOT).contains("init"))))
                throw new IllegalArgumentException("Confirm driver initialization and possible embedded file creation before testing");
            job.progress="Loading isolated JDBC driver";
            try(var loader=Connections.loader(draft.profile())){
                Driver driver=Connections.driver(loader,draft.profile());Properties props=draft.properties();
                try{
                    if(!driver.acceptsURL(url))throw new IllegalArgumentException("The selected driver does not accept this JDBC URL");
                    job.progress="Connecting (driver cancellation is best effort)";
                    try(Connection c=driver.connect(url,props)){
                        if(c==null)throw new IllegalArgumentException("Driver rejected the URL");if(job.cancelled)throw new java.util.concurrent.CancellationException();
                        var m=c.getMetaData();ObjectNode result=Profiles.JSON.createObjectNode().put("connected",true).put("database",m.getDatabaseProductName()).put("version",m.getDatabaseProductVersion()).put("driverVersion",m.getDriverVersion()).put("elapsedMillis",System.currentTimeMillis()-job.started).put("stage","connected");
                        if(OracleDialect.isOracle(c)){job.progress="Resolving Oracle service, container and privileges";result.set("oracle",OracleDialect.capabilities(job,c));}
                        String query=versionQuery(draft.profile().path("templateId").asText(),m.getDatabaseProductName());
                        if(query!=null){job.progress="Reading database version";result.put("versionQuery",query);try(Statement statement=c.createStatement()){job.statement=statement;statement.setMaxRows(11);statement.setQueryTimeout(10);try(ResultSet rows=statement.executeQuery(query)){result.set("versionResult",QueryJobs.rows(rows,10,65536));}}catch(SQLException e){result.set("versionException",QueryJobs.exceptionInfo(e));result.put("versionWarning","Connected, but the version query was not permitted or supported. JDBC version metadata is shown instead.");}finally{job.statement=null;}}
                        else result.put("versionWarning","No verified version query for this custom driver; JDBC metadata is shown.");
                        // Re-read files after connect; a rotation during the test must not produce a receipt.
                        ConnectionDraft fresh=profiles.draft(id,input);try{if(!fingerprint.equals(fresh.fingerprint()))throw new IllegalArgumentException("Driver or key changed during test; test the new content again");}finally{fresh.clear();}
                        result.put("receipt",receipt(owner,id,fingerprint));return result;
                    }
                }finally{props.clear();}
            }
        }finally{draft.clear();}
    }
    static String versionQuery(String template,String product){
        if(template.equals("custom")){String name=product.toLowerCase(Locale.ROOT);template=name.contains("postgres")?"postgresql":name.contains("microsoft")?"sqlserver":name.contains("oracle")?"oracle":name.contains("snowflake")?"snowflake":name.contains("hsql")?"hsqldb":name;}
        return switch(template){case "postgresql","mysql","mariadb","duckdb"->"SELECT version()";case "snowflake"->"SELECT CURRENT_VERSION()";case "sqlserver"->"SELECT @@VERSION";case "oracle"->"SELECT BANNER FROM V$VERSION";case "db2"->"SELECT SERVICE_LEVEL FROM SYSIBMADM.ENV_INST_INFO";case "h2"->"SELECT H2VERSION()";case "hsqldb"->"CALL DATABASE_VERSION()";case "sqlite"->"SELECT sqlite_version()";default->null;};
    }
    private synchronized String receipt(String owner,String id,String fingerprint){prune();while(receipts.size()>=32)receipts.remove(receipts.keySet().iterator().next());String token=BrowserAuth.token();receipts.put(token,new Receipt(owner,id,fingerprint,System.currentTimeMillis()+600_000));return token;}
    private void prune(){receipts.values().removeIf(r->r.expires<System.currentTimeMillis());}
    synchronized ObjectNode save(String owner,String id,JsonNode input)throws Exception{
        prune();ConnectionDraft draft=profiles.draft(id,input);
        try{
            String fingerprint=draft.fingerprint();Receipt receipt=receipts.get(input.path("receipt").asText());
            boolean tested=receipt!=null&&receipt.owner.equals(owner)&&Objects.equals(receipt.connection,id)&&receipt.fingerprint.equals(fingerprint);
            if(!tested&&!input.path("saveUntested").asBoolean())throw new IllegalArgumentException("Test this unchanged draft first, or explicitly confirm Save untested");
            // Even Save untested must load a genuine driver and validate required key settings.
            if(DatabaseTransport.of(draft.profile())!=DatabaseTransport.JDBC)NativeConnections.validateSupported(draft.profile());
            else try(var loader=Connections.loader(draft.profile())){if(!Connections.driver(loader,draft.profile()).acceptsURL(Profiles.expand(draft.profile().path("url").asText())))throw new IllegalArgumentException("Driver does not accept URL");}
            ObjectNode saved=profiles.saveDraft(id,draft,tested?"tested":"untested");receipts.remove(input.path("receipt").asText());return saved;
        }finally{draft.clear();}
    }
    static ObjectNode inspect(JsonNode input)throws Exception {
        ObjectNode out=Profiles.JSON.createObjectNode();ArrayNode jars=out.putArray("jars");JsonNode supplied=input.path("jars");if(!supplied.isArray()||supplied.isEmpty()||supplied.size()>64)throw new IllegalArgumentException("Select between 1 and 64 JARs");long total=0;
        for(JsonNode n:supplied){Path p=Path.of(n.asText()).toRealPath();if(!p.toString().toLowerCase(Locale.ROOT).endsWith(".jar")||!Files.isRegularFile(p))throw new IllegalArgumentException("JAR files only");total+=Files.size(p);if(total>512L<<20)throw new IllegalArgumentException("Driver bundle exceeds 512 MiB");jars.add(p.toString());}
        out.put("jar",jars.get(0).asText()).put("source","Local files");out.set("classes",classes(out));return out;
    }
    static ArrayNode classes(JsonNode input)throws Exception {
        Set<String> names=new LinkedHashSet<>();
        for(JsonNode n:input.path("jars"))try(JarFile jar=new JarFile(n.asText())){
            var service=jar.getJarEntry("META-INF/services/java.sql.Driver");if(service!=null){if(service.getSize()>16384)throw new IllegalArgumentException("Driver service descriptor too large");try(var in=jar.getInputStream(service)){String text=new String(in.readNBytes(16385),java.nio.charset.StandardCharsets.UTF_8);text.lines().map(l->l.split("#",2)[0].strip()).filter(l->l.matches("[A-Za-z_$][A-Za-z0-9_$.]+" )).forEach(names::add);}}
        }
        ArrayNode out=Profiles.JSON.createArrayNode();names.stream().limit(64).forEach(out::add);return out;
    }
    private ObjectNode descriptors(JsonNode input)throws Exception{
        String cacheKey=null;
        if(input.path("jars").isArray()){
            var identity=Profiles.JSON.createObjectNode().put("template",input.path("templateId").asText()).put("driver",input.path("driverClass").asText()).put("url",input.path("url").asText());var hashes=identity.putArray("hashes");for(JsonNode jar:input.path("jars"))hashes.add(DriverBundles.hashFile(Path.of(jar.asText())));cacheKey=DriverBundles.hash(Profiles.JSON.writeValueAsBytes(identity));
            synchronized(descriptorCache){var cached=descriptorCache.get(cacheKey);if(cached!=null)return cached.deepCopy().put("cached",true);}
        }
        String template=input.path("templateId").asText("custom");ObjectNode result=Profiles.JSON.createObjectNode();ArrayNode properties=DatabaseCatalog.properties(template);result.set("properties",properties);result.put("catalog","Curated vendor names; JDBC descriptions are specific to the selected driver. Unknown options remain secret.");
        if(input.path("jars").isArray()&&!input.path("driverClass").asText().isBlank()){
            ObjectNode p=inspect(input);p.put("driverClass",input.path("driverClass").asText());
            try(var loader=Connections.loader(p)){
                Driver driver=Connections.driver(loader,p);DriverPropertyInfo[] info=driver.getPropertyInfo(input.path("url").asText(""),new Properties());
                if(info!=null){Map<String,ObjectNode> byName=new LinkedHashMap<>();properties.forEach(n->byName.put(n.path("name").asText(),(ObjectNode)n));for(var i:info){if(byName.size()>=512)break;if(i.name==null)continue;ObjectNode prop=byName.computeIfAbsent(i.name,n->Profiles.JSON.createObjectNode().put("name",n).put("category",DatabaseCatalog.category(n)).put("secret",!DatabaseCatalog.knownPublic(template,n)).put("type","string"));prop.put("description",bounded(i.description,2048)).put("required",i.required).put("source","JDBC driver");if(!prop.path("secret").asBoolean()&&i.value!=null)prop.put("default",bounded(i.value,2048));if(i.choices!=null){ArrayNode choices=prop.putArray("choices");Arrays.stream(i.choices).limit(64).forEach(c->choices.add(bounded(c,256)));}}properties.removeAll();byName.values().forEach(properties::add);}
            }
        }
        if(cacheKey!=null&&Profiles.JSON.writeValueAsBytes(result).length<=131072)synchronized(descriptorCache){while(descriptorCache.size()>=8)descriptorCache.remove(descriptorCache.keySet().iterator().next());descriptorCache.put(cacheKey,result.deepCopy());}
        return result;
    }
    private static String bounded(String value,int max){return value==null?"":value.substring(0,Math.min(max,value.length()));}
    private static ObjectNode select(JsonNode input,QueryJobs.Job job)throws Exception{
        String kind=input.path("kind").asText();boolean key=kind.equals("key"),settings=kind.equals("maven-settings"),cert=kind.equals("maven-cert"),maven=kind.equals("maven-executable");
        if(!Set.of("jar","key","maven-settings","maven-cert","maven-executable").contains(kind))throw new IllegalArgumentException("Unsupported file selection kind");
        if(java.awt.GraphicsEnvironment.isHeadless())return Profiles.JSON.createObjectNode().put("available",false).put("message",maven?"Enter the existing local mvn/mvn.cmd file path manually, or leave blank to use PATH. Executables are not uploaded.":settings||cert?"Enter the existing local file path manually. Settings/certificates are not uploaded.":key?"Enter the existing key path manually. Key upload is not supported.":"Enter JAR paths or upload JAR files.");
        java.awt.FileDialog dialog=new java.awt.FileDialog((java.awt.Frame)null,maven?"Select Maven launcher (bin/mvn or bin/mvn.cmd)":settings?"Select Maven settings.xml":cert?"Select public CA certificate PEM":key?"Select existing PKCS#8 private key (never uploaded)":"Select trusted JDBC JARs",java.awt.FileDialog.LOAD);dialog.setMultipleMode(kind.equals("jar"));if(!key)dialog.setFilenameFilter((d,n)->maven?mavenLauncherName(n):n.toLowerCase(Locale.ROOT).endsWith(settings?".xml":cert?".pem":".jar"));
        try{job.progress="Waiting for the native file picker";java.awt.EventQueue.invokeAndWait(()->dialog.setVisible(true));ArrayNode paths=Profiles.JSON.createArrayNode();for(var file:dialog.getFiles()){if(maven)validateMavenSelection(file.toPath());paths.add(file.getAbsolutePath());}return Profiles.JSON.createObjectNode().put("available",true).set("paths",paths);}finally{java.awt.EventQueue.invokeLater(dialog::dispose);}
    }
    static boolean mavenLauncherName(String name){return Set.of("mvn","mvn.cmd","mvn.bat","mvn.exe").contains(name.toLowerCase(Locale.ROOT));}
    // Native filename filters are advisory on some desktops. Validate the selection as well.
    static void validateMavenSelection(Path path){
        if(!Files.isRegularFile(path)||!Files.isReadable(path)||!mavenLauncherName(path.getFileName().toString()))
            throw new IllegalArgumentException("Select the readable Maven launcher file (bin/mvn or bin/mvn.cmd), not its installation folder. Custom launchers can be entered manually.");
    }
}
