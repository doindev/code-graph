package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.util.*;

/** One private, unsaved configuration snapshot. Never stringify secrets in diagnostics. */
record ConnectionDraft(ObjectNode profile,ObjectNode secret) {
    @Override public String toString(){return "ConnectionDraft[redacted]";}
    static ConnectionDraft create(JsonNode input,ObjectNode oldSecret)throws Exception {
        if(DatabaseTransport.of(input)!=DatabaseTransport.JDBC)return NativeProfile.create(input,oldSecret);
        String template=input.path("templateId").asText("custom");DatabaseCatalog.get(template);
        String url=Profiles.text(input,"url",8192),driver=Profiles.text(input,"driverClass",200);
        if(!url.startsWith("jdbc:")||url.matches("(?is).*(password|passwd|pwd|secret|token|credential|user|privatekey|private_key_base64)=.*"))throw new IllegalArgumentException("JDBC URL must not contain credentials; use the credential/property controls");
        String decoded=java.net.URLDecoder.decode(url,java.nio.charset.StandardCharsets.UTF_8);
        if(decoded.matches("(?is).*(password|passwd|pwd|secret|token|credential|user|privatekey|private_key[^=]*)=.*")||decoded.matches("(?is).*//[^/?#]*@.*")||decoded.matches("(?is)jdbc:oracle:thin:[^@].*@.*"))throw new IllegalArgumentException("Credentials and key authentication must be supplied as properties, never in the URL");
        if(!driver.matches("[A-Za-z_$][A-Za-z0-9_$.]+"))throw new IllegalArgumentException("Invalid driver class");
        ObjectNode p=Profiles.JSON.createObjectNode().put("name",Profiles.text(input,"name",120)).put("templateId",template).put("url",url).put("driverClass",driver);
        p.put("color",color(input));
        if(input.has("username"))p.put("username",input.path("username").asText());
        p.put("readOnly",input.path("readOnly").asBoolean(true));
        ArrayNode jars=p.putArray("jars"),hashes=p.putArray("hashes");JsonNode supplied=input.path("jars");if(!supplied.isArray()||supplied.isEmpty())supplied=Profiles.JSON.createArrayNode().add(Profiles.text(input,"jar",4096));
        if(supplied.size()>64)throw new IllegalArgumentException("Maximum 64 driver JARs");long total=0;
        for(JsonNode jar:supplied){Path path=Path.of(jar.asText()).toRealPath();if(!Files.isRegularFile(path)||!path.toString().toLowerCase(Locale.ROOT).endsWith(".jar"))throw new IllegalArgumentException("Choose readable JDBC JAR files");total+=Files.size(path);if(total>512L<<20)throw new IllegalArgumentException("Driver bundle exceeds 512 MiB");jars.add(path.toString());hashes.add(DriverBundles.hashFile(path));}
        p.put("jar",jars.get(0).asText());if(input.has("driverBundle")){ObjectNode bundle=p.putObject("driverBundle");for(String k:List.of("groupId","artifactId","version","classifier","source","bundleId"))if(input.path("driverBundle").has(k)&&!input.path("driverBundle").path(k).asText().isBlank())bundle.put(k,Profiles.text(input.path("driverBundle"),k,200));bundle.set("jars",jars.deepCopy());bundle.set("hashes",hashes.deepCopy());}
        ObjectNode secret=oldSecret.deepCopy();if(input.path("removePassword").asBoolean())secret.remove("password");if(input.has("password"))secret.put("password",input.path("password").asText());
        ObjectNode hidden=secret.has("properties")?(ObjectNode)secret.path("properties"):secret.putObject("properties");
        if(input.path("replaceSecretProperties").asBoolean())hidden.removeAll();
        JsonNode changes=input.path("secretProperties");if(!changes.isMissingNode()&&!changes.isObject())throw new IllegalArgumentException("secretProperties must be an object");
        changes.fields().forEachRemaining(e->{if(e.getValue().isNull())hidden.remove(e.getKey());else hidden.put(e.getKey(),propertyValue(e.getValue()));});
        ObjectNode properties=p.putObject("properties");JsonNode props=input.path("properties");if(!props.isMissingNode()&&!props.isObject())throw new IllegalArgumentException("properties must be an object");
        props.fields().forEachRemaining(e->{String k=e.getKey();String v=propertyValue(e.getValue());if(DatabaseCatalog.knownPublic(template,k)){properties.put(k,v);hidden.remove(k);}else hidden.put(k,v);});
        if(properties.size()+hidden.size()>1024)throw new IllegalArgumentException("Maximum 1024 explicit properties");
        Set<String> described=new HashSet<>();DatabaseCatalog.properties(template).forEach(n->described.add(n.path("name").asText()));
        for(String key:keys(properties,hidden))if(!template.equals("custom")&&key.toLowerCase(Locale.ROOT).contains("proxy")&&!described.contains(key))throw new IllegalArgumentException("This template does not support that proxy property; routing must not silently fall back");
        for(String key:keys(properties,hidden)){if(key.length()>180||key.isBlank())throw new IllegalArgumentException("Invalid property name");if(key.equalsIgnoreCase("password")||key.equalsIgnoreCase("user"))throw new IllegalArgumentException("Use dedicated username/password fields");String encoded=java.net.URLEncoder.encode(key,java.nio.charset.StandardCharsets.UTF_8);if(url.matches("(?is).*[?;&]"+java.util.regex.Pattern.quote(key)+"=.*")||url.matches("(?is).*[?;&]"+java.util.regex.Pattern.quote(encoded)+"=.*"))throw new IllegalArgumentException("A driver property is also present in the URL; specify it only once");}
        if(template.equals("snowflake")){
            if(!properties.has("authenticator"))properties.put("authenticator","snowflake_jwt");
            for(String key:keys(properties,hidden))if(Set.of("privatekey","private_key_base64","private_key").contains(key.toLowerCase(Locale.ROOT)))throw new IllegalArgumentException("Snowflake accepts an existing private key file only; inline keys are not supported");
            String auth=properties.path("authenticator").asText();if(!auth.equalsIgnoreCase("snowflake_jwt")&&(properties.has("private_key_file")||hidden.has("private_key_pwd")))throw new IllegalArgumentException("Remove key-file settings when using a different Snowflake authentication method");
        }
        ObjectNode pool=p.putObject("pool");JsonNode proposed=input.path("pool");
        for(String key:List.of("maximumPoolSize","minimumIdle","connectionTimeout","validationTimeout","idleTimeout","maxLifetime"))if(proposed.has(key)){long value=proposed.path(key).asLong(-1);long min=switch(key){case "maximumPoolSize"->1;case "connectionTimeout","validationTimeout"->250;default->0;};long max=key.endsWith("Size")||key.equals("minimumIdle")?16:86_400_000;if(value<min||value>max)throw new IllegalArgumentException("Invalid pool setting: "+key);pool.put(key,value);}
        if(pool.path("minimumIdle").asInt()>pool.path("maximumPoolSize").asInt(2))throw new IllegalArgumentException("Minimum idle cannot exceed maximum pool size");
        return new ConnectionDraft(p,secret);
    }
    private static String propertyValue(JsonNode n){if(!n.isValueNode()||n.isNull()||n.asText().length()>32_768)throw new IllegalArgumentException("Property values must be scalar and at most 32768 characters");return n.asText();}
    private static Set<String> keys(ObjectNode a,ObjectNode b){Set<String> keys=new LinkedHashSet<>();a.fieldNames().forEachRemaining(keys::add);b.fieldNames().forEachRemaining(keys::add);return keys;}
    Properties properties(){Properties props=new Properties();if(profile.has("username")&&!profile.path("username").asText().isEmpty())props.setProperty("user",Profiles.expand(profile.path("username").asText()));if(secret.has("password"))props.setProperty("password",Profiles.expand(secret.path("password").asText()));profile.path("properties").fields().forEachRemaining(e->props.setProperty(e.getKey(),Profiles.expand(e.getValue().asText())));secret.path("properties").fields().forEachRemaining(e->props.setProperty(e.getKey(),Profiles.expand(e.getValue().asText())));return props;}
    ObjectNode validateKey(){if(!profile.path("templateId").asText().equals("snowflake"))return Profiles.JSON.createObjectNode();Properties props=properties();try{if(!props.getProperty("authenticator","snowflake_jwt").equalsIgnoreCase("snowflake_jwt"))return Profiles.JSON.createObjectNode();if(props.containsKey("privateKey")||props.containsKey("privatekey")||props.containsKey("private_key_base64"))throw new IllegalArgumentException("Snowflake key-pair mode accepts an existing key file only");String file=props.getProperty("private_key_file","");if(file.isBlank())throw new IllegalArgumentException("Choose the existing Snowflake PKCS#8 key file");return SnowflakeKeys.validate(file,props.getProperty("private_key_pwd",props.getProperty("private_key_file_pwd","")).toCharArray());}finally{props.clear();}}
    static String color(JsonNode input){JsonNode n=input.path("color");if(n.isMissingNode())return "transparent";if(!n.isTextual()||!n.asText().matches("transparent|#[0-9a-fA-F]{6}"))throw new IllegalArgumentException("Connection color must be transparent or a six-digit hex color");return n.asText().toLowerCase(Locale.ROOT);}
    String fingerprint(){try{ObjectNode canonical=Profiles.JSON.createObjectNode(),tested=profile.deepCopy();tested.remove("color");canonical.set("profile",tested);canonical.set("secret",secret);canonical.set("key",validateKey());byte[] bytes=Profiles.JSON.writeValueAsBytes(canonical);try{return DriverBundles.hash(bytes);}finally{Arrays.fill(bytes,(byte)0);}}catch(java.io.IOException e){throw new IllegalArgumentException("Cannot validate draft");}}
    void clear(){secret.removeAll();}
}
