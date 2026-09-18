package io.doindev.codegraph.query;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Base64;
import java.util.function.LongSupplier;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Signed, expiring continuation state. Retains neither rows nor graph generations. */
public final class GenerationCursor {
    public record Position(String key, long seen, long expiresAt) {}
    private final byte[] secret = new byte[32];
    private final LongSupplier clock;
    private final long lifetimeMillis;

    public GenerationCursor() { this(System::currentTimeMillis, 300_000); }
    public GenerationCursor(LongSupplier clock, long lifetimeMillis) {
        if (lifetimeMillis < 1) throw new IllegalArgumentException("Positive cursor lifetime required");
        this.clock = clock; this.lifetimeMillis = lifetimeMillis;
        new SecureRandom().nextBytes(secret);
    }

    public Position read(String token, String scope, long generation) {
        if (token == null || token.isEmpty()) return new Position("", 0, clock.getAsLong() + lifetimeMillis);
        if (token.length() > 16384) throw invalid();
        try {
            String[] parts = token.split("\\.", -1);
            if (parts.length != 2) throw invalid();
            byte[] payload = Base64.getUrlDecoder().decode(parts[0]);
            if (!MessageDigest.isEqual(sign(payload), Base64.getUrlDecoder().decode(parts[1]))) throw invalid();
            try (var in = new DataInputStream(new ByteArrayInputStream(payload))) {
                if (in.readByte() != 1 || !in.readUTF().equals(hash(scope))) throw invalid();
                if (in.readLong() != generation) throw new IllegalArgumentException("stale_cursor: generation changed; restart without cursor");
                long expires = in.readLong(), seen = in.readLong();
                String key = in.readUTF();
                if (expires <= clock.getAsLong()) throw new IllegalArgumentException("expired_cursor: restart without cursor");
                if (seen < 0 || in.available() != 0) throw invalid();
                return new Position(key, seen, expires);
            }
        } catch (IOException | IllegalArgumentException e) {
            if (e instanceof IllegalArgumentException arg && arg.getMessage() != null
                    && (arg.getMessage().startsWith("stale_cursor:") || arg.getMessage().startsWith("expired_cursor:"))) throw arg;
            throw invalid();
        }
    }

    public String issue(String scope, long generation, Position position) {
        try {
            var bytes = new ByteArrayOutputStream();
            try (var out = new DataOutputStream(bytes)) {
                out.writeByte(1); out.writeUTF(hash(scope)); out.writeLong(generation);
                out.writeLong(position.expiresAt()); out.writeLong(position.seen()); out.writeUTF(position.key());
            }
            byte[] payload = bytes.toByteArray();
            if (payload.length > 12000) throw new IllegalArgumentException("Cursor key too large; narrow the query");
            var encoder = Base64.getUrlEncoder().withoutPadding();
            return encoder.encodeToString(payload) + "." + encoder.encodeToString(sign(payload));
        } catch (IOException e) { throw new IllegalArgumentException("Cursor key too large; narrow the query"); }
    }

    private byte[] sign(byte[] payload) {
        try { Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(secret, "HmacSHA256")); return mac.doFinal(payload); }
        catch (GeneralSecurityException e) { throw new IllegalStateException("Cursor signing unavailable", e); }
    }
    private static String hash(String scope) {
        try { return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(scope.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("invalid_cursor: wrong query, server, or malformed cursor; restart without cursor"); }
}
