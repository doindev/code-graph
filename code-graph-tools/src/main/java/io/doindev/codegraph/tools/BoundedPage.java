package io.doindev.codegraph.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.doindev.codegraph.config.CodeGraphConfig;
import io.doindev.codegraph.query.GenerationCursor;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Rescan-based, ordered page selection. Retains a bounded prefix, never a full inventory. */
final class BoundedPage {
    private record Item(String key, ObjectNode value, int bytes) {}
    private final int limit, byteLimit;
    private final String after;
    private final PriorityQueue<Item> rows = new PriorityQueue<>(Comparator.comparing(Item::key).reversed());
    private long total, bytes;
    private boolean byteEnded;
    private final java.util.function.Consumer<ObjectNode> projection;

    BoundedPage(int limit, String after, int byteLimit) {
        this(limit,after,byteLimit,row -> {});
    }
    BoundedPage(int limit, String after, int byteLimit, java.util.function.Consumer<ObjectNode> projection) {
        this.limit = limit; this.after = after; this.byteLimit = byteLimit; this.projection = projection;
    }
    void accept(String key, ObjectNode row) {
        if (Thread.currentThread().isInterrupted()) throw new IllegalArgumentException("Navigation cancelled");
        total++;
        if (key.compareTo(after) <= 0 || rows.size() == limit && key.compareTo(rows.peek().key()) >= 0) return;
        projection.accept(row);
        int size = row.toString().getBytes(StandardCharsets.UTF_8).length;
        // An oversize sentinel preserves ordering: it must cause an error when it reaches the
        // front, not be silently skipped in favour of a later smaller record.
        Item item = new Item(key, size > byteLimit ? null : row, size);
        if (rows.size() == limit) bytes -= rows.remove().bytes();
        rows.add(item); bytes += size;
        while (bytes > byteLimit && rows.size() > 1) {
            bytes -= rows.remove().bytes(); byteEnded = true;
        }
    }
    ToolResponse finish(ObjectNode out, GenerationCursor cursors, String scope, long generation,
                        GenerationCursor.Position position, CodeGraphConfig config) {
        return finish(out,cursors,scope,generation,position,config,"symbols",false,false);
    }
    /** A bundle scans only keys after its cursor; omitted distinct-neighbor suffixes are lower bounds. */
    ToolResponse finishSuffix(ObjectNode out, GenerationCursor cursors, String scope, long generation,
                              GenerationCursor.Position position, CodeGraphConfig config, boolean further) {
        return finish(out,cursors,scope,generation,position,config,"items",true,further);
    }
    private ToolResponse finish(ObjectNode out, GenerationCursor cursors, String scope, long generation,
                                GenerationCursor.Position position, CodeGraphConfig config,
                                String collection,boolean suffix,boolean further) {
        var page = new ArrayList<>(rows);
        page.sort(Comparator.comparing(Item::key));
        while (true) {
            var array = out.putArray(collection);
            for (Item item : page) {
                if (item.value() == null) throw oversized();
                array.add(item.value());
            }
            long knownTotal = suffix ? position.seen()+total : total;
            long seen = position.seen() + page.size(), remaining = Math.max(0, knownTotal - seen);
            boolean workLimited=out.path("workLimitReached").asBoolean();
            out.put("total", knownTotal).put("truncated", remaining > 0 || further || workLimited).put("omitted", remaining);
            if(suffix)out.put("totalCountComplete",!further&&!workLimited).put("omittedCountComplete",!further&&!workLimited);
            out.put("requestedCount", limit).put("returnedCount", page.size());
            out.put("pageEndReason", remaining == 0&&!further ? workLimited?"work_limit":"complete" : byteEnded ? "byte_limit" : "item_limit");
            out.remove("nextCursor");
            if ((remaining > 0 || further) && !page.isEmpty()) {
                try {
                    out.put("nextCursor", cursors.issue(scope, generation,
                            new GenerationCursor.Position(page.getLast().key(), seen, position.expiresAt())));
                } catch (IllegalArgumentException e) { throw oversized(); }
            }
            // Measure the actual final envelope, including signed cursor and coverage metadata.
            String json = out.toString();
            if (json.getBytes(StandardCharsets.UTF_8).length <= byteLimit)
                return ToolResponse.ok(json);
            if (page.size() <= 1) throw oversized();
            page.removeLast(); byteEnded = true;
        }
    }
    private IllegalArgumentException oversized() {
        return new IllegalArgumentException("item_too_large: one record plus cursor/coverage exceeds maxResponseBytes; increase the configured response allowance or narrow the target");
    }
}
