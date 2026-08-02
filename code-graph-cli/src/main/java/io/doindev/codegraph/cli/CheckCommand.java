package io.doindev.codegraph.cli;

import io.doindev.codegraph.analysis.BlastScore;
import io.doindev.codegraph.model.FileId;
import io.doindev.codegraph.model.NodeId;
import io.doindev.codegraph.rules.Drift;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * {@code code-graph check --target <symbolId|path>} — index, then print the blast-score factor
 * table for one target plus any architecture violations that touch it. A target containing
 * {@code ':'} is parsed as a canonical node id; anything else is treated as a repo-relative
 * file path.
 */
final class CheckCommand {

    private CheckCommand() {
    }

    static int run(List<String> argv, PrintStream out, PrintStream err) {
        Args args = Args.parse(argv, Set.of("--target", "--root"), Set.of());
        String target = args.require("--target");
        Path root = Path.of(args.value("--root", ".")).toAbsolutePath().normalize();

        Indexing.Indexed indexed = Indexing.index(root);
        NodeId id = target.contains(":") ? NodeId.parse(target)
                : new FileId(target.replace('\\', '/'));
        if (indexed.graph().node(id).isEmpty()) {
            err.println("target not found in index: " + id.value());
            return 1;
        }

        BlastScore.Scored scored = new BlastScore(indexed.graph(), indexed.config()).compute(id);
        out.println(id.value());
        out.printf("blast score: %d (%s)%n", scored.score(), scored.band());
        out.println(scored.explanation());
        out.println();
        out.print(CiReport.factorTable(scored.factors()));
        if (!scored.topDependents().isEmpty()) {
            out.println();
            out.println("top dependents:");
            scored.topDependents().forEach(dependent -> out.println("  " + dependent));
        }

        String needle = id instanceof FileId file ? file.relPath() : id.value();
        List<Drift.Violation> touching = Drift.evaluate(indexed.graph(), indexed.config())
                .violations().stream()
                .filter(violation -> violation.witnesses().stream().anyMatch(w -> w.contains(needle)))
                .toList();
        if (!touching.isEmpty()) {
            out.println();
            out.println("architecture violations touching this target:");
            for (Drift.Violation violation : touching) {
                out.println("  [" + violation.type() + "] " + violation.rule()
                        + " (" + violation.fingerprint() + ")");
                violation.witnesses().forEach(witness -> out.println("    witness: " + witness));
            }
        }
        return 0;
    }
}
