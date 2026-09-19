# Dependency precision implementation and validation

Resolver implementation and correctness verification are complete for the scope below.
An additional adaptive benchmark now measures eliminated discovery work for five bounded Java
navigation questions, with three control tasks. Full coding-session productivity remains unmeasured. See the
[acceptance report and measured tradeoffs](dependency-precision-results.md).

## Plan

1. Capture the running MCP's same-name collision baseline and an explicit source oracle.
2. Resolve Java calls using their receiver/owner, imports and package before any name heuristic.
3. Extract bounded lexical type evidence for parameters, locals, fields, literals, casts, constructor
   expressions and simple return/field chains. Use declared inheritance and parameter types to
   narrow overloads; preserve ambiguity rather than inventing exact targets.
4. Share resolution between memory, incremental and hybrid indexing. Keep disk-backed lookups
   bounded, invalidate consumers when declaration/type context changes, and publish atomically.
5. Report resolution evidence and incomplete coverage. Do not promise a complete reference list
   for reflection, runtime dispatch, external libraries, inferred generics or unsupported syntax.
6. Test correctness fixtures, full/incremental/hybrid parity, budgets and regression suites.
7. Measure task-adaptive agent workflows before/after: hold the question and correctness gate
   constant, not the tool sequence. Log the knowledge gap motivating each filesystem fallback
   and stop discovery when MCP provides sufficient evidence. Separate discovery searches/reads,
   implementation reads, evaluator checks and build/test commands. Measure total task time and
   accuracy as well as call counts. The bounded adaptive test is now complete; broader coding
   workflows remain unmeasured, and fixed lookup timings alone do not satisfy that gate.
8. Stop the old application after isolated tests pass; compile/package and restart with its
   existing MCP/UI/DBA/hybrid configuration. Re-onboard this repository for the requested live test.

## Scope

Java receives broader type-aware resolution in this iteration. Other languages retain their
existing analyzers and heuristic coverage; this is not a compiler or whole-program type checker.
The resolver must not convert an unresolved external receiver into an unrelated local call.
Unknown overload argument types remain possible targets. Static declaration binding does not
enumerate every implementation invoked by dynamic dispatch.

No database changes, automatic commit or push are included.

Follow-up: [language-family isolation and MCP-first navigation](language-resolution.md)
now prevent unrelated languages from entering generic resolver lookups and their
hybrid budgets. JS/TS module/alias resolution remains a separately reproduced
limitation; see that report rather than treating generic import confidence as proof.

## Measurement ledger

Raw measurements and the navigation activity ledger are kept under docs/validation/dependency-precision/.
The ledger measures this agent's tool usage, not the server's internal file reads. A filesystem
search can find occurrences in comments/strings as well as executable calls; compare results
against source-reviewed fixtures, not against unreviewed grep output as a semantic oracle.

## Implemented behavior

- Explicit Java receiver/owner matching; no same-file or global-name fallback for an unresolved
  Java receiver. Package, direct/wildcard imports, static imports and nested named types participate.
- Lexical parameters, local variables (including straightforward var initializers), fields,
  shadowing, casts, literals, constructors, simple field/return chains and generic upper bounds.
- Declared inheritance, overridden signatures, fixed/variable argument counts, primitive widening,
  boxing/unboxing and conservative overload candidates when type evidence is incomplete.
- Shared resolution through SymbolLookup. Hybrid indexing performs disk-backed qualified lookups
  with a per-file 256-key / 2 MiB encoded-size-estimate LRU; individual lookups retain the existing
  10,000-candidate/record-byte bound. These are accounting estimates, not hard JVM RAM limits.
- Expression inference is bounded to eight levels, hierarchy visits to 64 types and per-reference
  qualified-lookup work to 2,048. An exhausted resolution stays unresolved, never an invented edge.
- In-memory incremental indexing reparses changed files only. Declaration-context changes trigger
  re-resolution of existing fragments so return-type, inheritance and overload changes cannot leave
  stale bindings. The later [hybrid incremental update](hybrid-incremental-delivery.md)
  reuses disk fragments and copy-on-write pages, retaining full rebuild fallbacks.
