# Scheduled jobs in the DBA browser

Expand **Scheduled Jobs** in the connection, database, or schema tree to load jobs visible to that connection's database account. Expand a job for a compact summary; double-click it or press Enter for Properties. Discovery probes scheduler metadata, not job definitions. Job definitions and available recent runs are fetched only when expanded or opened. Refresh repeats detection, so installing an extension or granting access does not require restarting the app.

Use **New** on the folder's context menu to create a draft. Change the name, schedule, command and **Enabled** fields where offered. **Save** opens the existing SQL review dialog; **Revert** restores the loaded definition. **Delete** has its own SQL confirmation. Creation, editing and toggles use the same bounded, cancellable browser jobs and conflict checks as other object editors. New form-created jobs default to disabled. Enabling a due schedule can start work immediately. Browsing never invokes the stored command or enables a scheduler service.

Advanced properties and providers without a form use the **DDL** page's native SQL editor and the same review/apply flow. Native SQL can have immediate effects; review the actual command. Existing multi-step jobs, shared schedules, task graphs, definers and execution identities are retained rather than reconstructed as a single SQL string. Runtime status and history are excluded from configuration conflict checks where their catalogs distinguish them. Failed/partial saves retain the draft and require reconciliation when their outcome is uncertain.

## Provider coverage

Selection uses actual JDBC database metadata (including PostgreSQL-compatible server version detection), even for Custom connections. Db2 LUW, IBM i and z/OS are separate providers. Availability is probed using the saved connection's credentials, database, network and TLS configuration. Permission failures remain visible and are not cached as unsupported. Missing optional catalogs suppress their node. An installed but disabled scheduler is shown with an explanation; this browser does not start scheduler-wide services.

| Templates / engine | Catalog and location | Management in this version |
| --- | --- | --- |
| PostgreSQL, Greenplum, YugabyteDB | `pg_cron`, database containing the extension | Named-job create; cron, command, execution database and active state edit; unschedule. Function availability and EXECUTE grants checked. Requires a compatible installed extension. |
| MySQL, MariaDB | Event Scheduler, database | Create, recurring/one-time schedule, command, comment, enable/disable, delete. Existing definer and completion behavior preserved. Event time zone is explicit. |
| Oracle | Scheduler and legacy DBMS_JOB, schema | Scheduler creates direct PL/SQL/procedure jobs and edits action/calendar/comment/state. Program/chain jobs retain their native structure. Legacy jobs edit body/interval/broken state; creation uses native SUBMIT. Both offer delete. |
| SQL Server, Azure SQL | SQL Server Agent / Elastic Jobs, connection | Agent creates a disabled single T-SQL step with a daily schedule; edits simple step, description and active state; deletes without removing unused/shared schedules. Multi-step/shared schedule edits use native SQL. Elastic Jobs edits active state/description and deletes; creation/targets/steps use native SQL in the jobs database. Catalogs appear only where available. |
| Snowflake | Tasks, schema | Create, command/schedule/comment edit, suspend/resume and drop. Graph changes use native SQL and may require root-task suspension. |
| SAP HANA | Scheduler Jobs, schema | Create procedure jobs, change cron and enabled state, drop. Other procedure arguments/start/end options use native SQL. |
| Apache Hive | Scheduled Queries, connection | Create, query/Quartz cron edit, enable/disable, drop; current server namespace is read and applied to catalog lookup. Requires Hive 4 scheduling catalogs. |
| CockroachDB | Schedules, connection | Pause/resume and drop; backup/changefeed creation and specialized alterations use native SQL. |
| Informix | `sysadmin:ph_task`, connection | Create TASK, edit command/database/description/time window/frequency/state and delete. Existing sensors, weekday choices, groups and retention are preserved. Advanced properties use native SQL. |
| Altibase | `SYSTEM_.SYS_JOBS_`, connection | Enable/disable existing jobs (6.5.1+), drop; creation and schedule changes use native SQL. |
| IBM Db2 LUW | Administrative Task Scheduler, connection | Inspect definition/history; remove; create/update through native `SYSPROC.ADMIN_TASK_*` calls. `DB2_ATS_ENABLE` and SYSTOOLSPACE prerequisites remain server-owned. |
| IBM Db2 for i | `QSYS2.SCHEDULED_JOB_INFO`, connection | Inspect; reviewed native CL management via `QSYS2.QCMDEXC` on DDL. The SQL service is read-only. |
| IBM Db2 z/OS | `DSNADM.ADMIN_TASK_LIST()`, connection | Inspect; native z/OS administrative-task calls on DDL, including their OUT parameters and WLM prerequisites. No LUW calls are substituted. |
| ClickHouse | `system.view_refreshes`, connection/current database | Inspect refresh status; native refreshable-view CREATE/ALTER/SYSTEM commands on DDL. |
| StarRocks | `information_schema.tasks`, connection/current database | Inspect; native SUBMIT/ALTER/DROP TASK on DDL. Server-version restrictions apply (suspend/resume requires newer releases). |
| Neo4j | APOC background jobs, connection | Detect `apoc.periodic.list`; inspect job names/rates/status; Delete cancels the selected job. Native Cypher on DDL creates/replaces jobs. APOC does not expose original query text or persistent/restartable definitions; repeat may start immediately. |
| BigQuery | Separate-service explanation | Scheduled queries require BigQuery Data Transfer Service credentials/API; no JDBC job management is claimed. |
| Amazon Redshift | Separate-service explanation | Scheduled queries require EventBridge/Redshift Data API access. |
| Databricks | Separate-service explanation | Job schedules require the workspace Jobs API, separate from SQL warehouse JDBC. |
| MongoDB SQL Interface | Separate-service explanation | Atlas scheduled triggers are outside the SQL interface. |
| Calcite, Cosmos Cassandra, Cassandra, CSV, Elasticsearch, Exasol, Firebird, JSON, DuckDB, H2, HSQLDB, OpenSearch, PrestoDB, Redis Calcite, SQLite, Teradata, Trino | No portable scheduler catalog in configured transport | No invented scheduler node. This does not claim that separate orchestrators or vendor management services do not exist. |
| MongoDB native, Redis native | Existing native transport | No scheduler integration in these transports. Atlas services and external Redis task frameworks require separate integrations. |
| Custom | Actual engine detection | Uses the matching provider above; unrecognized engines have no scheduler adapter. |

