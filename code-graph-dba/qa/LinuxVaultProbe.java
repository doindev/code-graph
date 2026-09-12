package io.doindev.codegraph.dba;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Runs against an actual container Secret Service, never a mock. */
public final class LinuxVaultProbe {
    public static void main(String[] args){
        Vault vault=Vault.system();String id=System.getenv("DBA_VAULT_TEST_ID");
        if(id==null||!id.startsWith("code-graph-dba-test-"))throw new IllegalArgumentException("Test-owned vault ID required");
        switch(args[0]){
            case "roundtrip"->{byte[] initial="fixture-only".getBytes(StandardCharsets.UTF_8),replacement="fixture-replaced".getBytes(StandardCharsets.UTF_8);vault.put(id,initial);if(!Arrays.equals(initial,vault.get(id)))throw new AssertionError("Read mismatch");vault.put(id,replacement);if(!Arrays.equals(replacement,vault.get(id)))throw new AssertionError("Replace mismatch");System.out.println("PASS actual Linux Secret Service create/read/replace");}
            case "locked","missing"->{try{vault.get(id);}catch(RuntimeException expected){System.out.println("PASS vault fails closed: "+args[0]);return;}throw new AssertionError("Vault did not fail closed: "+args[0]);}
            case "remove"->{if(!new String(vault.get(id),StandardCharsets.UTF_8).equals("fixture-replaced"))throw new AssertionError("Entry unavailable before removal");vault.remove(id);try{vault.get(id);}catch(RuntimeException expected){System.out.println("PASS test-owned entry removal");return;}throw new AssertionError("Entry not removed");}
            default->throw new IllegalArgumentException("Unknown probe");
        }
    }
}
