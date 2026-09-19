# MCP precision and efficiency delivery

Implemented and locally validated on 2026-09-18/19. The candidate has not been
deployed to the user's running server. Unavailable client/platform environments
and unsupported resolver constructs below are not reported as passes.

## Frozen baseline

- Source copy: cgraph-efficiency-92332f3e79654ca89d7e3c11fc72f7f4/baseline in the OS temporary directory; excludes build output, Git metadata and dependency caches.
- Baseline HTTP reactor package: passed, 2026-09-18, JDK 25 / Maven 3.9.11 / Windows.
- Existing live build SHA-256: 989DD94D6203C83A0F7B35F4643B1AA4DD6F34B4A624129E340ABE4FF4E1220A.
- Live discovery generation at implementation start: 16. The live process is not the candidate build.
- Prior diagnostic evidence: language-resolution-followups.md and validation/language-resolution.

## Acceptance checklist

| Phase | Status | Evidence / remaining gate |
|---|---|---|
| 0 baseline and defect fixtures | Passed | Frozen baseline package; initial five-test regression fixture has four expected baseline failures; retained module/discovery/duplicate diagnostics |
| 1 discovery | Passed locally | Build identity, canonical fingerprint, registry-change notifications; actual fresh/existing HTTP and stdio parity; stale session rejected |
| 2 distinct call graphs | Passed | Distinct caps/counts, cycles/self-calls, streaming, cancellation, 100,000-visit bound, existing-node links at cap; attached risk scoring shares the bound |
| 3 byte-aware pages | Passed | All five tools, escaped/Unicode payloads, returned-prefix cursors, generation rejection and explicit oversized-record error |
| 4 ES module bindings | Passed for documented subset | Renamed/default/namespace imports, explicit/star exports, shadowing, stable callable symbols, serialization and memory/hybrid parity |
| 5 project module patterns | Passed for documented subset | Static CommonJS, workspace packages, config inheritance/aliases, conditional-entry restrictions, config-only invalidation, moves/deletes and work-budget exhaustion |
| 6 workflow and skill | Passed locally | Three independent runs per build, independent correctness oracles, updated optional skill, installer/HTTP/stdio/browser/reactor checks |

Detailed results, test gates, limits, and raw evidence are in the
[acceptance report](validation/mcp-efficiency/README.md).

## External or explicitly unsupported gates

- The connected client still advertises 32 tools while a fresh request to the
  unchanged live DBA server lists 68. The diagnostic distinguishes this from a
  server-registration failure; whether host caching or filtering causes this
  particular mismatch remains unverified. No client configuration was changed.
- Native discovery/refresh and realistic model-inclusive workflows in Copilot,
  Claude and Windsurf were unavailable. Codex's connected tool inventory and
  instrumented development calls were observed; this is not a fresh-client
  controlled behavioral trial.
- Node/TypeScript compiler equivalence is not claimed. Dynamic loading/exports,
  nested imported object access, unsupported conditions/configuration, installed
  packages outside the root, and work-budget exhaustion remain explicitly
  unresolved. See the [coverage contract](mcp-precision-efficiency.md).
- Environment-gated external database/vault/desktop/stress tests remain skipped,
  not passed. No Docker or vendor resources were needed for this iteration.

Discovery searches locate implementation. Source reads inspect code that must be understood or edited. They are reported separately; necessary implementation reads are not counted as failed navigation. Deterministic harness results do not represent fresh agent/client behavior.

No deployment, restart, commit, push, user-database access or Docker resource is
part of this delivery. Browser regressions use isolated disposable fixtures.