This feature does not provision external scheduling services, install extensions, grant privileges, add authentication, or broaden MCP/agent permissions. `pg_cron` jobs live in its control database, and their execution database is shown separately. Other PostgreSQL scheduling extensions such as pgAgent are not included. Database accounts control which definitions/history can be read or modified.

## Limits and recovery

Lists page at 200 jobs, with at most 2,000 preceding entries; metadata reads retain the existing 256-column/16,384-cell limits, an 8 KiB text-value limit and at most 1 MiB per editor snapshot. Oversized definitions fail explicitly rather than being saved partially. Binary owner IDs are retained as bounded hexadecimal values for conflict checking. Recent-run queries retain the newest 25 rows where supported; unavailable history is a warning, not a failure to open the definition. Driver cancellation, timeout, retained-result and browser-session bounds remain in effect. No query jobs or schedules are restored/executed merely by reopening a workspace.

Native APIs can commit each command separately; their review includes a partial-commit warning. Delete on a different database still requires a saved connection targeting that database, matching existing destructive object actions. Runtime status from a scheduler is not evidence that its service is running or that a future invocation will succeed.

## Verification

`ScheduledJobsTest` checks explicit template coverage, distinct Db2 variants, fixed/bound catalog queries, permission recovery, lazy discovery, pagination, binary ownership, namespace targeting, ambiguity and size failures, cancellation, generated commands and unchanged configuration fingerprints after runtime activity. These are adapter contracts, **not live certification of every server**.

Live disposable fixtures cover PostgreSQL 16 with pg_cron, MySQL 8.4 and MariaDB 11.4: absent/installed discovery, create, list/detail, edit, enable/disable, stale-save rejection, delete and confirmation that the stored command never ran. Browser tests cover lazy tree requests, keyboard opening, New, review/Apply, Revert, deletion and desktop/narrow screenshots.

```powershell
mvn -o -pl code-graph-dba -am test -Djava.awt.headless=true
./code-graph-dba/test-scheduled-jobs.ps1 -Browser -NodeModules <directory-containing-playwright>
```

The fixture harness creates uniquely labelled Docker resources, uses loopback ports and temporary profiles, and removes only its own resources. It never opens saved user connections.

## Native references

- [pg_cron](https://github.com/citusdata/pg_cron/blob/main/README.md), [MySQL events](https://dev.mysql.com/doc/refman/8.4/en/create-event.html)
- [SQL Server Agent catalog](https://learn.microsoft.com/en-us/sql/relational-databases/system-tables/dbo-sysjobs-transact-sql), [Azure Elastic Jobs](https://learn.microsoft.com/en-us/azure/azure-sql/database/elastic-jobs-tsql-create-manage)
- [Oracle Scheduler](https://docs.oracle.com/en/database/oracle/oracle-database/19/arpls/DBMS_SCHEDULER.html), [Snowflake tasks](https://docs.snowflake.com/en/sql-reference/sql/create-task)
- [Hive scheduled queries](https://hive.apache.org/docs/latest/language/scheduled-queries/), [HANA scheduler jobs](https://developers.sap.com/tutorials/hana-cloud-automation-scheduling.html)
- [Db2 LUW tasks](https://www.ibm.com/docs/en/db2/11.1?topic=views-admin-task-add-procedure-schedule-new-task), [IBM i schedule](https://www.ibm.com/docs/en/i/7.6.0?topic=services-scheduled-job-info-view), [Db2 z/OS tasks](https://www.ibm.com/docs/en/db2-for-zos/13.0.0?topic=db2-admin-task-update)
- [Informix tasks](https://www.ibm.com/docs/en/informix-servers/12.10.0?topic=tables-ph-task-table), [Altibase jobs](https://manual.altibase.com/7.3/ref/sql/3.-Data-Definition-Language/CREATE-JOB/)
- [ClickHouse refreshable views](https://clickhouse.com/docs/materialized-view/refreshable-materialized-view), [StarRocks tasks](https://docs.starrocks.io/docs/sql-reference/information_schema/tasks/), [Neo4j APOC jobs](https://neo4j.com/docs/apoc/current/overview/apoc.periodic/)
