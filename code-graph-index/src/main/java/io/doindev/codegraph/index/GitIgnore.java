package io.doindev.codegraph.index;

import io.doindev.codegraph.util.Globs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Minimal hierarchical {@code .gitignore} support: {@code *}, {@code **}, {@code ?},
 * trailing {@code /} (directory-only), leading {@code /} (anchored), {@code !} negation,
 * last-match-wins. Best-effort — exotic git semantics (escapes, character classes) are out of
 * scope and documented as such.
 */
final class GitIgnore {

    record Rule(Pattern exact, Pattern subtree, boolean negate, boolean dirOnly) {

        boolean matches(String relPath) {
            return exact.matcher(relPath).matches() || subtree.matcher(relPath).matches();
        }
    }

    private GitIgnore() {
    }

    /** Parse the {@code .gitignore} in {@code dir} (repo-relative prefix {@code dirPrefix}, "" for root). */
    static List<Rule> load(Path dir, String dirPrefix) {
        Path file = dir.resolve(".gitignore");
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        List<Rule> rules = new ArrayList<>();
        try {
            for (String raw : Files.readAllLines(file)) {
                String line = raw.strip();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                boolean negate = line.startsWith("!");
                if (negate) {
                    line = line.substring(1);
                }
                boolean dirOnly = line.endsWith("/");
                if (dirOnly) {
                    line = line.substring(0, line.length() - 1);
                }
                boolean anchored = line.startsWith("/");
                if (anchored) {
                    line = line.substring(1);
                } else {
                    anchored = line.contains("/");
                }
                String prefix = dirPrefix.isEmpty() ? "" : dirPrefix + "/";
                String glob = anchored ? prefix + line : prefix + "**/" + line;
                rules.add(new Rule(Globs.compile(glob), Globs.compile(glob + "/**"), negate, dirOnly));
            }
        } catch (IOException e) {
            return List.of(); // unreadable .gitignore: index everything rather than fail
        }
        return rules;
    }

    /** Last matching rule wins; no match = not ignored. */
    static boolean ignored(String relPath, List<List<Rule>> ruleStack) {
        Boolean decision = null;
        for (List<Rule> rules : ruleStack) {
            for (Rule rule : rules) {
                if (rule.matches(relPath)) {
                    decision = !rule.negate();
                }
            }
        }
        return decision != null && decision;
    }
}
