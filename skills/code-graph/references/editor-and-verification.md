# Editor collaboration and verification

Use editor tools only if advertised for an eligible browser workspace. Pairing
must be initiated/revoked by the user and bound to this MCP session. It does not
grant SQL permission. Inspect document ID, revision and fixed connection context
before editing; submit the expected revision. On conflict, reread and reconcile
without overwriting unrelated unsaved text. Do not execute SQL, save a local file,
or retarget the connection as an implicit consequence of an editor edit.

Ask the user to open **Settings → Pair editor** in the DBA toolbar and provide the
short-lived code. Call `dba_pair_editor` only in the same logical MCP session that
will edit. Then list documents to obtain `workspaceRevision`; creation requires
that revision, while edits require the exact document revision and a bounded
UTF-16 start/end range. A conflict requires rereading. Pairing events cause the
browser to resynchronize and display an agent-change notice; they are bounded and
acknowledged. Closing the session or user revocation ends access.

After code/schema changes, verify current source/catalog generations. Use
`validate_database_contracts` and `find_affected_tests` when available. Report
mapping confidence, unresolved dynamic SQL/custom naming/reflection, and actual
coverage. Candidate tests are recommendations; run tests only through the agent's
normal execution tools when authorized. Absence of a mapping is not proof of
non-use. Initially verified ORM adapters cover Java/JPA/MyBatis and
JavaScript/TypeScript/Prisma/TypeORM only when that server reports them supported.

Use estimated Explain, then plan analysis/comparison when supported. Retain raw
vendor evidence and equivalent target/query context. Do not automatically execute
EXPLAIN ANALYZE, compare unrelated vendor cost units, or describe every scan as a
regression. Distinguish observed results, heuristics, and untested environments.
