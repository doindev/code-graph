# Hybrid incremental validation artifacts

See the [acceptance report](../../hybrid-incremental-delivery.md) for scope,
commands, measured tradeoffs and incomplete environments.

- raw/baseline-{1,2,3}.json and raw/candidate-{1,2,3}.json:
  six final independent runs on the same frozen 74-file workload.
- logs/acceptance-reactor.log: final full Maven package/test run.
- logs/browser-all.log: all 17 seeded isolated browser suites.
- logs/catalog-transports.log: HTTP/stdio catalog and stale-session checks.
- logs/node-regressions.log: 18 Node workflow/discovery/installer tests.
- logs/java-installer.log, logs/java-skill-installer.log: portable installer
  safety checks; no client configuration was installed.
- logs/benchmark-final.log: benchmark execution progress and diagnostics.
- logs/final-stress-repository.log: million-edge constrained-heap updates and
  separate complete-repository indexing.
- navigation-ledger.json: selected MCP discovery/status calls, explicitly
  not a complete agent-call ledger.
- artifact-hashes.json: compared index/storage/parser JAR fingerprints.

No source payloads, credentials, database records or graph-store files are included.
Machine-local paths and temporary ports are retained for reproducibility.
