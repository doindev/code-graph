package io.doindev.codegraph.mcp.http;

import io.doindev.codegraph.dba.DbaRuntime;
import io.doindev.codegraph.mcp.DbaMcpTools;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.net.InetAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/** Enforce local-only MCP access and bind sessions to the shared local or optional token identity. */
final class DbaHttpAccess implements Filter {
    private record Binding(String principal,long expires){}
    private final Map<String,Binding> sessions=new ConcurrentHashMap<>();
    private final DbaRuntime runtime;
    private final LongSupplier clock;
    DbaHttpAccess(DbaRuntime runtime){this(runtime,System::currentTimeMillis);}
    DbaHttpAccess(DbaRuntime runtime,LongSupplier clock){this.runtime=runtime;this.clock=clock;}
    public void doFilter(ServletRequest request,ServletResponse response,FilterChain chain)throws IOException,ServletException {
        HttpServletRequest req=(HttpServletRequest)request;HttpServletResponse res=(HttpServletResponse)response;
        long now=clock.getAsLong();sessions.entrySet().removeIf(e->e.getValue().expires()<now);
        // Apply peer and browser-origin checks even without DBA or an Authorization header.
        // Never trust X-Forwarded-For/Forwarded to establish local access.
        String host=req.getHeader("Host"),origin=req.getHeader("Origin");
        if(!InetAddress.getByName(req.getRemoteAddr()).isLoopbackAddress()||host==null||!host.matches("(?i)(localhost|127\\.0\\.0\\.1|\\[::1\\])(:[0-9]+)?")
                ||(origin!=null&&!origin.equals("http://"+host))||"cross-site".equals(req.getHeader("Sec-Fetch-Site"))){res.sendError(403);return;}
        String principal="",authorization=req.getHeader("Authorization");
        if(authorization!=null){
            try{if(runtime==null||!authorization.startsWith("Bearer "))throw new SecurityException();principal=runtime.authenticateAgent(authorization.substring(7));}
            catch(SecurityException e){res.sendError(403);return;}
        }else if(runtime!=null)principal=runtime.trustedLocalAgent();
        String session=req.getHeader("Mcp-Session-Id");
        if(session!=null){
            Binding binding=sessions.get(session);
            // MCP clients reinitialize after a 404 for a session lost to expiry or server restart.
            // A known session presented by another identity remains an authorization failure.
            if(binding==null){res.sendError(HttpServletResponse.SC_NOT_FOUND);return;}
            if(!binding.principal().equals(principal)){res.sendError(HttpServletResponse.SC_FORBIDDEN);return;}
        }
        else if(sessions.size()>=256){res.sendError(503);return;}
        req.setAttribute(DbaMcpTools.PRINCIPAL,principal);
        final String identity=principal;
        HttpServletResponseWrapper wrapper=new HttpServletResponseWrapper(res){
            private void bind(String name,String value){if(name.equalsIgnoreCase("Mcp-Session-Id"))sessions.put(value,new Binding(identity,now+3_600_000));}
            @Override public void setHeader(String name,String value){bind(name,value);super.setHeader(name,value);}
            @Override public void addHeader(String name,String value){bind(name,value);super.addHeader(name,value);}
        };
        chain.doFilter(req,wrapper);
        if(session!=null&&req.getMethod().equals("DELETE")&&res.getStatus()<300)sessions.remove(session);
    }
}
