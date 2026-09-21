package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Explicit, revocable MCP-session pairing with revision-checked browser workspace edits. */
final class EditorPairings implements AutoCloseable {
    private static final long CODE_TTL=10*60_000L;
    record Pending(String code,String browser,long expires){}
    record Pair(String id,String principal,String mcpSession,String browser,long created){}
    record Event(long id,String type,ObjectNode value){}
    private final BrowserAuth auth;
    private final java.util.function.BiPredicate<String,String> sessionAlive;
    private final Map<String,Pending> pending=new HashMap<>();
    private final Map<String,Pair> pairs=new HashMap<>();
    private final Map<String,ArrayDeque<Event>> events=new HashMap<>();private long sequence;
    EditorPairings(BrowserAuth auth,java.util.function.BiPredicate<String,String> sessionAlive){this.auth=auth;this.sessionAlive=sessionAlive;}

    synchronized ObjectNode create(String browser){
        requireBrowser(browser);reap();pending.values().removeIf(value->value.browser.equals(browser));String code;
        do{byte[] random=new byte[6];new SecureRandom().nextBytes(random);code=Base64.getUrlEncoder().withoutPadding().encodeToString(random).toUpperCase(java.util.Locale.ROOT);}while(pending.containsKey(code));
        Pending value=new Pending(code,browser,System.currentTimeMillis()+CODE_TTL);pending.put(code,value);
        return Profiles.JSON.createObjectNode().put("code",code).put("expiresAt",value.expires).put("paired",pairs.values().stream().anyMatch(pair->pair.browser.equals(browser)))
                .put("notice","Pairing permits revision-checked Script drafts and edits only. It grants no database permission and never executes SQL or saves files.");
    }
    synchronized ObjectNode status(String browser){requireBrowser(browser);reap();ObjectNode out=Profiles.JSON.createObjectNode().put("paired",false);for(Pair pair:pairs.values())if(pair.browser.equals(browser))out.put("paired",true).put("pairId",pair.id).put("agent",pair.principal).put("createdAt",pair.created);return out;}
    synchronized ObjectNode pair(String principal,String mcpSession,String code){
        reap();if(mcpSession==null||!sessionAlive.test(mcpSession,principal))throw new SecurityException("A live logical MCP session is required");
        Pending request=pending.remove(code.toUpperCase(java.util.Locale.ROOT));if(request==null||request.expires<System.currentTimeMillis())throw new IllegalArgumentException("Pairing code is invalid or expired");
        return connect(principal,mcpSession,request.browser);
    }
    synchronized boolean occupied(String browser){reap();return pairs.values().stream().anyMatch(p->p.browser.equals(browser));}
    synchronized boolean sessionPaired(String session){reap();return pairs.containsKey(session);}
    synchronized ObjectNode connect(String principal,String mcpSession,String browser){
        reap();requireBrowser(browser);
        if(mcpSession==null||!sessionAlive.test(mcpSession,principal))throw new SecurityException("A live logical MCP session is required");
        if(occupied(browser)||pairs.containsKey(mcpSession))throw new IllegalArgumentException("A workspace or MCP session is already paired; disconnect it before pairing again");
        Pair pair=new Pair(UUID.randomUUID().toString(),principal,mcpSession,browser,System.currentTimeMillis());pairs.put(mcpSession,pair);
        emit(browser,"paired",Profiles.JSON.createObjectNode().put("pairId",pair.id).put("agent",principal));
        return Profiles.JSON.createObjectNode().put("state","paired").put("pairId",pair.id).put("scope","one browser workspace").put("databasePermissionsGranted",false);
    }
    synchronized ObjectNode revokeBrowser(String browser){requireBrowser(browser);pending.values().removeIf(value->value.browser.equals(browser));pairs.values().removeIf(pair->pair.browser.equals(browser));emit(browser,"revoked",Profiles.JSON.createObjectNode());return Profiles.JSON.createObjectNode().put("revoked",true);}
    synchronized void sessionEnded(String mcpSession){Pair pair=pairs.remove(mcpSession);if(pair!=null)emit(pair.browser,"revoked",Profiles.JSON.createObjectNode().put("reason","MCP session ended"));}

