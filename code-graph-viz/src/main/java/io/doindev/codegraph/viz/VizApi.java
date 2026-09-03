package io.doindev.codegraph.viz;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.doindev.codegraph.analysis.BlastFormula;
import io.doindev.codegraph.analysis.BlastScore;
import io.doindev.codegraph.model.Direction;
import io.doindev.codegraph.model.Edge;
import io.doindev.codegraph.model.EdgeKind;
import io.doindev.codegraph.model.FileId;
import io.doindev.codegraph.model.Node;
import io.doindev.codegraph.model.NodeId;
import io.doindev.codegraph.model.NodeKind;
import io.doindev.codegraph.model.SymbolId;
import io.doindev.codegraph.query.ClosureHit;
import io.doindev.codegraph.query.ClosureResult;
import io.doindev.codegraph.query.GraphQuery;
import io.doindev.codegraph.query.IndexStatus;
import io.doindev.codegraph.rules.ModuleGraph;
import io.doindev.codegraph.lifecycle.ProjectLifecycle;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Read-only JSON endpoints behind {@link VizServer}. All graph payloads share one shape the
 * frontend renders directly: {@code {nodes:[{id,label,kind,group,val,...}], links:[{source,
 * target,kind,count,confidence}]}}. Every payload is capped — the browser must never receive
 * an unbounded graph.
 */
final class VizApi {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final float CONFIDENT = 0.8f;
    /** Hard ceiling on galaxy edges — keeps the browser responsive on large codebases. */
    private static final int MAX_GALAXY_LINKS = 10000;

    private final VizControl.VizProject project;
    private final BlastScore blastScore;

    VizApi(VizControl.VizProject project) {
        this.project = project;
        this.blastScore = new BlastScore(project.graph(), project.config());
    }

    /** Whether this cached API still points at the given project's live graph (else rebuild). */
    boolean isFor(VizControl.VizProject other) {
        return project.graph() == other.graph();
    }

    // ---- endpoints ----

    /** Roster in workspace order (not map order), so the default project stays first. */
    static String projects(List<VizControl.VizProject> order, ProjectLifecycle lifecycle) {
        ArrayNode out = JSON.createArrayNode();
        for (VizControl.VizProject project : order) {
            var idle = lifecycle == null ? null : lifecycle.status(project.name());
            if (lifecycle != null && idle == null) continue;
            IndexStatus status = project.graph().status();
            ObjectNode row = out.addObject();
            row.put("name", project.name());
            row.put("state", status.state());
            row.put("files", status.filesIndexed());
            row.put("symbols", status.symbolCount());
            row.put("edges", status.edgeCount());
            row.put("generation", status.generation());
            if (idle != null) {
                row.put("instanceId", idle.instanceId());
                row.put("lastActivityAt", idle.lastActivityAt().toString());
                row.put("expiresAt", idle.expiresAt().toString());
                row.put("remainingSeconds", idle.remainingSeconds());
                row.put("activeOperations", idle.activeOperations());
            }
        }
        return out.toString();
    }

    /** Lightweight freshness poll used by the UI after a reindex. */
    <T> T read(java.util.function.Supplier<T> query) { return project.graph().read(query); }

    String status() {
        IndexStatus status = project.graph().status();
        ObjectNode out = JSON.createObjectNode();
        out.put("state", status.state());
        out.put("generation", status.generation());
        out.put("files", status.filesIndexed());
        out.put("symbols", status.symbolCount());
        out.put("edges", status.edgeCount());
        out.put("dirtyPending", status.dirtyPending());
        return out.toString();
    }

    /** Module-level aggregation — the drill-down entry view. */
    String overview() {
        GraphQuery graph = project.graph();
        ModuleGraph modules = ModuleGraph.of(graph, project.config());
        Map<String, int[]> stats = new TreeMap<>(); // [files, symbols]
        graph.scanNodes(Set.of(NodeKind.FILE), file -> {
            String module = modules.moduleOf(file.relPath(), project.config().withDefaults().architecture());
            stats.computeIfAbsent(module, k -> new int[2])[0]++;
        });
        graph.scanNodes(Set.of(NodeKind.TYPE, NodeKind.FUNCTION), symbol -> {
            String module = modules.moduleOf(symbol.relPath(), project.config().withDefaults().architecture());
            stats.computeIfAbsent(module, k -> new int[2])[1]++;
        });
        GraphPayload payload = new GraphPayload();
        stats.forEach((module, counts) -> payload.node("mod:" + module, module, "module", module,
                Math.max(1, counts[0]), node -> {
                    node.put("files", counts[0]);
                    node.put("symbols", counts[1]);
                }));
        for (ModuleGraph.ModuleEdge edge : modules.moduleEdges()) {
            payload.link("mod:" + edge.from(), "mod:" + edge.to(), "DEPENDS", edge.count(), 1.0f);
        }
        return payload.toJson(false);
    }

