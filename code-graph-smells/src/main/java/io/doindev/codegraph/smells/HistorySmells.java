package io.doindev.codegraph.smells;

import io.doindev.codegraph.config.CodeGraphConfig;
import io.doindev.codegraph.model.Direction;
import io.doindev.codegraph.model.Edge;
import io.doindev.codegraph.model.FileId;
import io.doindev.codegraph.model.Node;
import io.doindev.codegraph.model.NodeKind;
import io.doindev.codegraph.model.SymbolId;
import io.doindev.codegraph.query.GraphQuery;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Wave-B detectors that need the working tree: temporal coupling (git co-change mining) and
 * duplicated logic (normalized-line shingle clones). Both are registered only when the
 * {@link SmellEngine} was constructed with a repo root.
 */
final class HistorySmells {

    private HistorySmells() {
    }

    /**
     * Temporal coupling: file pairs that keep changing in the same commits but share NO
     * structural edge — hidden coupling the graph cannot see. Mined from
     * {@code git log --name-only} (bounded); silently absent when git is unavailable.
     */
    static final class TemporalCoupling implements SmellEngine.Detector {

        private static final int MAX_COMMITS = 1000;
        private static final int MAX_FILES_PER_COMMIT = 25; // bulk commits (reformat, rename) carry no signal

        private final Path repoRoot;

        TemporalCoupling(Path repoRoot) {
            this.repoRoot = repoRoot;
        }

        @Override
        public String id() {
            return "temporal-coupling";
        }

        @Override
        public String defaultSeverity() {
            return "info";
        }

        @Override
        public List<SmellFinding> detect(GraphQuery graph, CodeGraphConfig config, SmellEngine engine) {
            double minSupport = engine.threshold(id(), "minCoChanges", 5);
            double minConfidence = engine.threshold(id(), "minConfidence", 0.6);

            List<List<String>> commits = gitCommitFiles();
            if (commits.isEmpty()) {
                return List.of();
            }
            Set<String> indexed = new HashSet<>();
            for (Node node : graph.allNodes(Set.of(NodeKind.FILE))) {
                indexed.add(node.relPath());
            }
            Map<String, Integer> changeCount = new HashMap<>();
            Map<String, Integer> pairCount = new HashMap<>();
            for (List<String> files : commits) {
                List<String> relevant = files.stream().filter(indexed::contains).toList();
                if (relevant.size() < 2 || relevant.size() > MAX_FILES_PER_COMMIT) {
                    relevant.forEach(f -> changeCount.merge(f, 1, Integer::sum));
                    continue;
                }
                relevant.forEach(f -> changeCount.merge(f, 1, Integer::sum));
                for (int i = 0; i < relevant.size(); i++) {
                    for (int j = i + 1; j < relevant.size(); j++) {
                        String a = relevant.get(i);
                        String b = relevant.get(j);
                        pairCount.merge(a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a, 1, Integer::sum);
                    }
                }
            }

            List<SmellFinding> findings = new ArrayList<>();
            pairCount.forEach((pair, support) -> {
                if (support < minSupport) {
                    return;
                }
                String[] files = pair.split("\\|", 2);
                int minChanges = Math.min(changeCount.getOrDefault(files[0], support),
                        changeCount.getOrDefault(files[1], support));
                double confidence = (double) support / minChanges;
                if (confidence < minConfidence || structurallyCoupled(graph, files[0], files[1])) {
                    return;
                }
                findings.add(new SmellFinding(id(), "file:" + files[0], engine.severity(this),
                        SmellEngine.ev("with", files[1],
                                "coChanges", support + " of " + minChanges + " changes ("
                                        + String.format(java.util.Locale.ROOT, "%.0f%%", confidence * 100) + ")"),
                        "files that always change together without any structural edge hide an implicit contract"));
            });
            return findings;
        }

        /** Any confident symbol-level edge between the two files counts as structural coupling. */
        private static boolean structurallyCoupled(GraphQuery graph, String fileA, String fileB) {
            Node file = graph.node(new FileId(fileA)).orElse(null);
            if (file == null) {
                return false;
            }
            for (Edge contains : graph.edges(file.id(), Direction.OUT, Set.of(io.doindev.codegraph.model.EdgeKind.CONTAINS))) {
                for (Edge out : graph.edges(contains.to(), Direction.BOTH, null)) {
                    String otherPath = out.to() instanceof SymbolId s ? s.relPath()
                            : out.from() instanceof SymbolId s2 ? s2.relPath() : null;
                    if (fileB.equals(otherPath)) {
                        return true;
                    }
                }
            }
            return false;
        }

