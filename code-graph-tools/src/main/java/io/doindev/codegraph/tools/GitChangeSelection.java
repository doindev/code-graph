package io.doindev.codegraph.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Resolves bounded Git selections without shell interpretation or historical-index claims. */
final class GitChangeSelection {
    private static final int MAX_OUTPUT_BYTES = 1_048_576;
    private static final int MAX_PATHS = 100;
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    record Selection(List<String> paths, ObjectNode evidence) {}

    static Selection resolve(Path root, JsonNode input) {
        if (root == null || input == null || !input.isObject()) {
            throw new IllegalArgumentException("git must be an object and the indexed project must have a repository root");
        }
        Path repository = root.toAbsolutePath().normalize();
        if (!Files.isDirectory(repository.resolve(".git"))) {
            throw new IllegalArgumentException("The indexed project is not a Git repository");
        }
        String kind = text(input, "kind", "working_tree");
        if (!kind.equals("working_tree") && !kind.equals("revisions")) {
            throw new IllegalArgumentException("git.kind must be working_tree or revisions");
        }
        input.fieldNames().forEachRemaining(key -> {
            if (!List.of("kind", "base", "head").contains(key)) {
                throw new IllegalArgumentException("Unsupported git selection field: " + key);
            }
        });

        String base;
        String head;
        byte[] changed;
        if (kind.equals("working_tree")) {
            base = resolveCommit(repository, text(input, "base", "HEAD"));
            head = "WORKING_TREE";
            changed = run(repository, List.of("diff", "--find-renames=50%", "--name-status", "-z", base, "--"));
            byte[] untracked = run(repository, List.of("ls-files", "--others", "--exclude-standard", "-z"));
            changed = join(changed, encodeUntracked(untracked));
        } else {
            base = resolveCommit(repository, required(input, "base"));
            head = resolveCommit(repository, text(input, "head", "HEAD"));
            changed = run(repository, List.of("diff", "--find-renames=50%", "--name-status", "-z", base, head, "--"));
        }

        ArrayNode changes = ToolSupport.JSON.createArrayNode();
        LinkedHashSet<String> paths = parseStatus(changed, changes);
        ObjectNode evidence = ToolSupport.JSON.createObjectNode();
        evidence.put("kind", kind).put("baseCommit", base).put("headCommit", head);
        evidence.set("changes", changes);
        evidence.put("pathCount", paths.size()).put("complete", true);
        return new Selection(List.copyOf(paths), evidence);
    }

    private static LinkedHashSet<String> parseStatus(byte[] bytes, ArrayNode changes) {
        List<String> fields = nulFields(bytes);
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        for (int i = 0; i < fields.size();) {
            String status = fields.get(i++);
            if (status.isBlank()) continue;
            char code = Character.toUpperCase(status.charAt(0));
            String oldPath = null;
            String path;
            if (code == 'R' || code == 'C') {
                if (i + 1 >= fields.size()) throw new IllegalArgumentException("Git returned an incomplete rename/copy record");
                oldPath = safePath(fields.get(i++));
                path = safePath(fields.get(i++));
            } else {
                if (i >= fields.size()) throw new IllegalArgumentException("Git returned an incomplete change record");
                path = safePath(fields.get(i++));
            }
            addPath(paths, path);
            if (oldPath != null) addPath(paths, oldPath);
            ObjectNode change = changes.addObject().put("status", status).put("path", path);
            if (oldPath != null) change.put("previousPath", oldPath);
        }
        return paths;
    }

    private static void addPath(LinkedHashSet<String> paths, String path) {
        paths.add(path);
        if (paths.size() > MAX_PATHS) throw new IllegalArgumentException("Git selection exceeds the 100-path analysis limit");
    }

