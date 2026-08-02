package io.doindev.codegraph.smells;

import io.doindev.codegraph.config.CodeGraphConfig;
import io.doindev.codegraph.model.Metrics;
import io.doindev.codegraph.model.Node;
import io.doindev.codegraph.model.NodeKind;
import io.doindev.codegraph.query.GraphQuery;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Metric-threshold detectors over the AST metrics captured at index time. */
final class MetricSmells {

    private MetricSmells() {
    }

    /** God class: too many methods AND fields, or sheer size — a type doing everything. */
    static final class GodClass implements SmellEngine.Detector {
        @Override
        public String id() {
            return "god-class";
        }

        @Override
        public String defaultSeverity() {
            return "warning";
        }

        @Override
        public List<SmellFinding> detect(GraphQuery graph, CodeGraphConfig config, SmellEngine engine) {
            double methodCount = engine.threshold(id(), "methodCount", 25);
            double fieldCount = engine.threshold(id(), "fieldCount", 15);
            double loc = engine.threshold(id(), "loc", 500);
            List<SmellFinding> findings = new ArrayList<>();
            for (Node node : graph.allNodes(Set.of(NodeKind.TYPE))) {
                Metrics m = node.metrics();
                boolean crowded = m.methodCount() >= methodCount && m.fieldCount() >= fieldCount;
                boolean huge = m.loc() >= loc && m.methodCount() >= methodCount / 2;
                if (!crowded && !huge) {
                    continue;
                }
                Map<String, String> evidence = SmellEngine.ev(
                        "methodCount", m.methodCount() + " (threshold " + (long) methodCount + ")",
                        "fieldCount", m.fieldCount() + " (threshold " + (long) fieldCount + ")",
                        "loc", m.loc() + " (threshold " + (long) loc + ")");
                findings.add(new SmellFinding(id(), node.id().value(), engine.severity(this), evidence,
                        "a type with >= " + (long) methodCount + " methods and >= " + (long) fieldCount
                                + " fields (or >= " + (long) loc + " LOC) concentrates too many responsibilities"));
            }
            return findings;
        }
    }

    /** Long method: size, branching complexity or nesting beyond thresholds. */
    static final class LongMethod implements SmellEngine.Detector {
        @Override
        public String id() {
            return "long-method";
        }

        @Override
        public String defaultSeverity() {
            return "warning";
        }

        @Override
        public List<SmellFinding> detect(GraphQuery graph, CodeGraphConfig config, SmellEngine engine) {
            double loc = engine.threshold(id(), "loc", 75);
            double complexity = engine.threshold(id(), "cyclomatic", 15);
            double nesting = engine.threshold(id(), "nesting", 5);
            List<SmellFinding> findings = new ArrayList<>();
            for (Node node : graph.allNodes(Set.of(NodeKind.FUNCTION))) {
                Metrics m = node.metrics();
                List<String> hits = new ArrayList<>();
                if (m.loc() >= loc) {
                    hits.add("loc " + m.loc() + " >= " + (long) loc);
                }
                if (m.cyclomaticApprox() >= complexity) {
                    hits.add("cyclomatic " + m.cyclomaticApprox() + " >= " + (long) complexity);
                }
                if (m.maxNestingDepth() >= nesting) {
                    hits.add("nesting " + m.maxNestingDepth() + " >= " + (long) nesting);
                }
                if (hits.isEmpty()) {
                    continue;
                }
                findings.add(new SmellFinding(id(), node.id().value(), engine.severity(this),
                        SmellEngine.ev("exceeded", String.join("; ", hits)),
                        "functions beyond " + (long) loc + " LOC / cyclomatic " + (long) complexity
                                + " / nesting " + (long) nesting + " resist safe modification"));
            }
            return findings;
        }
    }

    /** Long parameter list. */
    static final class LongParameterList implements SmellEngine.Detector {
        @Override
        public String id() {
            return "long-parameter-list";
        }

        @Override
        public String defaultSeverity() {
            return "info";
        }

        @Override
        public List<SmellFinding> detect(GraphQuery graph, CodeGraphConfig config, SmellEngine engine) {
            double params = engine.threshold(id(), "paramCount", 6);
            List<SmellFinding> findings = new ArrayList<>();
            for (Node node : graph.allNodes(Set.of(NodeKind.FUNCTION))) {
                if (node.metrics().paramCount() < params) {
                    continue;
                }
                findings.add(new SmellFinding(id(), node.id().value(), engine.severity(this),
                        SmellEngine.ev("paramCount",
                                SmellEngine.evidence(String.valueOf(node.metrics().paramCount()), params)),
                        ">= " + (long) params + " parameters suggest a missing parameter object"));
            }
            return findings;
        }
    }

    /** Large file. */
    static final class LargeFile implements SmellEngine.Detector {
        @Override
        public String id() {
            return "large-file";
        }

        @Override
        public String defaultSeverity() {
            return "info";
        }

        @Override
        public List<SmellFinding> detect(GraphQuery graph, CodeGraphConfig config, SmellEngine engine) {
            double loc = engine.threshold(id(), "loc", 1000);
            List<SmellFinding> findings = new ArrayList<>();
            for (Node node : graph.allNodes(Set.of(NodeKind.FILE))) {
                if (node.metrics().loc() < loc) {
                    continue;
                }
                findings.add(new SmellFinding(id(), node.id().value(), engine.severity(this),
                        SmellEngine.ev("loc", SmellEngine.evidence(String.valueOf(node.metrics().loc()), loc)),
                        "files beyond " + (long) loc + " LOC accumulate unrelated concerns"));
            }
            return findings;
        }
    }
}
