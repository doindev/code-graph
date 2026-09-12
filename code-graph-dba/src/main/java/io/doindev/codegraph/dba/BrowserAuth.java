package io.doindev.codegraph.dba;

import com.sun.net.httpserver.HttpExchange;
import java.net.*;
import java.security.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;

/** Local browser sessions are automatic. This is origin protection, not local-user authentication. */
final class BrowserAuth {
    static final byte[] EMPTY_WORKSPACE="{\"version\":1,\"active\":null,\"lastSelected\":null,\"tabs\":[]}".getBytes(StandardCharsets.UTF_8);
    static final class Session {
        private final String id,csrf;
        private final long expires;
        private byte[] workspace=EMPTY_WORKSPACE.clone();
        Session(String id,String csrf,long expires){this.id=id;this.csrf=csrf;this.expires=expires;}
        String id(){return id;}String csrf(){return csrf;}long expires(){return expires;}
    }
    private final Map<String,Session> sessions=new HashMap<>();
    BrowserAuth(Path directory)throws IOException {Files.deleteIfExists(directory.resolve("browser-token"));}
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
        if(cookie!=null)for(String part:cookie.split(";")){String[] kv=part.strip().split("=",2);if(kv.length==2&&kv[0].equals("dba_session"))id=kv[1];}
        return sessions.get(id);
    }
    synchronized Session require(HttpExchange x){prune();Session s=find(x);if(s==null)throw new SecurityException("DBA session expired");
        if(!x.getRequestMethod().equals("GET")&&!equal(s.csrf,x.getRequestHeaders().getFirst("X-Dba-CSRF")))throw new SecurityException("Invalid CSRF token");
        return s;
    }
    synchronized void logout(String id){Session removed=sessions.remove(id);if(removed!=null)Arrays.fill(removed.workspace,(byte)0);}
    synchronized boolean alive(String id){prune();return sessions.containsKey(id);}
    synchronized byte[] workspace(String id){Session s=sessions.get(id);if(s==null)throw new SecurityException("DBA session expired");return s.workspace.clone();}
    synchronized void workspace(String id,byte[] state){Session s=sessions.get(id);if(s==null)throw new SecurityException("DBA session expired");Arrays.fill(s.workspace,(byte)0);s.workspace=state.clone();}
    private void prune(){long now=System.currentTimeMillis();sessions.values().removeIf(s->{if(s.expires()>=now)return false;Arrays.fill(s.workspace,(byte)0);return true;});}
    synchronized void close(){sessions.values().forEach(s->Arrays.fill(s.workspace,(byte)0));sessions.clear();}
}
