# Corporate Maven driver downloads

Use **DBA → Settings → Driver downloads** when public Maven Central is blocked
and your installed Maven already works with an internal mirror.

## Configuration

- **Download method:** Embedded Maven (default, direct public Central) or Installed Maven.
  Installed mode invokes the local Maven application for **both version metadata and JARs**.
  It never falls back to the embedded downloader after a failure.
- **Maven executable:** optional full path to mvn/mvn.cmd; blank searches PATH.
  Use **Browse** to select the launcher file in Maven's **bin** directory:
  **mvn.cmd** on Windows, **mvn** on macOS/Linux (not the installation folder).
  Browsing does not run Maven or download anything; Apply persists the selected path.
  Use Maven 3.9.x with Java 25. Installed Maven is an additional prerequisite only for
  this mode; the application's embedded mode still needs no external Maven.
- **Maven settings.xml:** optional complete local filename, with Browse. Blank leaves Maven
  to merge its global settings with the normal user settings:
  Windows `%USERPROFILE%\.m2\settings.xml`; macOS/Linux `~/.m2/settings.xml`.
  Mirror rules, server credentials, proxies, active profiles, offline mode and the local
  artifact repository are handled by Maven, not reconstructed by the application.
- **CA certificate PEM:** optional complete local filename, with Browse. Supply public
  X.509 certificates, not private keys. The bundle is limited to 1 MiB. A temporary
  PKCS12 trust store combines these certificates with the selected Maven Java runtime's
  standard CA roots. It is deleted when the operation finishes; original PEM/settings
  files and system trust stores are never modified.
- **Disable TLS certificate verification:** explicit, **off by default**, installed mode only.
  The warning explains that certificate trust, hostname and certificate-date checks are
  bypassed for the Maven download process. The optional PEM is ignored while this is on.
  Maven uses its Wagon transport for the bypass; mirror routing, authentication and
  strict artifact-checksum checks remain enabled. Intercepted downloads can install
  executable code: use only a trusted network. Prefer a corporate CA PEM when available.

Settings apply only when **Apply** is clicked. Cancel discards the dialog changes.
They persist under `driverDownloads` in **`<dba-dir>/settings.json`**, normally
`%USERPROFILE%\.code-graph\dba\settings.json` on Windows or
`~/.code-graph/dba/settings.json` on macOS/Linux. Other sections in that JSON are preserved.
Only mode, executable/path preferences and the explicit TLS choice are stored—not Maven
passwords, tokens, XML contents, or certificate contents. They are application settings,
not per-connection profile properties or browser storage.

Blank override fields remain blank in the JSON so future Maven user-default changes are
honored. Saved connections stay pinned. Changing preferences affects subsequent downloads;
an already running download keeps its original configuration. Missing mounted files fail
the affected operation without preventing the application from starting.

Native Browse dialogs for JARs, keys, Maven launchers, settings and PEMs allow **90 seconds**, independent of
query timeouts. No desktop: enter the local server path manually; executable/XML/PEM/key uploads are
not offered. Browsing happens on the machine running code-graph.

## Startup overrides

Optional explicit flags override the saved download settings for that run as a complete
configuration; unspecified download fields take their defaults. They do not overwrite
the JSON until the user clicks Apply in the UI. Omit these flags to restore saved settings.
Raw HTTP/stdio launchers also require `--dba`; cgraph enables DBA by default.

```powershell
# Use Maven's normal user-default settings.xml:
cgraph --dba-driver-download maven

# Explicit executable/settings/CA overrides:
cgraph --dba-driver-download maven --dba-maven-command "C:\Tools\maven\bin\mvn.cmd" --dba-maven-settings "C:\Company\settings.xml" --dba-maven-cert "C:\Company\cert.pem"

# UNSAFE, explicit opt-in for driver downloads only:
cgraph --dba-driver-download maven --dba-maven-insecure-tls true
```

The same flags work on macOS/Linux with native paths and mvn. Runtime choices are separate
from installer `-MavenSettings`, `-CertPem`, and installer-only TLS options.
No JDBC/database TLS settings, browser security, MCP permissions or application-wide trust
configuration are changed.

## Execution and troubleshooting

