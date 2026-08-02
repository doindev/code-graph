# code-graph

Enterprise blast-radius and code-intelligence MCP server for the JVM (Java 25). code-graph turns
large text codebases into a queryable **code property graph** so AI agents can answer
*"what breaks if I change this?"* in milliseconds instead of burning tokens on file-reading loops.

## What it does

- **Tree-sitter AST parsing** for the top-10 languages: Java, JavaScript, TypeScript (+TSX),
  Python, C#, Go, Rust, C, C++, PHP — plus Ruby and Kotlin. New languages plug in behind a
  `LanguageAnalyzer` SPI.
- **Unified code property graph**: files, types, functions and variables connected by
  CONTAINS / IMPORTS / CALLS / REFERENCES / EXTENDS / IMPLEMENTS / READS / WRITES edges, every
  cross-file edge carrying a resolution **confidence** score.
- **Sub-5ms queries**: the in-memory graph is the sole query engine (lock-free reads via
  generation-swapped immutable state); H2, Neo4j and ArangoDB act as write-through mirrors for
  durability and org-wide analytics.
- **Incremental background indexing**: a file watcher re-parses only changed files and patches
  the graph atomically — agents never query stale maps.
- **Blast score + risk gating**: an auditable weighted formula (transitive reach, fan-in, module
  spread, cross-language reach, test coverage proxy); high-score targets get a mandatory risk
  report attached, and CI can fail the build on it.
- **Architectural drift**: a golden blueprint (layers + allowed dependencies) checked with cycle
  detection and baseline diffing — only NEW violations are reported.
- **Code smells**: god class, long method, hubs, cycles, feature envy, data clumps, refused
  bequest, temporal coupling (git co-change mining) and duplicate logic — every finding carries
  its metric evidence.
- **MCP tools** (stdio for local agents, streamable HTTP for team deployment): `search_symbols`,
  `get_symbol`, `get_impact_radius`, `get_call_graph`, `get_blast_score`, `find_dead_code`,
  `find_code_smells`, `compare_architectural_drift`, `index_status`, `reindex`.
- **Headless CI mode**: scan a PR, compute the blast radius of the diff, post a markdown risk
  report to GitHub/GitLab, and gate merges via exit codes.
- **Local-first**: all parsing and graph traversal stays inside your perimeter; tools return
  minimized metadata (signatures, IDs, counts) — never file contents.

## Modules

| Module | Purpose |
|---|---|
| `code-graph-bom` | Bill of Materials for version-managing all modules |
| `code-graph-core` | Zero-dependency graph model, stable symbol IDs, `GraphQuery` API, `GraphStore` SPI, in-memory engine, binary snapshot + journal |
| `code-graph-config` | `code-graph.json` (canonical, comments allowed) / `code-graph.yaml` loader with env overrides |
| `code-graph-parsers` | `LanguageAnalyzer` SPI + tree-sitter walk infrastructure (grammar ABI gate lives here) |
| `code-graph-lang-*` | One analyzer per language: java, javascript (JS/TS/TSX), python, go, rust, c (C/C++), csharp, php, ruby, kotlin |
| `code-graph-index` | Two-pass pipeline, confidence-laddered resolution, incremental indexer + file watcher |
| `code-graph-analysis` | Blast-score formula, dead code, Tarjan SCC |
| `code-graph-rules` | Golden-blueprint architecture rules, module graph, drift fingerprints |
| `code-graph-smells` | Smell detectors (god class, long method, hubs, cycles, unstable dependencies) |
| `code-graph-tools` | Transport-agnostic tool layer: schemas, response caps, RiskGate, multi-project routing |
| `code-graph-mcp` | stdio MCP server (bundles all languages; the only module importing the MCP SDK) |
| `code-graph-mcp-http` | Streamable-HTTP MCP server on embedded Jetty 12 (Docker-ready) |
| `code-graph-viz` | Embedded 3D visualization: drill-down, ego networks, galaxy view (vendored WebGL, no CDN) |
| `code-graph-store-h2` / `-neo4j` / `-arangodb` | Durable write-through mirrors of the graph |
| `code-graph-linker` | Cross-language HTTP-route linking (`INVOKES_REMOTE` edges) |
| `code-graph-cli` | Headless CI runner: diff-scoped risk reports, GitHub/GitLab posting, gating exit codes |

## Guides

