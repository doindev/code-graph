package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;

/** Bounded diagnostics for download failures only; never expose raw JDBC exceptions. */
final class DriverDiagnostics {
    static final int LIMIT=16000;
    static final class Failure extends java.io.IOException {
        final ObjectNode diagnostic;
        Failure(ObjectNode diagnostic){super(diagnostic.path("summary").asText());this.diagnostic=diagnostic;}
    }
    static ObjectNode describe(String operation,String mode,Throwable error,String output,Collection<String> secrets,Integer exit) {
        StringBuilder detail=new StringBuilder(output==null?"":output);
        Set<Throwable> visited=Collections.newSetFromMap(new IdentityHashMap<>());
        for(int i=0;error!=null&&i<8&&visited.add(error);i++,error=error.getCause()){
            detail.append('\n').append(error.getClass().getSimpleName()).append(": ").append(error.getMessage());
        }
        String safe=redact(detail.toString(),secrets),lower=safe.toLowerCase(Locale.ROOT),code="download_failed";
        String guidance="Check the Maven coordinates, repository availability and Maven configuration. Retry explicitly after correcting the problem.";
        if(lower.contains("pkix")||lower.contains("certificate")||lower.contains("sslhandshake")||lower.contains("trustanchors")){
            code="certificate";guidance="Configure your corporate CA PEM in DBA Settings → Driver downloads, or fix the trust configuration used by installed Maven. Certificate verification remains enabled.";
        }else if(lower.contains("401")||lower.contains("403")||lower.contains("407")||lower.contains("unauthorized")){
            code="authentication";guidance="Check mirror/server IDs and repository or proxy credentials in Maven settings.xml. Do not put credentials into JDBC URLs.";
        }else if(lower.contains("404")||lower.contains("could not find artifact")||lower.contains("no published")||lower.contains("no stable")){
            code="artifact_missing";guidance="Check that your mirror contains the requested driver/version, its POM and runtime dependencies.";
        }else if(lower.contains("timed out")||lower.contains("timeout")){
            code="timeout";guidance="Check mirror/proxy reachability. DBA RAM settings also control the setup job timeout (maximum 300 seconds).";
        }else if(lower.contains("checksum")){
            code="checksum";guidance="Ask your repository administrator to correct the artifact/checksum. Integrity checks are not bypassed.";
        }else if(lower.contains("cannot run")||lower.contains("maven executable")||lower.contains("not recognized")||lower.contains("unsupportedclassversion")){
            code="maven_setup";guidance="Install Maven 3.9.x, select its mvn/mvn.cmd executable, and use Java 25 for the Maven process (JAVA_HOME).";
        }
        ObjectNode n=Profiles.JSON.createObjectNode().put("operation",operation).put("mode",mode).put("code",code)
            .put("summary",operation.equals("status")?"Driver version check failed":"Driver download failed").put("guidance",guidance)
            .put("details",safe).put("truncated",detail.length()>LIMIT);
        if(exit!=null)n.put("exitCode",exit);return n;
    }
    static String redact(String text,Collection<String> secrets){
        String value=text==null?"":text;
        // Strip terminal escapes and controls; renderer also uses textContent, never HTML.
        value=value.replaceAll("\u001B\\[[0-9;]*[A-Za-z]","").replaceAll("[\\p{Cntrl}&&[^\\n\\r\\t]]","");
        for(String secret:secrets)if(secret!=null&&!secret.isEmpty()){
            value=value.replace(secret,"[redacted]");
            value=value.replace(java.net.URLEncoder.encode(secret,java.nio.charset.StandardCharsets.UTF_8),"[redacted]");
        }
        value=value.replaceAll("(?i)(https?://)[^\\s/@]+@","$1[redacted]@")
            .replaceAll("(https?://[^\\s?#]+)[?#][^\\s]*","$1?[redacted]")
            .replaceAll("(?im)((?:proxy-)?authorization\\s*[:=])[^\\r\\n]*","$1 [redacted]")
            .replaceAll("(?i)((?:password|passphrase|secret|token|api[-_]?key|trustStorePassword)\\s*[=:]\\s*)(?:\"[^\"]*\"|'[^']*'|[^\\s,;]+)","$1[redacted]")
            .replaceAll("(?is)(<(?:password|passphrase|secret|token|privateKey)>).*?(</[^>]+>)","$1[redacted]$2");
        return value.length()>LIMIT?value.substring(0,LIMIT)+"\n[diagnostics truncated]":value;
    }
    static Set<String> settingsSecrets(Collection<Path> paths) {
        Set<String> values=new HashSet<>();
        for(Path path:paths)try{
            if(path==null||!Files.isRegularFile(path)||Files.size(path)>1<<20)continue;
            var factory=DocumentBuilderFactory.newInstance();factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
            factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
            var builder=factory.newDocumentBuilder();builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler());
            var nodes=builder.parse(path.toFile()).getElementsByTagName("*");
            for(int i=0;i<nodes.getLength();i++){
                var node=nodes.item(i);String name=node.getNodeName().toLowerCase(Locale.ROOT);
                if(!name.matches(".*(password|passphrase|secret|token|privatekey|username|apikey).*"))continue;
                String raw=node.getTextContent().strip();if(!raw.isEmpty())values.add(raw);
                var matcher=Pattern.compile("\\$\\{env\\.([^}]+)}").matcher(raw);
                while(matcher.find()){String resolved=System.getenv(matcher.group(1));if(resolved!=null&&!resolved.isEmpty())values.add(resolved);}
            }
        }catch(Exception ignored){/* Never log settings contents or parsing exceptions. */}
        return values;
    }
}