    /** Files inside one module; cross-module edges collapse into module pseudo-nodes. */
    String module(String moduleName) {
        GraphQuery graph = project.graph();
        ModuleGraph modules = ModuleGraph.of(graph, project.config());
        var architecture = project.config().withDefaults().architecture();
        GraphPayload payload = new GraphPayload();
        Set<String> memberFiles = new LinkedHashSet<>();
        graph.scanNodes(Set.of(NodeKind.FILE), file -> {
            if (moduleName.equals(modules.moduleOf(file.relPath(), architecture))) {
                memberFiles.add(file.relPath());
                payload.node("file:" + file.relPath(), fileName(file.relPath()), "file", moduleName,
                        Math.max(1, file.metrics().loc() / 50), node -> {
                            node.put("path", file.relPath());
                            node.put("loc", file.metrics().loc());
                        });
            }
        });
        // aggregate symbol edges to file level
        Map<String, int[]> fileEdges = new LinkedHashMap<>();
        graph.scanNodes(Set.of(NodeKind.TYPE, NodeKind.FUNCTION, NodeKind.VARIABLE), symbol -> {
            String fromFile = symbol.relPath();
            if (fromFile == null || !memberFiles.contains(fromFile)) {
                return;
            }
            for (Edge edge : graph.edges(symbol.id(), Direction.OUT, BlastScore.IMPACT_KINDS)) {
                if (edge.confidence() < CONFIDENT || !(edge.to() instanceof SymbolId target)) {
                    continue;
                }
                String toFile = target.relPath();
                if (toFile.equals(fromFile)) {
                    continue;
                }
                String toKey = memberFiles.contains(toFile) ? "file:" + toFile
                        : "mod:" + modules.moduleOf(toFile, architecture);
                fileEdges.merge("file:" + fromFile + "|" + toKey, new int[] {1}, (a, b) -> {
                    a[0]++;
                    return a;
                });
                if (fileEdges.size() > graph.materializationLimit()) throw new IllegalArgumentException("module view exceeds hybrid query bound");
            }
        });
        fileEdges.forEach((key, count) -> {
            String[] parts = key.split("\\|", 2);
            if (parts[1].startsWith("mod:")) {
                String otherModule = parts[1].substring(4);
                payload.nodeIfAbsent(parts[1], otherModule, "module", otherModule, 1);
            }
            payload.link(parts[0], parts[1], "DEPENDS", count[0], 1.0f);
        });
        return payload.toJson(false);
    }

    /** Symbols declared in one file, with their internal and (capped) external edges. */
    String file(String relPath) {
        GraphQuery graph = project.graph();
        Node fileNode = graph.node(new FileId(relPath)).orElse(null);
        if (fileNode == null) {
            throw new IllegalArgumentException("unknown file: " + relPath);
        }
        ClosureResult members = graph.closure(fileNode.id(), Direction.OUT,
                Set.of(EdgeKind.CONTAINS), 3, 500, 0f);
        GraphPayload payload = new GraphPayload();
        payload.node("file:" + relPath, fileName(relPath), "file", relPath, 2, n -> { });
        Set<NodeId> included = new LinkedHashSet<>();
        included.add(fileNode.id());
        for (ClosureHit hit : members.hits()) {
            included.add(hit.node().id());
            addSymbolNode(payload, hit.node(), relPath);
        }
        int externals = 0;
        for (NodeId id : included) {
            for (Edge edge : graph.edges(id, Direction.OUT, null)) {
                if (included.contains(edge.to())) {
                    payload.link(edge.from().value(), edge.to().value(),
                            edge.kind().name(), 1, edge.confidence());
                } else if (edge.kind() != EdgeKind.CONTAINS && edge.confidence() >= CONFIDENT
                        && externals < 50 && edge.to() instanceof SymbolId target) {
                    Node external = graph.node(target).orElse(null);
                    if (external != null) {
                        payload.nodeIfAbsent(target.value(), external.name(), "external",
                                target.relPath(), 1);
                        payload.link(edge.from().value(), target.value(),
                                edge.kind().name(), 1, edge.confidence());
                        externals++;
                    }
                }
            }
        }
        return payload.toJson(false);
    }

