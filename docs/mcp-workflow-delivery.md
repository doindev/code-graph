# MCP coding and database workflow delivery

This is the acceptance checklist for the approved expansion, not a declaration
that unmarked capabilities are available. Existing authorization, standalone
targets, headless restrictions, and graph/DBA resource limits remain in force.

## Milestones

- [x] **1 — Contracts and discovery:** nested typed input schemas, structured
  results/output schemas, advisory annotations, consistent result metadata,
  workspace context, capabilities, templates, generation-aware pagination,
  catalog refresh and bounded generation waits, documented compatibility aliases.
- [x] **2 — Navigation and mappings:** file outline, position resolution,
  references and implementations, incremental SQL/JPA/MyBatis/Prisma/TypeORM
  mappings, exact versus inferred locations and explicit uncertainty.
- [x] **3 — Impact and comparison:** bounded unified change analysis with
  revision-accurate Git inputs, authorized schema capture/comparison, semantic
  differences, incomplete-inventory handling and independent target authorization.
- [x] **4 — Migrations:** preparation/artifacts, exact reviewed application,
  target/fingerprint/expiry checks, explicit disposable rehearsal targets,
  bounded synthetic fixtures, partial/uncertain execution reconciliation.
- [x] **5 — Verification:** contract validation, evidence-based affected tests,
  distinct estimated-plan/analysis operations and normalized plan comparisons.
- [x] **6 — Editor handoff:** user-initiated session/workspace pairing,
  revision-checked drafts/edits, bounded events/acknowledgements/resynchronization,
  revocation and no implicit execution or permission widening.
- [x] **Shared skill:** one maintained skill with focused references; explicit
  non-overwriting installers for Codex, Copilot, Claude and Windsurf; official
  client documentation checked and unavailable discovery environments identified.

## Mandatory gates (record evidence; do not infer a pass)

- [x] Focused tests after each milestone, before dependent work.
- [x] Protocol: legacy text, structured content, schemas, pagination, errors,
  HTTP/stdio sessions, stale sessions and reduced headless toolset.
- [x] Authorization: exact targets, standalone and project/environment isolation,
  revocation, stale plans, replay, redaction, forged capabilities and audit failure.
- [x] Analysis: overloads/inheritance, aliases/duplicate names, ORM fixtures,
  dynamic SQL, renames/deletions and concurrent indexing.
- [x] Database matrix for **every** template: pinned driver/image, licence gates,
  supported operations, actual live evidence and incomplete/unsupported cases.
- [x] Browser pairing: ownership/conflicts/disconnects/unsaved content/accessibility.
- [x] Resources: large inventories, concurrency, budget reductions, pagination
  expiry, cancellation and repeated lifecycle cleanup.
- [x] Three independent performance runs, with baseline, percentiles, throughput,
  heap/GC/process/accounted memory, disk use and bounded response sizes.
- [x] JavaScript, DBA, HTTP/MCP, browser, installer and full Maven regressions.
- [x] Final milestone/engine acceptance report and recovery documentation.

## Disposable test resource ownership

Run database fixtures sequentially by default. Before pulling, record the image
IDs already present. Label containers/volumes with a unique test-run owner and
record any newly introduced image IDs/digests. In `finally` cleanup, stop/remove
only this run's containers and volumes; remove newly introduced images only
after verifying no other container uses them. Report retained images and why.
Never remove pre-existing images, source databases, unrelated containers or use
Docker pruning. Licence acceptance is a separate human prerequisite. No paid
resources, expiring trials or automatic acceptance of vendor EULAs.

## Deferred language mapping backlog

Only Java/JPA/MyBatis and JavaScript/TypeScript/Prisma/TypeORM are the initial
verified ORM mapping scope. Other indexed languages retain graph navigation.
Their future adapters require fixtures for static SQL, qualified names, aliases,
dynamic/unresolved names, exact source locations, incremental replacement and
deletion, confidence/coverage reporting, and bounded memory. Missing mappings
must never be presented as proof of non-use.

## Boundaries

No production database mutation, application restart, commit or push is implied
by this checklist. Rehearsal never copies application records. Docker remains a
test-harness/agent responsibility, not an application or MCP capability.

## Evidence log

- Milestone 1 implementation: structured/text-compatible envelope and advisory
  annotations; nested DBA input descriptions; bounded symbol/catalog cursors;
  templates, workspace and cached capability discovery; catalog refresh and
  bounded generation waits. No new vendor live verification claimed.
- Focused checks passed in an isolated source build: cursor, tools, workspace,
  template/capability discovery, catalog/H2 tests, SDK contract tests and HTTP/MCP
  access/end-to-end tests. Full regression evidence is recorded separately.
