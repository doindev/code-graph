package io.doindev.codegraph.store.arango;

import io.doindev.codegraph.model.Metrics;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Hand-rolled compact string codecs for {@link Metrics} and attribute maps — deliberately
 * dependency-free (this module stays Jackson-free). Both directions round-trip exactly,
 * including {@code Float.NaN} (via {@link Float#toString}) and attribute values containing
 * the delimiter characters (backslash-escaped).
 */
final class Codecs {

    private Codecs() {
    }

    /** {@code loc,methodCount,fieldCount,paramCount,maxNestingDepth,cyclomaticApprox,internalCallDensity}. */
    static String metrics(Metrics m) {
        return m.loc() + "," + m.methodCount() + "," + m.fieldCount() + "," + m.paramCount() + ","
                + m.maxNestingDepth() + "," + m.cyclomaticApprox() + "," + m.internalCallDensity();
    }

    static Metrics parseMetrics(String s) {
        if (s == null || s.isEmpty()) {
            return Metrics.NONE;
        }
        String[] p = s.split(",", -1);
        if (p.length != 7) {
            throw new IllegalArgumentException("bad metrics encoding: " + s);
        }
        return new Metrics(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]),
                Integer.parseInt(p[3]), Integer.parseInt(p[4]), Integer.parseInt(p[5]),
                Float.parseFloat(p[6]));
    }

    /** Sorted {@code key=value;key=value} pairs; {@code \}, {@code =} and {@code ;} are backslash-escaped. */
    static String attrs(Map<String, String> attrs) {
        if (attrs.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : new TreeMap<>(attrs).entrySet()) {
            if (sb.length() > 0) {
                sb.append(';');
            }
            escape(sb, e.getKey());
            sb.append('=');
            escape(sb, e.getValue());
        }
        return sb.toString();
    }

    static Map<String, String> parseAttrs(String s) {
        if (s == null || s.isEmpty()) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        StringBuilder current = new StringBuilder();
        String key = null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') {
                current.append(s.charAt(++i));
            } else if (c == '=' && key == null) {
                key = current.toString();
                current.setLength(0);
            } else if (c == ';') {
                result.put(key, current.toString());
                current.setLength(0);
                key = null;
            } else {
                current.append(c);
            }
        }
        if (key != null) {
            result.put(key, current.toString());
        }
        return Map.copyOf(result);
    }

    private static void escape(StringBuilder sb, String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' || c == '=' || c == ';') {
                sb.append('\\');
            }
            sb.append(c);
        }
    }
}
