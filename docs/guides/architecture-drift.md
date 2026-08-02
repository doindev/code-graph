# Architectural Drift

`compare_architectural_drift` checks the live graph against the golden blueprint in
`code-graph.json → architecture` and reports violations with **stable fingerprints**, so CI can
diff against a baseline and flag only what is NEW.

## Blueprint

```jsonc
"architecture": {
  "modules": [                             // first-match-wins path globs
    { "name": "api",    "paths": ["src/main/java/com/acme/api/**"] },
    { "name": "domain", "paths": ["src/main/java/com/acme/domain/**"] },
    { "name": "infra",  "paths": ["src/main/java/com/acme/infra/**"] }
  ],
  "allowedDependencies": { "api": ["domain"], "infra": ["domain"] },
  "forbidCycles": true,
  "unassigned": "warn"                     // files matching no module glob
}
```

Without a blueprint, files map to their top-level directory and only cycle checks run.

## How it works

1. The symbol graph is projected onto modules: every cross-module CALLS / REFERENCES / IMPORTS /
   EXTENDS / IMPLEMENTS edge aggregates into a module edge with up to 3 witness symbol pairs.
   **Only edges with confidence ≥ 0.8 project** — architecture verdicts never rest on heuristic
   name-matching.
2. **Layer violations**: module edge `A → B` where `B` is not in `allowedDependencies[A]`
   (modules absent from the map may depend on nothing). Fingerprint:
   `layer:<A>-><B>:<witnessQualifiedName>`.
3. **Cycles**: iterative Tarjan SCC over the module graph; every component of size >1 is a
   violation. Fingerprint: `cycle:<members sorted, joined by |>`.
4. **Baseline diff** (CI): fingerprints exclude line numbers and file paths of the witnesses,
   so pure moves don't churn; `new = head − base`, `fixed = base − head`, preexisting are
   counted but suppressed from the report.

## Reading a violation

```json
{ "type": "layer", "rule": "domain -/-> infra",
  "from": "domain", "to": "infra",
  "witnesses": ["java:...#Order.persist/0 -> java:...#JdbcOrders.save/1"],
  "fingerprint": "layer:domain->infra:Order.persist/0" }
```

The witness is the actual symbol edge that breached the boundary — fix that call (or admit the
dependency into `allowedDependencies` deliberately).
