package io.doindev.codegraph.dba;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ReadPermissionsTest {
    @TempDir Path directory;
    Profiles profiles;AgentAccess agents;ReusableApprovals store;ReadPermissions reads;String agent,one,two;
    @BeforeEach void setup()throws Exception{
        profiles=new Profiles(directory,new DbaTest.MemoryVault());agents=new AgentAccess(directory);agent=agents.trustedLocal();
        one=connection("First");two=connection("Second");store=new ReusableApprovals(directory,System::currentTimeMillis);reads=new ReadPermissions(store,profiles,agents);
        store.sessions.register("session",agent,System.currentTimeMillis()+60000);store.sessions.register("other",agent,System.currentTimeMillis()+60000);
    }
    String connection(String name)throws Exception{return profiles.put(null,new DbaTest().input().put("name",name).put("templateId","mysql").put("url","jdbc:mysql://localhost/app")).path("id").asText();}
    @AfterEach void close()throws Exception{store.close();profiles.close();}
    ObjectNode scope(String connection){return ApprovalScope.resolve(profiles.get(connection),Profiles.JSON.createObjectNode(),Profiles.JSON.createObjectNode().put("database","app"));}
    ObjectNode selector(String connection,String level,String database,String schema,String object){
        var s=Profiles.JSON.createObjectNode().put("connectionId",connection).put("level",level);
        if(database!=null)s.put("database",database);if(schema!=null)s.put("schema",schema);if(object!=null)s.put("object",object);return s;
    }
    ObjectNode grant(String lifetime,ObjectNode... selectors){var g=Profiles.JSON.createObjectNode().put("lifetime",lifetime);var a=g.putArray("selectors");for(var s:selectors)a.add(s);return g;}
    ObjectNode save(ObjectNode input){return reads.save("human",reads.prepare(agent,"session",input,null,null),null);}
    List<ReadQueries.Relation> relation(String table){return List.of(new ReadQueries.Relation("app","app",table));}
    @Test void scopesUnionAndNoAutomaticConnectionExpansion(){
        save(grant("until_revoked",selector(one,"object","app","app","a")));
        assertNull(reads.match(agent,"session",scope(one),relation("b")));
        save(grant("until_revoked",selector(one,"object","app","app","b")));
        assertEquals(2,reads.match(agent,"session",scope(one),List.of(new ReadQueries.Relation("app","app","a"),new ReadQueries.Relation("app","app","b"))).size());
        assertNull(reads.match(agent,"session",scope(two),relation("a")));
        save(grant("until_revoked",selector(one,"schema","app","app",null)));
        assertNotNull(reads.match(agent,"session",scope(one),relation("future")));
        assertNull(reads.match(agent,"session",scope(one),List.of(new ReadQueries.Relation("other","other","a"))));
        save(grant("until_revoked",selector(two,"connection",null,null,null)));
        assertNotNull(reads.match(agent,"session",scope(two),List.of(new ReadQueries.Relation("futuredb","futuredb","future"))));
    }
    @Test void sessionLifetimePersistenceAndSafeLabels()throws Exception{
        save(grant("mcp_session",selector(one,"database","app",null,null)));
        assertNotNull(reads.match(agent,"session",scope(one),relation("a")));assertNull(reads.match(agent,"other",scope(one),relation("a")));
        assertFalse(store.list(agent).toString().contains("\"session\":"));assertFalse(store.list(agent).toString().contains("profileRevision"));
        String label=store.sessions.list(agent).get(0).path("label").asText();assertNotNull(store.sessions.resolve(agent,label));
        try(var restarted=new ReusableApprovals(directory,System::currentTimeMillis)){assertTrue(restarted.list(agent).isEmpty());}
        save(grant("until_revoked",selector(two,"connection",null,null,null)));
        try(var restarted=new ReusableApprovals(directory,System::currentTimeMillis)){assertEquals(1,restarted.list(agent).size());}
    }
    @Test void editRevisionDisableRevokeAndConnectionInvalidation(){
        var saved=save(grant("until_revoked",selector(one,"connection",null,null,null),selector(two,"connection",null,null,null)));
        var proof=reads.match(agent,"session",scope(one),relation("a"));reads.require(agent,"session",proof);
        var changed=grant("until_revoked",selector(one,"object","app","app","a"));String id=saved.path("id").asText();
        reads.save("human",reads.prepare(agent,"session",changed,id,1L),1L);
        assertThrows(ReadPermissions.Conflict.class,()->reads.save("human",reads.prepare(agent,"session",changed,id,1L),1L));
        assertThrows(SecurityException.class,()->reads.require(agent,"session",proof));
        var current=reads.match(agent,"session",scope(one),relation("a"));store.change(agent,id,false);assertThrows(SecurityException.class,()->reads.require(agent,"session",current));
        store.change(agent,id,true);assertThrows(SecurityException.class,()->reads.require(agent,"session",current));
        store.change(agent,id,null);assertNull(reads.match(agent,"session",scope(one),relation("a")));
        save(grant("until_revoked",selector(one,"connection",null,null,null),selector(two,"connection",null,null,null)));
        store.invalidateConnection(one);assertNull(reads.match(agent,"session",scope(one),relation("a")));assertNotNull(reads.match(agent,"session",scope(two),relation("a")));
    }
    @Test void catalogCannotUseObjectArgumentToBypassDatabaseFiltering(){
        save(grant("until_revoked",selector(one,"object","app","app","a")));
        var args=Profiles.JSON.createObjectNode().put("kind","databases").put("object","a");
        assertThrows(IllegalArgumentException.class,()->TrustedCatalogRead.prepare("dba_get_metadata",scope(one),args));
        var result=Profiles.JSON.createObjectNode();result.putArray("columns").add("database_name");result.putArray("rows").addArray().add("app");result.withArray("rows").addArray().add("information_schema");
        var proof=reads.catalogMatch(agent,"session",scope(one),args);reads.filterMetadata(agent,"session",scope(one),args,proof,result);
        assertEquals(1,result.path("rows").size());assertEquals("app",result.path("rows").get(0).get(0).asText());
        assertNull(reads.match(agent,"session",scope(one),List.of(new ReadQueries.Relation("information_schema","information_schema","tables"))));
        for(String kind:List.of("function","type","constraint","index","policy","event","future_unknown_kind"))assertEquals("",ReadPermissions.metadataObject(Profiles.JSON.createObjectNode().put("kind",kind).put("name","a")));
        assertEquals("a",ReadPermissions.metadataObject(Profiles.JSON.createObjectNode().put("kind","view").put("name","a")));
    }
    @Test void limitsIdentityAndExpiredSessionsFailClosed()throws Exception{
        var input=grant("until_revoked");for(int i=0;i<257;i++)input.withArray("selectors").add(selector(one,"object","app","app","t"+i));
        assertThrows(IllegalArgumentException.class,()->save(input));
        save(grant("mcp_session",selector(one,"connection",null,null,null)));var proof=reads.match(agent,"session",scope(one),relation("a"));
        assertNull(reads.match("another-identity","session",scope(one),relation("a")));
        store.sessions.remove("session");assertThrows(SecurityException.class,()->reads.require(agent,"session",proof));assertTrue(store.list(agent).isEmpty());
    }
    @Test void cachedCatalogFiltersBeforeSearchPaginationAndObjectLookup()throws Exception{
        save(grant("until_revoked",selector(one,"object","app","app","a"),selector(one,"object","app","app","b")));
        var proof=reads.catalogMatch(agent,"session",scope(one),"");var target=new CatalogCache.Target(one,"app","app","revision");var cache=new CatalogCache.Scope(target);
        var documents=io.doindev.codegraph.store.DocumentStore.memory(1<<20);
        documents.replace(writer->{for(String name:List.of("a","b","secret")){var row=Profiles.JSON.createObjectNode().put("id",name).put("name",name).put("schema","app").put("kind","table").put("ddl","hidden definition "+name);byte[] bytes=row.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);writer.put("i/"+name,bytes);writer.put("o/"+name,bytes);}});
        cache.publish(new CatalogCache.Publication(documents,Profiles.JSON.createObjectNode().put("generation",1).put("finishedAt",1),()->{}));
        try{
            var queries=new CatalogQueries(System::currentTimeMillis);
            java.util.function.Predicate<com.fasterxml.jackson.databind.JsonNode> permitted=row->reads.coveredByProof(agent,"session",scope(one),proof,new ReadQueries.Relation("app","app",ReadPermissions.metadataObject(row)));
            var first=queries.read(cache,agent,target.key(),proof.toString(),"dba_search_objects",Profiles.JSON.createObjectNode().put("limit",1),row->row,permitted);
            assertEquals(2,first.path("total").asInt());assertEquals("a",first.path("objects").get(0).path("name").asText());assertTrue(first.has("nextCursor"));
            var second=queries.read(cache,agent,target.key(),proof.toString(),"dba_search_objects",Profiles.JSON.createObjectNode().put("limit",1).put("cursor",first.path("nextCursor").asText()),row->row,permitted);
            assertEquals("b",second.path("objects").get(0).path("name").asText());assertFalse(second.has("nextCursor"));
            assertThrows(IllegalArgumentException.class,()->queries.read(cache,agent,target.key(),proof.toString(),"dba_get_indexed_ddl",Profiles.JSON.createObjectNode().put("objectId","secret"),row->row,permitted));
            store.change(agent,proof.get(0).path("id").asText(),null);
            assertThrows(SecurityException.class,()->queries.read(cache,agent,target.key(),proof.toString(),"dba_search_objects",Profiles.JSON.createObjectNode(),row->row,permitted));
        }finally{cache.retire();}
    }
    @Test void invalidInputAndPartialCatalogFiltering(){
        assertThrows(IllegalArgumentException.class,()->save(grant("forever",selector(one,"connection",null,null,null))));
        assertThrows(IllegalArgumentException.class,()->save(grant("until_revoked",selector(one,"schema","app","other",null))));
        assertThrows(IllegalArgumentException.class,()->save(grant("until_revoked",selector(one,"connection","app",null,null))));
        save(grant("until_revoked",selector(one,"object","app","app","a")));
        assertNotNull(reads.catalogMatch(agent,"session",scope(one),""));assertNull(reads.catalogMatch(agent,"session",scope(one),"b"));
        var result=Profiles.JSON.createObjectNode();result.putArray("columns").add("table_schema").add("table_name");result.putArray("rows").addArray().add("app").add("a");result.withArray("rows").addArray().add("app").add("b");
        reads.filterMetadata(agent,"session",scope(one),Profiles.JSON.createObjectNode(),reads.catalogMatch(agent,"session",scope(one),""),result);assertEquals(1,result.path("rows").size());
    }
}