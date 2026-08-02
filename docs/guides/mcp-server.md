# MCP Server

code-graph serves its code-intelligence tools over the Model Context Protocol so any MCP-capable
agent (Claude Code, IDE assistants) can query blast radii instead of reading files. The stdio
transport is the primary local mode; streamable HTTP (team deployment) ships in
`code-graph-mcp-http`.

## Registering with Claude Code

```
claude mcp add code-graph -- java --enable-native-access=ALL-UNNAMED \
    -cp <code-graph-mcp jar + dependencies> io.doindev.codegraph.mcp.Main --root C:/repos/acme
```

On startup the server fully indexes the repo (450 files ≈ 3 s), starts the file watcher, and
serves until the client disconnects. Progress goes to stderr; stdout belongs to the protocol.

## Tool reference

| Tool | Purpose |
|---|---|
| `search_symbols` | Find symbols by name — call this FIRST to obtain valid symbol IDs |
| `get_symbol` | Signature, location, dependency counts for one ID |
| `get_impact_radius` | Direct + transitive dependents/dependencies, compressed to counts and spread |
| `get_call_graph` | Callers (up) / callees (down) as compact index-pair adjacency |
| `get_blast_score` | Auditable 0–100 change-risk score, factor by factor |
| `find_dead_code` | Unreferenced symbols with confidence tiers and caveats |
| `index_status` | Freshness: generation, counts, pending changes |
| `reindex` | Async re-index (the only state-mutating tool; never touches source) |

### Symbol IDs

`<lang>:<repo-relative-path>#<qualifiedName>/<arity>`, e.g.
`java:src/main/java/com/acme/auth/AuthService.java#com.acme.auth.AuthService.validateToken/1`.
IDs are stable across re-indexing — agents can hold them between calls.

### Worked session

```json
→ search_symbols {"query": "AuthService.validate", "kind": "function"}
← {"symbols": [{"id": "java:...#AuthService.validateToken/1", "kind": "function",
     "sig": "validateToken(String jwt)", "line": 88}], "total": 1, "truncated": false}

→ get_impact_radius {"target": "java:...#AuthService.validateToken/1", "depth": 3}
← {"dependents": {"direct": ["java:...#LoginController.login/1", "..."],
     "byDepth": [35, 172, 213], "total": 420, "modules": ["api","auth","billing"],
     "langs": ["java","ts"], "topByFanIn": ["java:...#ApiGatewayFilter.doFilter/3"]},
   "risk": {"score": 83, "band": "critical", "gate": "fail",
            "note": "MANDATORY RISK REVIEW: blast score 83 >= gating threshold 70. ..."}}
```

The `risk` block is the **threshold gate**: it is attached automatically to
`get_impact_radius`, `get_symbol` and `get_call_graph` whenever the target's blast score
reaches `gating.threshold` — an agent about to edit high-risk code always sees the risk report.

## Token frugality guarantees

- Hard caps: `limits.maxResults` (default 50) per list, `limits.maxResponseBytes`
  (default 32 768) per response; oversized responses become an error asking the agent to narrow
  the query — never a silent dump.
- Every truncated list carries `"truncated": true` plus an `omitted` count.
- **No tool ever returns file contents** — signatures, IDs and counts only.
- Call graphs are node-array + `[callerIdx, calleeIdx]` pairs, so each ID string appears once.

## Security posture

Local-first: the serve path makes zero outbound connections and never writes outside
`.code-graph/`. There is no telemetry.
