# Bounded Redis bitmap, HyperLogLog and geospatial workflows

The native Redis workspace and `dba_request_native_command` share a bounded
single-command adapter. Supply the exact binding or standalone connection
UUID/name/database. Cluster targets require database 0; multi-key operations
must use one hash slot. Normal exact review and startup YOLO use the same
validation. Read-only profiles still reject writes. No new tool or grant exists.

## Supported grammar

All control arguments are JSON strings, not JSON numbers. Keys and member values
may use canonical `{ "base64": "AP8=" }` objects. The aggregate command limit is
128 KiB, keys 8 KiB, and member values 64 KiB. Unknown options fail before dispatch.

| Commands | Bounds and semantics |
|---|---|
| SETBIT | Offset 0–524287, value 0 or 1; at most the first 64 KiB can be addressed |
| BITCOUNT | Whole key, or nonnegative start/end with optional BYTE/BIT; range within 64 KiB |
| BITPOS | Bit 0/1, optional nonnegative start/end and BYTE/BIT; range within 64 KiB |
| BITFIELD | 1–32 GET/SET/INCRBY operations; i1–i64 or u1–u63, numeric or `#` offsets within 64 KiB; OVERFLOW WRAP/SAT/FAIL before writes |
| BITFIELD_RO | GET only, same encoding/address/count bounds |
| PFADD | 1–100 members |
| PFCOUNT | 1–100 keys; **reviewed write**, not a safe-read operation |
| PFMERGE | Destination plus 1–99 source keys; destination contents change |
| GEOADD | Optional NX or XX, optional CH, then 1–100 longitude/latitude/member triples; finite coordinates in Redis's supported range |
| GEODIST | Exactly two members; optional m, km, ft or mi unit |
| GEOPOS / GEOHASH | 1–100 members; missing members retain null |
| GEOSEARCH | FROMMEMBER or FROMLONLAT; BYRADIUS or BYBOX; required COUNT 1–100, optional ANY, ASC/DESC, WITHCOORD/WITHDIST/WITHHASH |
| GEOSEARCHSTORE | Explicit destination/source, same bounded search grammar; optional STOREDIST instead of WITH* |

These new workflows execute individually, not inside managed pipeline/transaction
objects. Existing GETBIT remains available through its prior adapter. BITOP,
scripts/functions, modules, Pub/Sub and server administration are not enabled by
this increment. Negative bitmap ranges are explicitly unsupported.

Example command portions (supply the ordinary exact target, request ID and purpose):

```json
["BITFIELD", "{app}:flags", "OVERFLOW", "FAIL", "INCRBY", "u8", "0", "1"]
```

```json
["GEOSEARCH", "{app}:places", "FROMMEMBER", "Palermo", "BYRADIUS", "200", "km", "ASC", "COUNT", "100", "WITHDIST", "WITHCOORD"]
```

