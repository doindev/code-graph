package io.doindev.codegraph.cli;

import java.io.PrintStream;
import java.util.List;

/**
 * Entry point of the shaded {@code code-graph} executable jar:
 * {@code java -jar code-graph.jar <command> [flags]}. Commands: {@code index}, {@code ci},
 * {@code check}. Bad usage exits 64 (EX_USAGE); execution errors exit 1; the {@code ci}
 * gating codes are documented on {@link CiReport}.
 */
public final class Main {

    static final String USAGE = """
            usage:
              code-graph index [--root DIR] [--snapshot-out FILE]
              code-graph ci    --base <ref> [--head <ref>] [--root DIR] [--report FILE] [--format md|json]
                               [--github-comment] [--gitlab-comment] [--pr N]
                               [--fail-on blast,drift] [--threshold N]
              code-graph check --target <symbolId|path> [--root DIR]

            exit codes (ci): 0 pass · 1 error · 2 blast gate · 3 drift gate · 4 both · 64 usage""";

    private Main() {
    }

    public static void main(String[] args) {
        System.exit(execute(args, System.out, System.err));
    }

    /** Testable dispatch — never calls {@code System.exit} itself. */
    static int execute(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 0) {
            err.println(USAGE);
            return 64;
        }
        List<String> rest = List.of(args).subList(1, args.length);
        try {
            return switch (args[0]) {
                case "index" -> IndexCommand.run(rest, out, err);
                case "ci" -> CiCommand.run(CiCommand.Options.parse(rest), out, err);
                case "check" -> CheckCommand.run(rest, out, err);
                default -> {
                    err.println("code-graph: unknown command: " + args[0]);
                    err.println(USAGE);
                    yield 64;
                }
            };
        } catch (Args.UsageException e) {
            err.println("code-graph: " + e.getMessage());
            err.println(USAGE);
            return 64;
        } catch (Exception e) {
            err.println("code-graph: " + e.getMessage());
            return 1;
        }
    }
}