    /** N-hop neighborhood of one symbol — the blast radius, literally visible. */
    String ego(String rawId, int depth, int cap) {
        GraphQuery graph = project.graph();
        NodeId center = NodeId.parse(rawId);
        Node centerNode = graph.node(center)
                .orElseThrow(() -> new IllegalArgumentException("unknown node: " + rawId));
        ClosureResult closure = graph.closure(center, Direction.BOTH, BlastScore.IMPACT_KINDS,
                depth, cap, 0f);
        GraphPayload payload = new GraphPayload();
        addSymbolNode(payload, centerNode, "center");
        Set<NodeId> included = new LinkedHashSet<>();
        included.add(center);
        for (ClosureHit hit : closure.hits()) {
            included.add(hit.node().id());
            addSymbolNode(payload, hit.node(), "depth-" + hit.depth());
        }
        for (NodeId id : included) {
            for (Edge edge : graph.edges(id, Direction.OUT, BlastScore.IMPACT_KINDS)) {
                if (included.contains(edge.to())) {
                    payload.link(edge.from().value(), edge.to().value(),
                            edge.kind().name(), 1, edge.confidence());
                }
            }
        }
        return payload.toJson(closure.truncated());
    }

    /** Capped whole-graph view: highest-degree symbols first, optional lang/module filters. */
    String galaxy(int cap, String lang, String module, float minConfidence) {
        GraphQuery graph = project.graph();
        ModuleGraph modules = module == null ? null : ModuleGraph.of(graph, project.config());
        var architecture = project.config().withDefaults().architecture();
        record Ranked(Node node, int degree, long order) { }
        Comparator<Ranked> rank = Comparator.comparingInt(Ranked::degree).reversed().thenComparingLong(Ranked::order);
        var candidates = new java.util.PriorityQueue<Ranked>(rank.reversed());
        long[] count = {0};
        graph.scanNodes(Set.of(NodeKind.TYPE, NodeKind.FUNCTION), node -> {
            if (lang != null && !lang.equals(node.lang())) {
                return;
            }
            if (modules != null && !module.equals(modules.moduleOf(node.relPath(), architecture))) {
                return;
            }
            candidates.add(new Ranked(node, graph.edges(node.id(), Direction.BOTH, BlastScore.IMPACT_KINDS).size(), count[0]++));
            if (candidates.size() > cap) candidates.poll();
        });
        boolean truncated = count[0] > cap;
        List<Node> selected = candidates.stream().sorted(rank).map(Ranked::node).toList();

        GraphPayload payload = new GraphPayload();
        Set<NodeId> included = new LinkedHashSet<>();
        for (Node node : selected) {
            included.add(node.id());
            addSymbolNode(payload, node, topModule(node.relPath()));
        }
        // Cap links too: the render cost (and browser hangs) on large graphs is dominated by
        // edges among top-degree hubs, not node count. Stop well before the browser chokes.
        int linkCount = 0;
        boolean linksTruncated = false;
        outer:
        for (NodeId id : included) {
            for (Edge edge : graph.edges(id, Direction.OUT, BlastScore.IMPACT_KINDS)) {
                if (edge.confidence() >= minConfidence && included.contains(edge.to())) {
                    if (linkCount >= MAX_GALAXY_LINKS) {
                        linksTruncated = true;
                        break outer;
                    }
                    payload.link(edge.from().value(), edge.to().value(),
                            edge.kind().name(), 1, edge.confidence());
                    linkCount++;
                }
            }
        }
        return payload.toJson(truncated || linksTruncated);
    }

