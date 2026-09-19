import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Optional current-user skills. No client configuration, network access, or Node dependency. */
final class SkillInstaller {
    final Path source;
    final Properties locations = new Properties();
    final List<String> clients;
    record Result(String client, String status, Path destination, String error) {}

    SkillInstaller(Path repository) throws IOException {
        source = repository.resolve("skills/code-graph");
        try (Reader reader = Files.newBufferedReader(repository.resolve("skills/clients.properties"))) {
            locations.load(reader);
        }
        clients = List.of(locations.getProperty("clients").split(","));
    }

    List<String> selection(String value) {
        if (value == null || value.isBlank()) return List.of();
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        if (normalized.equals("none")) return List.of();
        if (normalized.equals("all")) return clients;
        Set<String> chosen = new LinkedHashSet<>();
        for (String item : normalized.split(",", -1)) {
            String client = item.strip();
            if (!clients.contains(client) || !chosen.add(client))
                throw new IllegalArgumentException("Skills must be all, none, or unique comma-separated clients: " + String.join(",", clients));
        }
        return List.copyOf(chosen);
    }

    List<String> choose(String explicit, boolean nonInteractive, boolean buildOnly, Console console) throws IOException {
        if (explicit != null) {
            if (explicit.isBlank()) throw new IllegalArgumentException("--skills requires all, none, or selected clients");
            List<String> chosen = selection(explicit);
            if (buildOnly && !chosen.isEmpty()) throw new IllegalArgumentException("--build-only cannot install skills; use --skills none or omit --skills");
            return chosen;
        }
        if (nonInteractive || buildOnly || console == null) return List.of();
        console.printf("%nOptional current-user skills: Codex, GitHub Copilot, Claude Code, Windsurf.%n");
        console.printf("These only recommend code-graph when its MCP is available and useful; no MCP settings or permissions change.%n");
        for (String client : clients) {
            String location;
            try { location=destination(client, Path.of(System.getProperty("user.home")), System.getenv()).toString(); }
            catch (IllegalArgumentException e) { location="unavailable (" + e.getMessage() + ")"; }
            console.printf("  %s: %s%n", locations.getProperty(client + ".label"), location);
        }
        return promptSelection(new BufferedReader(console.reader()), console.writer());
    }

    List<String> promptSelection(BufferedReader input, PrintWriter output) throws IOException {
        while (true) {
            output.print("Install skills [all / codex,copilot,claude,windsurf / none] (none): ");
            output.flush();
            String answer = input.readLine();
            try { return selection(answer); }
            catch (IllegalArgumentException e) { output.println(e.getMessage()); }
        }
    }

    Path destination(String client, Path home, Map<String, String> environment) {
        if (!clients.contains(client)) throw new IllegalArgumentException("Unknown skill client: " + client);
        String override = environment.get(locations.getProperty(client + ".homeEnv", ""));
        Path base;
        if (override != null && !override.isBlank()) {
            base = Path.of(override);
            if (!base.isAbsolute()) throw new IllegalArgumentException("Client configuration directory must be absolute: " + client);
        } else base = home.resolve(locations.getProperty(client + ".global"));
        return base.toAbsolutePath().normalize().resolve("skills/code-graph");
    }

    List<String> chooseConfigured(String explicit, boolean nonInteractive, boolean buildOnly, Console console,
                                  List<String> configured) throws IOException {
        if (buildOnly) return choose(explicit, true, true, null);
        List<String> eligible = clients.stream().filter(configured::contains).toList();
        if (explicit != null) return configuredSelection(explicit, eligible);
        if (nonInteractive || console == null || eligible.isEmpty()) return List.of();
        console.printf("%nCode-graph MCP is configured for: %s%n", String.join(", ", eligible));
        console.printf("Optional skills recommend MCP only when available/useful; they grant no permissions.%n");
        for (String client : eligible) console.printf("  %s: %s%n", locations.getProperty(client + ".label"),
                destination(client, Path.of(System.getProperty("user.home")), System.getenv()));
        return promptConfigured(new BufferedReader(console.reader()), console.writer(), eligible);
    }