    private static String safePath(String path) {
        String normalized = path.replace('\\', '/');
        Path parsed = Path.of(normalized).normalize();
        if (parsed.isAbsolute() || normalized.startsWith("../") || normalized.equals("..") || parsed.startsWith("..")) {
            throw new IllegalArgumentException("Git returned a path outside the repository");
        }
        return normalized;
    }

    private static String resolveCommit(Path root, String ref) {
        validateRef(ref);
        String resolved = new String(run(root, List.of("rev-parse", "--verify", ref + "^{commit}")), StandardCharsets.UTF_8).trim();
        if (!resolved.matches("[0-9a-fA-F]{40,64}")) throw new IllegalArgumentException("Git did not resolve a commit for " + ref);
        return resolved.toLowerCase(Locale.ROOT);
    }

    private static void validateRef(String ref) {
        if (ref == null || ref.isBlank() || ref.length() > 200 || ref.startsWith("-")) throw new IllegalArgumentException("Invalid Git revision");
        for (int i = 0; i < ref.length(); i++) if (Character.isISOControl(ref.charAt(i))) throw new IllegalArgumentException("Invalid Git revision");
    }

    private static byte[] run(Path root, List<String> arguments) {
        ArrayList<String> command = new ArrayList<>(); command.add("git"); command.addAll(arguments);
        Process process;
        try { process = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).start(); }
        catch (IOException e) { throw new IllegalArgumentException("Git is unavailable: " + e.getMessage()); }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = Thread.ofVirtual().start(() -> {
            try (InputStream stream = process.getInputStream()) {
                byte[] buffer = new byte[8192]; int count;
                while ((count = stream.read(buffer)) >= 0) {
                    if (output.size() + count > MAX_OUTPUT_BYTES) { process.destroyForcibly(); return; }
                    output.write(buffer, 0, count);
                }
            } catch (IOException ignored) { }
        });
        try {
            if (!process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) { process.destroyForcibly(); throw new IllegalArgumentException("Git selection timed out"); }
            reader.join(1_000);
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); process.destroyForcibly(); throw new IllegalArgumentException("Git selection was interrupted"); }
        if (output.size() >= MAX_OUTPUT_BYTES) throw new IllegalArgumentException("Git selection output exceeds 1 MiB");
        if (process.exitValue() != 0) {
            String message = new String(output.toByteArray(), StandardCharsets.UTF_8).trim().replaceAll("[\\r\\n]+", " ");
            throw new IllegalArgumentException("Git selection failed" + (message.isBlank() ? "" : ": " + message));
        }
        return output.toByteArray();
    }

    private static byte[] encodeUntracked(byte[] paths) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (String path : nulFields(paths)) {
            if (path.isBlank()) continue;
            out.writeBytes("?".getBytes(StandardCharsets.UTF_8)); out.write(0);
            out.writeBytes(path.getBytes(StandardCharsets.UTF_8)); out.write(0);
        }
        return out.toByteArray();
    }

    private static byte[] join(byte[] left, byte[] right) { byte[] both = new byte[left.length + right.length]; System.arraycopy(left, 0, both, 0, left.length); System.arraycopy(right, 0, both, left.length, right.length); return both; }
    private static List<String> nulFields(byte[] bytes) { ArrayList<String> out = new ArrayList<>(); int start = 0; for (int i = 0; i < bytes.length; i++) if (bytes[i] == 0) { out.add(new String(bytes, start, i - start, StandardCharsets.UTF_8)); start = i + 1; } if (start < bytes.length) out.add(new String(bytes, start, bytes.length - start, StandardCharsets.UTF_8)); return out; }
    private static String required(JsonNode node, String key) { String value = text(node, key, ""); if (value.isBlank()) throw new IllegalArgumentException("git." + key + " is required for revision comparisons"); return value; }
    private static String text(JsonNode node, String key, String fallback) { JsonNode value = node.get(key); if (value == null || value.isNull()) return fallback; if (!value.isTextual()) throw new IllegalArgumentException("git." + key + " must be a string"); return value.asText(); }
}
