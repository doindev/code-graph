package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class EditorRequestsTest {
    @TempDir Path directory;
    BrowserAuth auth;EditorPairings pairs;EditorRequests requests;
    String browser;AtomicBoolean alive=new AtomicBoolean(true);AtomicLong clock=new AtomicLong(System.currentTimeMillis());
    AtomicReference<URI> launched=new AtomicReference<>();AtomicBoolean launchSuccess=new AtomicBoolean(true);
    @BeforeEach void setup()throws Exception{
        auth=new BrowserAuth(directory);browser=auth.create(System.currentTimeMillis()+3_600_000).id();
        pairs=new EditorPairings(auth,(s,p)->alive.get());
        requests=new EditorRequests(auth,pairs,(s,p)->alive.get(),()->true,uri->{launched.set(uri);return launchSuccess.get();},id->"QA agent",directory,clock::get);
        requests.address(URI.create("http://localhost:8137/"));
    }
    @AfterEach void close(){requests.close();pairs.close();auth.close();}
    String tab(){return auth.registerTab(browser,UUID.randomUUID().toString(),UUID.randomUUID().toString());}
    ObjectNode submit(String session){return requests.request("agent",session,Profiles.JSON.createObjectNode().put("requestId","request-"+session).put("purpose","Review this Script together"));}
    @Test void singleTabRequiresNativeConsentAndDoesNotGrantDatabaseAccess(){
        String tab=tab(),id=submit("mcp").path("id").asText();assertTrue(requests.offers(tab).path("requests").isEmpty());
        assertThrows(SecurityException.class,()->requests.accept(tab,id,""));
        requests.decide(id,"editor_existing");assertTrue(requests.offers(tab).path("requests").get(0).path("autoAccept").asBoolean());
        var result=requests.accept(tab,id,"");assertEquals("paired",result.path("state").asText());assertFalse(result.path("databasePermissionsGranted").asBoolean());
        assertEquals(0,pairs.documents("agent","mcp").path("documents").size());
        assertThrows(IllegalArgumentException.class,()->requests.accept(tab,id,""));
        assertThrows(SecurityException.class,()->requests.status("agent","other",id));
    }
    @Test void firstAcceptanceWinsAndOtherTabRemainsIsolated()throws Exception{
        String first=tab(),second=tab(),id=submit("mcp").path("id").asText();requests.decide(id,"editor_existing");
        assertFalse(requests.offers(first).path("requests").get(0).path("autoAccept").asBoolean());
        try(var pool=Executors.newFixedThreadPool(2)){
            var gate=new CountDownLatch(1);AtomicInteger wins=new AtomicInteger();
            List<Future<?>> tasks=new ArrayList<>();for(String tab:List.of(first,second))tasks.add(pool.submit(()->{try{gate.await();requests.accept(tab,id,"");wins.incrementAndGet();}catch(IllegalArgumentException expected){}catch(InterruptedException e){throw new RuntimeException(e);}}));
            gate.countDown();for(var task:tasks)task.get();assertEquals(1,wins.get());
        }
        assertTrue(requests.offers(second).path("requests").isEmpty());
        String selected=pairs.status(first).path("paired").asBoolean()?first:second,other=selected.equals(first)?second:first;
        pairs.createDraft("agent","mcp",Profiles.JSON.createObjectNode().put("title","Agent draft").put("sql","SELECT 1").put("expectedWorkspaceRevision",0));
        assertTrue(new String(auth.workspace(selected)).contains("Agent draft"));assertFalse(new String(auth.workspace(other)).contains("Agent draft"));
        auth.leaveTab(selected);assertThrows(SecurityException.class,()->pairs.documents("agent","mcp"));
    }
    @Test void newWorkspaceUsesSingleUseTicketAndDoesNotExposeIt()throws Exception{
        String id=submit("mcp").path("id").asText();requests.decide(id,"editor_new");
        for(int i=0;i<100&&launched.get()==null;i++)Thread.sleep(10);assertNotNull(launched.get());
        String ticket=launched.get().getFragment().substring("editor-handoff=".length());
        assertFalse(requests.status("agent","mcp",id).toString().contains(ticket));
        String tab=tab();assertThrows(SecurityException.class,()->requests.accept(tab,id,""));
        assertEquals("paired",requests.accept(tab,"",ticket).path("state").asText());
        assertThrows(IllegalArgumentException.class,()->requests.accept(tab,"",ticket));
        assertFalse(Files.readString(directory.resolve("editor-consent.jsonl")).contains(ticket));
    }
    @Test void denialExpirySessionLossAndNoTakeover(){
        String tab=tab();String denied=submit("one").path("id").asText();requests.decide(denied,"reject");assertEquals("denied",requests.status("agent","one",denied).path("state").asText());
        String expires=submit("two").path("id").asText();clock.addAndGet(300_001);assertEquals("expired",requests.status("agent","two",expires).path("state").asText());
        String pair=submit("three").path("id").asText();requests.decide(pair,"editor_existing");requests.accept(tab,pair,"");
        assertEquals("paired",submit("three").path("state").asText(),"Exact retries return their receipt without requesting another consent");
        assertThrows(IllegalArgumentException.class,()->requests.request("agent","three",Profiles.JSON.createObjectNode().put("requestId","new").put("purpose","New pairing")));
        String other=submit("four").path("id").asText();requests.decide(other,"editor_existing");assertThrows(IllegalArgumentException.class,()->requests.accept(tab,other,""));
        alive.set(false);assertThrows(SecurityException.class,()->pairs.documents("agent","three"));assertTrue(requests.pending().isEmpty());
    }
    @Test void defaultModeAndYoloCannotForgeSqlDecisionAsEditorConsent(){
        String id=submit("mcp").path("id").asText();
        for(String action:List.of("approve_once","always_exact","always_similar","startup_yolo"))assertThrows(IllegalArgumentException.class,()->requests.decide(id,action));
        assertEquals("awaiting_approval",requests.status("agent","mcp",id).path("state").asText());
    }
    @Test void headlessBrowserConsentNeedsExplicitTabAcceptance(){
        requests.close();requests=new EditorRequests(auth,pairs,(s,p)->true,()->false,uri->false,id->"QA",directory,clock::get);
        String tab=tab(),id=submit("mcp").path("id").asText();assertEquals(1,requests.offers(tab).path("requests").size());assertTrue(requests.pending().isEmpty());
        assertFalse(requests.offers(tab).path("requests").get(0).path("autoAccept").asBoolean());assertEquals("paired",requests.accept(tab,id,"").path("state").asText());
    }
    @Test void auditFailureDoesNotApproveAndPendingCapacityIsReleased()throws Exception{
        Semaphore capacity=new Semaphore(1);requests.configure(capacity,()->{});String id=submit("mcp").path("id").asText();
        assertThrows(IllegalArgumentException.class,()->submit("other"));Files.createDirectory(directory.resolve("editor-consent.jsonl"));
        assertThrows(IllegalStateException.class,()->requests.decide(id,"editor_existing"));assertEquals("awaiting_approval",requests.status("agent","mcp",id).path("state").asText());
        requests.cancel("agent","mcp",id);assertEquals(1,capacity.availablePermits());
    }
    @Test void duplicateTabsCannotStealAndReloadRecoversOnlyItsOwnWorkspace(){
        String id=UUID.randomUUID().toString(),doc=UUID.randomUUID().toString(),one=auth.registerTab(browser,id,doc);
        auth.workspace(one,"{\"tabs\":[]}".getBytes());String two=auth.registerTab(browser,id,UUID.randomUUID().toString());assertNotEquals(one,two);
        assertThrows(SecurityException.class,()->auth.requireTab(browser,id,UUID.randomUUID().toString()));
        auth.leaveTab(one);String nextDoc=UUID.randomUUID().toString();assertEquals(one,auth.registerTab(browser,id,nextDoc));assertEquals(one,auth.requireTab(browser,id,nextDoc));
        assertEquals("{\"tabs\":[]}",new String(auth.workspace(one)));assertThrows(SecurityException.class,()->auth.legacyWorkspace(browser));
        auth.logout(browser);assertFalse(auth.alive(one));assertFalse(auth.alive(two));
    }
}