    List<String> configuredSelection(String answer, List<String> eligible) {
        if (answer != null && answer.strip().equalsIgnoreCase("all")) return List.copyOf(eligible);
        List<String> selected = selection(answer);
        if (!eligible.containsAll(selected))
            throw new IllegalArgumentException("Skills require a detected code-graph MCP connection for each selected client. Eligible: "
                    + String.join(", ", eligible) + ". Configure MCP first, or use --skills none.");
        return selected;
    }

    List<String> promptConfigured(BufferedReader input, PrintWriter output, List<String> eligible) throws IOException {
        while (true) {
            output.print("Install optional skills [" + String.join(",", eligible) + " / all / none] (none): "); output.flush();
            try { return configuredSelection(input.readLine(), eligible); }
            catch (IllegalArgumentException e) { output.println(e.getMessage()); }
        }
    }

    List<Result> install(List<String> selected, Path home, Map<String, String> environment) {
        List<Result> results = new ArrayList<>();
        for (String client : selected) {
            Path target = null;
            try {
                target = destination(client, home.toRealPath(), environment);
                results.add(new Result(client, copy(source, target), target, null));
            } catch (IOException | IllegalArgumentException e) {
                results.add(new Result(client, "failed", target, e.getMessage()));
            }
        }
        return results;
    }

    static void safeDirectories(Path target) throws IOException {
        // toRealPath detects Windows junctions as well as symbolic links. Walk from the root.
        Path current = target.toAbsolutePath().normalize().getRoot();
        for (Path part : target.toAbsolutePath().normalize()) {
            current = current.resolve(part);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)
                    && (Files.isSymbolicLink(current) || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)
                        || !current.toRealPath().equals(current)))
                throw new IOException("Refusing linked or non-directory skill destination: " + current);
        }
    }

    static Map<String, byte[]> inventory(Path root) throws IOException {
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Skill source/destination must be a real directory");
        Map<String, byte[]> files = new TreeMap<>();
        long bytes = 0;
        try (var paths = Files.walk(root)) {
            for (var iterator = paths.iterator(); iterator.hasNext();) {
                Path entry = iterator.next();
                if (Files.isSymbolicLink(entry) || !entry.toRealPath().equals(entry.toAbsolutePath().normalize()))
                    throw new IOException("Skills must not contain links");
                if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) continue;
                if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Unsupported skill entry");
                long size = Files.size(entry);
                bytes += size;
                if (size > 2 * 1024 * 1024 || bytes > 8 * 1024 * 1024 || files.size() >= 128)
                    throw new IOException("Skill inventory exceeds safe installation bounds");
                files.put(root.relativize(entry).toString(), Files.readAllBytes(entry));
            }
        }
        return files;
    }

    static String copy(Path source, Path target) throws IOException {
        Map<String, byte[]> contents = inventory(source);
        if (!contents.containsKey("SKILL.md")) throw new IOException("Missing SKILL.md");
        safeDirectories(target);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            Map<String, byte[]> existing = inventory(target);
            if (contents.keySet().equals(existing.keySet())
                    && contents.entrySet().stream().allMatch(e -> Arrays.equals(e.getValue(), existing.get(e.getKey()))))
                return "already-installed";
            throw new IOException("Existing code-graph skill differs; merge updates manually. Nothing was overwritten.");
        }
        Files.createDirectories(target.getParent());
        safeDirectories(target.getParent());
        Path stage = Files.createTempDirectory(target.getParent(), ".code-graph-install-");
        try {
            Path staged = stage.resolve("code-graph");
            for (var entry : contents.entrySet()) {
                Path file = staged.resolve(entry.getKey());
                Files.createDirectories(file.getParent());
                Files.write(file, entry.getValue(), StandardOpenOption.CREATE_NEW);
            }
            safeDirectories(target);
            // No REPLACE_EXISTING: a concurrent installation must not overwrite a user's directory.
            Files.move(staged, target);
            return "installed";
        } finally {
            // Only this operation's uniquely created staging directory; never recurse into the destination.
            try (var paths = Files.walk(stage)) {
                for (Path file : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
            }
        }
    }
}
