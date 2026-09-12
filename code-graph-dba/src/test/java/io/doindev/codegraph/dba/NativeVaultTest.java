package io.doindev.codegraph.dba;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in: uses and then removes only a fresh test-owned OS credential entry. */
class NativeVaultTest {
    @Test @EnabledIfSystemProperty(named="dba.vault.integration",matches="true")
    void chunkedConfigurationRoundTrip()throws Exception{Vault vault=Vault.system();var secret=Profiles.JSON.createObjectNode().put("password","test-chunk-password");secret.putObject("properties").put("oversizedTestProperty","chunk-data-".repeat(1200));var profile=Profiles.JSON.createObjectNode();try{profile.set("credentialRefs",SecretRecords.write(vault,secret));assertTrue(profile.path("credentialRefs").size()>4);assertEquals(secret,SecretRecords.read(vault,profile));}finally{SecretRecords.remove(vault,profile);}}
    @Test @EnabledIfSystemProperty(named="dba.vault.integration",matches="true")
    void realOperatingSystemRoundTrip(){Vault vault=Vault.system();String id="code-graph-dba-test/"+UUID.randomUUID();try{vault.put(id,"test-only".getBytes(StandardCharsets.UTF_8));assertEquals("test-only",new String(vault.get(id),StandardCharsets.UTF_8));vault.put(id,"replacement".getBytes(StandardCharsets.UTF_8));assertEquals("replacement",new String(vault.get(id),StandardCharsets.UTF_8));}finally{vault.remove(id);}assertThrows(RuntimeException.class,()->vault.get(id));}
}
