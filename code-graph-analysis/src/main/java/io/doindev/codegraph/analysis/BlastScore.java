package io.doindev.codegraph.analysis;

import io.doindev.codegraph.config.CodeGraphConfig;
import io.doindev.codegraph.model.Direction;
import io.doindev.codegraph.model.EdgeKind;
import io.doindev.codegraph.model.Node;
import io.doindev.codegraph.model.NodeId;
import io.doindev.codegraph.model.NodeKind;
import io.doindev.codegraph.query.ClosureHit;
import io.doindev.codegraph.query.ClosureResult;
import io.doindev.codegraph.query.GraphQuery;
import io.doindev.codegraph.util.Globs;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Gathers the blast-score inputs from the graph and applies {@link BlastFormula}.
 * A file or type scores as the <em>maximum</em> over its contained symbols — a file is as
 * risky as its riskiest symbol; summing would punish big files.
 */
public final class BlastScore {

    /** Reverse edge kinds that constitute "dependents" for impact purposes. */
    public static final Set<EdgeKind> IMPACT_KINDS =
            Set.of(EdgeKind.CALLS, EdgeKind.REFERENCES, EdgeKind.IMPORTS, EdgeKind.EXTENDS, EdgeKind.IMPLEMENTS);

    private static final int MAX_DEPTH = 10;
    private static final int NODE_CAP = 5000;
    private static final int TOP_DEPENDENTS = 5;

    public record Scored(NodeId target, int score, String band, List<BlastFormula.Factor> factors,
                         List<String> topDependents, String explanation, boolean truncated) {
    }

    private final GraphQuery graph;
    private final CodeGraphConfig config;
    private final Runnable edgeVisit;

    public BlastScore(GraphQuery graph, CodeGraphConfig config) {
        this(graph,config,null);
    }
    private BlastScore(GraphQuery graph,CodeGraphConfig config,Runnable edgeVisit) {
        this.graph = graph;
        this.config = config.withDefaults();
        this.edgeVisit=edgeVisit;
    }

    /** Streaming variant sharing its caller's work/cancellation budget. No complete adjacency lists. */
    public Scored computeBounded(NodeId target,Runnable edgeVisit) {
        return new BlastScore(graph,config,java.util.Objects.requireNonNull(edgeVisit)).compute(target);
    }

    public Scored compute(NodeId target) {
        Node node = graph.node(target).orElse(null);
        if (node == null) {
            throw new IllegalArgumentException("unknown target: " + target.value());
        }
        if (node.kind() == NodeKind.FILE || node.kind() == NodeKind.TYPE) {
            // max over contained symbols (including the container itself for direct references)
            Scored best = scoreSymbol(target);
            ClosureResult members = closure(target, Direction.OUT,
                    Set.of(EdgeKind.CONTAINS), 3, 500, 0f);
            for (ClosureHit member : members.hits()) {
                NodeKind kind = member.node().kind();
                if (kind != NodeKind.FUNCTION && kind != NodeKind.TYPE) {
                    continue;
                }
                Scored candidate = scoreSymbol(member.node().id());
                if (candidate.score() > best.score()) {
                    best = candidate;
                }
            }
            return new Scored(target, best.score(), best.band(), best.factors(),
                    best.topDependents(),
                    best.target().equals(target) ? best.explanation()
                            : "max over contained symbols; riskiest: " + best.target().value()
                                    + " — " + best.explanation(),
                    best.truncated());
        }
        return scoreSymbol(target);
    }

    private Scored scoreSymbol(NodeId target) {
        ClosureResult closure = closure(target, Direction.IN, IMPACT_KINDS, MAX_DEPTH, NODE_CAP, 0f);
        List<ClosureHit> hits = closure.hits();

        long fanIn = degree(target,Set.of(EdgeKind.CALLS, EdgeKind.REFERENCES));
        Set<String> modules = new TreeSet<>();
        Set<String> langs = new HashSet<>();
        boolean tested = false;
        List<String> testGlobs = config.tests().globs();
        for (ClosureHit hit : hits) {
            String relPath = hit.node().relPath();
            if (relPath != null) {
                modules.add(relPath.contains("/") ? relPath.substring(0, relPath.indexOf('/')) : "(root)");
                if (!tested && Globs.matchesAny(testGlobs, relPath)) {
                    tested = true;
                }
            }
            String lang = hit.node().lang();
            if (lang != null) {
                langs.add(lang);
            }
        }
        // "untested" only adds risk when the symbol actually has dependents to break —
        // an isolated symbol with no reach carries no change risk from missing tests
        boolean untested = !tested && !hits.isEmpty();

        BlastFormula.Result result = BlastFormula.score(hits.size(), fanIn, modules.size(),
                langs.size(), untested, config.scoring());

        List<String> top = new ArrayList<>(TOP_DEPENDENTS);
        var degrees=new java.util.HashMap<NodeId,Long>();
        if(edgeVisit!=null)for(var hit:hits)degrees.computeIfAbsent(hit.node().id(),id->degree(id,IMPACT_KINDS));
        hits.stream()
                .map(h -> h.node().id())
                .distinct()
                .sorted(Comparator.comparingLong(
                        (NodeId id) -> edgeVisit==null?degree(id,IMPACT_KINDS):degrees.get(id)).reversed())
                .limit(TOP_DEPENDENTS)
                .forEach(id -> top.add(id.value()));

        String explanation = hits.size() + " transitive dependents across " + modules.size()
                + " module(s) and " + langs.size() + " language(s); " + fanIn + " direct callers/refs; "
                + (untested ? "no test references" : "has test references");
        return new Scored(target, result.score(), result.band(), result.factors(), top,
                explanation, closure.truncated());
    }

    private long degree(NodeId id,Set<EdgeKind> kinds) {
        if(edgeVisit==null)return graph.edges(id,Direction.IN,kinds).size();
        long[] count={0};
        graph.scanEdges(id,Direction.IN,kinds,e->{edgeVisit.run();count[0]++;});
        return count[0];
    }
    private ClosureResult closure(NodeId start,Direction direction,Set<EdgeKind> kinds,int depth,int limit,float confidence) {
        if(edgeVisit==null)return graph.closure(start,direction,kinds,depth,limit,confidence);
        record Step(NodeId id,int depth){}
        var pending=new java.util.ArrayDeque<Step>();pending.add(new Step(start,0));
        var seen=new HashSet<NodeId>();seen.add(start);var hits=new ArrayList<ClosureHit>();
        try {
            while(!pending.isEmpty()) {
                Step current=pending.removeFirst();if(current.depth()>=depth)continue;
                graph.scanEdges(current.id(),direction,kinds,e->{
                    edgeVisit.run();
                    if(e.confidence()<confidence)return;
                    NodeId next=direction==Direction.IN?e.from():e.to();
                    if(seen.contains(next))return;
                    if(hits.size()>=limit)throw new NodeLimit();
                    seen.add(next);Node node=graph.node(next).orElse(null);if(node==null)return;
                    hits.add(new ClosureHit(node,current.depth()+1,e));pending.addLast(new Step(next,current.depth()+1));
                });
            }
        }catch(NodeLimit reached){return new ClosureResult(hits,true);}
        return new ClosureResult(hits,false);
    }
    private static final class NodeLimit extends RuntimeException {
        NodeLimit(){super(null,null,false,false);}
    }
}
