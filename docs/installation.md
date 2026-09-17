# Native installation and `cgraph`

## Design

`install.ps1` (Windows PowerShell 5.1+) and `install.sh` (macOS/Linux, Bash 3+) use a shared,
dependency-free JDK 25 source launcher, `installer/CgraphInstaller.java`. They build the HTTP
server and its Maven reactor dependencies, run their tests unless explicitly skipped, and use
`jpackage --type app-image` to produce a native executable plus a private Java runtime.

The installation includes `code-graph-server.jar`, its runtime `lib` directory, and Java.
It is not a single self-contained JAR, nor an MSI/DMG/DEB installer. Preserving separate runtime
JARs also preserves JDBC/service-loader and native grammar resources. Installed users need
neither a system JDK nor Maven, Git, Node.js or npm to run `cgraph`. No npm package is registered;
the native launcher is registered directly on the user's PATH.

Package on each target OS/architecture. The image includes desktop/JDBC Java modules and service
providers; this favors compatibility over minimum download size. Signing/notarization and
published prebuilt downloads are separate release tasks, not performed by these scripts.
See [JDK 25 packaging overview](https://docs.oracle.com/en/java/javase/25/jpackage/packaging-overview.html).

## Quick start

Download/review the installer script from a trusted published revision, then run it. Do not pipe
an unreviewed remote script into a shell. By default the script clones
`https://github.com/doindev/code-graph.git`, branch `main`, into a unique temporary directory.
Use `-Repository`/`--repository` and `-Ref`/`--ref` for another trusted repository, branch or tag.
Only run source you trust: Maven builds execute source-controlled plugins and tests.

```powershell
# Windows: prerequisite check only (no install prompts or PATH changes)
powershell -NoProfile -ExecutionPolicy Bypass -File .\install.ps1 -Check

# Clone and install a published revision containing the installer
powershell -NoProfile -ExecutionPolicy Bypass -File .\install.ps1

# Or build this working copy, including unpublished changes, without modifying it
powershell -NoProfile -ExecutionPolicy Bypass -File .\install.ps1 -SourceDir .
```

```bash
# macOS/Linux
bash ./install.sh --check
bash ./install.sh
# Or build this working copy, including unpublished changes
bash ./install.sh --source-dir "$PWD"
```

An older remote ref without `installer/CgraphInstaller.java` fails with an actionable message.
The installer uses a second isolated source snapshot, excluding generated `target` directories,
Git internals and node_modules. It never stops an existing server, rebuilds its loaded JARs,
or automatically onboards its source directory. Source symlinks are rejected rather than
following links outside the checkout. Allow several GiB of free disk for build/cache/image copies.

Default locations:

| OS | Installation | Command |
|---|---|---|
| Windows | `%LOCALAPPDATA%\CodeGraph` | `bin\cgraph.cmd` → native `cgraph.exe` |
| macOS | `~/Library/Application Support/CodeGraph` | `bin/cgraph` → app bundle executable |
| Linux | `${XDG_DATA_HOME:-$HOME/.local/share}/code-graph` | `bin/cgraph` → native executable |

Override with `-InstallDir` / `--install-dir`. Choose a dedicated empty directory outside your
source checkout; unrelated nonempty directories and symbolic-link install paths are refused.
Windows adds only a user PATH entry. macOS/Linux asks before appending a PATH entry to the
recognized shell profile; noninteractive installs print the command instead. Open a new terminal
(or use the printed full path). `-NoPath` / `--no-path` disables PATH registration on every OS.

## Requirements and missing-software handling

The **build machine** requires Git, Maven 3.9+ (3.x), and a full JDK 25 containing `java`, `javac`,
`jlink`, and `jpackage`. Set `CGRAPH_JAVA_HOME` or `JAVA_HOME` to choose the JDK.
Linux also needs the usual native system libraries for Java desktop support when desktop approvals
are enabled; the Java image does not bundle the OS display server or graphics libraries.

The scripts identify missing/incompatible tools, offer supported package-manager commands **only
after confirmation**, and recheck before cloning. They do not install a package manager, change
system security policy, accept vendor licences automatically, or perform unattended elevation.

- Windows: existing winget can install Git and Temurin 25; existing Scoop can install Maven.
- macOS: existing Homebrew can install Git, Maven, and Temurin 25.
- Linux: existing apt/dnf can install Git/Maven with explicit sudo confirmation. Distro packages
  can be too old; version checks still apply. Install a full JDK 25 from your approved vendor.
- If no supported manager is available, or installation fails, follow the printed official
  installation links, open a new terminal and rerun. No clone/build proceeds with missing tools.

Use `-NonInteractive` / `--non-interactive` to fail instead of prompting for missing prerequisites
or proxy passwords. This is not an unattended agreement to install additional software.
`-Check` / `--check` checks versions only, not network reachability, proxy authentication or certificates.

## Proxy-aware installation

Git and Maven have different proxy configuration mechanisms. The bootstrap applies an explicit
`-Proxy` / `--proxy`, or falls back to `HTTPS_PROXY`, then `HTTP_PROXY` (also lowercase on Unix).
Use a concrete `http://host:port` or `https://host:port`, not a PAC/WPAD URL. Operating-system
automatic proxy discovery is not implemented. Corporate NTLM/Kerberos/PAC deployments may need
an approved local forwarding proxy or separately configured Git/Maven transports.

```powershell
.\install.ps1 -SourceDir . -Proxy 'http://proxy.example:8080' `
  -NoProxy 'localhost,127.0.0.1,.internal.example'
```

```bash
bash ./install.sh --source-dir "$PWD" --proxy http://proxy.example:8080 \
  --no-proxy 'localhost,127.0.0.1,.internal.example'
```

`NO_PROXY` / `--no-proxy` / `-NoProxy` supplies comma-separated bypass hosts. Generated Maven
settings translate these to Maven's pipe-separated wildcard syntax; `.example` includes both
the domain and its subdomains. CIDR ranges require an explicit Maven settings file rather than
an inaccurate translation. See [Maven proxy configuration](https://maven.apache.org/guides/mini/guide-proxies.html).

### Authentication

Use `-ProxyUser 'DOMAIN\name'` / `--proxy-user 'DOMAIN\name'` for a hidden password prompt.
For noninteractive use, inject `CGRAPH_PROXY_USER` and `CGRAPH_PROXY_PASSWORD` into the process
environment through your secret manager. There is deliberately no password command-line argument.
Do not put credentials in proxy/repository URLs or in shell history.

Git receives transient process configuration, not a global Git config change. Temporary Maven
settings refer to `${env.CGRAPH_PROXY_USER}` and `${env.CGRAPH_PROXY_PASSWORD}` rather than
embedding the values. The installed image, command shim, and install manifest contain none of
these proxy settings. PowerShell restores modified process variables afterward; Bash runs as a
child shell. Environment values are still accessible to privileged local processes and build
children: use a trusted machine/build, and do not enable shell tracing, Maven debug logging or
Git HTTP tracing when handling credentials. Third-party tools control their own diagnostics.

### Existing Maven settings, mirrors and trust

Pass an existing settings file when your organization supplies proxies, mirrors, server credentials,
or repository policy:

```powershell
.\install.ps1 -SourceDir . -Proxy 'http://proxy.example:8080' `
  -MavenSettings "$HOME\.m2\settings.xml" -GitCaFile 'C:\Company\root-ca.pem'
```

```bash
bash ./install.sh --source-dir "$PWD" --proxy http://proxy.example:8080 \
  --maven-settings "$HOME/.m2/settings.xml" --git-ca-file /approved/company-ca.pem
```

The explicit settings file is used unchanged and takes precedence **for Maven**; configure its
proxy too. `--proxy` still configures Git. If an automatic proxy was requested and a default
`~/.m2/settings.xml` already exists, installation stops and asks you to pass/configure it explicitly
instead of silently replacing its mirrors/credentials. Without a proxy or explicit settings,
Maven's normal user/global settings remain in effect.
See [Maven settings precedence](https://maven.apache.org/settings.html).

`-GitCaFile` / `--git-ca-file` configures Git's PEM CA bundle; it does **not** configure Java's
truststore. For TLS-intercepting proxies, use an organization-approved Java truststore and Maven's
`MAVEN_OPTS` JVM options (`-Djavax.net.ssl.trustStore=...`), or your approved JDK trust configuration.
Never disable TLS verification. Package managers and the initial download of this installer may
require their own proxy/CA setup, particularly across `sudo`. The scripts do not promise to make
winget/Homebrew/apt/dnf work through every enterprise authentication system.

Failure guidance distinguishes prerequisite failures from clone/build failures. Authentication
failures, certificate/PKIX errors, unreachable hosts and wrong repository refs must be corrected
before rerunning; the installer does not automatically retry with weakened TLS or different proxies.
Failed work directories are retained with their exact paths for diagnosis. A failed build never
switches the active `cgraph` launcher to the incomplete release.

Installer proxy options apply to installation, **not** later JDBC driver downloads or database
connections. Configure runtime network access separately; installer secrets are not persisted.

## Runtime defaults and overrides

```text
cgraph
cgraph --port 3001 --viz 8138 --graph-memory 512m
cgraph --root "path to project"
cgraph --dba-approval-mode auto
cgraph --no-ui --dba-approval-mode none
cgraph --no-dba --no-admin
cgraph --print-config
cgraph --version
cgraph --help
```

The friendly launcher supplies `--port 3000 --viz 8137 --viz-admin --dba
--dba-approval-mode desktop --graph-storage hybrid --graph-memory 1g` unless overridden.
It uses the existing local listeners. Graph: `http://localhost:8137/`; DBA:
`http://localhost:8137/dba`; MCP: `http://localhost:3000/mcp`.

The budget accounts for shared graph/cache residency, not total process RAM or JVM heap;
DBA has its separate allowance. Other server arguments pass through. `--no-defaults` opts
out of launcher defaults and uses the legacy HTTP entry-point behavior. Existing direct
`HttpMain` and stdio entry points are unchanged. Clear `CODE_GRAPH_ROOT` if you want an empty
workspace and previously configured it in your environment.

Desktop approvals require an interactive graphical session and fail closed if unavailable.
On a headless host explicitly choose `--dba-approval-mode none` (reduced safe tools), or
browser/auto mode as appropriate. No background service, login autostart or administrator
elevation is configured. Keep the console open and use Ctrl+C to stop. Port conflicts are errors;
installation never kills a process occupying a desired port.

`JAVA_TOOL_OPTIONS` can supply runtime JVM options, including an explicit heap cap or a
`-Djava.io.tmpdir=...` paging location. It is distinct from `--graph-memory`. Quote paths with
spaces appropriately and never store credentials in launcher scripts. User DBA profiles/vault
records remain outside the application installation; see [DBA configuration](dba.md).

## Updates, distribution and removal

Rerun the installer with the desired source/ref. It builds and verifies a new versioned release
before atomically switching the managed command. Existing processes continue using their old
release; stop/restart them manually to use the update. Old releases are retained for recovery;
remove an old release only after confirming no process is using it. User configuration is not
migrated or removed by the installer. Never overwrite an unrelated `cgraph` shim.

`-BuildOnly` / `--build-only` produces the versioned app image without a command shim or PATH change.
Archive the **entire** app-image directory, preserving executable permissions and symlinks. Do not
distribute only its EXE or JAR. Build separate images for each supported OS/architecture and test
them there. A macOS image is unsigned unless a release signing pipeline is added; do not tell
users to bypass their organization's Gatekeeper/security policy.

For removal, stop the installed process, remove its exact user PATH entry, and remove only the
dedicated install directory bearing `.cgraph-install`. Preserve DBA profiles, vault entries,
projects, Maven caches and unrelated data unless separately choosing to remove them. No broad
recursive uninstall command is supplied. Successful temporary builds/clones are ownership-checked
before cleanup; `-KeepBuild` / `--keep-build` retains them intentionally.

## Reproducible validation

From the repository, with JDK 25:

```text
javac -d target/installer-tests installer/CgraphInstaller.java installer/CgraphInstallerTest.java installer/NativeSmokeTest.java installer/ProxySmokeTest.java
java -cp target/installer-tests CgraphInstallerTest
java -cp target/installer-tests ProxySmokeTest PATH-TO-GIT PATH-TO-MAVEN
```

```powershell
# Full build/test + packaging, isolated installation, no PATH changes
powershell -NoProfile -ExecutionPolicy Bypass -File installer/Test-Bootstrap.ps1
.\install.ps1 -SourceDir . -InstallDir "$env:TEMP\cgraph-manual-test" -NoPath -NonInteractive
# Use the exact native EXE path printed by installation, not the .cmd wrapper:
java -cp target/installer-tests NativeSmokeTest 'PATH-TO-cgraph.exe' desktop
```

```bash
bash -n install.sh
bash installer/test-bootstrap.sh
bash ./install.sh --source-dir "$PWD" --install-dir /tmp/cgraph-manual-test --no-path --non-interactive
java -cp target/installer-tests NativeSmokeTest /path/to/native/cgraph none
```

`NativeSmokeTest` uses ephemeral ports and disposable profile/home/temp directories, verifies
Graph/DBA/MCP, hybrid 1 GiB/admin defaults and zero projects, then stops only its own child process.
It deliberately supplies an invalid system JAVA_HOME to verify the bundled runtime is used.
`ProxySmokeTest` exercises real Git/Maven HTTPS requests through a local Basic-auth HTTP proxy,
which always rejects authenticated requests and never forwards anything externally. It verifies
authentication, environment-secret interpolation and failure behavior without pulling packages.

Validation status (2026-09-17): Windows/PowerShell 5.1 prerequisite checks, complete HTTP-runtime
reactor test/package, native image creation, bundled-runtime Graph/DBA/MCP smoke with desktop
approval mode, and local Git/Maven Basic-auth proxy checks passed. Installer unit
checks cover paths with spaces, proxy validation/redaction, bypass mapping, settings preservation,
safe replacement and cleanup. Bash syntax is checked on Git Bash; **native macOS/Linux builds,
PATH registration, package-manager installs, enterprise proxy authentication/TLS and signing remain
unverified** on actual environments. Conditional DBA integration/desktop tests are not made passed
merely by a successful headless Maven build. `--skip-tests` is explicit and not the default.
