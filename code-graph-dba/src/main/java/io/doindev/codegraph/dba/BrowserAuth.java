package io.doindev.codegraph.dba;

import com.sun.net.httpserver.HttpExchange;
import java.net.*;
import java.security.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;

/** Local browser sessions are automatic. This is origin protection, not local-user authentication. */
final class BrowserAuth implements AutoCloseable {
    static final byte[] EMPTY_WORKSPACE="{\"version\":1,\"active\":null,\"lastSelected\":null,\"tabs\":[]}".getBytes(StandardCharsets.UTF_8);
    static final class Session {
        private final String id,csrf;
        private final long expires;
        private byte[] workspace=EMPTY_WORKSPACE.clone();
        private long workspaceRevision;
        Session(String id,String csrf,long expires){this.id=id;this.csrf=csrf;this.expires=expires;}
        String id(){return id;}String csrf(){return csrf;}long expires(){return expires;}
    }
    final String cookieName;
    private final Map<String,Session> sessions=new HashMap<>();
    private final Map<String,Tab> tabs=new HashMap<>();
    private static final class Tab {
        final String parent,id; String document; long seen;
        final Session workspace;
        Tab(String parent,String id,String document,long expires){this.parent=parent;this.id=id;this.document=document;workspace=new Session(parent+":"+id,"",expires);seen=System.currentTimeMillis();}
    }
    /** Separate workspaces for tabs sharing a cookie. A duplicated tab cannot steal a live document. */
    synchronized String registerTab(String parent,String requested,String document){
        prune();Session session=sessions.get(parent);if(session==null)throw new SecurityException("DBA session expired");
        UUID.fromString(requested);UUID.fromString(document);String key=parent+":"+requested;Tab tab=tabs.get(key);
        if(tab!=null&&!tab.document.equals(document)&&!tab.document.isEmpty()&&System.currentTimeMillis()-tab.seen<120_000){requested=UUID.randomUUID().toString();key=parent+":"+requested;tab=null;}
        if(tab==null){if(tabs.size()>=24||tabs.values().stream().filter(t->t.parent.equals(parent)).count()>=8)throw new IllegalArgumentException("Close or reuse a DBA workspace (maximum eight per browser session)");
            tab=new Tab(parent,requested,document,session.expires());
            if(tabs.values().stream().noneMatch(t->t.parent.equals(parent))){tab.workspace.workspace=session.workspace;tab.workspace.workspaceRevision=session.workspaceRevision;session.workspace=EMPTY_WORKSPACE.clone();session.workspaceRevision++;}
            tabs.put(key,tab);
        }
        tab.document=document;tab.seen=System.currentTimeMillis();return key;
    }
    synchronized String requireTab(String parent,String id,String document){
        prune();if(id==null||document==null)throw new SecurityException("Both DBA workspace and document headers are required");UUID.fromString(id);UUID.fromString(document);Tab tab=tabs.get(parent+":"+id);
        if(tab==null||!tab.document.equals(document))throw new SecurityException("DBA tab ownership expired; reload this page");return tab.workspace.id();
    }
    synchronized void presence(String key){Tab tab=tabs.get(key);if(tab==null)throw new SecurityException("Unknown DBA tab");tab.seen=System.currentTimeMillis();}
    synchronized String legacyWorkspace(String parent){List<Tab> candidates=tabs.values().stream().filter(t->t.parent.equals(parent)).toList();if(candidates.size()>1)throw new SecurityException("Explicit DBA workspace headers required with multiple tabs");return candidates.isEmpty()?parent:candidates.getFirst().workspace.id();}
    synchronized void leaveTab(String key){Tab tab=tabs.get(key);if(tab!=null){tab.document="";tab.seen=System.currentTimeMillis();}}
    synchronized boolean collaborationAlive(String key){if(!alive(key))return false;Tab tab=tabs.get(key);return tab==null||System.currentTimeMillis()-tab.seen<120_000;}
    synchronized List<String> responsiveTabs(){prune();long now=System.currentTimeMillis();return tabs.values().stream().filter(t->!t.document.isEmpty()&&now-t.seen<25_000).map(t->t.workspace.id()).sorted().toList();}
    synchronized boolean tabResponsive(String key){return !key.contains(":")?alive(key):responsiveTabs().contains(key);}
    private Session workspaceSession(String id){prune();Session s=sessions.get(id);if(s==null&&tabs.containsKey(id))s=tabs.get(id).workspace;if(s==null)throw new SecurityException("DBA session expired");return s;}
    private void checkWorkspaceBudget(String id,int bytes){String parent=id.contains(":")?id.substring(0,id.indexOf(':')):id;long total=sessions.get(parent).workspace.length;for(Tab tab:tabs.values())if(tab.parent.equals(parent))total+=tab.workspace.workspace.length;if(total-workspaceSession(id).workspace.length+bytes>(16L<<20))throw new IllegalArgumentException("DBA browser workspace allowance exceeded (16 MiB shared across tabs)");}
    BrowserAuth(Path directory)throws IOException {this(directory,"dba_session");}
    BrowserAuth(Path directory,String cookieName)throws IOException {this.cookieName=cookieName;Files.deleteIfExists(directory.resolve("browser-token"));}
    synchronized Session create(long expires){prune();if(sessions.size()>=8)throw new SecurityException("Too many review sessions");Session s=new Session(token(),token(),Math.min(expires,System.currentTimeMillis()+3_600_000));sessions.put(s.id,s);return s;}
    static String token(){byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    static boolean equal(String a,String b){return a!=null&&b!=null&&MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),b.getBytes(StandardCharsets.UTF_8));}
    static void local(HttpExchange x) {
        if(!x.getRemoteAddress().getAddress().isLoopbackAddress())throw new SecurityException("DBA is local-only");
        String host=x.getRequestHeaders().getFirst("Host");
        if(host==null || !host.matches("(?i)(localhost|127\\.0\\.0\\.1|\\[::1\\])(:[0-9]+)?"))throw new SecurityException("Invalid local host");
        String origin=x.getRequestHeaders().getFirst("Origin");
        if(origin!=null&&!origin.equalsIgnoreCase("http://"+host))throw new SecurityException("Cross-origin DBA request rejected");
        if(!Set.of("GET","HEAD").contains(x.getRequestMethod())&&origin==null)throw new SecurityException("Origin required");
        String fetch=x.getRequestHeaders().getFirst("Sec-Fetch-Site");if("cross-site".equals(fetch))throw new SecurityException("Cross-site request rejected");
    }
    synchronized Session bootstrap(HttpExchange x){prune();Session existing=find(x);if(existing!=null)return existing;if(sessions.size()>=8)throw new SecurityException("Too many browser sessions; end an existing session or wait for expiration");Session s=new Session(token(),token(),System.currentTimeMillis()+3_600_000);sessions.put(s.id,s);return s;}
    private Session find(HttpExchange x){String cookie=x.getRequestHeaders().getFirst("Cookie");String id=null;
        if(cookie!=null)for(String part:cookie.split(";")){String[] kv=part.strip().split("=",2);if(kv.length==2&&kv[0].equals(cookieName))id=kv[1];}
        return sessions.get(id);
    }
    synchronized Session require(HttpExchange x){prune();Session s=find(x);if(s==null)throw new SecurityException("DBA session expired");
        if(!x.getRequestMethod().equals("GET")&&!equal(s.csrf,x.getRequestHeaders().getFirst("X-Dba-CSRF")))throw new SecurityException("Invalid CSRF token");
        return s;
    }
    synchronized void logout(String id){Session removed=sessions.remove(id);if(removed!=null)Arrays.fill(removed.workspace,(byte)0);prune();}
    synchronized boolean alive(String id){prune();return sessions.containsKey(id)||tabs.containsKey(id);}
    synchronized byte[] workspace(String id){return workspaceSession(id).workspace.clone();}
    synchronized void workspace(String id,byte[] state){Session s=workspaceSession(id);checkWorkspaceBudget(id,state.length);Arrays.fill(s.workspace,(byte)0);s.workspace=state.clone();s.workspaceRevision++;}
    record WorkspaceState(byte[] state,long revision){}
    synchronized WorkspaceState workspaceState(String id){Session s=workspaceSession(id);return new WorkspaceState(s.workspace.clone(),s.workspaceRevision);}
    synchronized long replaceWorkspace(String id,long expected,byte[] state){Session s=workspaceSession(id);if(s.workspaceRevision!=expected)throw new IllegalArgumentException("Browser workspace changed; refresh document metadata before editing");checkWorkspaceBudget(id,state.length);Arrays.fill(s.workspace,(byte)0);s.workspace=state.clone();return ++s.workspaceRevision;}
    private void prune(){long now=System.currentTimeMillis();sessions.values().removeIf(s->{if(s.expires()>=now)return false;Arrays.fill(s.workspace,(byte)0);return true;});tabs.values().removeIf(t->{if(sessions.containsKey(t.parent))return false;Arrays.fill(t.workspace.workspace,(byte)0);return true;});}
    @Override public synchronized void close(){sessions.values().forEach(s->Arrays.fill(s.workspace,(byte)0));tabs.values().forEach(t->Arrays.fill(t.workspace.workspace,(byte)0));tabs.clear();sessions.clear();}
}