The application creates an owned, temporary minimal Maven project and bundles a tiny local
Maven core extension for the request. It runs Maven in batch mode against that project, not
against an onboarded repository. No downloaded Maven plugin is needed for the bridge.
The extension uses Maven's effective repository session; it does not fetch external JARs
directly from the application. Maven itself remains an HTTP(S) client of your configured
mirror—your settings must route the required repositories there.

Maven uses its normal local cache (including any settings override). Completed driver bundles
are copied atomically into `<dba-dir>/drivers`, with exact dependencies and SHA-256 hashes.
Checksums are enforced during resolution, and existing bundle hashes are checked on reuse.
Limits remain 64 JARs / 512 MiB per driver bundle. Version selection filters prereleases;
the version check downloads metadata only, not driver JARs.

The subprocess inherits JAVA_HOME, MAVEN_OPTS and JAVA_TOOL_OPTIONS for your Maven/JVM setup.
MAVEN_ARGS is removed to prevent inherited goals/debug mode; settings and JVM properties
remain the supported configuration channels. This bridge is compiled for Java 25.
Maven memory/native overhead is separate from the application's DBA accounting allowance.
Jobs share the existing admission and query/setup deadline (30 seconds by default, up to
300 via DBA RAM settings). Cancellation terminates the owned Maven process tree; no automatic
retry occurs. No credentials are passed as command-line arguments by code-graph.

Failed version checks retain verified cached alternatives. The driver-choice dialog shows
sanitized failure details and **Retry version check**. Installation failures open a detail
dialog with **Retry download**, leaving the connection draft intact. Messages include
exception causes/Maven output, exit status where available, and corrective guidance for:

- certificate / PKIX failures;
- repository or proxy authentication failures;
- missing artifacts, metadata, POMs or dependencies;
- checksum failures;
- missing Maven / incompatible Java;
- timeouts and unreachable mirrors.

Maven offline mode reports latest-version status as unavailable, even if old metadata is
cached. A verified installed bundle remains usable; the user must choose it explicitly.

Diagnostics are bounded, rendered as plain text, stripped of credential URL components,
authorization headers, secret-looking assignments and known settings secret values.
They are not written to general application logs or retained as raw subprocess log files.
Third-party Maven extensions can emit unexpected content: review diagnostics before sharing.
JDBC connection-test exception redaction is unchanged.

If Maven works in a terminal but not here, compare the Maven executable, JAVA_HOME,
the OS account running code-graph, effective settings paths, global Maven configuration,
mirror IDs/credentials, and the CA trust configured for that Java runtime.

## Reproducible validation

```powershell
mvn -pl code-graph-dba -am test "-Dtest=DriverDownloadTest,ExternalMavenTest,ConnectionSetupTest,DbaTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Ddba.maven.integration=true" "-Ddba.maven.command=C:\Tools\maven\bin\mvn.cmd"
$env:DBA_BROWSER_SUITE = 'driver-downloads'
.\code-graph-dba\test-browser.ps1 -NodeModules 'path\to\node_modules'
```

The real-Maven fixtures serve synthetic artifacts from disposable loopback repositories,
including authenticated and self-signed HTTPS mirrors. They do not need a live corporate
mirror, external drivers, database containers or Docker images. Browser checks mock native
file selection so automated tests do not open a chooser on the user's desktop.
Native macOS/Linux desktop selection and a real corporate proxy remain environment-specific
verification; the synthetic checks do not certify your company's Maven configuration.

### Validation record — 2026-09-21

- Windows, JDK 25, installed Maven 3.9.11: all 10 focused configuration/download tests
  passed, including authenticated mirrors, runtime dependencies, cached/offline behavior,
  self-signed HTTPS, PEM trust, explicit TLS bypass, checksum rejection, cancellation,
  redaction, persistence, CSRF, and the independent 90-second chooser deadline.
- Full Maven reactor: 864 tests passed, 65 optional/environment-dependent tests skipped,
  no failures. Skipped vendor/live-environment gates are not claimed as verified.
- All 21 DBA browser suites passed, including settings persistence, Browse integration,
  sanitized failure details/retry, draft preservation, toolbar, grid, editor and approval
  regressions. Native chooser windows were mocked in browser tests.
- JavaScript syntax checks and packaged launcher/resource checks passed. The installed
  Maven core dependency is not included in the application's runtime libraries.

Validation used isolated builds and test servers; it did not restart the user's server.
