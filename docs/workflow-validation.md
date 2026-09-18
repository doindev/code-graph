# Workflow expansion validation

Implementation and local acceptance completed on 2026-09-18. The detailed result,
including explicit external limitations, is in
[mcp-workflow-acceptance.md](mcp-workflow-acceptance.md). The machine-readable
[42-template matrix](workflow-capabilities.json) is authoritative for vendor status.

## Core regressions

```powershell
# Complete MCP/DBA dependency reactor
mvn -B -pl code-graph-mcp-http -am test

# All browser modules and the complete browser suite
$files = rg --files code-graph-dba/src/main/resources/codegraph/dba code-graph-dba |
  Where-Object { $_ -match '\.(js|cjs)$' } | Sort-Object -Unique
foreach ($file in $files) { node --check $file }
./code-graph-dba/test-browser.ps1 `
  -NodeModules 'C:/Users/timhj/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules'

# Shared skill installer
node --test skills/install-skill.test.mjs

# Docker ownership/cleanup logic without invoking Docker
./code-graph-dba/test-docker-resources.tests.ps1

# Final reactor
mvn -B verify
```

The complete browser run passed every dispatched suite: tree menus, merged toolbar,
Script selection, Data Grid menus/scheduling/controllers/editing, Table tabs/designer,
view queries, Visual Query Builder geometry/lifecycle, object creation/designer,
project context, editor pairing, approvals, the approval-only site, recovery,
keyboard/accessibility behavior and responsive layout. All 54 JavaScript/CommonJS
files passed `node --check`.

Environment-gated desktop/vault/vendor tests remain skips unless their documented
prerequisites exist. A skip is never counted as a pass. The Java Swing approval
surface has separate interactive evidence in [approval-broker.md](approval-broker.md).

## Live workflow engines

```powershell
./code-graph-dba/test-reusable-vendors.ps1 -Vendors postgresql,mysql,mariadb
```

The sequential harness creates two ownership-labelled disposable servers per vendor:
one source and one rehearsal target. It verifies reusable approvals plus bounded
schema capture/comparison, migration preparation/validation/application, isolated
rehearsal with synthetic fixtures, contract validation and estimated-plan analysis/
comparison.

| Engine | Server | JDBC | Result |
|---|---|---|---|
| PostgreSQL | 16.14 | 42.7.13 | `WORKFLOW_FULL_VERIFIED` |
| MySQL | 8.4.11 | 9.7.0 | `WORKFLOW_FULL_VERIFIED` |
| MariaDB | 11.4.13 | 3.5.7 | `WORKFLOW_FULL_VERIFIED` |
| H2 | 2.4.240 | 2.4.240 | Embedded workflow and migration/rehearsal tests passed |

The pre-run Docker inventory contained 55 unique image IDs. Final cleanup removed
only harness-owned containers/anonymous volumes and the newly introduced unused
MariaDB image; the inventory returned to 55. Existing MySQL/PostgreSQL, Timescale
and unrelated user resources were preserved. No broad prune was used.

Oracle remains behind `-OracleLicenseAccepted`. Managed services, paid resources
and vendor licence agreements are never provisioned or accepted automatically.
Other free server templates remain advanced-adapter-unverified and therefore do not
advertise migration support; this is an explicit unsupported result, not a pass.

## Performance

Run the graph query gate three times:

```powershell
$env:CODE_GRAPH_PERF='1'
1..3 | ForEach-Object { mvn -q -pl code-graph-core -Dtest=EnginePerfTest test }
Remove-Item Env:CODE_GRAPH_PERF
```

Observed build/p50/p99 results were `1685 ms / 265 µs / 390 µs`,
`1820 ms / 281 µs / 532 µs`, and `1765 ms / 265 µs / 1301 µs`; all p99 values
passed the 5 ms gate. The repeated storage benchmark and raw samples in
[memory-budgeted-storage.md](benchmarks/memory-budgeted-storage.md) supply p95,
throughput, heap/GC, native/process memory, accounted residency and disk evidence.
They do not claim an operating-system-cold cache or a total-process hard RAM cap.

## Skill validation

The installer tests cover Codex `.agents/skills`, Copilot `.github/skills`, Claude
`.claude/skills`, and Windsurf `.windsurf/skills`, including idempotency, dry-run,
customization preservation and linked-directory escape rejection. The system
`quick_validate.py` could not import PyYAML in either available Python runtime.
An equivalent dependency-free check applied the validator's allowed frontmatter,
name/description and TODO/fence rules and passed. No package was installed merely
to run validation. Real Copilot/Claude/Windsurf discovery surfaces were unavailable
on this host and are reported as unverified.

## Snapshot and migration recovery

Poll captures to completion before comparing them. Captures and comparisons belong
to one agent and retain exact target permissions/revisions. Release completed jobs;
an in-use response means a dependent workflow still holds an accounted lease.

Plans expire after ten minutes and are single-use. A stale target, changed schema
fingerprint, partial commit or uncertain connection outcome requires a fresh capture
and reconciliation. Never replay committed or uncertain steps. Rehearsal does not
copy application records or delete its target; the owner of disposable infrastructure
performs scoped cleanup.