- Remaining M1 limitations: output `data` schemas preserve heterogeneous legacy
  payloads rather than describing every legacy field. Live standalone capability
  observation passed embedded-H2 job/ownership/permission tests. Broader vendor
  capability validation remains an acceptance item, not an inferred pass.
- Full Maven reactor passed for the first M1 snapshot (2026-09-18); follow-up
  live-discovery and HTTP/MCP tests passed after adding the explicit live probe.
  Existing environment-dependent skips remain skips, not passes.
- Docker image events showed no new pulls during the full regression run; its
  Neo4j/Arango temporary containers were removed. Existing user containers/images
  were preserved. A 55-image-ID baseline is recorded for subsequent test cleanup.
- Shared skill references and explicit per-client installation helper added.
  Three Node tests passed: all destinations/idempotency, dry-run/customization
  preservation, and junction/symlink escape rejection. Real discovery in Copilot,
  Claude and Windsurf has not been verified in those client environments.
- Navigation foundation added with five focused tool tests, streaming adjacency
  parity checks, HTTP registration, and incremental reference-location retention.
  Direct type implementations and expression-level position candidates are
  supported; full method dispatch and exact identifier-token resolution remain
  limitations, not implied capabilities.
- Mapping adapters now participate in file generations and shared graph storage.
  Six focused tests passed for SQL aliases/joins/ambiguity, JPA, TypeORM adapter,
  Prisma, MyBatis safe XML and replacement/deletion in memory/hybrid modes.
  Follow-up tests exercise the actual JavaScript/TypeScript analyzer registration.
  Static framework subsets are not complete framework semantics: custom naming,
  computed expressions, ORM inheritance/overrides and CTE/derived
  lineage require further acceptance work before M2 is marked complete.
- Static Prisma relationship fixtures now cover explicitly mapped models/fields
  and composite relation positions; implicit/dynamic relation targets remain uncertain.
- Milestone 3 foundation: bounded live schema capture and retained-snapshot
  comparison, both targets independently authorized and revalidated. H2 checks
  passed for changes, missing-object uncertainty, no captured application records,
  partial limits, ownership, revocation and accounted result leases. Unified Git
  impact, large-schema paging and full semantic/vendor coverage remain incomplete.
- Disposable live checks passed on PostgreSQL 16.14 / JDBC 42.7.13,
  MySQL 8.4.11 / JDBC 9.7.0, and MariaDB 11.4.13 / JDBC 3.5.7.
  These cover bounded observations, column differences, views, retained fixture
  values and reusable-approval regressions—not migration certification.
  [All 42 templates](workflow-capabilities.json) remain represented, including
  engines whose eligibility/licensing/live coverage has not yet been verified.
- Full 35-module Maven reactor passed at the M3 foundation checkpoint on
  2026-09-18. Environment-dependent skips remain incomplete, not passed.
- Docker harnesses now clean each vendor before starting the next. Cleanup checks
  actual image IDs against the pre-test baseline, verifies container ownership,
  removes owned anonymous volumes, and preserves any image in use. Fake-Docker
  safety tests passed. Real PostgreSQL/MySQL/MariaDB fixtures were removed;
  the newly pulled MariaDB image was deleted. All 55 pre-existing image IDs remain,
  with no introduced images left after that run. No broad prune was used.
- `analyze_change` and `find_affected_tests` expose the tested streaming
  explicit-target foundation. Git selections are bound to resolved revisions or
  labelled as working-tree evidence. Test recommendations never execute commands.
- Migration preparation, validation, exact one-time reviewed application and
  distinct-target rehearsal are complete for live-observed PostgreSQL, MySQL,
  MariaDB and H2. Reusable SQL permissions cannot authorize retained plans.
- The same live matrix now covers full bounded schema/rehearsal/migration/contract/
  plan workflows on isolated source and rehearsal servers. Docker cleanup returned
  to the 55-image baseline and preserved all pre-existing resources.
- Editor pairing is explicit, revocable and session-bound. Document changes use
  revisions, browser autosaves use workspace compare-and-swap, events are bounded
  and acknowledged, and edits neither execute SQL nor save files.
- The complete browser suite and all 54 browser JavaScript syntax checks passed.
  Three fresh million-edge performance runs remained below the 5 ms p99 gate;
  the established repeated storage matrix supplies p95, heap/GC, process/native,
  accounted-residency and disk evidence.
- The final implementation/result details and external client/vendor limitations
  are recorded in [mcp-workflow-acceptance.md](mcp-workflow-acceptance.md).
