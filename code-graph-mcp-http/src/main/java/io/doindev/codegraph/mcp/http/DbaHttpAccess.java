package io.doindev.codegraph.mcp.http;

import io.doindev.codegraph.dba.DbaRuntime;
import io.doindev.codegraph.mcp.DbaMcpTools;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.net.InetAddress;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/** Enforce local-only MCP access and bind sessions to the shared local or optional token identity. */
final class DbaHttpAccess implements Filter {
    static final long IDLE_MILLIS=3_600_000;
    private static final class Binding {
        final String principal;
        long expires;
        int active;
        Binding(String principal,long expires){this.principal=principal;this.expires=expires;}
    }
    // One lock covers expiry, identity checks and leases, including concurrent POSTs.
    private final Map<String,Binding> sessions=new HashMap<>();
    private final DbaRuntime runtime;
    private final LongSupplier clock;
    DbaHttpAccess(DbaRuntime runtime){this(runtime,System::currentTimeMillis);}
    DbaHttpAccess(DbaRuntime runtime,LongSupplier clock){this.runtime=runtime;this.clock=clock;}
    public void doFilter(ServletRequest request,ServletResponse response,FilterChain chain)throws IOException,ServletException {
        HttpServletRequest req=(HttpServletRequest)request;HttpServletResponse res=(HttpServletResponse)response;
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
        Activity activity=new Activity();
        synchronized(sessions){
            reap();
            if(session!=null){
                Binding binding=sessions.get(session);
                // Terminated IDs must receive 404 so clients reinitialize; never rotate or revive them.
                if(binding==null){res.sendError(HttpServletResponse.SC_NOT_FOUND);return;}
                if(!binding.principal.equals(principal)){res.sendError(HttpServletResponse.SC_FORBIDDEN);return;}
                touch(session,binding);
                if(req.getMethod().equals("POST"))activity.begin(session,binding);
            }else if(sessions.size()>=256){res.sendError(503);return;}
        }
        req.setAttribute(DbaMcpTools.PRINCIPAL,principal);
        req.setAttribute(DbaMcpTools.SESSION,session==null?"":session);
        final String identity=principal;
        HttpServletResponseWrapper wrapper=new HttpServletResponseWrapper(res){
            private void bind(String name,String value){
                if(session!=null||!name.equalsIgnoreCase("Mcp-Session-Id")||value==null)return;
                synchronized(sessions){
                    if(!sessions.containsKey(value)){
                        if(sessions.size()>=256)throw new IllegalStateException("MCP session capacity reached");
                        Binding binding=new Binding(identity,clock.getAsLong()+IDLE_MILLIS);
                        publish(value,binding);sessions.put(value,binding);
                        if(req.getMethod().equals("POST"))activity.begin(value,binding);
                    }
                }
            }
            @Override public void setHeader(String name,String value){bind(name,value);super.setHeader(name,value);}
            @Override public void addHeader(String name,String value){bind(name,value);super.addHeader(name,value);}
        };
        HttpServletRequest tracked=new HttpServletRequestWrapper(req){
            private AsyncContext track(AsyncContext context){
                try{context.addListener(activity);}catch(IllegalStateException completed){activity.finish();}
                return context;
            }
            @Override public AsyncContext startAsync(){return track(super.startAsync());}
            @Override public AsyncContext startAsync(ServletRequest request,ServletResponse response){return track(super.startAsync(request,response));}
        };
        try{
            chain.doFilter(tracked,wrapper);
            if(session!=null&&req.getMethod().equals("DELETE")&&res.getStatus()<300)end(session);
        }finally{
            // SSE GETs do not keep idle sessions alive. POSTs remain leased until their response completes.
            if(!req.isAsyncStarted())activity.finish();
        }
    }
    private void publish(String id,Binding binding){if(runtime!=null)runtime.registerMcpSession(id,binding.principal,binding.active>0?Long.MAX_VALUE:binding.expires);}
    private void touch(String id,Binding binding){binding.expires=clock.getAsLong()+IDLE_MILLIS;publish(id,binding);}
    private void reap(){synchronized(sessions){long now=clock.getAsLong();var iterator=sessions.entrySet().iterator();while(iterator.hasNext()){
        var entry=iterator.next();if(entry.getValue().active==0&&entry.getValue().expires<=now){iterator.remove();if(runtime!=null)runtime.endMcpSession(entry.getKey());}
    }}}
    private void end(String id){synchronized(sessions){if(sessions.remove(id)!=null&&runtime!=null)runtime.endMcpSession(id);}}
    private final class Activity implements AsyncListener {
        private final AtomicBoolean finished=new AtomicBoolean();
        private String id;
        private Binding binding;
        void begin(String id,Binding binding){
            if(finished.get())return;
            this.id=id;this.binding=binding;binding.active++;
            try{publish(id,binding);}catch(SecurityException revoked){binding.active--;end(id);throw revoked;}
        }
        void finish(){if(!finished.compareAndSet(false,true))return;synchronized(sessions){
            if(binding!=null&&sessions.get(id)==binding){binding.active--;try{touch(id,binding);}catch(SecurityException revoked){end(id);}}
        }}
        @Override public void onComplete(AsyncEvent event){finish();}
        @Override public void onTimeout(AsyncEvent event){finish();}
        @Override public void onError(AsyncEvent event){finish();}
        @Override public void onStartAsync(AsyncEvent event){event.getAsyncContext().addListener(this);}
    }
    @Override public void destroy(){synchronized(sessions){if(runtime!=null)sessions.keySet().forEach(runtime::endMcpSession);sessions.clear();}}
}
