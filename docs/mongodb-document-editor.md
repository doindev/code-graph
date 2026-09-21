# Guarded MongoDB document editor

The native workspace's **Edit MongoDB document** action provides a bounded,
memory-only complete-document draft. Select an exact collection and enter an
Extended JSON _id (string, ObjectId, Int32 or Int64). Opening the editor performs
no query. **Load document** explicitly requests exact collection metadata and a
bounded find; incomplete, missing or ambiguous results cannot become editable
snapshots. The native JSON command and SQL Script contents are not rewritten.

## Scope and workflow

- Writes require a writable, explicitly configured replica-set profile and an
  existing ordinary collection. Standalone, SRV, sharded, views, capped and
  time-series targets remain inspect-only in this editor.
- Sharded identity/routing support is unfinished: an _id is not necessarily
  globally unique when it is not the shard key. Do not switch to an unguarded
  command to force an edit through. See [MongoDB shard-key restrictions](https://www.mongodb.com/docs/manual/core/sharding-shard-key-indexes/).
- Edit canonical Extended JSON, not relaxed JSON numbers or JavaScript. This
  preserves Int64/Decimal128 precision, binary, dates, nested values, missing
  fields and explicit nulls. Numeric values require the appropriate BSON type
  wrappers. The server rejects noncanonical representations and unsupported IDs.
  [Canonical Extended JSON reference](https://www.mongodb.com/docs/manual/reference/mongodb-extended-json/).
- The _id is immutable. This is a complete replacement: removing a field from
  the draft removes it from the saved document. No upsert, insert, delete, bulk,
  update-operator or projected-document editing is enabled here.
- Save opens the existing exact native review. Only **Apply once** submits the
  retained reviewed plan. Canceling review preserves the draft.
- Revert restores the loaded original without a write. Reloading or closing a
  dirty draft requires confirmation. Tab switching preserves drafts; profile
  change/removal disables further operations. Drafts are not session-persisted.
- A confirmed commit leaves the submitted text visible but read-only; explicitly
  Load again before another edit. It is not presented as freshly queried data.
- Conflicts and confirmed rollback preserve the draft. Lost replies, invalid
  receipts or uncertain commit/rollback block Save, Revert and Load. Reconcile
  with authorized reads, then explicitly **Discard reconciled document draft**.
  This never retries, rolls back or repairs a prior write.

The editor reserves 512 KiB from existing browser workspace accounting, admits
at most 32 KiB UTF-8 per original/replacement document, rejects duplicate JSON
field names, and shares existing native result/job limits. It retains no cursor,
socket, background timer, disk copy or second inventory while idle. Driver wire
buffers and transient/DOM overhead are not a hard total-RAM guarantee.

## Server/MCP guard contract

The existing native command tool accepts an optional documentGuard on a managed
MongoDB transaction. Discover its current schema/capability before use:

~~~json
{
  "transaction": [{
    "update": "items",
    "updates": [{
      "q": {"_id": "example"},
      "u": {"_id": "example", "value": {"$numberInt": "2"}},
      "multi": false,
      "upsert": false
    }]
  }],
  "documentGuard": {
    "collectionUuid": "AAAAAAAAAAAAAAAAAAAAAA==",
    "expected": {"_id": "example", "value": {"$numberInt": "1"}}
  }
}
~~~

The UUID above is an illustration, not a usable target identity. Supply the
actual listCollections info.uuid binary subtype 04 as canonical base64. The
expected document must be complete canonical Extended JSON, not a projection
or shortened result. Original and replacement are each at most 32 KiB.

Only one replacement update is allowed. Original/filter/replacement must contain
the same supported, typed _id; guard fields, filters and options are allowlisted.
The application controls simple collation and forbids caller collation overrides.
Preflight verifies replica-set/session support, ordinary collection identity and
the reviewed UUID. Within the existing snapshot transaction it reads the exact
document and compares BSON bytes, including field order and numeric types.
The subsequent update shares that transaction, so concurrent changes cannot be
silently overwritten. Collection identity and authorization are rechecked before
the single non-retrying commit. Existing transaction cancellation, timeouts,
audit, ownership, resource reservations and cleanup remain unchanged.

Byte equality is deliberately conservative: field reordering or a numeric-type
change conflicts, even if MongoDB's ordinary value equality might compare them
as equivalent. Values returning to the exact original bytes are not historical
identity evidence. BSON documents whose duplicate fields or ordering cannot be
faithfully represented through JSON must not be edited this way.

Receipts retain kind transaction, atomic true, documentGuard true, explicit
commit/rollback outcome and exactly one update's matched count/state. An HTTP
success or terminal job state alone is not proof of commit. Normal agents still
need exact one-time approval; startup YOLO changes consent, not guard checks.
No reusable write grants, additional tools or database-access authority are added.
Existing unguarded transaction contracts are unchanged. See
[transaction outcomes and recovery](mongodb-transactions.md).

## Validation and delivery

Validated on Windows with OpenJDK 25, Maven 3.9.11, Node 22.22.3 and Playwright,
using an isolated source snapshot. Acceptance checkpoint: 2026-09-20.

| Gate | Result |
| --- | --- |
| Focused DBA, HTTP/MCP schemas, ownership/review and restricted-site checks | 46 passed, four opt-in live skips; zero failures/errors |
| MongoDB replica-set fixture | 17 passed, zero skips/failures; repeated after final Java changes |
| MongoDB sharded-router fixture | 17 passed, zero skips/failures, including explicit document-guard rejection; this does not enable sharded editing |
| Full Maven reactor | 35 modules, 183 suites, 906 tests: 842 passed, 64 explicit opt-in/environment skips, zero failures/errors |
| Browser regressions | All 19 suites passed; final packaged native suite also passed after narrow-layout refinements, with fixture shutdown confirmed |
| JavaScript and maintained optional skill | DBA module/test syntax and skill frontmatter/reference validation passed |
| Installer regressions | Java: 39 CgraphInstaller, 50 SkillInstaller and 149 McpInstaller tests passed; Windows bootstrap passed; Node: 14 passed, two optional real-client CLI checks skipped |

Live document cases cover typed BSON replacement, byte-exact original conflicts,
a concurrent external write between guard-read and update, collection UUID
replacement, cancellation/revocation, and a lost reply after an actual commit.
The lost-reply case verifies one commit attempt and no automatic replay. Normal
agent requests still await exact approval; forged persistent choices fail.
Startup YOLO also preserves the guard. Existing transaction/stream tests run in
the same live harness. Browser mocks cover lifecycle and review presentation,
not live database correctness.

The live runs use MongoDB 8.0 pinned to
mongo@sha256:4968f22d0c6c10ef29952f3e807f62872ba22b3312f25803564fbfc08255efc2
and Java driver 5.12.0. Test JVMs use a 256 MiB heap and 64 MiB direct-memory
limit; owned containers use the harness's 1.5 GiB limit. These are bounded
functional checks, not a new three-run performance or total-memory certification.

Evidence is retained beneath
target/mcp-efficiency-coverage/mongo-document-editor-f9438f99ddfc4f6c94b0aade0885c8d1:
focused-rerun.log, mongo-replica-final.log, mongo-sharded.log, live-reports,
reactor.log, browser-all.log, browser-native-packaged-verified.log,
package-final-ui.log and installer logs. The final narrow screenshot is
source/code-graph-dba/target/mongo-document-editor.png.

Failed attempts remain recorded: a focused assertion initially expected the
wrong notice wording; a layout assertion measured client height instead of the
specified border-box height. Both were corrected and rerun. An earlier browser
wrapper reported unsuccessful shutdown despite passing assertions; the final
explicit check reports Node exit 0 and its owned fixture stopped.

Owned Docker containers/volumes and newly introduced unused images were removed.
The final image inventory matches the pre-test inventory; existing databases,
containers and images were preserved. No broad prune or production-data mutation
was used. Real macOS/Linux desktop behavior and unavailable client environments
remain unverified; skipped gates are not passes.

Before this continuation, the validated Redis stream composer was committed and
pushed as c7fa379 and restarted with MCP 3000, admin/DBA UI 8137, desktop approvals,
hybrid storage, a 1536 MiB graph/cache budget and no automatically onboarded
project. Both UI pages and MCP initialization returned HTTP 200. This MongoDB
increment is implemented and tested locally; it has not been committed, pushed
or deployed over that running server.

The connected client exposed no code-graph navigation tools despite the healthy
server. Focused filesystem reads were necessary; no MCP navigation savings are
claimed. The maintained optional skill reference was updated using skill-creator
guidance; global/customized client skills and MCP configuration were not changed.

Reproduction from an isolated checkout:

~~~powershell
mvn -B -ntp -pl code-graph-mcp-http -am test '-Djava.awt.headless=true' '-Dtest=NativeMongoDocumentTest,NativeMongoTransactionTest,NativeFoundationTest,NativeReviewTest,NativeMcpHttpTest,DbaToolSchemaTest,ApprovalReviewServerTest' '-Dsurefire.failIfNoSpecifiedTests=false'
./code-graph-dba/test-mongo-topologies.ps1 -Topology replica_set
./code-graph-dba/test-mongo-topologies.ps1 -Topology sharded
./code-graph-dba/test-browser.ps1 -NodeModules <path-to-playwright-node_modules>
mvn -B -ntp package '-Djava.awt.headless=true'
~~~

The broad native roadmap remains incomplete: graphical document creation/deletion,
sharded document identity/routing, aggregation builders, subscriptions, broader
administration and the remaining vendor/platform gates are not completed here.
