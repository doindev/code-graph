package io.doindev.codegraph.dba;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.cert.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.jar.*;

/** A cancellable Maven process, never a shell command supplied by a browser/agent. */
final class ExternalMaven {
    private static final String EXTENSION="io/doindev/codegraph/dba/DriverMavenExtension.class";
    static Path executable(DriverDownloadConfig config)throws IOException {
        boolean windows=System.getProperty("os.name").startsWith("Windows");
        String name=config.command().isBlank()?(windows?"mvn.cmd":"mvn"):config.command();
        Path supplied=Path.of(name);
        if(supplied.isAbsolute()||supplied.getParent()!=null){
            if(!Files.isRegularFile(supplied))throw new IOException("Maven executable not found; configure the full mvn/mvn.cmd path");
            return supplied.toAbsolutePath().normalize();
        }
        for(String dir:System.getenv().getOrDefault("PATH","").split(java.io.File.pathSeparator)){
            Path candidate=Path.of(dir.replace("\"","")).resolve(name);
            if(Files.isRegularFile(candidate))return candidate.toAbsolutePath().normalize();
        }
        throw new IOException("Maven executable not found on PATH; configure the full mvn/mvn.cmd path");
    }
    static Properties run(Path root,DriverDownloadConfig config,Properties input,BooleanSupplier cancelled,Consumer<String> progress)throws Exception {
        Path work=Files.createTempDirectory(root,".maven-");Process process=null;Thread reader=null;
        StringBuilder tail=new StringBuilder();Integer exit=null;
        Set<String> secrets=new HashSet<>();
        try{
            Path executable=executable(config);
            var settingsPaths=new ArrayList<Path>();
            settingsPaths.add(config.settings()!=null?config.settings():Path.of(System.getProperty("user.home"),".m2","settings.xml"));
            settingsPaths.add(executable.getParent().resolve("../conf/settings.xml").normalize());
            secrets.addAll(DriverDiagnostics.settingsSecrets(settingsPaths));
            try(var out=Files.newOutputStream(work.resolve("request.properties"))){input.store(out,"Driver coordinates only");}
            Files.writeString(work.resolve("pom.xml"),"""
                <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                <groupId>io.doindev</groupId><artifactId>driver-resolution</artifactId><version>1</version><packaging>pom</packaging></project>
                """);
            // Stop Maven's upward .mvn discovery at this owned, project-free workspace.
            Files.createDirectory(work.resolve(".mvn"));
            Path extension=work.resolve("driver-extension.jar");
            try(var jar=new JarOutputStream(Files.newOutputStream(extension))){
                jar.putNextEntry(new JarEntry(EXTENSION));
                try(var in=ExternalMaven.class.getClassLoader().getResourceAsStream(EXTENSION)){
                    if(in==null)throw new IOException("Bundled Maven driver bridge is missing");in.transferTo(jar);
                }
                jar.closeEntry();jar.putNextEntry(new JarEntry("META-INF/plexus/components.xml"));
                jar.write("""
                    <component-set><components><component>
                    <role>org.apache.maven.AbstractMavenLifecycleParticipant</role><role-hint>codegraph-driver</role-hint>
                    <implementation>io.doindev.codegraph.dba.DriverMavenExtension</implementation>
                    <requirements><requirement><role>org.eclipse.aether.RepositorySystem</role><field-name>repositorySystem</field-name></requirement></requirements>
                    </component></components></component-set>
                    """.getBytes(StandardCharsets.UTF_8));jar.closeEntry();
            }
            List<String> args=new ArrayList<>(List.of(executable.toString(),"-B","-ntp","-U","-C",
                "-Dstyle.color=never","-Dmaven.ext.class.path="+extension,"-f",work.resolve("pom.xml").toString()));
            if(config.settings()!=null){args.add("-s");args.add(config.settings().toString());}
            if(config.insecureTls())args.addAll(List.of("-Dmaven.resolver.transport=wagon",
                "-Dmaven.wagon.http.ssl.insecure=true","-Dmaven.wagon.http.ssl.allowall=true","-Dmaven.wagon.http.ssl.ignore.validity.dates=true"));
            else args.addAll(List.of("-Daether.connector.https.securityMode=default","-Dmaven.wagon.http.ssl.insecure=false",
                "-Dmaven.wagon.http.ssl.allowall=false","-Dmaven.wagon.http.ssl.ignore.validity.dates=false"));
            args.add("validate");
            ProcessBuilder builder=new ProcessBuilder(command(args)).directory(work.toFile()).redirectErrorStream(true);
            builder.environment().remove("MAVEN_ARGS"); // no inherited goals/-X; Maven JVM/trust options remain.
            builder.environment().remove("MAVEN_PROJECTBASEDIR");
            builder.environment().putIfAbsent("JAVA_HOME",System.getProperty("java.home"));
            if(config.certPem()!=null&&!config.insecureTls()){
                Path trust=trustStore(work,config.certPem(),Path.of(builder.environment().get("JAVA_HOME")));
                String old=builder.environment().getOrDefault("MAVEN_OPTS","");
                builder.environment().put("MAVEN_OPTS",old+" \"-Djavax.net.ssl.trustStore="+trust+"\" -Djavax.net.ssl.trustStoreType=PKCS12 -Djavax.net.ssl.trustStorePassword=codegraph-public-certs");
            }
            if(cancelled.getAsBoolean())throw new CancellationException();
            progress.accept("Installed Maven: "+(input.getProperty("operation").equals("status")?"checking driver versions":"resolving driver and runtime dependencies"));
            process=builder.start();process.getOutputStream().close();Process running=process;
            reader=Thread.ofPlatform().daemon().name("driver-maven-output").start(()->{
                try(var in=new InputStreamReader(running.getInputStream(),StandardCharsets.UTF_8)){
                    char[] chunk=new char[2048];int n;
                    while((n=in.read(chunk))!=-1)synchronized(tail){
                        tail.append(chunk,0,n);if(tail.length()>32768)tail.delete(0,tail.length()-32768);
                    }
                }catch(IOException ignored){}
            });
            long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(5);
            while(!process.waitFor(100,TimeUnit.MILLISECONDS)){
                if(cancelled.getAsBoolean())throw new CancellationException();
                if(System.nanoTime()>=deadline)throw new IOException("Maven download timeout (300 seconds)");
            }
            exit=process.exitValue();reader.join(2000);
            if(cancelled.getAsBoolean())throw new CancellationException();
            Path result=work.resolve("response.properties");
            if(exit!=0)throw new IOException("Maven exited with code "+exit);
            if(!Files.isRegularFile(result)||Files.size(result)>1<<20)throw new IOException("Maven did not return a bounded driver result; use Maven 3.9.x with Java 25");
            Properties output=new Properties();try(var in=Files.newInputStream(result)){output.load(in);}return output;
        }catch(InterruptedException|CancellationException e){throw new CancellationException("Maven driver operation cancelled");}
        catch(Exception e){
            String log;synchronized(tail){log=tail.toString();}
            throw new DriverDiagnostics.Failure(DriverDiagnostics.describe(input.getProperty("operation"),"maven",e,log,secrets,exit));
        }finally{
            if(process!=null&&process.isAlive()){
                var descendants=process.descendants().toList();descendants.forEach(ProcessHandle::destroyForcibly);process.destroyForcibly();
                try{process.waitFor(3,TimeUnit.SECONDS);}catch(InterruptedException ignored){Thread.currentThread().interrupt();}
            }
            if(process!=null)try{process.getInputStream().close();}catch(IOException ignored){}
            if(reader!=null)try{reader.join(1000);}catch(InterruptedException ignored){Thread.currentThread().interrupt();}
            // Only this call's newly-created directory; never Maven's cache/settings/PEM.
            try(var files=Files.walk(work)){for(Path p:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}
            secrets.clear();
        }
    }
    static List<String> command(List<String> args){
        if(!System.getProperty("os.name").startsWith("Windows"))return args;
        // cmd expands percent variables even inside quotes. Reject rather than interpolate.
        for(String arg:args)if(arg.matches("(?s).*[\"%\\r\\n].*"))throw new IllegalArgumentException("Maven paths cannot contain quotes, percent signs or line breaks on Windows");
        String quoted=args.stream().map(a->"\""+a+"\"").collect(java.util.stream.Collectors.joining(" "));
        return List.of(System.getenv().getOrDefault("ComSpec","cmd.exe"),"/d","/v:off","/s","/c","\""+quoted+"\"");
    }
    static Collection<? extends java.security.cert.Certificate> certificates(Path pem)throws Exception{
        if(Files.size(pem)>1<<20)throw new IllegalArgumentException("CA PEM exceeds 1 MiB");
        String text=Files.readString(pem);
        if(text.contains("PRIVATE KEY")||!text.contains("-----BEGIN CERTIFICATE-----"))throw new IllegalArgumentException("Select public X.509 CA certificates in PEM format, never a private key");
        var certificates=CertificateFactory.getInstance("X.509").generateCertificates(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
        if(certificates.isEmpty())throw new IllegalArgumentException("The CA PEM contains no certificates");
        for(var certificate:certificates)((X509Certificate)certificate).checkValidity();
        return certificates;
    }
    static Path trustStore(Path work,Path pem,Path javaHome)throws Exception{
        var added=certificates(pem);KeyStore merged=KeyStore.getInstance("PKCS12");merged.load(null,null);
        Path roots=javaHome.resolve("lib/security/cacerts");int count=0;
        if(Files.isRegularFile(roots)){
            KeyStore defaults=KeyStore.getInstance(roots.toFile(),"changeit".toCharArray());
            for(var names=defaults.aliases();names.hasMoreElements();){var cert=defaults.getCertificate(names.nextElement());if(cert!=null)merged.setCertificateEntry("jdk-"+count++,cert);}
        }else throw new IllegalArgumentException("Maven JAVA_HOME has no readable lib/security/cacerts; configure JAVA_HOME before adding a CA PEM");
        for(var certificate:added)merged.setCertificateEntry("corporate-"+count++,certificate);
        Path destination=work.resolve("ca-trust.p12");
        try(var out=Files.newOutputStream(destination)){merged.store(out,"codegraph-public-certs".toCharArray());}
        return destination;
    }
}
