package io.doindev.codegraph.cli;

import io.doindev.codegraph.index.FullIndexer;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/** {@code code-graph index} — full index, stats, optional single-file snapshot. */
final class IndexCommand {

    private IndexCommand() {
    }

    static int run(List<String> argv, PrintStream out, PrintStream err) throws IOException {
        Args args = Args.parse(argv, Set.of("--root", "--snapshot-out"), Set.of());
        Path root = Path.of(args.value("--root", ".")).toAbsolutePath().normalize();

        long start = System.nanoTime();
        Indexing.Indexed indexed = Indexing.index(root);
        FullIndexer.Result result = indexed.result();
        out.printf("indexed %d files, %d symbols, %d edges in %d ms (root=%s)%n",
                result.filesIndexed(), result.symbolCount(), result.edgeCount(),
                (System.nanoTime() - start) / 1_000_000, root);
        if (!result.failedFiles().isEmpty()) {
            err.println(result.failedFiles().size() + " file(s) failed to parse");
        }

        String snapshotOut = args.value("--snapshot-out");
        if (snapshotOut != null) {
            Path file = Path.of(snapshotOut).toAbsolutePath().normalize();
            Snapshots.write(indexed.graph(), file);
            out.println("snapshot written to " + file);
        }
        return 0;
    }
}
