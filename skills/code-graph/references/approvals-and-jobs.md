# Approvals, jobs and uncertain outcomes

Do not treat MCP safety annotations, templates, a skill, or an accepted request as
authorization. The server decides eligibility using exact targets and the user's
policies. Local transport trust does not grant database access. Reduced headless
mode omits approval-dependent operations; do not bypass it with browser APIs,
direct JDBC, curl, a new identity or another target.

Keep SQL and typed parameter values separate. Inspect an approval's returned
state and ID. Poll `dba_request_status` with the returned approval ID in
`requestId`, not the original idempotency key. The older live-request aliases use
`approvalId`. Do not repeatedly submit the same intent to create extra prompts.

Reusable permissions are exact requests or verified categories within the
displayed scope and lifetime. Session grants end with that logical MCP session;
they do not survive reconnect/reinitialization as a new session. Persistent
trusted-local policies can be shared between local agents. Creation permission
does not grant routine execution or destructive alterations. Migration plans
require their own exact review even when individual SQL has reusable permission.

Poll asynchronous jobs with bounded waits/backoff. Submission and cancellation
acknowledgements are not completion. Retain partial-result/partial-commit
warnings; release completed results when no longer needed. Cancel only jobs
owned by this task. Do not busy-poll, bypass row limits, or silently broaden
queries to obtain complete datasets.

On uncertain commit/connection loss, stop mutation retries. Reconcile actual
metadata/data through authorized reads and report what is known, unknown, and
already committed. Never replay a migration or write merely because its response
was lost. Preserve sanitized database errors without echoing credentials.
