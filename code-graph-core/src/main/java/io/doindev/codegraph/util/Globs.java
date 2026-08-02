package io.doindev.codegraph.util;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Minimal glob matching over repo-relative '/'-separated paths: {@code **} crosses directory
 * boundaries, {@code *} and {@code ?} do not. Used for config globs (paths, tests, dead-code
 * entry points, blueprint modules). Full-path anchored.
 */
public final class Globs {

    private Globs() {
    }

    public static boolean matches(String glob, String relPath) {
        return compile(glob).matcher(relPath).matches();
    }

    public static boolean matchesAny(List<String> globs, String relPath) {
        for (String glob : globs) {
            if (matches(glob, relPath)) {
                return true;
            }
        }
        return false;
    }

    public static Pattern compile(String glob) {
        StringBuilder regex = new StringBuilder();
        int i = 0;
        int length = glob.length();
        while (i < length) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> {
                    if (i + 1 < length && glob.charAt(i + 1) == '*') {
                        // "**/" also matches zero directories
                        if (i + 2 < length && glob.charAt(i + 2) == '/') {
                            regex.append("(?:.*/)?");
                            i += 3;
                        } else {
                            regex.append(".*");
                            i += 2;
                        }
                    } else {
                        regex.append("[^/]*");
                        i++;
                    }
                }
                case '?' -> {
                    regex.append("[^/]");
                    i++;
                }
                default -> {
                    if ("\\.[]{}()+-^$|".indexOf(c) >= 0) {
                        regex.append('\\');
                    }
                    regex.append(c);
                    i++;
                }
            }
        }
        return Pattern.compile(regex.toString());
    }
}
