package io.doindev.codegraph.dba;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.apache.maven.*;
import org.apache.maven.execution.MavenSession;
import org.eclipse.aether.*;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.graph.*;
import org.eclipse.aether.repository.RepositoryPolicy;
import org.eclipse.aether.resolution.*;
import org.eclipse.aether.util.filter.DependencyFilterUtils;

/**
 * Packaged into a tiny, local core extension by ExternalMaven. Executes inside the
 * installed Maven, using its effective mirrors/proxies/authentication/offline cache.
 * No remote plugin, user project, Maven installation change, or application classes.
 */
public final class DriverMavenExtension extends AbstractMavenLifecycleParticipant {
    private RepositorySystem repositorySystem; // Plexus requirement in the generated descriptor.
    @Override public void afterProjectsRead(MavenSession maven) throws MavenExecutionException {
        try {
            Path work=maven.getCurrentProject().getBasedir().toPath();
            Properties input=new Properties(),output=new Properties();
            try(var in=Files.newInputStream(work.resolve("request.properties"))){input.load(in);}
            var session=new DefaultRepositorySystemSession(maven.getRepositorySession());
            session.setChecksumPolicy(RepositoryPolicy.CHECKSUM_POLICY_FAIL);
            session.setConfigProperty("aether.artifactDescriptor.ignoreRepositories",true);
            var remotes=maven.getCurrentProject().getRemoteProjectRepositories();
            String group=input.getProperty("groupId"),artifact=input.getProperty("artifactId");
            if(input.getProperty("operation").equals("status")){
                if(session.isOffline())throw new IOException("Maven is offline; latest-version status is unavailable. Use a verified cached or manual driver explicitly.");
                var versions=repositorySystem.resolveVersionRange(session,new VersionRangeRequest(new DefaultArtifact(group+":"+artifact+":[0,)"),remotes,null));
                // A missing local-only metadata file is normal for a remote artifact.
                // A failed remote lookup must not make cached metadata appear current.
                for(Exception failure:versions.getExceptions()){
                    if(failure instanceof org.eclipse.aether.transfer.MetadataNotFoundException missing&&missing.getRepository()==null)continue;
                    throw failure;
                }
                if(versions.getVersions().isEmpty()){
                    throw new IOException("No published driver versions were found in the configured Maven repositories");
                }
                // Return metadata only: selecting a database must not download a driver.
                if(versions.getVersions().size()>10000)throw new IOException("Driver version metadata exceeds 10000 entries");
                int n=0;for(var version:versions.getVersions())output.setProperty("version."+(n++),version.toString());
                output.setProperty("count",Integer.toString(n));
            } else {
                String classifier=input.getProperty("classifier",""),version=input.getProperty("version");
                var exclusions=classifier.equals("all")&&group.equals("com.google.cloud")&&artifact.equals("google-cloud-bigquery-jdbc")
                    ?List.of(new Exclusion("*","*","*","*")):List.<Exclusion>of();
                var dependency=new Dependency(new DefaultArtifact(group,artifact,classifier,"jar",version),"runtime",false,exclusions);
                var request=new DependencyRequest(new CollectRequest(dependency,remotes),DependencyFilterUtils.classpathFilter("runtime"));
                var artifacts=repositorySystem.resolveDependencies(session,request).getArtifactResults();
                if(artifacts.size()>64)throw new IOException("Driver dependency bundle exceeds 64 artifacts");
                int n=0;long bytes=0;
                for(var result:artifacts){
                    var a=result.getArtifact();if(!a.getExtension().equals("jar"))continue;
                    bytes+=Files.size(a.getFile().toPath());if(bytes>512L<<20)throw new IOException("Driver bundle exceeds 512 MiB");
                    output.setProperty("path."+n,a.getFile().getAbsolutePath());output.setProperty("coordinate."+n,a.toString());n++;
                }
                output.setProperty("count",Integer.toString(n));
            }
            try(var out=Files.newOutputStream(work.resolve("response.properties"))){output.store(out,"Resolved driver metadata; no credentials");}
        } catch(Exception e){throw new MavenExecutionException("Code Graph driver resolution failed",e);}
    }
}
