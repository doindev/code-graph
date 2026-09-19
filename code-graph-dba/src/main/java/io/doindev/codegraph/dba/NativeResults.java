package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.util.Base64;

/** Retained native payload caps. Driver wire buffers are separately accounted overhead. */
final class NativeResults {
    private final int rowLimit, byteLimit;
    private final ObjectNode result;
    private final ArrayNode entries;
    private int bytes;

    NativeResults(String kind, int rowLimit, int byteLimit) {
        if (rowLimit < 1 || rowLimit > 10000 || byteLimit < 16384 || byteLimit > 4 << 20) throw new IllegalArgumentException("Invalid native result limits");
        this.rowLimit=rowLimit; this.byteLimit=byteLimit;
        result=Profiles.JSON.createObjectNode().put("kind", kind).put("truncated", false);
        entries=result.putArray("entries");
    }

    boolean add(JsonNode value) {
        int size;
        try { size=Profiles.JSON.writeValueAsBytes(value).length; }
        catch (java.io.IOException e) { throw new IllegalArgumentException("Cannot encode native result"); }
        if (entries.size() >= rowLimit || size > byteLimit-8192-bytes) {
            result.put("truncated", true).put("truncationReason", entries.size() >= rowLimit ? "row_limit" : "byte_limit");
            return false;
        }
        entries.add(value); bytes+=size; return true;
    }

    void incomplete(String reason) { result.put("truncated", true).put("truncationReason", reason); }
    ObjectNode finish() { return result.put("rowCount", entries.size()).put("payloadBytes", bytes); }

    static ObjectNode binary(byte[] value, int maxBytes) {
        ObjectNode out=Profiles.JSON.createObjectNode();
        if (value==null) return out.put("missing", true);
        int length=Math.min(value.length,maxBytes);
        byte[] preview=java.util.Arrays.copyOf(value,length);
        // Base64 is lossless for arbitrary Redis bytes. Text is an optional strict UTF-8 view.
        out.put("base64",Base64.getEncoder().encodeToString(preview)).put("previewBytes",length).put("truncated",length<value.length);
        try { out.put("text",java.nio.charset.StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(preview)).toString()); }
        catch (java.nio.charset.CharacterCodingException binary) { out.put("encoding","binary"); }
        return out;
    }
}
