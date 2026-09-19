# Code navigation and change evidence

Use the connected schemas and exact project context. Prefer indexed structural
evidence; do not turn every MCP query into an obligatory filesystem lookup.

## Evidence and stopping

- A declaration question is answered once the relevant identity, signature, path
  and required span are available. No extra `get_symbol` or file read is needed.
- Reference questions need the right target, relevant relationships, location
  precision, resolution evidence and coverage. Inspect ambiguity, candidate counts
  and omitted candidates where provided, not confidence alone. High confidence
  for returned rows does not establish completeness of the index.
- `find_references` may return exact identifier/expression spans, line-only sites,
  or containing declarations. Label these honestly. Do not invent precise tokens.
- `resolve_symbol_at_position` uses 1-based line/UTF-16 column. A containing-symbol
  answer is not identifier resolution; overloads can remain ambiguous.
- `find_implementations` covers direct type inheritance/interfaces, not complete
  method overrides or runtime dispatch. Expand only when the question needs it.
- Follow needed pages with unchanged target/query and the returned cursor. Keep
  generation/completeness/truncation metadata. Do not lower confidence thresholds
  or discard uncertain entries and then claim the remainder is exhaustive.
- A known path or sufficient answer already in context needs no discovery call.

Resolution is adapter-specific. Java uses type-aware evidence. Supported JS/TS
imports carry module-binding evidence: module specifier/path, exported name,
local alias, import kind, candidate count and resolution status. A single resolved
module binding with an exact occurrence can answer a scoped static reference
question without opening the caller file. Generic name heuristics remain uncertain.
Never promote a confidence score alone to proof of binding or exhaustive coverage.
File outlines can include module-resolution coverage/reasons for unresolved calls.
Dynamic imports, shadowed bindings, unsupported conditions/configuration, external
targets and exhausted work budgets require narrower checks or source inspection.

## Workflow examples

1. **Locate callers:** search a distinctive qualified name only if its ID is
   unknown, then ask `find_references`. If binding/location evidence and pages
   answer the scoped static question, report the locations and stop. Do not open
   every caller file just because source is available.
2. **Change implementation:** use a known path or MCP location, read the relevant
   source/context, edit and test using ordinary tools. Avoid a broad search to
   rediscover the path. The actual source is required for a safe edit.
3. **Locate a log message/config key:** use a targeted literal search directly;
   symbol lookup is not full-text search.
4. **Ambiguous calls:** narrow the owner/signature or inspect returned candidates.
   If the remaining gap needs source, read only the relevant caller/declaration.
   Record the unresolved relationship instead of promoting a name match to fact.
5. **Delete safely:** an empty or precise partial reference list is insufficient
   for exhaustive non-use. Check coverage, dynamic wiring and unindexed sources
   with scoped local checks; retain uncertainty and run appropriate tests.
6. **Stale/truncated answer:** finish current-generation pages, wait for an
   authorized refresh if needed, or use current source for the specific gap.
   Never combine old pages with a new generation.

Use small pages (for example 10 for symbol discovery). On current servers, limit
is a maximum: byte-aware pages may contain fewer rows and supply a next cursor.
Follow that cursor unchanged; do not issue speculative smaller-limit retries.
An item_too_large error means one record plus required metadata cannot fit; narrow
the target or report the configured allowance problem. Older servers can still
require a smaller page; follow their actual schema/error, not an assumed version.
Call graphs aggregate distinct caller/callee pairs. Parallel upOccurrences and
downOccurrences count indexed sites; incomplete inspection makes these lower
bounds. Use find_references only when individual occurrence locations are needed.
Reuse context and avoid repeatedly polling a healthy index. In performance
evaluations, log the question, evidence received, remaining gap and reason for
each MCP/search/read call. Separate discovery searches from required implementation
reads, and indexing/setup from steady-state work. Fewer total calls alone is not
proof of speedup: measure latency and whether the answer remains correct.

## Discovery mismatches

If an expected tool is missing, use only the connected client's advertised tools.
Do not invent aliases or call guessed schemas. Current context/status responses
include build and catalog identity without starting database work. A stale index
generation and a stale client tool catalog are separate problems.

The administrator can compare fresh tools/list with an exported client catalog
using scripts/diagnose-mcp-catalog.cjs. This is diagnostic support, not a required
shell/HTTP workaround for ordinary agent work. Reconnect or use the client's
supported configuration refresh when authorized; never automatically retry writes.

## Impact and database mappings

`analyze_change` and `find_affected_tests` accept the advertised explicit targets
or bounded Git selection. Preserve resolved commits, evidence, confidence, source
generations and unresolved targets. Git-selected paths are evaluated against the
current graph, not a reconstructed historical graph. Inspect rejected revisions
with normal Git tools and label findings accurately. Candidate tests are not
executed tests or complete coverage.

Static SQL/JPA/MyBatis/Prisma/TypeORM mappings provide names and source locations.
Dynamic strings, naming strategies, reflection and derived lineage can remain
uncertain. Obtain separately authorized catalog evidence; a mapping grants no
database access. Never infer a rename, safe drop or compatible type from an absent
or similarly named mapping.
