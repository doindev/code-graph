# Configuration

One file at the repo root: `code-graph.json` (canonical — Java-style comments and trailing
commas allowed) **or** `code-graph.yaml`. Having both is an error: one source of truth.
Unknown properties fail fast with the offending name, so typos never silently no-op.

Precedence: CLI flags > `CODE_GRAPH_*` env vars > file > built-in defaults.
Env overrides: `CODE_GRAPH_GATING_THRESHOLD`, `CODE_GRAPH_LIMITS_MAX_RESULTS`,
`CODE_GRAPH_LIMITS_MAX_RESPONSE_BYTES`.

## Full reference

```jsonc
{
  // which files are part of the indexed source tree (repo-relative globs)
  "paths": {
    "include": ["**"],                      // default: everything with a supported extension
    "exclude": ["**/generated/**"]          // .gitignore files are also honored
  },

  // globs identifying test files — feeds the blast score's `untested` factor
  "tests": { "globs": ["**/src/test/**", "**/*.test.ts", "**/test_*.py"] },

  // token-frugality caps enforced on every tool response
  "limits": { "maxResults": 50, "maxResponseBytes": 32768 },

  // blast-score weights (sum to 1) and normalization reference constants:
  // reach saturates at reachRef transitive dependents, fanin at faninRef direct callers
  "scoring": {
    "weights": { "reach": 0.40, "fanin": 0.25, "spread": 0.20, "lang": 0.05, "untested": 0.10 },
    "reachRef": 1000,
    "faninRef": 100
  },

  // risk gating: scores at/above threshold attach a mandatory risk report (MCP)
  // and fail the CI gate
  "gating": { "threshold": 70, "attachRiskReport": true, "failCiOn": ["blast", "drift"] },

  // dead-code tuning: entry points are never reported; excluded globs are skipped
  "deadCode": {
    "entryPoints": ["**/Main.java", "**/*Application.java", "**/cli/**"],
    "exclude": ["**/api/**"]                // e.g. a library's public surface
  },

  // per-detector smell tuning (Wave A/B detectors read their thresholds here)
  "smells": {
    "god-class": { "enabled": true, "severity": "warning",
                   "thresholds": { "methodCount": 25, "fieldCount": 15, "loc": 500 } }
  },

  // golden blueprint for architectural drift (ArchUnit-lite)
  "architecture": {
    "modules": [                             // first-match-wins path globs
      { "name": "api",    "paths": ["src/main/java/com/acme/api/**"] },
      { "name": "domain", "paths": ["src/main/java/com/acme/domain/**"] },
      { "name": "infra",  "paths": ["src/main/java/com/acme/infra/**"] }
    ],
    "allowedDependencies": {                 // a module absent here may depend on nothing
      "api":   ["domain"],
      "infra": ["domain"]
    },
    "forbidCycles": true,
    "unassigned": "warn"                     // warn | ignore | violation
  }
}
```

YAML users: identical structure in `code-graph.yaml`.

## Defaults worth knowing

- `tests.globs` defaults cover Maven/Gradle Java, Jest/Vitest TS/JS, pytest, Go, xUnit.
- Always-ignored directories (regardless of config): `.git`, `node_modules`, `target`, `build`,
  `dist`, `__pycache__`, `venv`, `.idea`, `.code-graph`, `vendor`, `bin`, `obj`.
- `gating.threshold` defaults to 70 ("high" band ends at 74; "critical" starts at 75).
