package io.doindev.codegraph.dba;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ConnectionAppearanceTest {
    @TempDir Path root;
    @Test void colorAndOrderPersistWithoutTouchingCredentialsOrConnectionSettings()throws Exception{
        var vault=new DbaTest.MemoryVault();String first,second;
        try(var p=new Profiles(root,vault)){
            var a=new DbaTest().input().put("name","Production").put("password","private-fixture-value");first=p.put(null,a).path("id").asText();second=p.put(null,new DbaTest().input().put("name","Development")).path("id").asText();assertEquals("transparent",p.publicList().get(0).path("color").asText());
            var before=p.get(first);var refs=before.path("credentialRefs").deepCopy();var keys=new HashSet<>(vault.secrets.keySet());
            var colored=p.appearance(first,Profiles.JSON.createObjectNode().put("color","#ED6363"));assertEquals("#ed6363",colored.path("color").asText());assertEquals(refs,p.get(first).path("credentialRefs"));assertEquals(keys,vault.secrets.keySet());assertEquals(before.path("url"),p.get(first).path("url"));
            var request=Profiles.JSON.createObjectNode();request.putArray("ids").add(second).add(first);p.reorder(request);assertEquals(second,p.publicList().get(0).path("id").asText());assertEquals(keys,vault.secrets.keySet());
            assertThrows(IllegalArgumentException.class,()->p.appearance(first,Profiles.JSON.createObjectNode().put("color","url(https://example.invalid)")));
            assertThrows(IllegalArgumentException.class,()->p.appearance(first,Profiles.JSON.createObjectNode().put("color","transparent").put("url","jdbc:other")));
            request.putArray("ids").add(first).add(first);assertThrows(IllegalArgumentException.class,()->p.reorder(request));request.putArray("ids").add(first);assertThrows(IllegalArgumentException.class,()->p.reorder(request));
        }
        try(var p=new Profiles(root,vault)){assertEquals(second,p.publicList().get(0).path("id").asText());assertEquals("#ed6363",p.get(first).path("color").asText());p.appearance(first,Profiles.JSON.createObjectNode().put("color","transparent"));assertEquals("transparent",p.get(first).path("color").asText());}
    }
    @Test void colorIsValidatedButNotPartOfConnectivityFingerprint()throws Exception{
        var input=new DbaTest().input();var first=ConnectionDraft.create(input,Profiles.JSON.createObjectNode());var second=ConnectionDraft.create(input.put("color","#ffffff"),Profiles.JSON.createObjectNode());
        try{assertEquals(first.fingerprint(),second.fingerprint());assertFalse(second.properties().containsKey("color"));}finally{first.clear();second.clear();}
        for(String value:List.of("red","#fff","#ffffffff","","transparent; color:red"))assertThrows(IllegalArgumentException.class,()->ConnectionDraft.create(input.put("color",value),Profiles.JSON.createObjectNode()));
    }
}
