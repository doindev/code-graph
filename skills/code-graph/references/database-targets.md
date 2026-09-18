# Select the database deliberately

Use actual MCP schemas, not remembered argument shapes. Start with
`get_workspace_context` or `dba_list_project_databases`; inspect capabilities
and effective permissions only for the relevant target. Do not connect all
profiles or scan every environment merely to orient yourself.

A project binding fixes application, environment (`local`, `dev`, `test`,
`stage`, `prod`), logical role, connection UUID, database and schema. Use its
`bindingId`; do not override its scope. For a standalone database, use the stable
connection UUID and exact name, with explicit database/schema where supported.
No project is mandatory. Never infer a target from the active browser selection.
When several bindings match, resolve the ambiguity before submitting work.

`dba_list_templates` returns recipes, not proof a driver is installed or an
operation is verified. `dba_get_capabilities` distinguishes cached observations
from unknown servers. `live: true`, when advertised and authorized, returns a job
for one target's actual JDBC metadata. A PostgreSQL-compatible product is not
automatically certified for all PostgreSQL administration.

Catalog data is cached, scoped and potentially incomplete. Inspect generation,
scan time, coverage, truncation and warnings. Request `dba_refresh_catalog` only
within authorized scope, then use bounded status waits/polling. Listing and
polling should not be used to prevent idle cleanup. An inaccessible object or
partial inventory cannot prove an object was removed.

Connection creation/removal is application configuration, not Docker lifecycle.
An agent-created connection grants its creator no database permissions. Discover
its stable ID through the creation result/created-connections tool. Driver
installation and secret submission require the advertised review workflow.
Prefer human credential entry: write-only MCP arguments can remain in the host's
task transcript. Never ask the server to return credentials or private-key files.