    synchronized ObjectNode documents(String principal,String session){
        Pair pair=requirePair(principal,session);BrowserAuth.WorkspaceState workspace=auth.workspaceState(pair.browser);JsonNode root=parse(workspace.state());
        ObjectNode out=Profiles.JSON.createObjectNode().put("state","complete").put("workspaceRevision",workspace.revision()).put("pairId",pair.id);ArrayNode documents=out.putArray("documents");
        for(JsonNode tab:root.path("tabs"))if(tab.path("type").asText("script").equals("script"))documents.add(metadata(tab));
        out.put("truncated",false);return out;
    }
    synchronized ObjectNode document(String principal,String session,String id){
        Pair pair=requirePair(principal,session);BrowserAuth.WorkspaceState workspace=auth.workspaceState(pair.browser);JsonNode tab=find(parse(workspace.state()),id);
        ObjectNode out=metadata(tab);out.put("sql",tab.path("sql").asText()).put("workspaceRevision",workspace.revision()).put("pairId",pair.id);return out;
    }
    synchronized ObjectNode createDraft(String principal,String session,JsonNode input){
        Pair pair=requirePair(principal,session);BrowserAuth.WorkspaceState workspace=auth.workspaceState(pair.browser);ObjectNode root=(ObjectNode)parse(workspace.state());
        long expected=input.path("expectedWorkspaceRevision").asLong(-1);if(expected!=workspace.revision())throw new IllegalArgumentException("Workspace revision conflict; list documents and retry");
        ArrayNode tabs=(ArrayNode)root.path("tabs");if(tabs.size()>=12)throw new IllegalArgumentException("Workspace tab limit reached");
        String title=Profiles.text(input,"title",255),sql=boundedText(input,"sql",1<<20,true);ObjectNode tab=tabs.addObject().put("type","script").put("id",UUID.randomUUID().toString()).put("title",title).put("sql",sql).put("dirty",true).put("lastEdited",System.currentTimeMillis()).put("editorRatio",.38);
        if(input.hasNonNull("connectionId")){String connection=Profiles.text(input,"connectionId",36);UUID.fromString(connection);tab.put("connection",connection);}else tab.putNull("connection");
        tab.putObject("railToggles").put("serverOutput",false).put("executionLog",false).put("sqlVariables",false);tab.putNull("outputView");root.put("active",tab.path("id").asText());
        byte[] validated=validate(root);long revision=auth.replaceWorkspace(pair.browser,workspace.revision(),validated);emit(pair.browser,"document_created",metadata(tab).put("workspaceRevision",revision));
        return metadata(tab).put("workspaceRevision",revision).put("agentChangeVisible",true).put("executed",false).put("fileSaved",false);
    }
    synchronized ObjectNode edit(String principal,String session,JsonNode input){
        Pair pair=requirePair(principal,session);BrowserAuth.WorkspaceState workspace=auth.workspaceState(pair.browser);ObjectNode root=(ObjectNode)parse(workspace.state());ObjectNode tab=(ObjectNode)find(root,Profiles.text(input,"documentId",36));
        if(!tab.path("type").asText("script").equals("script"))throw new IllegalArgumentException("Only Script documents can be edited");
        String revision=documentRevision(tab),expected=Profiles.text(input,"expectedRevision",128);if(!BrowserAuth.equal(revision,expected))throw new IllegalArgumentException("Document revision conflict; reload the document before editing");
        String sql=tab.path("sql").asText(),text=boundedText(input,"text",1<<20,true);int start=input.path("start").asInt(-1),end=input.path("end").asInt(-1);
        if(start<0||end<start||end>sql.length())throw new IllegalArgumentException("Edit range is outside the Script text");
        String updated=sql.substring(0,start)+text+sql.substring(end);if(updated.getBytes(StandardCharsets.UTF_8).length>1<<20)throw new IllegalArgumentException("Edited Script exceeds 1 MiB");
        tab.put("sql",updated).put("dirty",true).put("lastEdited",System.currentTimeMillis());byte[] validated=validate(root);long workspaceRevision=auth.replaceWorkspace(pair.browser,workspace.revision(),validated);
        ObjectNode result=metadata(tab).put("workspaceRevision",workspaceRevision).put("agentChangeVisible",true).put("executed",false).put("fileSaved",false);emit(pair.browser,"document_edited",result.deepCopy());return result;
    }
    synchronized ArrayNode events(String browser,long after){requireBrowser(browser);reap();ArrayNode out=Profiles.JSON.createArrayNode();for(Event event:events.getOrDefault(browser,new ArrayDeque<>()))if(event.id>after)out.add(event.value.deepCopy().put("eventId",event.id).put("type",event.type));return out;}
    synchronized ObjectNode acknowledge(String browser,long id){requireBrowser(browser);ArrayDeque<Event> queue=events.get(browser);if(queue!=null)while(!queue.isEmpty()&&queue.peekFirst().id<=id)queue.removeFirst();return Profiles.JSON.createObjectNode().put("acknowledged",id);}

