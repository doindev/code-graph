# Indexing

code-graph turns a source tree into a code property graph in two passes, then keeps it fresh
with an incremental file watcher. Every query sees one complete published generation.
While changes are queued or indexing, that generation can lag the filesystem; use
generation, pending-work and completion timestamps to assess freshness.

## Full index (two passes)

For the in-memory backend:

1. **Extract (parallel)** — one virtual thread per file (bounded at 2×cores; parsing is
   CPU-bound). Each file yields a *fragment*: declared nodes (file, types, functions, fields
   with AST metrics), CONTAINS edges, import strings, and unresolved references.
2. **Resolve (parallel)** — a frozen symbol table over all fragments, then a confidence ladder
   per reference:

| rung | confidence | meaning |
|---|---|---|
| import-exact | 1.0 | an import pins the qualified name |
| same-file | 0.95 | declared in the same file |
| same-directory | 0.9 | declared next door |
| unique-name | 0.8 | globally unique simple name (+arity match) in the language |
| heuristic | 0.5/k | ambiguous — one edge per candidate (max 5), marked `resolution=heuristic` |

Unresolved references are kept **pending** — a later file addition can heal them. Consumers
filter by minimum confidence; heuristic edges are never presented as exact.

Everything lands in ONE bulk `GraphDelta` (the engine's per-delta cost makes many small deltas
the wrong shape for bulk loading). Reference point: soma-graphs, 450 Java files → 4 687 symbols
and 36 484 edges in ~2.7 s.

Hybrid mode uses the same analyzers/resolvers but writes fragments and symbol/module
lookups to disk, parses one file at a time and resolves through bounded disk lookups.
Its staged initial build publishes atomically; it does not retain a second Java graph.

## Incremental indexing

The watcher (`java.nio.file.WatchService`; on Windows one recursive `FILE_TREE` registration)
feeds debounced batches: a path becomes ready after 250 ms of quiet or 1 s after its first
event, so IDE save-storms and `git checkout` produce one batch. Touch-only events are dropped
by content hash.

In-memory batches re-resolve three sets of files:

1. the **changed** files themselves (re-extracted; deleted files lose their fragment),
2. every file whose resolved edges pointed **into** a changed file (targets may have moved),
3. every file holding **pending** refs (a new declaration may satisfy them).

Declaration, module and configuration changes additionally re-resolve all stored
fragments so new overloads or alias changes cannot leave old bindings behind.

The whole batch becomes one `GraphDelta`: old nodes/edges of changed files are dropped, stale
pass-2 edges of affected files are retracted, new declarations and resolutions are added — and
the engine publishes it in a single volatile swap. Cross-file edges survive a callee's
re-extraction because symbol IDs are content-position facts (`lang:path#qname/arity`), not
counters.

`index_status` exposes `generation`, `dirtyPending` (events not yet applied) and
`lastIndexedAt`, so an agent can always judge how fresh a blast radius is.

### Hybrid updates

Hybrid batches store changed fragments and their lookup records under stable file/symbol
keys and update copy-on-write MVStore pages. A body-only edit resolves its changed fragments;
declaration/module changes, additions/deletions and module-configuration edits re-resolve all
disk fragments without reparsing unrelated source files. Unchanged saves skip parsing and do
not advance the generation.

The prior snapshot is pinned through intermediate write flushes until atomic publication.
Failure or cancellation rolls back staged changes. Batches are bounded to 1,024 paths and
1 MiB of path text; overflow, directory events and ignore-rule changes fall back to a full
staged rebuild. Events arriving during indexing remain queued. Completion latency includes
the debounce interval, waiting for another project's writer, parsing/resolution and publication;
the 1-second coalescing deadline is not a guarantee of indexing completion.

See [hybrid incremental validation](../hybrid-incremental-delivery.md) for reproducible
comparisons, bounded-memory tests and known tradeoffs.

## What gets indexed

Files with a supported extension, minus: `paths.exclude` globs, `.gitignore` rules
(hierarchical; `*`, `**`, `?`, trailing `/`, leading `/`, `!` negation — best effort), the
always-ignored directories (`.git`, `node_modules`, `target`, `build`, ...), and files over
2 MB.

## Persistence

`FileSnapshotStore` keeps a compact binary snapshot (`graph.snapshot`) plus an append-only
delta journal, written atomically (temp file + `ATOMIC_MOVE`); a torn journal tail from a crash
is ignored on replay. Durable mirrors (H2, Neo4j, ArangoDB) consume the same ordered delta
stream asynchronously — a slow mirror never stalls indexing.
