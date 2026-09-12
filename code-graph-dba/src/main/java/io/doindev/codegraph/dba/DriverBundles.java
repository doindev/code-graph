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
    private final List<RemoteRepository> remotes=List.of(new RemoteRepository.Builder("central","default","https://repo.maven.apache.org/maven2/").setReleasePolicy(new RepositoryPolicy(true,RepositoryPolicy.UPDATE_POLICY_ALWAYS,RepositoryPolicy.CHECKSUM_POLICY_FAIL)).build());
    DriverBundles(Path data)throws IOException {
        root=data.resolve("drivers");repository=root.resolve("repository");Files.createDirectories(root);
        var locator=MavenRepositorySystemUtils.newServiceLocator();locator.addService(RepositoryConnectorFactory.class,BasicRepositoryConnectorFactory.class);locator.addService(TransporterFactory.class,HttpTransporterFactory.class);
        resolver=locator.getService(RepositorySystem.class);if(resolver==null)throw new IOException("Embedded Maven Resolver initialization failed");
    }
    private DefaultRepositorySystemSession session(BooleanSupplier cancelled,Consumer<String> progress){
        var s=MavenRepositorySystemUtils.newSession();s.setLocalRepositoryManager(resolver.newLocalRepositoryManager(s,new LocalRepository(repository.toFile())));
        s.setDependencySelector(new org.eclipse.aether.util.graph.selector.AndDependencySelector(new org.eclipse.aether.util.graph.selector.ScopeDependencySelector("test","provided"),new org.eclipse.aether.util.graph.selector.OptionalDependencySelector(),new org.eclipse.aether.util.graph.selector.ExclusionDependencySelector()));
        s.setChecksumPolicy(RepositoryPolicy.CHECKSUM_POLICY_FAIL);s.setConfigProperty("aether.connector.connectTimeout",5000);s.setConfigProperty("aether.connector.requestTimeout",15000);s.setConfigProperty("aether.artifactDescriptor.ignoreRepositories",true);
        s.setTransferListener(new AbstractTransferListener(){public void transferProgressed(TransferEvent e)throws TransferCancelledException{if(cancelled.getAsBoolean())throw new TransferCancelledException();progress.accept("Downloading dependencies: "+e.getTransferredBytes()+" bytes");}public void transferInitiated(TransferEvent e)throws TransferCancelledException{if(cancelled.getAsBoolean())throw new TransferCancelledException();progress.accept("Resolving driver/dependency artifacts");}});return s;
    }
    static void coordinate(String value){if(value==null||!value.matches("[A-Za-z0-9_.-]{1,180}"))throw new IllegalArgumentException("Invalid Maven coordinate");}
    static boolean stable(String version,String template){String v=version.toLowerCase(Locale.ROOT);return !v.matches(".*(snapshot|alpha|beta|preview|rc[0-9.-]*|milestone|[.-]m[0-9]+).* ".strip())&&(!Set.of("sqlserver","azure-sql").contains(template)||v.endsWith(".jre11"));}
    private static String classifier(JsonNode input){String value=input.path("classifier").asText();if(value.isEmpty()){var t=DatabaseCatalog.get(input.path("templateId").asText("custom"));if(t.group().equals(input.path("groupId").asText())&&t.artifact().equals(input.path("artifactId").asText()))value=DatabaseCatalog.classifier(t.id());}if(!value.isEmpty())coordinate(value);return value;}
    synchronized ObjectNode status(JsonNode input,BooleanSupplier cancelled)throws Exception {
        String template=input.path("templateId").asText("custom"),group=Profiles.text(input,"groupId",180),artifact=Profiles.text(input,"artifactId",180);coordinate(group);coordinate(artifact);
        String classifier=classifier(input);
        ObjectNode result=Profiles.JSON.createObjectNode().put("groupId",group).put("artifactId",artifact).put("classifier",classifier);ArrayNode installed=result.putArray("installed");
        try(var paths=Files.list(root)){for(Path p:paths.filter(Files::isDirectory).toList()){Path manifest=p.resolve("manifest.json");if(Files.isRegularFile(manifest)&&Files.size(manifest)<65_536){JsonNode n=Profiles.JSON.readTree(manifest.toFile());if(group.equals(n.path("groupId").asText())&&artifact.equals(n.path("artifactId").asText())&&classifier.equals(n.path("classifier").asText()))try{verify(n);installed.add(n);}catch(Exception ignored){}}}}
        try{var versions=resolver.resolveVersionRange(session(cancelled,p->{}),new VersionRangeRequest(new DefaultArtifact(group+":"+artifact+":[0,)"),remotes,null)).getVersions();String latest=versions.stream().filter(v->stable(v.toString(),template)).reduce((a,b)->b).orElseThrow().toString();result.put("latestVersion",latest).put("latestAvailable",true);}
        catch(Exception e){if(cancelled.getAsBoolean())throw new java.util.concurrent.CancellationException();result.put("latestAvailable",false).put("message","Latest version unavailable. Use a verified cached bundle or browse to a local driver.");}return result;
    }
    synchronized ObjectNode install(JsonNode input,BooleanSupplier cancelled,Consumer<String> progress)throws Exception {
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
        var request=new DependencyRequest(new CollectRequest(new Dependency(new DefaultArtifact(group,artifact,classifier,"jar",version),"runtime",false,exclusions),remotes),DependencyFilterUtils.classpathFilter("runtime"));
        var results=new ArrayList<>(resolver.resolveDependencies(session(cancelled,progress),request).getArtifactResults());
        if(results.size()>64)throw new IllegalArgumentException("Driver dependency bundle exceeds 64 artifacts");
        Path staging=Files.createTempDirectory(root,".staging-");
        try{
            ObjectNode manifest=Profiles.JSON.createObjectNode().put("groupId",group).put("artifactId",artifact).put("version",version).put("classifier",classifier).put("source","Maven Central").put("bundleId",destination.getFileName().toString());ArrayNode jars=manifest.putArray("jars"),hashes=manifest.putArray("hashes"),dependencies=manifest.putArray("dependencies");long bytes=0;int index=0;
            // Keep root artifact first; supporting JARs must never shadow its driver classes.
            results.sort(Comparator.comparing(r->!r.getArtifact().getArtifactId().equals(artifact)));
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
}