        private List<List<String>> gitCommitFiles() {
            try {
                Process process = new ProcessBuilder("git", "log", "--name-only",
                        "--pretty=format:@@COMMIT@@", "-n", String.valueOf(MAX_COMMITS))
                        .directory(repoRoot.toFile())
                        .redirectErrorStream(false)
                        .start();
                List<List<String>> commits = new ArrayList<>();
                List<String> current = new ArrayList<>();
                try (var reader = process.inputReader(StandardCharsets.UTF_8)) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.equals("@@COMMIT@@")) {
                            if (!current.isEmpty()) {
                                commits.add(current);
                            }
                            current = new ArrayList<>();
                        } else if (!line.isBlank()) {
                            current.add(line.trim().replace('\\', '/'));
                        }
                    }
                }
                if (!current.isEmpty()) {
                    commits.add(current);
                }
                if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) {
                    return List.of();
                }
                return commits;
            } catch (IOException e) {
                return List.of(); // no git — detector silently contributes nothing
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return List.of();
            }
        }
    }

    /**
     * Duplicated logic: file pairs sharing many normalized-line shingles (windowed hashes over
     * comment-stripped, whitespace-collapsed lines). Catches copy-paste (type-1) and
     * lightly-edited (partial type-2) clones; renamed-identifier clones are out of scope for v1.
     */
    static final class Clones implements SmellEngine.Detector {

        private static final int SHINGLE_LINES = 6;
        private static final long MAX_FILE_BYTES = 512L * 1024;

        private final Path repoRoot;

        Clones(Path repoRoot) {
            this.repoRoot = repoRoot;
        }

        @Override
        public String id() {
            return "duplicated-logic";
        }

        @Override
        public String defaultSeverity() {
            return "warning";
        }

        @Override
        public List<SmellFinding> detect(GraphQuery graph, CodeGraphConfig config, SmellEngine engine) {
            double minShared = engine.threshold(id(), "minSharedShingles", 8);
            Map<Long, List<String>> filesByShingle = new HashMap<>();
            for (Node node : graph.allNodes(Set.of(NodeKind.FILE))) {
                String relPath = node.relPath();
                Path file = repoRoot.resolve(relPath);
                try {
                    if (!Files.isRegularFile(file) || Files.size(file) > MAX_FILE_BYTES) {
                        continue;
                    }
                    for (long shingle : shingles(Files.readString(file, StandardCharsets.UTF_8))) {
                        List<String> files = filesByShingle.computeIfAbsent(shingle, k -> new ArrayList<>(2));
                        if (!files.contains(relPath)) {
                            files.add(relPath);
                        }
                    }
                } catch (IOException | RuntimeException ignored) {
                    // unreadable file contributes nothing
                }
            }
            Map<String, Integer> sharedByPair = new HashMap<>();
            for (List<String> files : filesByShingle.values()) {
                if (files.size() < 2 || files.size() > 5) {
                    continue; // shingles in many files are boilerplate, not clones
                }
                for (int i = 0; i < files.size(); i++) {
                    for (int j = i + 1; j < files.size(); j++) {
                        String a = files.get(i);
                        String b = files.get(j);
                        sharedByPair.merge(a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a, 1, Integer::sum);
                    }
                }
            }
            List<SmellFinding> findings = new ArrayList<>();
            sharedByPair.forEach((pair, shared) -> {
                if (shared < minShared) {
                    return;
                }
                String[] files = pair.split("\\|", 2);
                findings.add(new SmellFinding(id(), "file:" + files[0], engine.severity(this),
                        SmellEngine.ev("with", files[1],
                                "sharedBlocks", shared + " x " + SHINGLE_LINES + "-line windows"),
                        "duplicated blocks drift apart silently — extract the shared logic"));
            });
            return findings;
        }

        static List<Long> shingles(String content) {
            List<String> normalized = new ArrayList<>();
            for (String raw : content.split("\n", -1)) {
                String line = raw.strip().replaceAll("\\s+", " ");
                if (line.length() < 4 || line.startsWith("//") || line.startsWith("#")
                        || line.startsWith("*") || line.startsWith("/*") || line.startsWith("import ")
                        || line.startsWith("package ") || line.startsWith("using ")
                        || line.equals("}") || line.equals("{") || line.equals("};")) {
                    continue;
                }
                normalized.add(line);
            }
            List<Long> shingles = new ArrayList<>();
            for (int i = 0; i + SHINGLE_LINES <= normalized.size(); i++) {
                long hash = 1125899906842597L;
                for (int j = 0; j < SHINGLE_LINES; j++) {
                    hash = 31 * hash + normalized.get(i + j).hashCode();
                }
                shingles.add(hash);
            }
            return shingles;
        }
    }
}