- Existing tool inputs and symbol IDs are preserved. find_references/position responses add
  resolutionEvidence; referenceCompleteness is explicitly not_guaranteed.

### Remaining coverage boundaries

This is declaration binding, not compiler-complete Java analysis. Runtime override enumeration,
reflection, method-reference/lambda target inference, external JAR hierarchies, generic substitution,
complex flow/pattern inference, and anonymous/local-class semantics are not guaranteed. Other
languages still use their existing heuristic resolver. Unknowns must not be interpreted as non-use.
The capped candidate list and a paginated result are different limits; inspect both.

## Reproducing the measurements

Use the same frozen source dataset for both packaged versions. Do not run builds/indexing in parallel
with the controlled comparison. On Windows:

~~~powershell
./scripts/benchmark-navigation.ps1 -BaselineLib C:/old/target/lib -CandidateLib C:/new/target/lib -Dataset C:/frozen-source
~~~

The script alternates three fresh JVM runs per engine, uses a 768 MiB maximum heap and 32 MiB hybrid
allowance, closes disposable graph stores, and samples process working set. Per JVM it warms three
query sequences then measures 30 sequences of symbol search, incoming calls and a bounded closure.
The query sequence is not an MCP transport round trip. Heap-pool peak sums are not a synchronized
peak or retained-heap measurement. Operating-system file caches are not cleared.

Against an already onboarded live application, before and after restarting:

~~~powershell
node scripts/benchmark-navigation.cjs http://localhost:3000/mcp code-graph before
node scripts/benchmark-navigation.cjs http://localhost:3000/mcp code-graph after
~~~

This fixed, read-only workload logs each MCP request and filesystem search separately with elapsed
time and output size. Each phase deliberately has six MCP symbol queries and three filesystem
searches. It measures result quality and lookup latency only: forcing those searches prevents it
from measuring whether improved MCP evidence makes them unnecessary. It must not be used as
the agent-productivity benchmark. Follow the task-adaptive acceptance protocol in the report.
Read source when implementation details or editing require it, not automatically after every MCP
answer. Filesystem calls by other agents/clients are not visible to this server. The separate
activity ledger records instrumented development navigation and is not an exhaustive audit of
every process or internal filesystem access.

### Adaptive workflow reproduction

Run baseline and candidate packaged builds on separate, unused loopback ports with the same
frozen source root. Use the raw HTTP entry point to avoid installed-launcher UI/DBA defaults:

~~~powershell
# In separate terminals; substitute package and dataset paths. Use 13002 for candidate.
java -Xmx768m --enable-native-access=ALL-UNNAMED -cp "C:/baseline/target/code-graph-server.jar;C:/baseline/target/lib/*" io.doindev.codegraph.mcp.http.HttpMain --port 13001 --root C:/frozen-source --graph-storage hybrid --graph-memory 32m

# Independent grader: semantic attribution only; no generated classes or production writes.
java -cp "C:/baseline/target/lib/*" scripts/NavigationWorkflowOracle.java C:/frozen-source docs/validation/dependency-precision/workflow-oracle.json

# Three runs per engine, alternating engine order; do not run both suites concurrently.
node scripts/benchmark-agent-navigation.cjs http://127.0.0.1:13001/mcp C:/frozen-source baseline 1
node scripts/benchmark-agent-navigation.cjs http://127.0.0.1:13002/mcp C:/frozen-source candidate 1
# Repeat as baseline/candidate 2 and 3, alternating which engine goes first.
node scripts/summarize-agent-navigation.cjs
node --test scripts/benchmark-agent-navigation.test.cjs
~~~

The adaptive runner does not read the compiler oracle. It logs decisions before each fallback,
follows precise-result pagination, refuses to equate absence with non-use, and treats implementation
reads separately from discovery. The post-run summary independently compares exact locations.
Use a unique writable java.io.tmpdir outside indexed roots for disposable stores when reproducing.
Remove only the isolated benchmark projects through their MCP servers, then stop those servers
and remove their empty owned temporary directories; preserve any existing application and caches.