    /** Detail panel: node facts, counts and the itemized blast score. */
    String node(String rawId) {
        GraphQuery graph = project.graph();
        NodeId id = NodeId.parse(rawId);
        Node node = graph.node(id)
                .orElseThrow(() -> new IllegalArgumentException("unknown node: " + rawId));
        ObjectNode out = JSON.createObjectNode();
        out.put("id", id.value());
        out.put("kind", node.kind().name().toLowerCase());
        out.put("label", node.name());
        out.put("sig", node.displaySignature());
        if (node.span() != null) {
            out.put("path", node.span().relPath());
            out.put("line", node.span().startLine());
        }
        if (node.lang() != null) {
            out.put("lang", node.lang());
        }
        ObjectNode counts = out.putObject("counts");
        counts.put("callers", graph.edges(id, Direction.IN, Set.of(EdgeKind.CALLS)).size());
        counts.put("callees", graph.edges(id, Direction.OUT, Set.of(EdgeKind.CALLS)).size());
        counts.put("refs", graph.edges(id, Direction.IN, Set.of(EdgeKind.REFERENCES)).size());
        if (node.metrics().loc() > 0) {
            out.put("loc", node.metrics().loc());
        }
        BlastScore.Scored scored = blastScore.compute(id);
        ObjectNode blast = out.putObject("blast");
        blast.put("score", scored.score());
        blast.put("band", scored.band());
        ArrayNode factors = blast.putArray("factors");
        for (BlastFormula.Factor factor : scored.factors()) {
            ObjectNode f = factors.addObject();
            f.put("name", factor.name());
            f.put("raw", factor.raw());
            f.put("points", factor.points());
        }
        blast.put("explanation", scored.explanation());
        return out.toString();
    }

    String search(String query, int limit) {
        ArrayNode out = JSON.createArrayNode();
        for (Node node : project.graph().findSymbols(query,
                Set.of(NodeKind.TYPE, NodeKind.FUNCTION, NodeKind.FILE), null, limit)) {
            ObjectNode row = out.addObject();
            row.put("id", node.id().value());
            row.put("label", node.name());
            row.put("kind", node.kind().name().toLowerCase());
            row.put("sig", node.displaySignature());
            if (node.span() != null) {
                row.put("path", node.span().relPath());
                row.put("line", node.span().startLine());
            }
        }
        return out.toString();
    }

    // ---- payload building ----

    private void addSymbolNode(GraphPayload payload, Node node, String group) {
        int size = node.kind() == NodeKind.FILE ? 2
                : Math.max(1, project.graph().edges(node.id(), Direction.IN,
                        BlastScore.IMPACT_KINDS).size() / 3);
        payload.nodeIfAbsent(node.id().value(), node.name(),
                node.kind().name().toLowerCase(), group, size);
    }

    private static String fileName(String relPath) {
        return relPath.substring(relPath.lastIndexOf('/') + 1);
    }

    private static String topModule(String relPath) {
        if (relPath == null) {
            return "(root)";
        }
        int slash = relPath.indexOf('/');
        return slash < 0 ? "(root)" : relPath.substring(0, slash);
    }

    /** Accumulates the shared {nodes, links, truncated} payload. */
    private static final class GraphPayload {
        private final Map<String, ObjectNode> nodes = new LinkedHashMap<>();
        private final ArrayNode links = JSON.createArrayNode();

        void node(String id, String label, String kind, String group, int val,
                  java.util.function.Consumer<ObjectNode> extra) {
            ObjectNode node = JSON.createObjectNode();
            node.put("id", id);
            node.put("label", label);
            node.put("kind", kind);
            node.put("group", group == null ? "(none)" : group);
            node.put("val", val);
            extra.accept(node);
            nodes.put(id, node);
        }

        void nodeIfAbsent(String id, String label, String kind, String group, int val) {
            if (!nodes.containsKey(id)) {
                node(id, label, kind, group, val, n -> { });
            }
        }

        void link(String source, String target, String kind, int count, float confidence) {
            ObjectNode link = links.addObject();
            link.put("source", source);
            link.put("target", target);
            link.put("kind", kind);
            link.put("count", count);
            link.put("confidence", confidence);
        }

        String toJson(boolean truncated) {
            ObjectNode out = JSON.createObjectNode();
            ArrayNode nodeArray = out.putArray("nodes");
            nodes.values().forEach(nodeArray::add);
            out.set("links", links);
            out.put("truncated", truncated);
            return out.toString();
        }
    }
}
