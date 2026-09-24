package io.doindev.codegraph.dba;

import java.nio.file.Path;
import java.security.KeyStore;
import java.util.*;
import javax.net.ssl.*;
import org.eclipse.aether.repository.*;
import org.eclipse.aether.util.repository.AuthenticationBuilder;

/** Trust configuration belongs to one embedded resolution, never the JVM default. */
final class EmbeddedMavenTls {
    private EmbeddedMavenTls() {}
    static List<RemoteRepository> repositories(List<RemoteRepository> repositories,DriverDownloadConfig config)throws Exception {
        if(config.certPem()==null||config.insecureTls())return repositories;
        SSLContext context=context(config.certPem());
        String identity=UUID.randomUUID().toString();
        Authentication tls=new Authentication(){
            public void fill(AuthenticationContext auth,String key,Map<String,String> data){
                if(AuthenticationContext.SSL_CONTEXT.equals(key))auth.put(AuthenticationContext.SSL_CONTEXT,context);
            }
            public void digest(AuthenticationDigest digest){digest.update(identity);}
        };
        return repositories.stream().map(repository->new RemoteRepository.Builder(repository)
            .setAuthentication(new AuthenticationBuilder().addCustom(repository.getAuthentication()).addCustom(tls).build()).build()).toList();
    }
    private static SSLContext context(Path pem)throws Exception {
        var added=ExternalMaven.certificates(pem);
        // Honor configured JVM trust roots and add the supplied certificates in memory.
        var defaults=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        defaults.init((KeyStore)null);
        KeyStore merged=KeyStore.getInstance("PKCS12");merged.load(null,null);int index=0;
        for(var manager:defaults.getTrustManagers())if(manager instanceof X509TrustManager x509)
            for(var certificate:x509.getAcceptedIssuers())merged.setCertificateEntry("default-"+index++,certificate);
        for(var certificate:added)merged.setCertificateEntry("supplied-"+index++,certificate);
        var trust=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());trust.init(merged);
        SSLContext context=SSLContext.getInstance("TLS");context.init(null,trust.getTrustManagers(),null);return context;
    }
}
