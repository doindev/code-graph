package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.io.*;
import java.security.*;
import java.util.*;
import java.util.function.*;
import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.eclipse.aether.*;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.*;
import org.eclipse.aether.resolution.*;
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory;
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory;
import org.eclipse.aether.spi.connector.transport.TransporterFactory;
import org.eclipse.aether.transport.http.HttpTransporterFactory;
import org.eclipse.aether.transfer.*;
import org.eclipse.aether.util.filter.DependencyFilterUtils;

/** Embedded Resolver, private cache, immutable verified bundles. No startup network access. */
final class DriverBundles {
    private final Path root,repository;
    private final RepositorySystem resolver;
    final DriverDownloadSettings settings;
    private static final List<RemoteRepository> CENTRAL=List.of(new RemoteRepository.Builder("central","default","https://repo.maven.apache.org/maven2/").setReleasePolicy(new RepositoryPolicy(true,RepositoryPolicy.UPDATE_POLICY_ALWAYS,RepositoryPolicy.CHECKSUM_POLICY_FAIL)).build());
    private final List<RemoteRepository> remotes;
    DriverBundles(Path data)throws IOException {
        this(data,DriverDownloadConfig.embedded());
    }
    DriverBundles(Path data,DriverDownloadConfig config)throws IOException {
        this(data,config,CENTRAL);
    }
    // Package-private repository injection for loopback integration fixtures, never a browser setting.
    DriverBundles(Path data,DriverDownloadConfig config,List<RemoteRepository> remotes)throws IOException {
        this.remotes=List.copyOf(remotes);
        root=data.resolve("drivers");repository=root.resolve("repository");Files.createDirectories(root);
        settings=new DriverDownloadSettings(data,config);
        var locator=MavenRepositorySystemUtils.newServiceLocator();locator.addService(RepositoryConnectorFactory.class,BasicRepositoryConnectorFactory.class);locator.addService(TransporterFactory.class,HttpTransporterFactory.class);
        resolver=locator.getService(RepositorySystem.class);if(resolver==null)throw new IOException("Embedded Maven Resolver initialization failed");
    }
    private DefaultRepositorySystemSession session(BooleanSupplier cancelled,Consumer<String> progress,DriverDownloadConfig config){
        var s=MavenRepositorySystemUtils.newSession();s.setLocalRepositoryManager(resolver.newLocalRepositoryManager(s,new LocalRepository(repository.toFile())));
        s.setDependencySelector(new org.eclipse.aether.util.graph.selector.AndDependencySelector(new org.eclipse.aether.util.graph.selector.ScopeDependencySelector("test","provided"),new org.eclipse.aether.util.graph.selector.OptionalDependencySelector(),new org.eclipse.aether.util.graph.selector.ExclusionDependencySelector()));
        s.setConfigProperty(ConfigurationProperties.HTTPS_SECURITY_MODE,config.insecureTls()?ConfigurationProperties.HTTPS_SECURITY_MODE_INSECURE:ConfigurationProperties.HTTPS_SECURITY_MODE_DEFAULT);
        s.setChecksumPolicy(RepositoryPolicy.CHECKSUM_POLICY_FAIL);s.setConfigProperty("aether.connector.connectTimeout",5000);s.setConfigProperty("aether.connector.requestTimeout",15000);s.setConfigProperty("aether.artifactDescriptor.ignoreRepositories",true);
        s.setTransferListener(new AbstractTransferListener(){public void transferProgressed(TransferEvent e)throws TransferCancelledException{if(cancelled.getAsBoolean())throw new TransferCancelledException();progress.accept("Downloading dependencies: "+e.getTransferredBytes()+" bytes");}public void transferInitiated(TransferEvent e)throws TransferCancelledException{if(cancelled.getAsBoolean())throw new TransferCancelledException();progress.accept("Resolving driver/dependency artifacts");}});return s;
    }
    static void coordinate(String value){if(value==null||!value.matches("[A-Za-z0-9_.-]{1,180}"))throw new IllegalArgumentException("Invalid Maven coordinate");}
    static boolean stable(String version,String template){String v=version.toLowerCase(Locale.ROOT);return !v.matches(".*(snapshot|alpha|beta|preview|rc[0-9.-]*|milestone|[.-]m[0-9]+).* ".strip())&&(!Set.of("sqlserver","azure-sql").contains(template)||v.endsWith(".jre11"));}
    private static String classifier(JsonNode input){String value=input.path("classifier").asText();if(value.isEmpty()){var t=DatabaseCatalog.get(input.path("templateId").asText("custom"));if(t.group().equals(input.path("groupId").asText())&&t.artifact().equals(input.path("artifactId").asText()))value=DatabaseCatalog.classifier(t.id());}if(!value.isEmpty())coordinate(value);return value;}
    synchronized ObjectNode status(JsonNode input,BooleanSupplier cancelled)throws Exception {
        DriverDownloadConfig config=settings.current();
        String template=input.path("templateId").asText("custom"),group=Profiles.text(input,"groupId",180),artifact=Profiles.text(input,"artifactId",180);coordinate(group);coordinate(artifact);
        String classifier=classifier(input);
        ObjectNode result=Profiles.JSON.createObjectNode().put("groupId",group).put("artifactId",artifact).put("classifier",classifier);ArrayNode installed=result.putArray("installed");
        try(var paths=Files.list(root)){for(Path p:paths.filter(Files::isDirectory).toList()){Path manifest=p.resolve("manifest.json");if(Files.isRegularFile(manifest)&&Files.size(manifest)<65_536){JsonNode n=Profiles.JSON.readTree(manifest.toFile());if(group.equals(n.path("groupId").asText())&&artifact.equals(n.path("artifactId").asText())&&classifier.equals(n.path("classifier").asText()))try{verify(n);installed.add(n);}catch(Exception ignored){}}}}
        result.put("downloadMode",config.mode()).put("insecureTls",config.insecureTls());
        try{
            List<String> versions;
            if(config.mode().equals("maven")){
                Properties request=request("status",group,artifact,"",classifier);
                Properties reply=ExternalMaven.run(root,config,request,cancelled,p->{});
                int count=Integer.parseInt(reply.getProperty("count"));if(count<0||count>10000)throw new IOException("Invalid Maven version count");
                versions=new ArrayList<>();for(int i=0;i<count;i++)versions.add(reply.getProperty("version."+i));
            }else{
                var resolved=resolver.resolveVersionRange(session(cancelled,p->{},config),new VersionRangeRequest(new DefaultArtifact(group+":"+artifact+":[0,)"),EmbeddedMavenTls.repositories(remotes,config),null));
                // Cached/local metadata must not hide a failed current remote TLS or repository check.
                for(var failure:resolved.getExceptions())if(failure instanceof MetadataTransferException transfer&&transfer.getRepository()!=null)throw failure;
                if(resolved.getVersions().isEmpty()&&!resolved.getExceptions().isEmpty())throw resolved.getExceptions().getFirst();
                versions=resolved.getVersions().stream().map(Object::toString).toList();
            }
            String latest=versions.stream().filter(v->stable(v,template)).reduce((a,b)->b).orElseThrow(()->new IOException("No stable compatible driver version found"));
            result.put("latestVersion",latest).put("latestAvailable",true);
        }catch(Exception e){
            if(cancelled.getAsBoolean()||e instanceof java.util.concurrent.CancellationException)throw new java.util.concurrent.CancellationException();
            result.put("latestAvailable",false).put("message","Latest version unavailable. Review failure details, retry, download an explicit pinned version, use a verified cached bundle, or browse to a local driver.");
            result.set("diagnostic",diagnostic("status",config,e));
        }return result;
    }
    synchronized ObjectNode install(JsonNode input,BooleanSupplier cancelled,Consumer<String> progress)throws Exception {
        DriverDownloadConfig config=settings.current();
        try{return install(input,cancelled,progress,config);}
        catch(Exception e){
            if(cancelled.getAsBoolean()||e instanceof java.util.concurrent.CancellationException)throw new java.util.concurrent.CancellationException();
            throw e instanceof DriverDiagnostics.Failure failure?failure:new DriverDiagnostics.Failure(diagnostic("install",config,e));
        }
    }
    private ObjectNode install(JsonNode input,BooleanSupplier cancelled,Consumer<String> progress,DriverDownloadConfig config)throws Exception {
        String group=Profiles.text(input,"groupId",180),artifact=Profiles.text(input,"artifactId",180),version=Profiles.text(input,"version",180);coordinate(group);coordinate(artifact);coordinate(version);
        if(!stable(version,input.path("templateId").asText("custom")))throw new IllegalArgumentException("Select a stable compatible driver release");
        String classifier=classifier(input),identity=group+":"+artifact+":"+version+(classifier.isEmpty()?"":":"+classifier);
        Path destination=root.resolve(hash(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        if(Files.exists(destination)){
            try{ObjectNode manifest=(ObjectNode)Profiles.JSON.readTree(destination.resolve("manifest.json").toFile());verify(manifest);return manifest;}
            catch(Exception corrupt){destination=root.resolve(destination.getFileName()+"-"+UUID.randomUUID());}
        }
        if(cancelled.getAsBoolean())throw new java.util.concurrent.CancellationException();
        var exclusions=classifier.equals("all")&&group.equals("com.google.cloud")&&artifact.equals("google-cloud-bigquery-jdbc")?List.of(new org.eclipse.aether.graph.Exclusion("*","*","*","*")):List.<org.eclipse.aether.graph.Exclusion>of();
        var request=new DependencyRequest(new CollectRequest(new Dependency(new DefaultArtifact(group,artifact,classifier,"jar",version),"runtime",false,exclusions),config.mode().equals("embedded")?EmbeddedMavenTls.repositories(remotes,config):remotes),DependencyFilterUtils.classpathFilter("runtime"));
        ArrayList<ArtifactResult> results;
        if(config.mode().equals("maven")){
            Properties reply=ExternalMaven.run(root,config,request("install",group,artifact,version,classifier),cancelled,progress);
            int count=Integer.parseInt(reply.getProperty("count"));if(count<1||count>64)throw new IOException("Invalid Maven artifact count");
            results=new ArrayList<>();
            for(int i=0;i<count;i++){
                var a=new DefaultArtifact(reply.getProperty("coordinate."+i)).setFile(Path.of(reply.getProperty("path."+i)).toFile());
                results.add(new ArtifactResult(new ArtifactRequest()).setArtifact(a));
            }
        }else results=new ArrayList<>(resolver.resolveDependencies(session(cancelled,progress,config),request).getArtifactResults());
        if(results.size()>64)throw new IllegalArgumentException("Driver dependency bundle exceeds 64 artifacts");
        Path staging=Files.createTempDirectory(root,".staging-");
        try{
            ObjectNode manifest=Profiles.JSON.createObjectNode().put("groupId",group).put("artifactId",artifact).put("version",version).put("classifier",classifier).put("source",config.mode().equals("maven")?"Installed Maven · configured repositories":"Maven Central").put("bundleId",destination.getFileName().toString());ArrayNode jars=manifest.putArray("jars"),hashes=manifest.putArray("hashes"),dependencies=manifest.putArray("dependencies");long bytes=0;int index=0;
            // Keep root artifact first; supporting JARs must never shadow its driver classes.
            results.sort(Comparator.comparing(r->!(r.getArtifact().getArtifactId().equals(artifact)&&r.getArtifact().getGroupId().equals(group)&&r.getArtifact().getClassifier().equals(classifier))));
            for(var r:results){if(cancelled.getAsBoolean())throw new java.util.concurrent.CancellationException();if(!r.getArtifact().getExtension().equals("jar"))continue;Path source=r.getArtifact().getFile().toPath();bytes+=Files.size(source);if(bytes>512L<<20)throw new IllegalArgumentException("Driver bundle exceeds 512 MiB");String name=(index++)+"-"+source.getFileName();Files.copy(source,staging.resolve(name));jars.add(destination.resolve(name).toString());hashes.add(hashFile(source));dependencies.add(r.getArtifact().toString());}
            if(jars.isEmpty())throw new IllegalArgumentException("No JDBC JAR found");
            manifest.put("jar",jars.get(0).asText());Files.write(staging.resolve("manifest.json"),Profiles.JSON.writeValueAsBytes(manifest));
            Files.move(staging,destination,StandardCopyOption.ATOMIC_MOVE);return manifest;
        }finally{if(Files.exists(staging)){try(var files=Files.walk(staging)){for(Path p:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}}
    }
    static void verify(JsonNode manifest)throws Exception{JsonNode jars=manifest.path("jars"),hashes=manifest.path("hashes");if(!jars.isArray()||jars.isEmpty()||jars.size()!=hashes.size())throw new IllegalArgumentException("Invalid driver bundle");for(int i=0;i<jars.size();i++)if(!hashFile(Path.of(jars.get(i).asText())).equals(hashes.get(i).asText()))throw new IllegalArgumentException("Driver content changed; browse/install and test again");}
    static String hashFile(Path path)throws Exception{var md=MessageDigest.getInstance("SHA-256");try(InputStream in=Files.newInputStream(path)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)md.update(b,0,n);}return HexFormat.of().formatHex(md.digest());}
    static String hash(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException(e);}}
    Path root(){return root;}
    private static Properties request(String operation,String group,String artifact,String version,String classifier){
        Properties p=new Properties();p.setProperty("operation",operation);p.setProperty("groupId",group);p.setProperty("artifactId",artifact);p.setProperty("version",version);p.setProperty("classifier",classifier);return p;
    }
    private static ObjectNode diagnostic(String operation,DriverDownloadConfig config,Exception error){
        return error instanceof DriverDiagnostics.Failure failure?failure.diagnostic:DriverDiagnostics.describe(operation,config.mode(),error,"",List.of(),null);
    }
}
