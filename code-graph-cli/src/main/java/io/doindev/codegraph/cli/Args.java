package io.doindev.codegraph.cli;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tiny hand-rolled flag parser (deliberately no CLI framework). Accepts {@code --flag value},
 * {@code --flag=value} and bare {@code --flag} switches. Unknown flags, missing values and
 * missing required flags raise {@link UsageException}; {@link Main} prints usage and exits 64
 * (EX_USAGE) — the parse itself never calls {@code System.exit} so tests can drive it directly.
 */
final class Args {

    /** Bad command line — caller prints usage and exits 64. */
    static final class UsageException extends RuntimeException {
        UsageException(String message) {
            super(message);
        }
    }

    private final Map<String, String> values;
    private final Set<String> switches;

    private Args(Map<String, String> values, Set<String> switches) {
        this.values = Map.copyOf(values);
        this.switches = Set.copyOf(switches);
    }

    static Args parse(List<String> argv, Set<String> valueFlags, Set<String> switchFlags) {
        Map<String, String> values = new LinkedHashMap<>();
        Set<String> switches = new LinkedHashSet<>();
        for (int i = 0; i < argv.size(); i++) {
            String token = argv.get(i);
            if (!token.startsWith("--")) {
                throw new UsageException("unexpected argument: " + token);
            }
            String name = token;
            String inline = null;
            int eq = token.indexOf('=');
            if (eq >= 0) {
                name = token.substring(0, eq);
                inline = token.substring(eq + 1);
            }
            if (switchFlags.contains(name)) {
                if (inline != null) {
                    throw new UsageException(name + " takes no value");
                }
                switches.add(name);
            } else if (valueFlags.contains(name)) {
                String value = inline;
                if (value == null) {
                    if (i + 1 >= argv.size()) {
                        throw new UsageException(name + " requires a value");
                    }
                    value = argv.get(++i);
                }
                values.put(name, value);
            } else {
                throw new UsageException("unknown flag: " + name);
            }
        }
        return new Args(values, switches);
    }

    String value(String flag) {
        return values.get(flag);
    }

    String value(String flag, String fallback) {
        return values.getOrDefault(flag, fallback);
    }

    String require(String flag) {
        String value = values.get(flag);
        if (value == null) {
            throw new UsageException(flag + " is required");
        }
        return value;
    }

    boolean has(String flag) {
        return switches.contains(flag);
    }
}
