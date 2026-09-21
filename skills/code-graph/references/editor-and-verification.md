# Editor collaboration and verification

Use editor tools only if advertised. Agents may request pairing, but the user must
approve and select its browser workspace. Pairing binds one tab to this logical MCP session. It does not
grant SQL permission. Inspect document ID, revision and fixed connection context
before editing; submit the expected revision. On conflict, reread and reconcile
without overwriting unrelated unsaved text. Do not execute SQL, save a local file,
or retarget the connection as an implicit consequence of an editor edit.

Prefer `dba_request_editor_access` with a unique `requestId` and concise `purpose`.
The Java prompt offers an existing DBA tab or a new workspace. When multiple tabs
are eligible, the first user acceptance of **Use this /dba instance?** selects
the tab. Without a desktop, a connected browser can request consent directly.
Poll `dba_request_status` with the returned `approvalId` in the same MCP session;
`awaiting_approval` and `awaiting_browser` are not access grants. Start editor
operations only after `paired`. Stop on denial, cancellation, or expiry; do not
keep submitting prompts. Cancel a no-longer-needed request with `dba_cancel_request`.
YOLO does not select a browser or grant editor consent. Browser focusing is best
effort; ask the user to bring their DBA tab forward if needed. Do not enumerate
browser history or use OS automation to bypass tab selection.

On older servers, or as an advanced fallback, ask the user to open **Settings →
Pair editor** and provide the short-lived code. Call `dba_pair_editor` in the same
MCP session that will edit. Never take over an already-paired workspace.
Then list documents to obtain `workspaceRevision`; creation requires
that revision, while edits require the exact document revision and a bounded
UTF-16 start/end range. A conflict requires rereading. Pairing events cause the
browser to resynchronize and display an agent-change notice; they are bounded and
acknowledged. The toolbar's **Agent connected · Disconnect** button revokes access.
Disconnected tabs cannot be edited. Brief refresh/reconnect is allowed for the
same workspace, but prolonged disconnection, MCP-session termination, expiry,
restart, and user revocation end access. Do not silently reconnect to another tab.

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
