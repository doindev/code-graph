# MCP coding and database workflow acceptance

Date: 2026-09-18

## Outcome

All six implementation milestones are delivered. Existing tools and text payloads
remain compatible; new tools add typed schemas, structured results, bounded retained
jobs, exact target selection and the existing approval boundary. The accepted ORM
scope is Java/JPA/MyBatis and JavaScript/TypeScript/Prisma/TypeORM. Other language
mappings remain the explicitly agreed backlog.

The database result is intentionally capability-gated rather than pretending every
JDBC connection supports migrations. PostgreSQL 16.14, MySQL 8.4.11, MariaDB 11.4.13
and H2 2.4.240 have verified workflow adapters. All 42 connection templates are
classified in [workflow-capabilities.json](workflow-capabilities.json). Unsupported,
unavailable, managed-service and licence/EULA cases remain visible; none is counted
as a live pass.

## Milestone evidence

| Milestone | Delivered evidence |
|---|---|
| Contracts and discovery | Nested input schemas, output envelopes, annotations, structured content, workspace/capability/template discovery, expiring generation cursors, catalog refresh and bounded waits |
| Navigation and mappings | File outlines, symbol-at-position, references, implementations, exact/inferred spans, incremental SQL/JPA/MyBatis/Prisma/TypeORM mappings and replacement/deletion tests |
| Impact and comparison | Explicit file/symbol/Git selection, revision-labelled Git evidence, mapping/database/test evidence, bounded authorized captures, independently authorized comparisons and incomplete-inventory safeguards |
| Migration workflows | Retained plans/manifests, exact fingerprint validation, one-time reviewed apply, same-engine isolated rehearsal, bounded synthetic fixtures/checks, transaction/partial/uncertain outcome reporting |
| Verification and plans | Contract validation, evidence-based affected tests, estimated Explain, evidence analysis and normalized comparison with raw source-plan retention |
| Editor handoff | User pairing, MCP-session ownership, revision-checked drafts/edits, bounded acknowledged events, browser resynchronization, revocation and no implicit SQL/file/connection action |

Migration plans cannot inherit exact or category SQL approval. They always require a
fresh one-time decision. Browser autosaves now carry a workspace revision, so a stale
save cannot overwrite an agent edit. Concurrent unsaved browser content is merged;
a same-document race is preserved as a local conflict Script when tab capacity allows.

## Database verification

The live sequential harness completed the full schema/rehearsal/migration/contract/
plan workflow on two isolated servers per vendor for:

| Engine | Server | JDBC | Result |
|---|---|---|---|
| PostgreSQL | 16.14 | 42.7.13 | Passed |
| MySQL | 8.4.11 | 9.7.0 | Passed |
| MariaDB | 11.4.13 | 3.5.7 | Passed |
| H2 | 2.4.240 | 2.4.240 | Passed through embedded workflow tests |

The harness verifies bounded capture/compare, retained fixture values, a distinct
disposable rehearsal target, migration application, direct post-DDL observation,
static mapping contract validation, estimated-plan analysis and plan comparison.
Every fixture uses synthetic data. No source database record is copied.

Free server/container templates without a verified adapter remain explicitly
`free_local_or_container_but_advanced_adapter_unverified`. The server permits their
bounded generic JDBC observation where supported, but does not advertise migration
generation/application merely because JDBC connects. Managed services were not
provisioned, paid resources were not created, and vendor licence agreements were not
accepted automatically.

## Resource and performance evidence

The workflow tests cover result/job limits, accounted snapshot leases, admission
failure cleanup, cursor expiry/staleness, scan generation consistency, bounded event
queues, cancellation, concurrent jobs and project/database isolation. Captures stop
at explicit object/metadata/definition/byte limits; migration plans stop at 32 steps,
16 retained plans and 2 MiB total retained plan data.

Three fresh 100,000-node/1,000,000-edge in-memory graph runs produced:

| Run | Build ms | Closure p50 µs | Closure p99 µs |
|---:|---:|---:|---:|
| 1 | 1,685 | 265 | 390 |
| 2 | 1,820 | 281 | 532 |
| 3 | 1,765 | 265 | 1,301 |

All remain under the existing 5 ms p99 gate. The established three-run storage
matrix provides p50/p95/p99, throughput, JVM heap/GC, native/process memory,
accounted residency and disk results for memory, MVStore and RocksDB scenarios; see
[memory-budgeted graph storage](benchmarks/memory-budgeted-storage.md). These are
developer-workstation microbenchmarks, not universal production latency guarantees.

## Test gates

- Full DBA/MCP dependency reactor: passed after updating the now-delivered migration
  capability assertion; environment-gated tests remain skips, not passes.
- Complete browser suite: passed, including database trees, grids, tables, object and
  table designers, query builder, project context, approvals, approval-only site,
  editor pairing, recovery, keyboard behavior and responsive layouts.
- All 54 DBA JavaScript/CommonJS files passed syntax checks.
- Shared skill installer: three tests passed for all destinations, idempotency,
  customization preservation, dry-run and junction/symlink escape rejection.
- Official skill-validator logic passed through an equivalent dependency-free check;
  the distributed validator could not start because both available Python runtimes
  lack PyYAML. No package was installed as a side effect.
- Full Maven reactor verification is recorded in
  [workflow-validation.md](workflow-validation.md).

## Docker and external-environment disposition

The live harness recorded 55 unique image IDs before testing. It removed only its
ownership-labelled containers/anonymous volumes and the newly pulled unused MariaDB
image. The final count returned to 55. Existing MySQL/PostgreSQL and unrelated user
containers/images were preserved. No broad Docker prune was used.

Real GitHub Copilot, Claude Code and Windsurf discovery surfaces were unavailable on
this host and remain unverified client environments. The maintained skill and its
references install into their documented project locations without changing MCP
configuration. Installing a skill in a cloud agent does not expose a developer
machine's loopback MCP server.

## Recovery boundaries

On a stale plan, changed target revision/fingerprint, partial commit, or uncertain
connection outcome, stop and recapture actual metadata. Do not retry migration steps
blindly. A rehearsal is evidence about its bounded synthetic fixture and reproduced
schema only; it is not a production-data simulation. Release retained jobs when the
workflow ends and clean up disposable infrastructure through the owner that created it.