| Guide | Topic |
|---|---|
| [docs/guides/mcp-server.md](docs/guides/mcp-server.md) | Registering with Claude Code, tool reference, worked session |
| [docs/guides/configuration.md](docs/guides/configuration.md) | Full `code-graph.json` / `.yaml` reference |
| [docs/guides/blast-score.md](docs/guides/blast-score.md) | The risk formula, worked example, threshold gating |
| [docs/guides/indexing.md](docs/guides/indexing.md) | Two-pass extraction, confidence ladder, incremental watching |
| [docs/guides/smells.md](docs/guides/smells.md) | All 13 smell detectors, thresholds, honest limits |
| [docs/guides/architecture-drift.md](docs/guides/architecture-drift.md) | Blueprint rules, cycle detection, baseline diffs |
| [docs/guides/ci.md](docs/guides/ci.md) | The `ci` pipeline, exit codes, GitHub Actions / GitLab examples |
| [docs/guides/visualization.md](docs/guides/visualization.md) | 3D graph UI and multi-project workspaces |

## Build

Requires JDK 25 and Maven 3.9+.

```
mvn verify
```

The `code-graph-parsers` tests are the **grammar ABI gate**: every bundled tree-sitter grammar
must load and parse against the core binding (`io.github.bonede:tree-sitter`). If a grammar
fails there, pin the core binding back (0.25.3 is the documented fallback) before building
anything on top.

Known limitation: the Kotlin grammar (0.3.8.1) reports errors on expression-body functions
(`fun m() = ...`); block bodies parse fine.

## Run

### MCP transports

| Transport | Module / entry point | Notes |
|---|---|---|
| stdio | `code-graph-mcp` — `io.doindev.codegraph.mcp.Main` | For local agent hosts (Claude Code, IDEs); stdout belongs to the protocol, logs go to stderr. `--viz` binds to localhost only, with viz admin actions (add/remove/reindex projects) **on** by default — pass `--viz-readonly` to disable. |
| Streamable HTTP | `code-graph-mcp-http` — `io.doindev.codegraph.mcp.http.HttpMain` | Team deployment on embedded Jetty 12; MCP mounted at `/mcp`, binds `0.0.0.0`. Port from `--port` or `CODE_GRAPH_PORT` (default 3000), root from `--root` or `CODE_GRAPH_ROOT` (default `.`). Viz admin actions are **off** by default — pass `--viz-admin` to enable. |
| Custom (embedded) | `CodeGraphMcpServer.serve(...)` | Overloads accept any MCP SDK `McpServerTransportProvider` or `McpStreamableServerTransportProvider`, so the tool set can be embedded behind another transport programmatically. |

Both entry points take the same workspace flags: repeated `--root DIR` or `--workspace FILE`,
plus `--viz PORT` for the 3D UI.

### Commands

Neither server module builds a fat jar, so install the modules and generate a runtime
classpath first:

```
mvn -DskipTests install
mvn -pl code-graph-mcp-http dependency:build-classpath -Dmdep.outputFile=cp.txt
```

**Team server + 3D UI** (streamable-HTTP MCP on `--port`, visualization on `--viz`):

```
# Windows (classpath separator is ';'; on Linux/macOS use ':')
java --enable-native-access=ALL-UNNAMED ^
    -cp "code-graph-mcp-http\target\code-graph-mcp-http-0.0.1-SNAPSHOT.jar;<contents of cp.txt>" ^
    io.doindev.codegraph.mcp.http.HttpMain --root C:\repos\acme --port 8136 --viz 8137
```

Then open <http://localhost:8137/> for the 3D visualization (MCP endpoint is
`http://localhost:8136/mcp`). Repeat `--root` (or pass `--workspace FILE`) to serve several
projects from one server.

**Local stdio server** (for a single agent, e.g. Claude Code — swap the module and main class;
generate its classpath with `-pl code-graph-mcp`):

```
claude mcp add code-graph -- java --enable-native-access=ALL-UNNAMED ^
    -cp "code-graph-mcp\target\code-graph-mcp-0.0.1-SNAPSHOT.jar;<contents of cp.txt>" ^
    io.doindev.codegraph.mcp.Main --root C:\repos\acme
```

The stdio server also accepts `--viz PORT` (bound to localhost). See
[docs/guides/mcp-server.md](docs/guides/mcp-server.md) and
[docs/guides/visualization.md](docs/guides/visualization.md) for details.

## Configuration

One file at the repo root — `code-graph.json` (canonical; Java-style comments and trailing
commas allowed) or `code-graph.yaml`. Having both is an error. Sections: `paths`, `tests`,
`limits`, `scoring`, `gating`, `deadCode`, `smells`, `architecture`. Precedence:
CLI flags > `CODE_GRAPH_*` env vars > file > built-in defaults.

```jsonc
{
  // fail CI when a changed file's blast score reaches 80
  "gating": { "threshold": 80, "attachRiskReport": true, "failCiOn": ["blast", "drift"] },
  "limits": { "maxResults": 50, "maxResponseBytes": 32768 },
  "architecture": {
    "modules": [
      { "name": "api",    "paths": ["src/main/java/com/acme/api/**"] },
      { "name": "domain", "paths": ["src/main/java/com/acme/domain/**"] }
    ],
    "allowedDependencies": { "api": ["domain"] },
    "forbidCycles": true
  }
}
```
