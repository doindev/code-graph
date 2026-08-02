package io.doindev.codegraph.query;

import java.time.Instant;
import java.util.Map;

/**
 * Freshness and size of the index backing a graph, surfaced by the {@code index_status} tool so
 * agents can judge how stale a blast radius is.
 *
 * @param state           {@code indexing}, {@code ready} or {@code empty}
 * @param generation      monotonically increasing, bumped by every applied delta
 * @param filesIndexed    files currently owned by the graph
 * @param symbolCount     symbol nodes in the graph
 * @param edgeCount       edges in the graph
 * @param dirtyPending    watched files changed but not yet re-indexed
 * @param lastIndexedAt   completion time of the most recent (full or incremental) index step; may be {@code null}
 * @param filesPerLang    indexed file count per language code
 * @param engineVersion   version of the indexing engine that produced the graph
 */
public record IndexStatus(String state, long generation, int filesIndexed, int symbolCount,
                          long edgeCount, int dirtyPending, Instant lastIndexedAt,
                          Map<String, Integer> filesPerLang, String engineVersion) {

    public IndexStatus {
        filesPerLang = filesPerLang == null ? Map.of() : Map.copyOf(filesPerLang);
    }

    public static IndexStatus empty(String engineVersion) {
        return new IndexStatus("empty", 0L, 0, 0, 0L, 0, null, Map.of(), engineVersion);
    }
}
