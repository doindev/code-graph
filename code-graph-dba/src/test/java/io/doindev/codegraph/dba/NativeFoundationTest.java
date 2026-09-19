package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class NativeFoundationTest {
    private final String id = UUID.randomUUID().toString();
    private ObjectNode profile(String engine) {
        return Profiles.JSON.createObjectNode().put("id", id).put("name", "Test")
                .put("templateId", engine + "-native").put("url", engine.equals("mongodb") ? "mongodb://localhost:27017" : "redis://localhost:6379");
    }
    private NativeTarget target(String engine) {
        ObjectNode request = Profiles.JSON.createObjectNode().put("connectionId", id).put("connectionName", "Test")
                .put("database", engine.equals("mongodb") ? "test" : "0");
        if (engine.equals("mongodb")) request.put("collection", "users");
        return NativeTarget.resolve(profile(engine), request);
    }
    private NativeCommand.Classification mongo(String json) throws Exception {
        return NativeCommand.classify(target("mongodb"), Profiles.JSON.readTree(json));
    }
    private NativeCommand.Classification redis(String... args) {
        return NativeCommand.classify(target("redis"), Profiles.JSON.valueToTree(args));
    }

    @Test void legacyProfilesStayJdbcAndNativeIdentityCannotBeForged() {
        for (String template : new String[]{"mongodb", "redis", "sqlserver", "custom"}) {
            ObjectNode p = Profiles.JSON.createObjectNode().put("templateId", template);
            assertEquals(DatabaseTransport.JDBC, DatabaseTransport.of(p));
            p.put("transport", "mongodb");
            assertThrows(IllegalArgumentException.class, () -> DatabaseTransport.of(p));
        }
        assertEquals(DatabaseTransport.MONGODB, DatabaseTransport.of(profile("mongodb")));
        assertEquals(DatabaseTransport.REDIS, DatabaseTransport.of(profile("redis")));
    }

    @Test void exactNamespaceRequiresNameIdAndExplicitDatabase() {
        ObjectNode request = target("mongodb").json();
        assertEquals("users", NativeTarget.resolve(profile("mongodb"), request).collection());
        request.put("connectionName", "test");
        assertThrows(IllegalArgumentException.class, () -> NativeTarget.resolve(profile("mongodb"), request));
        request.put("connectionName", "Test").remove("database");
        assertThrows(IllegalArgumentException.class, () -> NativeTarget.resolve(profile("mongodb"), request));
        request.put("database", "test").put("schema", "public");
        assertThrows(IllegalArgumentException.class, () -> NativeTarget.resolve(profile("mongodb"), request));
    }

    @Test void redisClusterCannotFallBackToAnotherDatabase() {
        ObjectNode p = profile("redis"); p.putObject("nativeOptions").put("topology", "cluster");
        ObjectNode request = target("redis").json(); request.put("database", "1");
        assertThrows(IllegalArgumentException.class, () -> NativeTarget.resolve(p, request));
        request.put("database", "0"); assertEquals("0", NativeTarget.resolve(p, request).database());
    }

    @Test void readsInspectNestedExpressionsAndCollectionTargets() throws Exception {
        assertTrue(mongo("{\"find\":\"users\",\"filter\":{\"age\":{\"$gte\":18}}}").reusableRead());
        assertFalse(mongo("{\"find\":\"users\",\"filter\":{\"$or\":[{\"$where\":\"sleep(1)\"}]}}").reusableRead());
        assertFalse(mongo("{\"find\":\"users\",\"filter\":{\"$expr\":{\"$function\":{\"body\":\"x\"}}}}").reusableRead());
        assertThrows(IllegalArgumentException.class, () -> mongo("{\"find\":\"other\"}"));
        assertThrows(IllegalArgumentException.class, () -> mongo("{\"find\":\"users\",\"$db\":\"other\"}"));
        assertThrows(IllegalArgumentException.class, () -> mongo("{\"find\":\"users\",\"tailable\":true}"));
    }

    @Test void pipelineWritesAndCrossCollectionJoinsAreNeverReusableReads() throws Exception {
        assertTrue(mongo("{\"aggregate\":\"users\",\"pipeline\":[{\"$match\":{\"age\":1}},{\"$limit\":5}]}").reusableRead());
        assertEquals(NativeCommand.Effect.WRITE, mongo("{\"aggregate\":\"users\",\"pipeline\":[{\"$merge\":\"other\"}]}").effect());
        assertEquals(NativeCommand.Effect.WRITE, mongo("{\"aggregate\":\"users\",\"pipeline\":[{\"$out\":\"other\"}]}").effect());
        assertFalse(mongo("{\"aggregate\":\"users\",\"pipeline\":[{\"$lookup\":{\"from\":\"other\",\"pipeline\":[],\"as\":\"x\"}}]}").reusableRead());
        assertFalse(mongo("{\"aggregate\":\"users\",\"pipeline\":[{\"$facet\":{\"nested\":[{\"$lookup\":{\"from\":\"other\"}}]}}]}").reusableRead());
        assertThrows(IllegalArgumentException.class, () -> mongo("{\"aggregate\":\"users\",\"pipeline\":[{\"$match\":{},\"$limit\":1}]}"));
    }

    @Test void explainIsNotPermissionToExecuteAndUnknownCommandsFailClosed() throws Exception {
        assertTrue(mongo("{\"explain\":{\"find\":\"users\"},\"verbosity\":\"queryPlanner\"}").reusableRead());
        assertFalse(mongo("{\"explain\":{\"find\":\"users\"},\"verbosity\":\"executionStats\"}").reusableRead());
        assertFalse(mongo("{\"explain\":{\"find\":\"users\"},\"verbosity\":\"allPlansExecution\"}").reusableRead());
        assertThrows(IllegalArgumentException.class, () -> mongo("{\"explain\":{\"dropDatabase\":1}}"));
        assertEquals(NativeCommand.Effect.ADMINISTRATION, mongo("{\"createUser\":\"admin\"}").effect());
        assertEquals(NativeCommand.Effect.DESTRUCTIVE, mongo("{\"dropDatabase\":1}").effect());
    }

    @Test void redisScriptsModulesAndConsumingCommandsCannotGainReadApproval() {
        assertTrue(redis("GET", "hello").reusableRead());
        assertTrue(redis("SCAN", "0", "COUNT", "10").reusableRead());
        for (String command : new String[]{"EVAL", "EVAL_RO", "FCALL", "FCALL_RO", "MONITOR", "CONFIG", "MODULE", "DEBUG", "JSON.GET", "SORT", "XREADGROUP", "PFCOUNT"})
            assertFalse(redis(command, "key").reusableRead(), command);
        for (String command : new String[]{"DEL", "GETDEL", "SPOP", "FLUSHALL", "BLPOP"}) assertEquals(NativeCommand.Effect.DESTRUCTIVE, redis(command, "key").effect());
        assertEquals(NativeCommand.Effect.WRITE, redis("SET", "key", "value").effect());
        for (String command : new String[]{"AUTH", "SELECT", "HELLO", "MULTI", "EXEC", "WATCH"}) assertThrows(IllegalArgumentException.class, () -> redis(command, "key"));
    }

    @Test void structuralBoundsLimitDepthSizeAndRejectMalformedRequests() {
        ObjectNode deep = Profiles.JSON.createObjectNode(), cursor = deep;
        for (int i=0;i<34;i++) cursor=cursor.putObject("x");
        assertThrows(IllegalArgumentException.class, () -> NativeCommand.bound(deep));
        assertThrows(IllegalArgumentException.class, () -> NativeCommand.bound(Profiles.JSON.getNodeFactory().textNode("x".repeat(131073))));
        assertThrows(IllegalArgumentException.class, () -> NativeCommand.classify(target("redis"), Profiles.JSON.createArrayNode().add("GET").add(12)));
        assertThrows(IllegalArgumentException.class, () -> NativeCommand.classify(target("mongodb"), Profiles.JSON.createArrayNode()));
    }

    @Test void profilesSeparateWriteOnlySecretsAndDoNotRequireJdbc() throws Exception {
        ObjectNode input=profile("mongodb").put("password", "private-value");
        input.putObject("nativeOptions").put("authDatabase", "admin").put("maximumPoolSize", 2).put("tls", true);
        ConnectionDraft draft=ConnectionDraft.create(input, Profiles.JSON.createObjectNode());
        assertEquals("mongodb", draft.profile().path("transport").asText());
        assertFalse(draft.profile().toString().contains("private-value"));
        assertEquals("private-value", draft.secret().path("password").asText());
        assertFalse(draft.toString().contains("private-value"));
        assertFalse(draft.fingerprint().contains("private-value"));
        draft.clear(); assertTrue(draft.secret().isEmpty());
    }

    @Test void nativeUrisRejectCredentialsOverridesAndSecretBearingErrors() {
        for (String url:new String[]{"mongodb://user:s3cret@localhost", "mongodb://user%3As3cret%40localhost", "mongodb://localhost/db", "mongodb://localhost/?password=s3cret", "mongodb://localhost/#s3cret", "mongodb://localhost:99999", "mongodb+srv://localhost:27017", "jdbc:mongodb://localhost"}) {
            var error=assertThrows(IllegalArgumentException.class, () -> NativeProfile.validateEndpoint(DatabaseTransport.MONGODB,url));
            assertFalse(error.getMessage().contains("s3cret"));
        }
        NativeProfile.validateEndpoint(DatabaseTransport.MONGODB, "mongodb://localhost:27017,localhost:27018");
        NativeProfile.validateEndpoint(DatabaseTransport.MONGODB, "mongodb+srv://example.org/");
        NativeProfile.validateEndpoint(DatabaseTransport.REDIS, "rediss://[::1]:6379");
    }

    @Test void unknownNativeOptionsAndWrongTransportCannotBeSilentlySaved() {
        ObjectNode input=profile("redis");input.putObject("nativeOptions").put("maximumPoolSize",99);
        assertThrows(IllegalArgumentException.class, () -> NativeProfile.create(input, Profiles.JSON.createObjectNode()));
        input.withObject("nativeOptions").removeAll();input.withObject("nativeOptions").put("tlsInsecure",true);
        assertThrows(IllegalArgumentException.class, () -> NativeProfile.create(input, Profiles.JSON.createObjectNode()));
        input.withObject("nativeOptions").removeAll();input.put("driverClass","invalid.Driver");
        assertThrows(IllegalArgumentException.class, () -> NativeProfile.create(input, Profiles.JSON.createObjectNode()));
    }

    @Test void mongoViewsValidateSourcePipelinesAndCannotSmuggleCollectionOptions()throws Exception{
        var target=target("mongodb");
        NativeMutations.validate(target,Profiles.JSON.readTree("{\"create\":\"users\",\"viewOn\":\"source\",\"pipeline\":[{\"$match\":{\"active\":true}}]}"));
        NativeMutations.validate(target,Profiles.JSON.readTree("{\"collMod\":\"users\",\"viewOn\":\"source\",\"pipeline\":[]}"));
        NativeMutations.validate(target,Profiles.JSON.readTree("{\"collMod\":\"users\",\"validationLevel\":\"strict\"}"));
        for(String sql:List.of(
                "{\"create\":\"users\",\"viewOn\":\"source\",\"pipeline\":[{\"$merge\":\"other\"}]}",
                "{\"create\":\"users\",\"viewOn\":\"source\",\"pipeline\":[{\"$match\":{\"$where\":\"code\"}}]}",
                "{\"create\":\"users\",\"viewOn\":\"source\",\"pipeline\":[],\"capped\":true}",
                "{\"create\":\"users\",\"pipeline\":[]}",
                "{\"collMod\":\"users\",\"viewOn\":\"system.users\",\"pipeline\":[]}",
                "{\"collMod\":\"users\"}")){
            assertThrows(IllegalArgumentException.class,()->NativeMutations.validate(target,Profiles.JSON.readTree(sql)));
        }
    }
    @Test void redisConditionalSetOptionsAreExplicitAndNeverReturnUnboundedOldValues(){
        var target=target("redis");
        for(String[] args:new String[][]{{"SET","k","v"},{"SET","k","v","NX","EX","10"},{"SET","k","v","PX","100","XX"},{"SET","k","v","XX","KEEPTTL"}})
            NativeMutations.validate(target,Profiles.JSON.valueToTree(args));
        for(String[] args:new String[][]{{"SET","k","v","NX","XX"},{"SET","k","v","GET"},{"SET","k","v","EX","0"},{"SET","k","v","PX"},{"SET","k","v","KEEPTTL","EX","10"}})
            assertThrows(IllegalArgumentException.class,()->NativeMutations.validate(target,Profiles.JSON.valueToTree(args)));
    }
    @Test void redisBinaryValuesAreExplicitBoundedAndCannotReplaceControlArguments()throws Exception{
        var target=target("redis");
        var command=Profiles.JSON.readTree("[\"SET\",{\"base64\":\"/wA=\"},{\"base64\":\"AP8B\"},\"NX\"]");
        NativeMutations.validate(target,command);
        assertArrayEquals(new byte[]{(byte)255,0},NativeRedisArguments.bytes(command,1));
        assertArrayEquals(new byte[]{0,(byte)255,1},NativeRedisArguments.bytes(command,2));
        for(String text:List.of(
                "[{\"base64\":\"U0VU\"},\"k\",\"v\"]",
                "[\"SET\",\"k\",\"v\",{\"base64\":\"Tlg=\"}]",
                "[\"EXPIRE\",\"k\",{\"base64\":\"NjA=\"}]",
                "[\"SCAN\",{\"base64\":\"MA==\"}]",
                "[\"GET\",{\"base64\":\"/w\"}]",
                "[\"GET\",{\"base64\":\"/w==\",\"other\":true}]",
                "[\"GET\",{\"base64\":\"_w==\"}]",
                "[\"GET\",{\"base64\":\"AB==\"}]",
                "[\"ZADD\",\"k\",{\"base64\":\"MQ==\"},\"v\"]",
                "[\"HSCAN\",\"k\",\"0\",\"MATCH\",{\"base64\":\"Kg==\"}]")){
            var error=assertThrows(IllegalArgumentException.class,()->NativeCommand.classify(target,Profiles.JSON.readTree(text)),text);
            assertFalse(error.getMessage().contains("AB=="));
        }
        var hugeKey=Profiles.JSON.createArrayNode().add("GET");
        hugeKey.addObject().put("base64",java.util.Base64.getEncoder().encodeToString(new byte[8193]));
        assertThrows(IllegalArgumentException.class,()->NativeCommand.classify(target,hugeKey));
        var hugeValue=Profiles.JSON.createArrayNode().add("SET").add("key");
        hugeValue.addObject().put("base64",java.util.Base64.getEncoder().encodeToString(new byte[65537]));
        assertThrows(IllegalArgumentException.class,()->NativeCommand.classify(target,hugeValue));
    }
    @Test void redisTreeUsesFullBinaryIdentityNotItsDisplayLabel(){
        var row=NativeResults.binary(new byte[]{(byte)255,0},8192);
        var node=NativeMetadataTree.keyNode(row,target("redis"));
        assertEquals("key:base64:/wA=",node.path("key").asText());
        assertEquals("/wA=",node.path("command").get(1).path("base64").asText());
        assertTrue(node.path("name").asText().startsWith("[binary key:"));
        var empty=NativeMetadataTree.keyNode(NativeResults.binary(new byte[0],8192),target("redis"));
        assertEquals("[empty key]",empty.path("name").asText());
        assertNotEquals(node.path("key"),empty.path("key"));
        assertNull(NativeMetadataTree.keyNode(NativeResults.binary(new byte[8193],8192),target("redis")));
    }
}
