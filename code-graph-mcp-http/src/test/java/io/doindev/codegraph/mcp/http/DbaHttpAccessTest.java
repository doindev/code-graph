package io.doindev.codegraph.mcp.http;

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
        boolean forwarded;
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
