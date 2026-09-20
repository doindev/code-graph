# MCP efficiency/coverage validation evidence

See the [acceptance report](../../mcp-efficiency-coverage-delivery.md) for scope,
results and exclusions. The benchmark runs are unabridged acquisition/event data;
they contain fixture source identities, not database credentials or application rows.

- [Summary](summary.json), [independent fixture oracle](oracle.json).
- Baseline: [1](baseline-1.json), [2](baseline-2.json), [3](baseline-3.json).
- Candidate: [1](candidate-1.json), [2](candidate-2.json), [3](candidate-3.json).
- [Development MCP navigation ledger](development-navigation.jsonl).
- [Full reactor log](final-reactor.log).
- [Mongo public workflow gate](native-mongo-public-workflow.log).
- [Redis public workflow gate](native-redis-public-workflow.log).
- [PostgreSQL regression](postgres-regression.log), [SQL Server regression](sqlserver-regression.log).
- Mongo [replica set](mongo-replica_set.log) and [sharded router](mongo-sharded.log).
- Redis [Sentinel promotion](redis-sentinel-failover.log) and [Cluster/draft test](redis-cluster-draft-test.log).
- [Installer tests](installer-final.log), [skill installer](skill-installer-final.log),
  [MCP installer](mcp-installer-final.log), [launcher installer](launcher-installer-final.log).
- [Browser initial suites](browser-initial.log) and `browser-*-confirmed.log` below
  contain the successful continuation after the recorded build collision.

## Reproduce without changing a running server

Prerequisites: JDK 25, Maven, Node and `rg`; Docker only for disposable database
gates. Windows commands below must run from the repository root. Bash/Maven/Node
equivalents are possible; actual macOS/Linux interactive/client gates were not run.

```powershell
# Copies tracked + untracked source, excluding outputs; does not restart the app.
./scripts/Test-IsolatedReactor.ps1 -Name acceptance -All -Package
# The command prints its unique source directory. Use that directory below.
```

Build the frozen baseline in another owned directory from commit
`deffa3fd9e2749577b87403d0cdb61beb85fe202`, retaining its adjacent `target/lib`
dependencies. Extract that same source archive into a third **fresh dataset**
directory. Neither candidate source changes nor either build's `target` files
belong in the frozen dataset.

```text
node scripts/benchmark-context-navigation.cjs <baseline/code-graph-mcp-http/target/code-graph-server.jar> <candidate/code-graph-mcp-http/target/code-graph-server.jar> <fresh-frozen-dataset> <results-directory>
node scripts/summarize-context-navigation.cjs <results-directory>
```

The benchmark adds only its four owned `context-fixture` sources and refuses to
overwrite an existing fixture directory. It starts six independent ephemeral
loopback servers in alternating build order, checks every answer and shuts down
each owned server. Failed runs remain failures, not omitted samples. It does not
restart, onboard into, or query databases through the user's application.

For live database checks, change directory to the isolated candidate:

```powershell
./code-graph-dba/test-native-vendors.ps1 -Engine mongodb
./code-graph-dba/test-native-vendors.ps1 -Engine redis
./code-graph-dba/test-mongo-topologies.ps1 -Topology replica_set
./code-graph-dba/test-mongo-topologies.ps1 -Topology sharded
./code-graph-dba/test-redis-topologies.ps1 -Topology sentinel
./code-graph-dba/test-redis-topologies.ps1 -Topology cluster
./code-graph-dba/test-sqlserver-native.ps1 -AcceptDeveloperEula -DriverJar <mssql-jdbc-13.4.0.jre11.jar>
./code-graph-dba/test-postgres.ps1
```

SQL Server's flag is explicit licence acceptance, not an application default.
Topology scripts record pinned image digests, loopback ports and unique owner
labels, removing only owned containers/volumes/new unused images. Do not point
the opt-in tests at a saved/user database.

Prepare `code-graph-dba/target/test-lib` with the test dependencies, then run:

```powershell
./code-graph-dba/test-browser.ps1 -NodeModules <directory-containing-playwright>
```

Do not run Maven recompilation concurrently against those same browser fixture
classes. One checkpoint hit `BrowserFixture` class-not-found during concurrent
test recompilation; the affected suites were rerun after the build settled.
This was a harness scheduling failure, not a production fallback or suppressed test.

Installer checks use disposable homes, never the user's client configuration:
`node --test skills/install-skill.test.mjs installer/skill-bootstrap.test.mjs installer/uninstall.test.mjs installer/mcp-client-smoke.test.mjs`, plus
`java installer/SkillInstallerTest.java`, `java installer/McpInstallerTest.java`
and `java installer/CgraphInstallerTest.java`. Actual client discovery needs
the corresponding installed client and opt-in test environment; skipped client
checks must not be presented as native-client certification.

## Local intermediate evidence

Focused build logs are retained below `target/mcp-efficiency-coverage/` (ignored
build artifacts): context, freshness/waits, standalone catalog, language method
fixtures/parity, native observations/contracts, topology and SQL Server checkpoints.
The final candidate is `acceptance-candidate-389320fe13404ea3895764eeceabc82f/source`.
Obsolete intermediate build-source copies were removed to recover disk space;
parent build logs remain. Earlier failed diagnostics are retained separately from passing retests, including
SQL Server collation/numeric conversion and the stale PostgreSQL Explain assertion.

The skill remains optional: its presence never grants authority, starts a server,
installs MCP configuration or requires source verification after every indexed result.
