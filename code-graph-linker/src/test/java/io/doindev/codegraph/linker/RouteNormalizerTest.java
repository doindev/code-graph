package io.doindev.codegraph.linker;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteNormalizerTest {

    private static String normalized(String raw) {
        Optional<String> result = RouteNormalizer.normalize(raw);
        assertTrue(result.isPresent(), "expected " + raw + " to normalize");
        return result.get();
    }

    // ---- param notations all collapse to {} ----

    @Test
    void colonParamBecomesBraces() {
        assertEquals("/users/{}", normalized("/users/:id"));
        assertEquals("/users/{}/orders/{}", normalized("/users/:userId/orders/:orderId"));
    }

    @Test
    void braceParamBecomesBraces() {
        assertEquals("/users/{}", normalized("/users/{id}"));
        assertEquals("/users/{}", normalized("/users/{user_id}"));
    }

    @Test
    void angleParamBecomesBraces() {
        assertEquals("/users/{}", normalized("/users/<id>"));
        assertEquals("/users/{}", normalized("/users/<int:id>"));
    }

    @Test
    void templateLiteralParamBecomesBraces() {
        assertEquals("/users/{}", normalized("/users/${x}"));
        assertEquals("/users/{}/detail", normalized("/users/${user.id}/detail"));
    }

    @Test
    void printfPlaceholderBecomesBraces() {
        assertEquals("/users/{}", normalized("/users/%s"));
        assertEquals("/users/{}", normalized("/users/%d"));
    }

    // ---- shape rules ----

    @Test
    void trailingSlashStripped() {
        assertEquals("/users", normalized("/users/"));
        assertEquals("/api/orders", normalized("/api/orders//"));
    }

    @Test
    void fullUrlHostStripped() {
        assertEquals("/api/users/{}", normalized("https://svc.example.com:8443/api/users/{id}"));
        assertEquals("/api/orders", normalized("http://localhost/api/orders"));
    }

    @Test
    void rejectsNonPathLikeLiterals() {
        assertTrue(RouteNormalizer.normalize("relative/path").isEmpty(), "must start with /");
        assertTrue(RouteNormalizer.normalize("/").isEmpty(), "too short");
        assertTrue(RouteNormalizer.normalize("users").isEmpty());
        assertTrue(RouteNormalizer.normalize(null).isEmpty());
        assertTrue(RouteNormalizer.normalize("https://example.com").isEmpty(), "URL without path");
    }

    @Test
    void rejectsAssetFileExtensions() {
        assertTrue(RouteNormalizer.normalize("/static/logo.png").isEmpty());
        assertTrue(RouteNormalizer.normalize("/css/site.css").isEmpty());
        assertTrue(RouteNormalizer.normalize("/js/app.js").isEmpty());
    }

    // ---- matching ----

    @Test
    void exactMatchIsPointSeven() {
        assertEquals(0.7f, RouteNormalizer.matchConfidence("/api/users/{}", null, "/api/users/{}", null));
    }

    @Test
    void versionPrefixToleratedAtReducedConfidence() {
        assertEquals(0.5f, RouteNormalizer.matchConfidence("/v1/users/{}", null, "/users/{}", null));
        assertEquals(0.5f, RouteNormalizer.matchConfidence("/users/{}", null, "/v2/users/{}", null));
        assertEquals(0.5f, RouteNormalizer.matchConfidence("/api/orders", null, "/orders", null));
        assertEquals(0.5f, RouteNormalizer.matchConfidence("/orders", null, "/api/orders", null));
    }

    @Test
    void prefixStrippedFromOneSideOnly() {
        // stripping BOTH sides would be needed here — must not match
        assertEquals(0f, RouteNormalizer.matchConfidence("/api/users", null, "/v1/users", null));
    }

    @Test
    void methodMatchRaisesConfidenceCappedAtPointEight() {
        assertEquals(0.8f, RouteNormalizer.matchConfidence("/api/users/{}", "GET", "/api/users/{}", "GET"));
        assertEquals(0.6f, RouteNormalizer.matchConfidence("/v1/users/{}", "POST", "/users/{}", "POST"),
                0.0001f);
    }

    @Test
    void methodMismatchKillsTheMatch() {
        assertEquals(0f, RouteNormalizer.matchConfidence("/api/users/{}", "GET", "/api/users/{}", "POST"));
    }

    @Test
    void unknownMethodOnEitherSideKeepsBaseConfidence() {
        assertEquals(0.7f, RouteNormalizer.matchConfidence("/api/users/{}", null, "/api/users/{}", "GET"));
        assertEquals(0.7f, RouteNormalizer.matchConfidence("/api/users/{}", "GET", "/api/users/{}", null));
    }

    @Test
    void differentPathsNeverMatch() {
        assertEquals(0f, RouteNormalizer.matchConfidence("/a/b", null, "/c/d", null));
    }
}
