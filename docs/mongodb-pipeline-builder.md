# MongoDB aggregation pipeline builder

Open a native MongoDB workspace, choose its exact database and collection, and
select **Build aggregation pipeline** (the workflow icon). Opening the dialog
does not connect, query, or write to the database.

## Draft workflow

- Add stages from the bounded palette; edit each stage's value as Extended JSON.
- Move stages with the Up/Down controls (keyboard accessible) or drag their name
  buttons. The insertion indicator marks the target; stage values follow stable
  identities when reordered.
- Disable a stage to omit it from the generated command. Disabled drafts still
  count toward resource limits and exist only until the dialog closes.
- Inspect the generated command in its read-only, non-wrapping preview.
- **Use pipeline** copies the command to the existing workspace editor and closes
  the dialog. It never executes. Use the existing **Run native command** action
  separately; results, cancellation, authorization and server budgets are unchanged.
- Cancel, X and Escape discard the builder draft, leaving the original command
  untouched. Closing a workspace containing the builder asks before discarding.
- Existing aggregate commands import only when their collection matches and all
  options/stages can be preserved. A non-aggregate command starts an explicitly
  labeled empty pipeline; it is not silently converted.
- A changed connection or workspace invalidates handoff rather than retargeting
  execution or overwriting a newer command. Closing releases the draft reservation.

The resizable dark dialog uses the existing DBA theme and Lucide icons. Its list
and stage editor scroll independently; narrow screens stack them vertically.
The target is displayed in full with wrapping. No database content is rendered
as HTML.

## Verified boundary and costs

The palette follows the existing read-stage adapter:

`$match`, `$project`, `$sort`, `$limit`, `$skip`, `$group`,
`$unwind`, `$addFields`, `$set`, `$unset`, `$replaceRoot`,
`$replaceWith`, `$count`, `$sortByCount`, `$sample`, `$bucket`,
and `$bucketAuto`.

This is a stage-oriented JSON builder, not a complete schema-aware expression
designer. Field names/types are not inferred. The server remains authoritative
for expression support and database errors. Unsupported stages/options are
rejected on import, not removed. Cross-namespace stages, JavaScript execution,
`$out` and `$merge` are not available in this builder. Existing native command
review remains available for separately supported operations.

Only aggregate, pipeline and boolean allowDiskUse are emitted. Server disk use
defaults off; enabling it permits MongoDB's temporary aggregation storage, not
application result spooling. Sort/group stages can scan extensively or consume
server resources even when returned rows are capped. Stage order changes
semantics. See [MongoDB aggregation](https://www.mongodb.com/docs/manual/aggregation/).

Limits: 64 stages including disabled drafts; 128 KiB combined draft text and
128 KiB generated command; 32 nesting levels and 16,384 JSON values. Opening
reserves 2 MiB under the existing browser workspace accounting allowance for
bounded draft/preview/control state. This estimate is not a hard browser-heap cap.
Normal backend memory, row, byte, deadline and concurrency limits still apply.

Canonical BSON wrappers preserve int64/Decimal128 and other Extended JSON
values. Raw unsafe integers, non-finite numbers, negative zero, duplicate JSON
keys and malformed JSON are rejected rather than silently rounded or overwritten.
Objects whose numeric field names would change order during JavaScript JSON
serialization are rejected as well; field order can affect compound sorts and
embedded-document equality.
Use $numberLong/$numberDecimal/$numberDouble string wrappers when appropriate.
Disabled stages are not serialized as active commands.

## Reproducible validation

```powershell
node --test code-graph-dba/mongo-pipeline.test.cjs
mvn -B -ntp -pl code-graph-dba -am test -Djava.awt.headless=true -Dtest=NativeFoundationTest,NativeReviewTest,NativeVendorTest,ApprovalReviewServerTest -Dsurefire.failIfNoSpecifiedTests=false
$env:DBA_BROWSER_SUITE='native'
./code-graph-dba/test-browser.ps1 -NodeModules <playwright-node-modules>
./code-graph-dba/test-native-vendors.ps1 -Engine mongodb
mvn -B -ntp package -Djava.awt.headless=true
```

Run the browser fixture after compilation finishes; do not change its loaded
classes concurrently. The MongoDB harness owns a unique loopback-only container
and removes it, its volumes and any newly introduced unused image afterward.
It never mutates saved user connections.

This closes the bounded pipeline-builder UI subtask, not the entire
[MongoDB delivery roadmap](native-database-delivery.md). Advanced expression
assistance, arbitrary aggregation stages, persistent subscriptions, GridFS,
external authentication and hosted-platform verification remain separate work.

## Acceptance checkpoint — 2026-09-21

Windows x64; OpenJDK 25, Maven 3.9.11, Node 22.22.3.

- Nine pipeline-model unit tests and 11 existing grid-state/search tests passed.
  All 31 DBA JavaScript modules passed syntax checks.
- Focused native/approval Java tests: 26 passed, three unconfigured live-fixture
  skips. Approval-only listeners reject both new builder assets.
- Disposable pinned MongoDB fixture: 37 passed, seven Redis-specific skips.
  Added live match/sort/project/limit/group and typed-literal cases, plus write-stage
  rejection through the read endpoint. This is not exhaustive expression coverage.
  The harness uses image
  `mongo@sha256:4968f22d0c6c10ef29952f3e807f62872ba22b3312f25803564fbfc08255efc2`
  and MongoDB Java driver 5.12.0. Its container, volumes and newly introduced image
  were removed; all six pre-existing containers and 55 image identities remained.
- Full Maven reactor: 35 modules, 184 suites, 916 tests; 851 passed, 65 explicit
  optional/platform skips, zero failures/errors. Elapsed time 4:29. The final
  JSON numeric-key guard then passed its focused test and was repackaged before
  browser validation; the actual launcher dependency contains that final resource.
- All 20 browser regression groups passed. Builder checks cover add/edit, mouse
  and keyboard ordering, enabling/disabling, exact BSON, preview, no automatic
  execution, exact-target handoff, stale editor/profile rejection, memory admission,
  dirty-close rejection, narrow layout and disposal. A screenshot was reviewed.
- Installer/optional-skill tests: 18 passed, two unavailable real-client checks.
  Maintained skill frontmatter validation passed; no global customization was
  overwritten.
- The running application's MCP found the newly indexed model using one
  `search_symbols` call with file kind and js language: generation 3, 178.1 ms,
  954 transport bytes, no filesystem discovery fallback. This is development
  evidence, not a controlled performance benchmark. Source reads still served
  implementation and review.

Local raw logs, isolated source/build, live reports and screenshots are retained
under `target/mongo-pipeline-bcda1b1526354590acda307ef68fd37c/`;
the discovery ledger is `target/mongo-pipeline-navigation.jsonl`.
Generated artifacts are not committed. Existing graph/MCP schemas, native
permissions and production session/resource limits were not changed.
