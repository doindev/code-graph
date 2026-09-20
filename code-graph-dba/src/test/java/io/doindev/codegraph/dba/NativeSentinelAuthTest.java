package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeSentinelAuthTest {
    @TempDir Path root;
    private static io.lettuce.core.RedisCredentials credentials(io.lettuce.core.RedisURI uri) {
        return uri.getCredentialsProvider().resolveCredentials().block(java.time.Duration.ofSeconds(1));
    }

    static ObjectNode input() {
        ObjectNode input=Profiles.JSON.createObjectNode().put("name","Sentinel")
                .put("templateId","redis-native").put("url","redis://localhost:26379")
                .put("username","data-user").put("password","data-secret");
        input.putObject("nativeOptions").put("topology","sentinel").put("database","3")
                .put("sentinelMaster","primary").put("sentinelUsername","sentinel-user")
                .putArray("seeds").add("redis://localhost:26380");
        input.putObject("secretProperties").put("sentinelPassword","sentinel-secret");
        return input;
    }

    @Test void authenticationStaysSeparateOnEverySeed() {
        ConnectionDraft draft=NativeProfile.create(input(),Profiles.JSON.createObjectNode());
        Properties secrets=draft.properties();
        try {
            var uri=NativeConnections.redisUri(draft.profile(),secrets);
            assertEquals("data-user",credentials(uri).getUsername());
            assertArrayEquals("data-secret".toCharArray(),credentials(uri).getPassword());
            assertEquals(3,uri.getDatabase());
            assertEquals(2,uri.getSentinels().size());
            for(var seed:uri.getSentinels()) {
                assertEquals("sentinel-user",credentials(seed).getUsername());
                assertArrayEquals("sentinel-secret".toCharArray(),credentials(seed).getPassword());
                assertEquals(0,seed.getDatabase());
                assertTrue(seed.isVerifyPeer());
            }
        } finally {secrets.clear();draft.clear();}
    }

    @Test void passwordOnlyAndUnauthenticatedSentinelNeverInheritDataCredentials() {
        ObjectNode input=input();input.withObject("nativeOptions").remove("sentinelUsername");
        ConnectionDraft draft=NativeProfile.create(input,Profiles.JSON.createObjectNode());
        var uri=NativeConnections.redisUri(draft.profile(),draft.properties());
        for(var seed:uri.getSentinels()) {
            assertFalse(credentials(seed).hasUsername());assertArrayEquals("sentinel-secret".toCharArray(),credentials(seed).getPassword());
        }
        input.remove("secretProperties");
        draft=NativeProfile.create(input,Profiles.JSON.createObjectNode());
        uri=NativeConnections.redisUri(draft.profile(),draft.properties());
        for(var seed:uri.getSentinels()) {assertFalse(credentials(seed).hasUsername());assertFalse(credentials(seed).hasPassword());}
        assertArrayEquals("data-secret".toCharArray(),credentials(uri).getPassword());
    }

    @Test void vaultKeepReplaceRemoveRevisionAndRedaction()throws Exception {
        var vault=new DbaTest.MemoryVault();
        try(var profiles=new Profiles(root,vault)) {
            ObjectNode profile=profiles.put(null,input());String id=profile.path("id").asText();
            String revision=ProjectContexts.profileRevision(profiles.get(id));
            assertTrue(profile.path("hasCredential").asBoolean());
            assertEquals("sentinelPassword",profile.path("secretPropertyNames").get(0).asText());
            assertFalse(profile.toString().contains("sentinel-secret"));
            assertFalse(Profiles.agentProfile(profiles.get(id)).toString().contains("sentinel-secret"));
            assertFalse(Files.readString(root.resolve("profiles.json")).contains("sentinel-secret"));
            assertEquals("[redacted] [redacted]",profiles.redactError(id,"data-secret sentinel-secret"));
            profiles.put(id,Profiles.JSON.createObjectNode().put("color","#334455"));
            assertEquals("sentinel-secret",profiles.credentials(profiles.get(id)).getProperty("sentinelPassword"));
            ObjectNode edit=Profiles.JSON.createObjectNode();edit.putObject("secretProperties").put("sentinelPassword","replacement");
            profiles.put(id,edit);
            assertNotEquals(revision,ProjectContexts.profileRevision(profiles.get(id)));
            assertEquals("replacement",profiles.credentials(profiles.get(id)).getProperty("sentinelPassword"));
            assertEquals(1,vault.secrets.size());
            edit.withObject("secretProperties").putNull("sentinelPassword");
            profiles.put(id,edit);
            assertNull(profiles.credentials(profiles.get(id)).getProperty("sentinelPassword"));
            assertEquals("data-secret",profiles.credentials(profiles.get(id)).getProperty("password"));
            profiles.remove(id);assertTrue(vault.secrets.isEmpty());
        }
    }

    @Test void invalidOptionsAndSecretSmugglingFailWithoutEchoingValues() {
        for(String topology:List.of("standalone","cluster")) {
            ObjectNode input=input();input.withObject("nativeOptions").remove(List.of("sentinelMaster","seeds"));
            input.withObject("nativeOptions").put("topology",topology).put("database","0");
            assertThrows(IllegalArgumentException.class,()->NativeProfile.create(input,Profiles.JSON.createObjectNode()));
        }
        for(Object bad:List.of(17,true,List.of("secret"))) {
            ObjectNode input=input();input.withObject("secretProperties").set("sentinelPassword",Profiles.JSON.valueToTree(bad));
            assertThrows(IllegalArgumentException.class,()->NativeProfile.create(input,Profiles.JSON.createObjectNode()));
        }
        ObjectNode input=input();input.withObject("nativeOptions").put("sentinelPassword","never-echo-this");
        assertFalse(assertThrows(IllegalArgumentException.class,()->NativeProfile.create(input,Profiles.JSON.createObjectNode())).getMessage().contains("never-echo-this"));
        input.withObject("nativeOptions").remove("sentinelPassword");input.withObject("secretProperties").put("arbitraryNativeOption","x");
        assertThrows(IllegalArgumentException.class,()->NativeProfile.create(input,Profiles.JSON.createObjectNode()));
        ObjectNode oversized=input();oversized.withObject("secretProperties").put("sentinelPassword","x".repeat(32769));
        assertThrows(IllegalArgumentException.class,()->NativeProfile.create(oversized,Profiles.JSON.createObjectNode()));
        ObjectNode mongo=input().put("templateId","mongodb-native").put("url","mongodb://localhost");
        mongo.withObject("nativeOptions").removeAll();
        assertThrows(IllegalArgumentException.class,()->NativeProfile.create(mongo,Profiles.JSON.createObjectNode()));
    }

    @Test void changingTopologyRequiresExplicitSecretRemovalAndClearingDoesNotEraseDataPassword() {
        var saved=NativeProfile.create(input(),Profiles.JSON.createObjectNode());
        ObjectNode next=saved.profile().deepCopy();
        next.withObject("nativeOptions").remove(List.of("sentinelUsername","sentinelMaster","seeds"));
        next.withObject("nativeOptions").put("topology","standalone");
        assertThrows(IllegalArgumentException.class,()->NativeProfile.create(next,saved.secret()));
        next.putObject("secretProperties").putNull("sentinelPassword");
        var changed=NativeProfile.create(next,saved.secret());
        assertFalse(changed.secret().path("properties").has("sentinelPassword"));
        assertEquals("data-secret",changed.properties().getProperty("password"));
        assertEquals("sentinel-secret",saved.properties().getProperty("sentinelPassword"),"Draft validation must not mutate saved credentials");
    }

    @Test void standalonePasswordIsNotClearedBeforeTheDriverCanAuthenticate() {
        ObjectNode input=input();input.withObject("nativeOptions").removeAll();input.remove("secretProperties");
        var draft=NativeProfile.create(input,Profiles.JSON.createObjectNode());
        Properties source=draft.properties();var uri=NativeConnections.redisUri(draft.profile(),source);
        source.clear();draft.clear();
        assertEquals("data-user",credentials(uri).getUsername());
        assertArrayEquals("data-secret".toCharArray(),credentials(uri).getPassword());
    }

    @Test void changedSentinelSecretInvalidatesTestFingerprintAndTlsStillVerifiesPeers() {
        ObjectNode input=input().put("url","rediss://localhost:26379");
        input.withObject("nativeOptions").putArray("seeds").add("rediss://localhost:26380");
        ConnectionDraft draft=NativeProfile.create(input,Profiles.JSON.createObjectNode());
        String fingerprint=draft.fingerprint();
        var uri=NativeConnections.redisUri(draft.profile(),draft.properties());
        assertTrue(uri.isSsl());assertTrue(uri.isVerifyPeer());
        for(var seed:uri.getSentinels()){assertTrue(seed.isSsl());assertTrue(seed.isVerifyPeer());}
        input.withObject("secretProperties").put("sentinelPassword","changed");
        assertNotEquals(fingerprint,NativeProfile.create(input,Profiles.JSON.createObjectNode()).fingerprint());
    }
}
