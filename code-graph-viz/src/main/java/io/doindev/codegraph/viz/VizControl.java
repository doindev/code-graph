package io.doindev.codegraph.viz;

import io.doindev.codegraph.config.CodeGraphConfig;
import io.doindev.codegraph.query.GraphQuery;
import io.doindev.codegraph.lifecycle.ProjectLifecycle;

import java.util.List;

/**
 * The mutable workspace behind the viz UI. The read-only endpoints only need
 * {@link #projects()}; the action endpoints (reindex, add, remove, browse) are served only when
 * {@link #mutable()} is true — off by default on the team HTTP server, on for the loopback
 * stdio server. Implemented by the serving layer (which owns the {@code Workspace} and tool
 * registry); the viz module stays free of those dependencies.
 */
public interface VizControl {

    /** One project as the viz layer sees it. */
    record VizProject(String name, GraphQuery graph, CodeGraphConfig config) {
    }

    /** Current projects, in registration order (first = default). */
    List<VizProject> projects();

    /** MCP endpoint description shown in the UI header, e.g. {@code "stdio"} or {@code "http://host:3000/mcp"}. */
    String mcpEndpoint();

    /** Live project policy; absent only for fixed, read-only embeddings. */
    default ProjectLifecycle lifecycle() { return null; }

    /** Whether the action endpoints (reindex/add/remove/browse) are enabled. */
    default boolean mutable() {
        return false;
    }

    /** Re-index a project from scratch (async is fine); returns false if the name is unknown. */
    default boolean reindex(String project) {
        throw new UnsupportedOperationException("this viz server is read-only");
    }

    /**
     * Start a cancellable, pollable re-index job for a project (shares the {@link AddJob} shape
     * and the same status/cancel endpoints). Returns {@code null} for an unknown project.
     * Cancelling a re-index only detaches the UI — the scan is not interruptible, so it finishes
     * in the background and refreshes the same project's graph (harmless).
     */
    default AddJob startReindex(String project) {
        throw new UnsupportedOperationException("this viz server is read-only");
    }

    /** Remove a project; returns false if unknown. */
    default boolean remove(String project) {
        throw new UnsupportedOperationException("this viz server is read-only");
    }

    /** Index a new directory as a project; returns the (deduplicated) project name. Synchronous. */
    default String add(String path) {
        throw new UnsupportedOperationException("this viz server is read-only");
    }

    /** An asynchronous "add project" job the UI can poll and cancel. */
    record AddJob(String id, String name, String state, long elapsedMs, String error) {
        // state: "indexing" | "ready" | "cancelled" | "error"
    }

    /** Start indexing a directory in the background; returns the job immediately (state "indexing"). */
    default AddJob startAdd(String path) {
        throw new UnsupportedOperationException("this viz server is read-only");
    }

    /** Poll a job by id; {@code null} if unknown. */
    default AddJob addStatus(String id) {
        throw new UnsupportedOperationException("this viz server is read-only");
    }

    /**
     * Cancel a job: its result is abandoned (the project will not be added; if it already
     * finished, it is removed). The scan already running finishes in the background but is
     * discarded. Returns false for an unknown id.
     */
    default boolean cancelAdd(String id) {
        throw new UnsupportedOperationException("this viz server is read-only");
    }

    /** Directory listing for the "browse to add a project" picker. */
    default DirListing browse(String path) {
        throw new UnsupportedOperationException("this viz server is read-only");
    }

    /** A browsable directory and its immediate subdirectories. */
    record DirListing(String path, String parent, List<Entry> entries) {
        public record Entry(String name, String path, boolean looksLikeRepo) {
        }
    }

    /** A fixed, read-only control over a static project list (tests and simple embeddings). */
    static VizControl readOnly(List<VizProject> projects, String mcpEndpoint) {
        List<VizProject> copy = List.copyOf(projects);
        return new VizControl() {
            @Override
            public List<VizProject> projects() {
                return copy;
            }

            @Override
            public String mcpEndpoint() {
                return mcpEndpoint;
            }
        };
    }
}
