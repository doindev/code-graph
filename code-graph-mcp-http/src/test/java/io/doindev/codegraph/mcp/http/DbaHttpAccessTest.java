package io.doindev.codegraph.mcp.http;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class DbaHttpAccessTest {
    @ParameterizedTest
    @ValueSource(strings = {"POST", "GET", "DELETE"})
    void unknownSessionReturnsNotFoundWithoutReachingTransport(String method) throws Exception {
        var filter = new DbaHttpAccess(null);
        var exchange = new Exchange(method, "unknown-session");

        exchange.send(filter);

        assertEquals(404, exchange.status);
        assertFalse(exchange.forwarded);
        assertTrue(exchange.headers.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "GET", "DELETE"})
    void expiredSessionReturnsNotFoundAndFreshInitializationStillWorks(String method) throws Exception {
        var clock = new AtomicLong(1000);
        var filter = new DbaHttpAccess(null, clock::get);
        initialize(filter, "expired-session");
        var valid = new Exchange("POST", "expired-session");
        valid.send(filter);
        assertEquals(200, valid.status);
        assertTrue(valid.forwarded);

        clock.addAndGet(3_600_001);
        var expired = new Exchange(method, "expired-session");
        expired.send(filter);
        assertEquals(404, expired.status);
        assertFalse(expired.forwarded);

        initialize(filter, "new-session");
        var fresh = new Exchange("POST", "new-session");
        fresh.send(filter);
        assertEquals(200, fresh.status);
        assertTrue(fresh.forwarded);
    }

    @Test
    void successfulDeletionMakesSessionUnknown() throws Exception {
        var filter = new DbaHttpAccess(null);
        initialize(filter, "deleted-session");
        var deletion = new Exchange("DELETE", "deleted-session");
        deletion.send(filter);
        assertTrue(deletion.forwarded);

        var stale = new Exchange("POST", "deleted-session");
        stale.send(filter);
        assertEquals(404, stale.status);
        assertFalse(stale.forwarded);
    }

    @ParameterizedTest
    @ValueSource(strings = {"192.0.2.10", "10.0.0.1", "2001:db8::1"})
    void remotePeersCannotClaimToBeLocalUsingForwardingHeaders(String remote) throws Exception {
        var exchange = new Exchange("POST", null);
        exchange.remote = remote;
        exchange.requestHeaders.put("X-Forwarded-For", "127.0.0.1");
        exchange.requestHeaders.put("Forwarded", "for=127.0.0.1");
        exchange.send(new DbaHttpAccess(null));
        assertEquals(403, exchange.status);
        assertFalse(exchange.forwarded);
    }

    @ParameterizedTest
    @ValueSource(strings = {"evil.example", "localhost.evil.example:3000", "127.0.0.1.evil.example", "192.0.2.10:3000"})
    void untrustedHostIsDeniedWithoutAuthorization(String host) throws Exception {
        var exchange = new Exchange("POST", null);
        exchange.requestHeaders.put("Host", host);
        exchange.send(new DbaHttpAccess(null));
        assertEquals(403, exchange.status);
        assertFalse(exchange.forwarded);
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://evil.example", "null", "http://localhost:8137"})
    void foreignOriginsAreDeniedWithoutAuthorization(String origin) throws Exception {
        var exchange = new Exchange("POST", null);
        exchange.requestHeaders.put("Origin", origin);
        exchange.send(new DbaHttpAccess(null));
        assertEquals(403, exchange.status);
        assertFalse(exchange.forwarded);
    }

    @Test void crossSiteRequestsAndInvalidAuthorizationNeverFallBackToLocalTrust() throws Exception {
        for (String header : new String[]{"Sec-Fetch-Site", "Authorization"}) {
            var exchange = new Exchange("POST", null);
            exchange.requestHeaders.put(header, header.equals("Authorization") ? "Bearer invalid" : "cross-site");
            exchange.send(new DbaHttpAccess(null));
            assertEquals(403, exchange.status);
            assertFalse(exchange.forwarded);
        }
    }

    @Test void missingHostIsDeniedAndSameOriginLoopbackIsAllowed() throws Exception {
        var missing = new Exchange("POST", null);
        missing.requestHeaders.remove("Host");
        missing.send(new DbaHttpAccess(null));
        assertEquals(403, missing.status);
        for (String host : new String[]{"localhost:3000", "127.0.0.1:3000", "[::1]:3000"}) {
            var local = new Exchange("POST", null);
            local.remote = host.startsWith("[") ? "::1" : "127.0.0.1";
            local.requestHeaders.put("Host", host);
            local.requestHeaders.put("Origin", "http://" + host);
            local.send(new DbaHttpAccess(null));
            assertEquals(200, local.status);
            assertTrue(local.forwarded);
        }
    }

    @Test void activitySlidesExpiryForMoreThanAnHour()throws Exception {
        var clock=new AtomicLong(1000);var filter=new DbaHttpAccess(null,clock::get);initialize(filter,"active");
        for(int i=0;i<8;i++){clock.addAndGet(30*60_000);var request=new Exchange("POST","active");request.send(filter);assertEquals(200,request.status);}
        clock.addAndGet(DbaHttpAccess.DEFAULT_IDLE_MILLIS+1);var idle=new Exchange("POST","active");idle.send(filter);assertEquals(404,idle.status);
    }
    @Test void changedPolicyAppliesToNewSessionsAndRenewalsWithoutRevivingExpiredIds()throws Exception {
        var clock=new AtomicLong(1000);var timeout=new AtomicLong(DbaHttpAccess.DEFAULT_IDLE_MILLIS);
        var filter=new DbaHttpAccess(null,clock::get,timeout::get);initialize(filter,"existing");initialize(filter,"renewed");
        timeout.set(60_000);initialize(filter,"new");
        clock.addAndGet(30_000);new Exchange("POST","renewed").send(filter);
        clock.addAndGet(30_001);var newExpired=new Exchange("POST","new");newExpired.send(filter);assertEquals(404,newExpired.status);
        var existing=new Exchange("POST","existing");existing.send(filter);assertEquals(200,existing.status,"Changing policy does not retroactively expire an idle session");
        clock.addAndGet(30_000);var renewedExpired=new Exchange("POST","renewed");renewedExpired.send(filter);assertEquals(404,renewedExpired.status);
        timeout.set(2*DbaHttpAccess.DEFAULT_IDLE_MILLIS);
        newExpired.send(filter);assertEquals(404,newExpired.status,"Raising the timeout cannot revive a terminated ID");
        existing.send(filter);assertEquals(200,existing.status);
        clock.addAndGet(DbaHttpAccess.DEFAULT_IDLE_MILLIS+1);existing.send(filter);assertEquals(200,existing.status,"A renewed session uses the longer timeout");
    }
    @Test void shorterPolicyStillProtectsOverlappingWorkAndAppliesAfterCompletion()throws Exception {
        var clock=new AtomicLong(1000);var timeout=new AtomicLong(DbaHttpAccess.DEFAULT_IDLE_MILLIS);
        var filter=new DbaHttpAccess(null,clock::get,timeout::get);initialize(filter,"busy");
        var first=new Exchange("POST","busy");first.async=true;filter.doFilter(first.request,first.response,(q,r)->q.startAsync());
        var second=new Exchange("POST","busy");second.async=true;filter.doFilter(second.request,second.response,(q,r)->q.startAsync());
        timeout.set(60_000);clock.addAndGet(2*DbaHttpAccess.DEFAULT_IDLE_MILLIS);first.listener.onComplete(new AsyncEvent(first.context));
        clock.addAndGet(60_001);var parallel=new Exchange("POST","busy");parallel.send(filter);assertEquals(200,parallel.status);
        second.listener.onComplete(new AsyncEvent(second.context));clock.addAndGet(60_001);
        parallel.send(filter);assertEquals(404,parallel.status);
    }
    @Test void synchronousWorkAndOverlappingAsyncPostsDoNotExpireOrShortenEachOthersLeases()throws Exception {
        var clock=new AtomicLong(1000);var filter=new DbaHttpAccess(null,clock::get);initialize(filter,"active");
        var sync=new Exchange("POST","active");
        filter.doFilter(sync.request,sync.response,(request,response)->{
            clock.addAndGet(2*DbaHttpAccess.DEFAULT_IDLE_MILLIS);
            var parallel=new Exchange("POST","active");filter.doFilter(parallel.request,parallel.response,(q,r)->{});assertEquals(200,parallel.status);
        });
        var first=new Exchange("POST","active");first.async=true;
        filter.doFilter(first.request,first.response,(request,response)->request.startAsync());
        var second=new Exchange("POST","active");second.async=true;
        filter.doFilter(second.request,second.response,(request,response)->request.startAsync());
        clock.addAndGet(2*DbaHttpAccess.DEFAULT_IDLE_MILLIS);first.listener.onComplete(new AsyncEvent(first.context));
        clock.addAndGet(2*DbaHttpAccess.DEFAULT_IDLE_MILLIS);var parallel=new Exchange("POST","active");parallel.send(filter);assertEquals(200,parallel.status);
        second.listener.onError(new AsyncEvent(second.context));second.listener.onComplete(new AsyncEvent(second.context));
        clock.addAndGet(DbaHttpAccess.DEFAULT_IDLE_MILLIS+1);var idle=new Exchange("GET","active");idle.send(filter);assertEquals(404,idle.status);
    }
    @Test void idleEventStreamDoesNotPinAndLateCompletionCannotResurrectDeletedSession()throws Exception {
        var clock=new AtomicLong(1000);var filter=new DbaHttpAccess(null,clock::get);initialize(filter,"stream");
        var stream=new Exchange("GET","stream");stream.async=true;filter.doFilter(stream.request,stream.response,(q,r)->q.startAsync());
        clock.addAndGet(DbaHttpAccess.DEFAULT_IDLE_MILLIS+1);var expired=new Exchange("POST","stream");expired.send(filter);assertEquals(404,expired.status);
        stream.listener.onComplete(new AsyncEvent(stream.context));expired.send(filter);assertEquals(404,expired.status);
        initialize(filter,"deleted");var post=new Exchange("POST","deleted");post.async=true;filter.doFilter(post.request,post.response,(q,r)->q.startAsync());
        new Exchange("DELETE","deleted").send(filter);post.listener.onComplete(new AsyncEvent(post.context));
        var stale=new Exchange("POST","deleted");stale.send(filter);assertEquals(404,stale.status);
    }
    @Test void failedRequestsAndAsyncTimeoutReleaseTheirLeasesAndInvalidOriginsDoNotRenew()throws Exception {
        var clock=new AtomicLong(1000);var filter=new DbaHttpAccess(null,clock::get);initialize(filter,"failure");
        var post=new Exchange("POST","failure");assertThrows(ServletException.class,()->filter.doFilter(post.request,post.response,(q,r)->{throw new ServletException("fixture");}));
        var async=new Exchange("POST","failure");async.async=true;filter.doFilter(async.request,async.response,(q,r)->q.startAsync());async.listener.onTimeout(new AsyncEvent(async.context));
        clock.addAndGet(DbaHttpAccess.DEFAULT_IDLE_MILLIS-1);var denied=new Exchange("POST","failure");denied.requestHeaders.put("Origin","https://evil.example");denied.send(filter);assertEquals(403,denied.status);
        clock.addAndGet(2);post.send(filter);assertEquals(404,post.status);
    }

    @Test void dbaRegistryFollowsTransportActivityAndTermination(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory)throws Exception {
        var vault=new io.doindev.codegraph.dba.Vault(){
            public void put(String id,byte[] value){throw new UnsupportedOperationException();}
            public byte[] get(String id){throw new UnsupportedOperationException();}
            public void remove(String id){}
        };
        var config=new io.doindev.codegraph.dba.DbaConfig(directory,64L<<20,2,100,100,5,60,"none");
        try(var initial=new io.doindev.codegraph.dba.DbaRuntime(config,vault,false)){assertEquals(DbaHttpAccess.DEFAULT_IDLE_MILLIS,initial.mcpSessionIdleTimeoutMillis());}
        java.nio.file.Files.writeString(directory.resolve("mcp-session-settings.json"),"{\"version\":1,\"mcpSessionIdleTimeoutMinutes\":120}");
        try(var runtime=new io.doindev.codegraph.dba.DbaRuntime(config,vault,false)){
            var clock=new AtomicLong(System.currentTimeMillis());var filter=new DbaHttpAccess(runtime,clock::get);initialize(filter,"runtime");
            String principal=runtime.trustedLocalAgent();var args=new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
            for(int i=0;i<5;i++){clock.addAndGet(90*60_000);new Exchange("POST","runtime").send(filter);assertDoesNotThrow(()->runtime.agentToolCall(principal,"runtime","dba_get_my_permissions",args));}
            clock.addAndGet(2*DbaHttpAccess.DEFAULT_IDLE_MILLIS+1);var expired=new Exchange("POST","runtime");expired.send(filter);assertEquals(404,expired.status);
            assertThrows(SecurityException.class,()->runtime.agentToolCall(principal,"runtime","dba_get_my_permissions",args));
            initialize(filter,"replacement");assertDoesNotThrow(()->runtime.agentToolCall(principal,"replacement","dba_get_my_permissions",args));filter.destroy();
        }
    }

    private static void initialize(DbaHttpAccess filter, String session) throws Exception {
        var exchange = new Exchange("POST", null);
        filter.doFilter(exchange.request, exchange.response, (request, response) ->
                ((HttpServletResponse) response).setHeader("Mcp-Session-Id", session));
        assertEquals(200, exchange.status);
        assertEquals(session, exchange.headers.get("Mcp-Session-Id"));
    }

    /** Minimal servlet doubles; real HTTP authentication/restart coverage lives in DbaMcpHttpTest. */
    private static final class Exchange {
        int status = 200;
        boolean forwarded,async;
        AsyncListener listener;
        final AsyncContext context=(AsyncContext)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{AsyncContext.class},(proxy,method,args)->{
            if(method.getName().equals("addListener")){listener=(AsyncListener)args[0];return null;}
            if(method.getName().equals("getRequest")||method.getName().equals("getResponse"))return null;
            throw new AssertionError(method.getName());
        });
        String remote = "127.0.0.1";
        final Map<String, String> requestHeaders = new HashMap<>();
        final Map<String, String> headers = new HashMap<>();
        final HttpServletRequest request;
        final HttpServletResponse response;

        Exchange(String method, String session) {
            requestHeaders.put("Host", "localhost:3000");
            if (session != null) requestHeaders.put("Mcp-Session-Id", session);
            request = (HttpServletRequest) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{HttpServletRequest.class}, (proxy, called, args) -> switch (called.getName()) {
                        case "getHeader" -> requestHeaders.get((String) args[0]);
                        case "getRemoteAddr" -> remote;
                        case "getMethod" -> method;
                        case "setAttribute" -> null;
                        case "isAsyncStarted" -> async;
                        case "startAsync", "getAsyncContext" -> context;
                        default -> throw new AssertionError("Unexpected request method: " + called.getName());
                    });
            response = (HttpServletResponse) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{HttpServletResponse.class}, (proxy, called, args) -> switch (called.getName()) {
                        case "getStatus" -> status;
                        case "sendError", "setStatus" -> { status = (Integer) args[0]; yield null; }
                        case "setHeader", "addHeader" -> { headers.put((String) args[0], (String) args[1]); yield null; }
                        default -> throw new AssertionError("Unexpected response method: " + called.getName());
                    });
        }

        void send(DbaHttpAccess filter) throws Exception {
            filter.doFilter(request, response, (request, response) -> forwarded = true);
        }
    }
}