    private Pair requirePair(String principal,String session){reap();Pair pair=pairs.get(session);if(pair==null||!pair.principal.equals(principal)||!sessionAlive.test(session,principal))throw new SecurityException("Pair this MCP session with an active DBA workspace first");requireBrowser(pair.browser);if(!auth.tabResponsive(pair.browser))throw new SecurityException("Paired DBA tab is disconnected; return to that tab before editing");return pair;}
    private void requireBrowser(String browser){if(auth==null||!auth.alive(browser))throw new SecurityException("DBA browser session is unavailable");}
    private void emit(String browser,String type,ObjectNode value){ArrayDeque<Event> queue=events.computeIfAbsent(browser,_ ->new ArrayDeque<>());if(queue.size()>=64)queue.removeFirst();queue.addLast(new Event(++sequence,type,value));}
    synchronized void reap(){long now=System.currentTimeMillis();pending.values().removeIf(value->value.expires<now||!auth.alive(value.browser));pairs.values().removeIf(pair->!auth.collaborationAlive(pair.browser)||!sessionAlive.test(pair.mcpSession,pair.principal));events.keySet().removeIf(key->!auth.alive(key));}
    private static JsonNode parse(byte[] bytes){try{return Profiles.JSON.readTree(bytes);}catch(Exception e){throw new IllegalStateException("Stored workspace is invalid");}}
    private static JsonNode find(JsonNode root,String id){for(JsonNode tab:root.path("tabs"))if(tab.path("id").asText().equals(id))return tab;throw new IllegalArgumentException("Unknown Script document");}
    private static ObjectNode metadata(JsonNode tab){return Profiles.JSON.createObjectNode().put("id",tab.path("id").asText()).put("title",tab.path("title").asText()).put("connectionId",tab.path("connection").asText()).put("dirty",tab.path("dirty").asBoolean()).put("sqlBytes",tab.path("sql").asText().getBytes(StandardCharsets.UTF_8).length).put("revision",documentRevision(tab));}
    private static String documentRevision(JsonNode tab){return CatalogScanner.hash(tab.path("id").asText()+"\n"+tab.path("connection").asText()+"\n"+tab.path("sql").asText()+"\n"+tab.path("dirty").asBoolean());}
    private static String boundedText(JsonNode input,String key,int maximum,boolean blank){JsonNode value=input.get(key);if(value==null||!value.isTextual()||value.asText().length()>maximum||!blank&&value.asText().isBlank())throw new IllegalArgumentException("Missing/oversize "+key);return value.asText();}
    private static byte[] validate(JsonNode workspace){try{return DbaRuntime.validatedWorkspace(workspace);}catch(Exception e){throw e instanceof IllegalArgumentException invalid?invalid:new IllegalArgumentException("Workspace update is invalid");}}
    public synchronized void close(){pending.clear();pairs.clear();events.clear();}
}
