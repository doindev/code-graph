package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import java.net.URI;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class ApprovalBrokerTest {
    final AtomicLong now=new AtomicLong(100_000);
    final Map<String,ObjectNode> records=new LinkedHashMap<>();
    final Set<String> alive=new HashSet<>(List.of("one","two"));
    final FakeDesktop desktop=new FakeDesktop();
    ApprovalBroker broker;
    static class FakeDesktop implements ApprovalBroker.Desktop {
        boolean available=true,browser=true;int shown,dismissed;URI launched;JsonNode displayed;Consumer<ApprovalBroker.Decision> callback;Runnable detailed;
        public boolean available(){return available;}
        public boolean browse(URI uri){launched=uri;return browser;}
        public void show(JsonNode request,int waiting,Consumer<ApprovalBroker.Decision> decide,Runnable detailed){shown++;displayed=request;callback=decide;this.detailed=detailed;}
        public void dismiss(){dismissed++;}
    }
    ApprovalBroker create(String mode,boolean ui){
        return broker=new ApprovalBroker(mode,ui,new ApprovalBroker.Requests(){
            public JsonNode list(){ArrayNode a=Profiles.JSON.createArrayNode();records.values().forEach(r->{if(r.path("expiresAt").asLong()<=now.get())r.put("state","expired");a.add(r.deepCopy());});return a;}
            public JsonNode decide(String reviewer,String id,String action,boolean acknowledgement,JsonNode options){
                ObjectNode r=records.get(id);if(!action.equals("reject")&&!acknowledgement)throw new IllegalArgumentException("Acknowledgement required");
                r.put("state",action.equals("reject")?"rejected":"complete");return r.deepCopy();
            }
        },desktop,alive::contains,now::get,false);
    }
    ObjectNode request(String type){ObjectNode r=Profiles.JSON.createObjectNode().put("id",UUID.randomUUID().toString()).put("type",type).put("state","awaiting_approval").put("expiresAt",now.get()+300_000).put("createdAt",now.get());records.put(r.path("id").asText(),r);return r;}
    String presence(String session,boolean visible,boolean focused){String tab=UUID.randomUUID().toString();broker.presence(session,Profiles.JSON.createObjectNode().put("tabId",tab).put("visible",visible).put("focused",focused).put("polling",true));return tab;}
    JsonNode poll(String session,String tab){return broker.presence(session,Profiles.JSON.createObjectNode().put("tabId",tab).put("visible",true).put("polling",true));}
    @AfterEach void close(){if(broker!=null)broker.close();}
    @Test void nativeReadValidationRetainsHumanDraft()throws Exception{
        ObjectNode r=request("live_sql");
        broker=new ApprovalBroker("desktop",false,new ApprovalBroker.Requests(){
            public JsonNode list(){return Profiles.JSON.createArrayNode().add(r);}
            public JsonNode decide(String reviewer,String id,String action,boolean ack,JsonNode options){throw new IllegalArgumentException("Invalid scope");}
        },desktop,alive::contains,now::get,false);
        broker.tick();var options=Profiles.JSON.createObjectNode();options.putObject("readGrant").put("lifetime","until_revoked").putArray("selectors").addObject().put("connectionId","example").put("level","connection");
        desktop.callback.accept(new ApprovalBroker.Decision(ReadPermissions.ACTION,true,options));
        for(int i=0;i<100;i++){Thread.sleep(10);broker.tick();if(desktop.displayed.has("readGrantDraft"))break;}
        assertEquals(options.path("readGrant"),desktop.displayed.path("readGrantDraft"));assertTrue(desktop.displayed.has("readGrantError"));assertEquals("awaiting_approval",r.path("state").asText());
    }
    @Test void latestFocusedVisibleTabReceivesOfferAndOthersOnlyCount(){
        create("auto",true);ObjectNode r=request("live_sql");String t1=presence("one",true,true),t2=presence("two",true,true);broker.tick();
        assertEquals(0,poll("one",t1).path("requests").size());assertEquals(r.path("id"),poll("two",t2).path("requests").get(0));
        assertEquals(1,poll("one",t1).path("pendingCount").asInt());assertEquals(0,desktop.shown);
    }
    @Test void editorConsentUsesDesktopEvenWithActiveBrowserOrSqlApprovalsDisabled()throws Exception{
        for(String mode:List.of("auto","browser","none")){
            records.clear();create(mode,true);presence("one",true,true);ObjectNode r=request("editor_pairing");broker.tick();
            assertEquals(r.path("id"),desktop.displayed.path("id"));
            desktop.callback.accept(new ApprovalBroker.Decision("editor_existing",true));
            for(int i=0;i<100&&!r.path("state").asText().equals("complete");i++)Thread.sleep(10);
            assertEquals("complete",r.path("state").asText());broker.close();
        }
    }
    @Test void leaseRejectsOtherWindowsReplayAndChanges(){
        create("browser",true);ObjectNode r=request("live_sql");String id=r.path("id").asText(),t1=presence("one",true,true),t2=presence("two",true,true);
        final String token=broker.claim("one",t1,id).path("lease").asText();
        assertThrows(SecurityException.class,()->broker.claim("two",t2,id));
        assertThrows(SecurityException.class,()->broker.decide("two",t2,id,token,"approve_once",true,Profiles.JSON.createObjectNode()));
        r.put("sql","SELECT changed");assertThrows(IllegalArgumentException.class,()->broker.renew("one",t1,id,token));
        String fresh=broker.claim("two",t2,id).path("lease").asText();
        broker.decide("two",t2,id,fresh,"reject",false,Profiles.JSON.createObjectNode());
        assertThrows(SecurityException.class,()->broker.decide("two",t2,id,fresh,"approve_once",true,Profiles.JSON.createObjectNode()));
    }
    @Test void leasesExpireAndDeadSessionsCannotKeepOwnership(){
        create("browser",true);String id=request("live_sql").path("id").asText(),t1=presence("one",true,true),t2=presence("two",true,true);
        String lease=broker.claim("one",t1,id).path("lease").asText();now.addAndGet(30_001);
        assertThrows(SecurityException.class,()->broker.renew("one",t1,id,lease));
        assertDoesNotThrow(()->broker.claim("two",t2,id));alive.remove("two");broker.tick();
        assertDoesNotThrow(()->broker.claim("one",t1,id));
    }
    @Test void stalePresenceFallsBackAndDesktopIsFifo(){
        create("auto",true);ObjectNode first=request("live_sql");now.incrementAndGet();request("connection_delete");
        presence("one",true,true);now.addAndGet(25_001);broker.tick();
        assertEquals(first.path("id"),desktop.displayed.path("id"));assertEquals(1,desktop.shown);broker.tick();assertEquals(1,desktop.shown);
    }
    @Test void headlessNoneAndForcedModesFailClosed(){
        desktop.available=false;create("auto",false);assertFalse(broker.enabled());assertThrows(ApprovalBroker.Unavailable.class,broker::requireAvailable);broker.close();
        assertThrows(IllegalArgumentException.class,()->create("desktop",false));assertThrows(IllegalArgumentException.class,()->create("browser",false));
        create("none",true);assertFalse(broker.enabled());
    }
    @Test void desktopLossExpiresItsLeaseWithoutApproving(){
        create("desktop",false);ObjectNode r=request("connection_delete");broker.tick();desktop.available=false;broker.tick();
        assertFalse(broker.enabled());assertEquals("awaiting_approval",r.path("state").asText());assertEquals("approval_unavailable",broker.decorate(r).path("deliveryStatus").asText());
    }
    @Test void complexRequestsNeverApproveDirectlyOnDesktop()throws Exception{
        create("desktop",false);ObjectNode r=request("connection_create");broker.tick();desktop.callback.accept(new ApprovalBroker.Decision("approve_once",true));
        Thread.sleep(100);assertEquals("awaiting_approval",r.path("state").asText());
    }
    @Test void desktopDenialIsOneTimeAndExpiryDisposes()throws Exception{
        create("desktop",false);ObjectNode r=request("live_sql");broker.tick();desktop.callback.accept(new ApprovalBroker.Decision("reject",false));Thread.sleep(100);
        assertEquals("rejected",r.path("state").asText());assertTrue(desktop.dismissed>0);
        ObjectNode next=request("live_sql");broker.tick();now.addAndGet(300_001);broker.tick();assertEquals("expired",next.path("state").asText());
    }
    @Test void browserLaunchUsesOnlyRequestIdAndIsNotRepeated()throws Exception{
        create("auto",true);ObjectNode r=request("live_sql").put("sql","sensitive SQL");
        now.incrementAndGet();request("connection_delete");
        broker.browserUri(URI.create("http://localhost:8137"));broker.tick();Thread.sleep(100);
        assertEquals("http://localhost:8137/dba#approval="+r.path("id").asText(),desktop.launched.toString());
        assertEquals(0,desktop.shown);
    }
    @Test void scopedSessionCannotClaimOtherRequests(){
        create("browser",true);String first=request("live_sql").path("id").asText(),second=request("live_sql").path("id").asText();
        broker.restrictSession("one",first);String tab=presence("one",true,true);broker.tick();
        assertEquals(1,poll("one",tab).path("pendingCount").asInt());assertThrows(SecurityException.class,()->broker.claim("one",tab,second));
    }
    @Test void noneNeverDisplaysOrApprovesAndRevocationInvalidatesLeaseImmediately(){
        create("none",true);request("live_sql");broker.tick();assertEquals(0,desktop.shown);broker.close();
        records.clear();create("browser",true);String id=request("live_sql").path("id").asText(),tab=presence("one",true,true);
        String token=broker.claim("one",tab,id).path("lease").asText();alive.remove("one");
        assertThrows(SecurityException.class,()->broker.renew("one",tab,id,token));
        assertThrows(SecurityException.class,()->broker.decide("one",tab,id,token,"approve_once",true,Profiles.JSON.createObjectNode()));
    }
    @Test void failedBrowserLaunchFallsBackToDesktop()throws Exception{
        desktop.browser=false;create("auto",true);request("live_sql");broker.browserUri(URI.create("http://localhost:8137"));
        broker.tick();Thread.sleep(100);broker.tick();assertEquals(1,desktop.shown);
    }
    @Test void presentationEscapesHtmlAndExcludesConnectionSecrets(){
        ObjectNode r=request("connection_create").put("purpose","<img src='http://evil'>");
        r.putObject("after").put("password","top-secret").put("credentialRef","vault-ref").put("private_key_file","C:/private.pem").put("name","Safe name");
        String html=ApprovalPresentation.html(r);assertFalse(html.contains("<img"));assertFalse(html.contains("top-secret"));assertFalse(html.contains("vault-ref"));assertFalse(html.contains("private.pem"));assertTrue(html.contains("Safe name"));assertTrue(html.contains("&lt;img"));
    }
    @Test void modesParseAndDesktopGeometrySupportsNegativeCoordinates(){
        assertEquals("auto",DbaConfig.parse(new String[]{"--dba"}).orElseThrow().approvalMode());
        for(String mode:List.of("auto","browser","desktop","none"))assertEquals(mode,DbaConfig.parse(new String[]{"--dba","--dba-approval-mode",mode}).orElseThrow().approvalMode());
        assertThrows(IllegalArgumentException.class,()->DbaConfig.parse(new String[]{"--dba","--dba-approval-mode","unsafe"}));
        var primary=new java.awt.Rectangle(0,0,1920,1080);var left=new java.awt.Rectangle(-1280,0,1280,1024);
        assertEquals(left,DesktopApprovals.chooseBounds(new java.awt.Point(-100,30),List.of(primary,left),primary));
        assertEquals(primary,DesktopApprovals.chooseBounds(null,List.of(primary,left),primary));
    }
}
