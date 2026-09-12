package io.doindev.codegraph.mcp.http;

import io.doindev.codegraph.dba.DbaRuntime;
import io.doindev.codegraph.mcp.DbaMcpTools;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.net.InetAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Bind every SDK HTTP session (including anonymous graph sessions) to its authentication identity. */
final class DbaHttpAccess implements Filter {
    private record Binding(String principal,long expires){}
    private final Map<String,Binding> sessions=new ConcurrentHashMap<>();
    private final DbaRuntime runtime;
    DbaHttpAccess(DbaRuntime runtime){this.runtime=runtime;}
    public void doFilter(ServletRequest request,ServletResponse response,FilterChain chain)throws IOException,ServletException {
        HttpServletRequest req=(HttpServletRequest)request;HttpServletResponse res=(HttpServletResponse)response;
        long now=System.currentTimeMillis();sessions.entrySet().removeIf(e->e.getValue().expires()<now);
        String principal="",authorization=req.getHeader("Authorization");
        if(authorization!=null){
            String host=req.getHeader("Host"),origin=req.getHeader("Origin");
            if(!InetAddress.getByName(req.getRemoteAddr()).isLoopbackAddress()||host==null||!host.matches("(?i)(localhost|127\\.0\\.0\\.1|\\[::1\\])(:[0-9]+)?")
                    ||(origin!=null&&!origin.equals("http://"+host))||"cross-site".equals(req.getHeader("Sec-Fetch-Site"))){res.sendError(403);return;}
            try{if(!authorization.startsWith("Bearer "))throw new SecurityException();principal=runtime.authenticateAgent(authorization.substring(7));}
            catch(SecurityException e){res.sendError(403);return;}
        }
        String session=req.getHeader("Mcp-Session-Id");
        if(session!=null){Binding binding=sessions.get(session);if(binding==null||!binding.principal().equals(principal)){res.sendError(403);return;}}
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