`PFCOUNT` can modify the cached cardinality bytes. Therefore it is not permitted
through the read executor or read-only profiles, even though its primary output
is a count. HyperLogLog cardinalities are estimates. See
[Redis PFCOUNT semantics](https://redis.io/docs/latest/commands/pfcount/).

Bitfield FAIL returns null for an overflowing operation without changing its
field. Other subcommands can still succeed. BITFIELD is conservatively reviewed
as a write even when it contains only GET; use BITFIELD_RO for verified reads.
See [Redis bitfield behavior](https://redis.io/docs/latest/commands/bitfield/).

COUNT limits returned geo members, not all server search work. Whole-key bitmap
counts and HLL unions may also perform substantial work. These controls are not
a database CPU or total-memory cap. See
[Redis GEOSEARCH](https://redis.io/docs/latest/commands/geosearch/).

## Results, resource accounting and recovery

Results use `kind: redis_value`, operation, outcome and ordinal `entries[].value`.
Arrays preserve returned tuple order; large integers use `$numberLong`, null stays
null, and binary fields use base64/optional UTF-8 text previews. Binary fields are
clipped before Java value decoding; `truncated` identifies previewed/omitted data.
Never write a preview back as if it were the complete original member/value.

The shared native 64 MiB job reservation, 8 MiB wire/RESP guard, row/byte limits,
deadlines and cancellation still apply. One operation-owned primary socket is
closed after completion; none is held between interactions. No second keyspace
inventory, graph or application executor is created. Native/network overhead is
not fully represented by retained-result accounting.

`acknowledged` means Redis replied, not necessarily that a conditional write
changed data. Inspect the returned count/value. `partial_or_unknown` means a
mutation was dispatched but its outcome was not confirmed. Cancellation cannot
undo a write; late cancellation retains acknowledged receipts. Revoked authority
does not leak returned values. Failures never trigger automatic write retries.
The browser reserves receipt space before dispatch and retains the outcome even
when aggregate display memory requires omitting values.

TYPE cannot distinguish an ordinary string from a bitmap/HyperLogLog, nor a sorted
set from a geo index. Metadata does not invent a semantic type from representation.

## Validation

Use an isolated source copy, never the runtime directory of the running server:

```powershell
./code-graph-dba/test-native-vendors.ps1 -Engine redis
./code-graph-dba/test-redis-topologies.ps1 -Topology cluster -BuildRoot .
./code-graph-dba/test-redis-topologies.ps1 -Topology sentinel -SentinelAuth acl -BuildRoot .
mvn verify '-Djava.awt.headless=true'
./code-graph-dba/test-browser.ps1 -NodeModules <directory-containing-playwright>
```

Fixtures pin Redis 7.4.1 digest
`sha256:c1e88455c85225310bbea54816e9c3f4b5295815e6dbf80c34d40afc6df28275`
and Lettuce 7.7.0.RELEASE, with a 256 MiB test heap and 64 MiB direct-buffer cap.
They preserve existing resources and remove owned containers/volumes plus newly
introduced unused images. Other Redis versions, TLS deployments, modules and
full infrastructure administration remain separate unverified/incomplete gates.

The first live run exposed Lettuce's missing BITFIELD_RO enum constant. Dispatch
now uses the structurally validated protocol keyword, never a command supplied
outside the allowlist. The failed sample is retained, not represented as passed.

### 2026-09-20 acceptance checkpoint

- Standalone, three-primary Cluster and ACL-authenticated Sentinel gates passed,
  including normal and YOLO agent requests, browser-plan ownership, read-only
  restrictions, cross-slot rejection, typed integer/overflow/null results, binary
  members, 64 KiB geo values projected to bounded previews, cancellation before
  dispatch, acknowledged late cancellation and uncertain in-flight cancellation.
  No persistent native policy is created. Wrong-type replies remain server errors.
- All 35 Maven modules verified: 890 tests discovered, 830 passed, 60 explicitly
  skipped, zero failures/errors. Live Redis fixtures run separately. The final
  resource-test additions passed on all three topologies; the final stylesheet
  was repackaged and its affected native browser suite rerun.
- All 19 browser suites passed. Native examples never execute on selection,
  reviewed PFCOUNT follows Cancel/Apply, and memory-pressure/late-cancel receipts
  remain visible. Visual inspection exposed a browser-default gray editor; native
  command text now matches the Script editor's font and dark colors, covered by
  a computed-style regression.
- All 23 DBA JavaScript modules passed syntax checks. Node skill/installer gates
  passed 14 tests; two optional external-client checks skipped. The maintained
  skill and all four existing uncustomized client copies validate and match.
- Owned fixture containers/volumes were removed after every run, including the
  failed enum diagnostic. Redis's pinned image was already installed and was
  preserved; no introduced image remains. Existing MySQL/PostgreSQL/Timescale
  containers were untouched. No broad prune, production data mutation or commit.
- Isolated build and raw logs: `target/mcp-efficiency-coverage/redis-values-d614e91d1f0246e68a788c2084b49013/`.
  This includes `redis-live.log` (failed diagnostic), final standalone/Cluster/
  Sentinel logs, `full-reactor.log`, `browser-all.log`, and
  `browser-native-final.log`. Logs are local artifacts, not required runtime data.
- MCP implementation navigation is recorded in `target/redis-values-navigation.jsonl`.
  Four queries located existing/new adapters; the new file appeared at generation
  8 without a manual reindex. Focused implementation source reads were still needed.
  These observations are not a controlled speed benchmark or a count of all shell
  invocations. New-operation latency percentiles and production TLS/module/vendor
  coverage remain unmeasured/unverified, not silently passed.

This closes the documented value-operation subset, not the broader native
roadmap. Follow the remaining [delivery checklist](native-database-delivery.md).

On the user's explicit restart request, the tested build was deployed on loopback
MCP 3000 and admin/DBA UI 8137, preserving desktop approvals and the existing
1536 MiB hybrid graph/cache allowance (not a total-RAM cap). Both UI pages and
fresh MCP initialization returned successfully; the catalog exposes 69 tools and
the updated command grammar. Startup still onboards nothing automatically. This
repository was then explicitly onboarded through MCP: generation 1, 619 files,
7,958 symbols, 33,865 edges, no pending changes. No commit or push was performed.
