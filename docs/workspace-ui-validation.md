# Shared workspace UI validation

Validated on Windows with JDK 25, Maven 3.9.11, Node 22 and headless Microsoft Edge.

- Full 35-module Maven reactor/package: 741 tests discovered, 699 passed, 42 conditional
  skips, no failures or errors. Conditional desktop/vendor/remote-service tests are not
  claimed as newly verified by this UI change.
- JavaScript syntax checks and three settings normalization/search tests passed.
- Root browser fixture passed empty, populated and read-only workspaces; DBA-disabled and
  stdio states; searchable settings and clear control; draft retention and Cancel; semantic
  unit equivalence; combined Apply; invalid input; rejected and ambiguous writes; reload
  recovery; keyboard tree navigation; pointer/keyboard pane resizing; focus restoration;
  explicit modal dismissal; and narrow layout. No project-activity or onboarding writes
  are caused by viewing settings.
- All 17 existing DBA browser suite groups passed, including toolbar, tree, Script/Grid,
  query builder, table/object designers, approvals, editor pairing and session recovery.
- HTTP tests cover combined settings against real hybrid storage, ensuring a rejected
  memory budget does not publish the requested TTL. Existing single-field requests and
  read-only rejection remain covered.
- Desktop and narrow screenshots were visually inspected; shared toolbar height, brand
  colors and typography were also checked by browser assertions.

Builds and database/browser fixtures ran in an isolated source copy. The user's running
server, onboarded roster, saved connections and existing build artifacts were not replaced.
Optional store integration tests reused pre-existing Docker images; their containers were
removed by the test harness. No new images were pulled and existing images were preserved.
No commit, push, or application restart was performed.

Reproduction commands are in the [visualization guide](guides/visualization.md#ui-regression-checks).
Run the full reactor with `mvn -B package`; prepare DBA browser dependencies with
`mvn -pl code-graph-dba dependency:copy-dependencies -DincludeScope=test -DoutputDirectory=target/test-lib`,
then run `code-graph-dba/test-browser.ps1` with Playwright available through `NODE_PATH`
or its `-NodeModules` parameter. Use isolated fixtures, not the user's application instance.

Interactive macOS/Linux browser and native desktop behavior was not exercised in this run.
