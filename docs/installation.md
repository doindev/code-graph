# Native installation and `cgraph`

## Design

`install.ps1` (Windows PowerShell 5.1+) and `install.sh` (macOS/Linux, Bash 3+) use a shared,
dependency-free JDK 25 source launcher, `installer/CgraphInstaller.java`. They build the HTTP
server and its Maven reactor dependencies, skip tests unless explicitly requested, and use
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

For automatic local-agent database authority, start the installed application with
`cgraph --yolo`; use `cgraph --no-ui --yolo` for a headless instance. This skips
desktop approval defaults but preserves database validation and limits. It is
never enabled by installation or saved configuration. See [the risks and exact
startup contract](yolo.md).

Download/review the installer script from a trusted published revision, then run it. Do not pipe
an unreviewed remote script into a shell. By default the script clones
`https://github.com/doindev/code-graph.git`, branch `main`, into a unique temporary directory.
Use `-Repository`/`--repository` and `-Ref`/`--ref` for another trusted repository, branch or tag.
Only run source you trust: Maven builds execute source-controlled plugins and tests.

**Installation security warning:** unless you supply `-CertPem` / `--cert-pem`, Git cloning and
Maven artifact downloads disable TLS certificate, hostname and certificate-date verification during
installation. This default accommodates corporate self-signed certificates without requiring users
to export/install certificate files. HTTPS is still used, but it cannot
authenticate the download server; intercepted source/dependencies can execute code during the build.
Use only a trusted network and source. This is not a global Git, Java truststore or Maven-settings
change, and it does not disable verification for the installed application's connections/downloads.

```powershell
# Windows: prerequisite check only (no install prompts or PATH changes)
powershell -NoProfile -ExecutionPolicy Bypass -File .\install.ps1 -Check

# Clone and install a published revision containing the installer
powershell -NoProfile -ExecutionPolicy Bypass -File .\install.ps1

# Or build this working copy, including unpublished changes, without modifying it
powershell -NoProfile -ExecutionPolicy Bypass -File .\install.ps1 -SourceDir .

# Opt in to Maven tests (the default installation build skips them)
powershell -NoProfile -ExecutionPolicy Bypass -File .\install.ps1 -SourceDir . -RunTests
```

```bash
# macOS/Linux
bash ./install.sh --check
bash ./install.sh
# Or build this working copy, including unpublished changes
bash ./install.sh --source-dir "$PWD"
bash ./install.sh --source-dir "$PWD" --run-tests
```

An older remote ref without `installer/CgraphInstaller.java` fails with an actionable message.
`-RunTests` / `--run-tests` explicitly enables Maven tests; legacy `-SkipTests` / `--skip-tests`
is still accepted but is now redundant. Supplying both choices is an error. Source-controlled
build plugins still execute when tests are skipped. Installation is not a substitute for release testing.
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

Git receives transient process configuration, not a global Git config change. Installer clones use
`-c http.sslVerify=false -c http.proxySSLVerify=false` by default, plus `-c credential.helper= -c core.askPass=`
and `GIT_TERMINAL_PROMPT=0`, with askpass environment hooks cleared for the clone. There are no
interactive Git credential prompts or credential-helper lookups. For private repositories, use
`-SourceDir` / `--source-dir` with a separately authenticated checkout; do not embed tokens in URLs.
Temporary Maven
settings refer to `${env.CGRAPH_PROXY_USER}` and `${env.CGRAPH_PROXY_PASSWORD}` rather than
embedding the values. The installed image, command shim, and install manifest contain none of
these proxy settings. PowerShell restores modified process variables afterward; Bash runs as a
child shell. Environment values are still accessible to privileged local processes and build
children: use a trusted machine/build, and do not enable shell tracing, Maven debug logging or
Git HTTP tracing when handling credentials. Third-party tools control their own diagnostics.

### Existing Maven settings, mirrors and trust

On Windows the installer automatically uses `%USERPROFILE%\.m2\settings.xml` **if it exists**.
`-MavenSettings` overrides this location. A missing default file is not an error; an explicitly
supplied missing/non-file path is rejected. The chosen file is never edited or replaced.
Use an explicit file for organization-specific proxies, mirrors, credentials or repository policy:

```powershell
.\install.ps1 -SourceDir . -Proxy 'http://proxy.example:8080' `
  -MavenSettings "$env:USERPROFILE\.m2\settings.xml"
```

```bash
bash ./install.sh --source-dir "$PWD" --proxy http://proxy.example:8080 \
  --maven-settings "$HOME/.m2/settings.xml"
```

The explicit settings file is used unchanged and takes precedence **for Maven**; configure its
proxy too. `--proxy` still configures Git. On macOS/Linux, if an automatic proxy was requested and a default
`~/.m2/settings.xml` already exists, installation stops and asks you to pass/configure it explicitly
instead of silently replacing its mirrors/credentials. Without a proxy or explicit settings,
Maven's normal user/global settings remain in effect.
See [Maven settings precedence](https://maven.apache.org/settings.html).

### Optional corporate PEM certificate

No certificate file is required for the default installation. If your IT team supplies one, use:

```powershell
.\install.ps1 -CertPem 'C:\Company\cert.pem'
# Optional settings override and tests are independent:
.\install.ps1 -SourceDir . -CertPem 'C:\Company\cert.pem' -MavenSettings 'C:\Company\settings.xml' -RunTests
```

```bash
bash ./install.sh --cert-pem /path/to/cert.pem
```

The supplied public X.509 PEM bundle (maximum 1 MiB; one or more certificates) **enables** certificate,
hostname and date verification for Git and Maven. Git uses it for server/proxy CA trust, including
the Windows Schannel CA-file setting. Maven combines the PEM with its default JVM trust roots in
a temporary PKCS#12 store under the owned build directory. That store is removed after Maven exits,
including failures; it is not copied into the installed application. The PEM, JDK trust store and
Maven settings remain unchanged. Missing, malformed, expired or private-key files fail rather than
silently falling back to insecure downloads. Do not supply a private key.

`-GitCaFile` / `--git-ca-file` is retained as an alias; it now configures **both** Git and Maven.
Supply only one certificate option. This does not configure prerequisite managers or application TLS.

Without a PEM, Maven uses command-scoped
`-Dmaven.resolver.transport=wagon`, `-Dmaven.wagon.http.ssl.insecure=true`,
`-Dmaven.wagon.http.ssl.allowall=true` and `-Dmaven.wagon.http.ssl.ignore.validity.dates=true`.
Selecting Wagon avoids depending on the native Resolver insecure option that early Maven 3.9
versions lack. These flags are **not** written into `MAVEN_OPTS`, global/user settings, installed
launcher arguments or runtime configuration. Maven repository checksums and package signatures
are not disabled, but checksums from the same intercepted server do not establish authenticity.
See [Maven's transport options](https://maven.apache.org/wagon/wagon-providers/wagon-http/).

**Boundary:** prerequisite managers (winget/Scoop/Homebrew/apt/dnf), the initial download of this
installer, SSH host-key verification and third-party build plugins with their own network stacks
retain their own security settings. There is no portable per-command bypass for all of them.
The installer does not change OS security policy, trust stores or package-signature checks to bypass
those protections. Such failures still need approved prerequisite/proxy setup; their errors remain visible.

Failure guidance distinguishes prerequisite failures from clone/build failures. Git clone errors
include the exit code and captured diagnostics, redacting known proxy passwords, URL userinfo and
authorization headers. Maven stdout/stderr streams directly to the terminal, including build errors.
Do not publish logs without checking for secrets emitted by third-party plugins. Authentication,
unreachable-host and wrong-ref errors are not hidden or retried with different proxies.
Failed work directories are retained with their exact paths for diagnosis. A failed build never
switches the active `cgraph` launcher to the incomplete release.

Installer proxy options apply to installation, **not** later JDBC driver downloads or database
connections. Configure runtime network access separately; installer secrets are not persisted.
For later driver downloads, **DBA → Settings → Driver downloads** supports installed Maven
with default/alternate settings.xml, an optional CA PEM and a separate, off-by-default
TLS-verification bypass. These preferences persist in the application's settings.json.
See [corporate Maven driver downloads](dba-driver-downloads.md).

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
--dba-approval-mode desktop --graph-storage hybrid --graph-memory 1536m` unless overridden.
It uses the existing local listeners. Graph: `http://localhost:8137/`; DBA:
`http://localhost:8137/dba`; MCP: `http://localhost:3000/mcp`.

The budget accounts for shared graph/cache residency, not total process RAM or JVM heap;
the installed launcher defaults to **1.5 GiB** = 1,610,612,736 bytes. `1536m` uses the
existing whole-MiB parser; `1.5g` is not a supported argument. Raw HTTP/stdio entry
points retain their existing 1 GiB default, and explicit `--graph-memory` values
still override the installed launcher default.
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

## Optional MCP connections, then skills

Interactive Windows, macOS/Linux, and JDK installs first ask which **current-user
client connections** to configure. Select one or more names/numbers, `all`, or
`none` (the default). After the application is successfully installed, the installer
adds only missing connections, checks both existing and newly configured connections,
and then offers optional skills **only for those matching code-graph clients**.
Selecting no MCP installations still allows skills for existing matching connections.

This configures clients to connect to one separately running server; it does not
install the client applications, launch extra servers, start cgraph, or grant any
tool approvals. The default endpoint is `http://localhost:3000/mcp`.

| Selection | Client/surface | Current-user MCP file |
|---|---|---|
| `codex` | Codex desktop/CLI/IDE | `~/.codex/config.toml` (`CODEX_HOME` honored) |
| `claude` | Claude Code | `~/.claude.json` (not `.claude/settings.json`) |
| `copilot` | GitHub Copilot CLI | `~/.copilot/mcp-config.json` (`COPILOT_HOME` honored) |
| `copilot-vscode` | GitHub Copilot in VS Code, default profile | Windows `%APPDATA%/Code/User/mcp.json`; macOS `~/Library/Application Support/Code/User/mcp.json`; Linux `${XDG_CONFIG_HOME:-~/.config}/Code/User/mcp.json` |
| `windsurf` | Windsurf | `~/.codeium/windsurf/mcp_config.json` |

The separate Copilot choices avoid assuming its CLI and editor share configuration.
Either matching Copilot connection qualifies for the existing Copilot skill offer;
the skill is copied once. These formats follow the official
[Codex MCP](https://developers.openai.com/codex/mcp),
[Claude Code MCP](https://code.claude.com/docs/en/mcp),
[Copilot CLI MCP](https://docs.github.com/en/copilot/how-tos/copilot-cli/customize-copilot/add-mcp-servers),
[VS Code MCP](https://code.visualstudio.com/docs/agent-customization/mcp-servers), and
[Windsurf MCP](https://docs.windsurf.com/windsurf/cascade/mcp) documentation.

```powershell
# Configure two clients, then install skills for all detected matching clients
.\install.ps1 -SourceDir . -McpClients 'codex,claude' -Skills all
# Unattended: choices must be explicit; neither MCP nor skills is implied
.\install.ps1 -SourceDir . -McpClients all -Skills all -NonInteractive
# Existing connections only; offer skills interactively
.\install.ps1 -SourceDir . -McpClients none
```

```bash
bash ./install.sh --source-dir "$PWD" --mcp-clients codex,claude --skills all
bash ./install.sh --source-dir "$PWD" --mcp-clients all --skills all --non-interactive
bash ./install.sh --source-dir "$PWD" --mcp-clients none
```

Use `-McpUrl` / `--mcp-url` for another local HTTP port, for example
`http://localhost:3100/mcp`. This is the **client URL only**: start the server with
the matching `cgraph --port 3100`. Credentials, query strings, fragments and remote
hosts are rejected. `localhost`, `127.0.0.1`, `[::1]`, and a trailing `/` are treated
as equivalent for duplicate detection when the port matches. No DNS probes,
downloads, connections or approval prompts are triggered by configuration detection.

Without a console or with `--non-interactive`, omitted selections mean none.
`--skills all` now means all **detected matching clients**, not every client.
An explicitly named skill without a detected connection is reported as incomplete.
Check-only never reads/writes client configuration or installs skills. Build-only
does neither and rejects nonempty explicit MCP/skill selections.

### Existing configuration and recovery

- Matching endpoints under any server name are a no-op; disabled entries, tool
  restrictions, headers and other customizations remain unchanged.
  Legacy VS Code `settings.json` → `mcp.servers` entries are checked too.
- The documented Java stdio command containing `io.doindev.codegraph.mcp.Main`
  is also recognized and preserved rather than adding a second HTTP connection.
  Custom wrapper commands cannot be reliably identified; named conflicts require review.
- An existing `code-graph`/`code_graph` entry using another endpoint or transport is
  a conflict, not permission to overwrite it or add a duplicate. Choose the correct
  URL or review the connection in the client before rerunning.
- Claude project-scoped matches are reported without silently promoting them to a
  second global entry. They qualify for an optional skill, which remains conditional
  on MCP availability in each project. Named VS Code profiles, remote/WSL profiles, plugin-managed
  servers, and arbitrary project-level configurations are not enumerated. Review
  those in the client's MCP settings; the installer cannot certify their absence.
- Conventional Codex `[mcp_servers.name]` tables, including quoted names and nested
  options, are recognized. Inline/dotted MCP table layouts are left untouched with
  manual-setup guidance instead of attempting a risky TOML rewrite. Unrelated TOML
  text and comments are retained. VS Code JSONC comments/trailing commas are preserved;
  other client JSON files must be strict JSON.
- With `CLAUDE_CONFIG_DIR` set, automatic Claude registration/detection is skipped
  with instructions to use that instance's `claude mcp add --transport http --scope
  user code-graph URL`. Relocated global-state paths vary across client versions;
  the installer does not guess. The standalone skill helper still honors the
  documented Claude skill-directory override.
- Configuration reads/edits are bounded to 2 MiB. Linked paths, invalid encodings,
  duplicate JSON keys and unsupported layouts fail without overwriting the file.
- Close clients before editing their configuration. Successful edits preserve
  existing text and file permissions, retain an exact adjacent
  `.cgraph-backup-<UUID>` copy, and atomically replace the file. Concurrent installer
  locks and observed client changes stop the edit. An external client can still
  race after the final check; the backup provides recovery. Backups may contain
  existing credentials: keep them private, never commit them, and remove them
  manually after verifying the connection. No backup is made for a no-op.
- If interrupted, a `.cgraph-mcp.lock` may remain. Remove that exact lock only after
  confirming no installer is active. Resolve conflicts per client and rerun; a
  failure for one client does not prevent the other selected clients being configured.
- Optional setup failures return partial-success status (JDK/Bash: exit 2;
  PowerShell bootstrap: reported error/exit 1). The installed application remains
  usable. No existing client configuration, server or skill is removed by `none`.

To configure MCP separately without building/installing the app (JDK 25, no Node):

```text
java installer/McpInstaller.java --clients codex,claude --url http://localhost:3000/mcp
java installer/McpInstaller.java --help
```

After setup, start `cgraph` yourself and refresh/restart the selected clients if
needed. Normal client trust, enterprise restrictions and server/database approval
rules still apply. Installing a connection or skill does not make this machine's
loopback server reachable from cloud-hosted agents.

## Optional global agent skills

The Windows, macOS/Linux, and JDK installers offer the same optional skill selection:
**all**, a comma-separated subset of **codex,copilot,claude,windsurf**, or **none**.
After MCP setup/detection, interactive terminal installs offer only configured
clients, with **none** as the default. Without a console,
or with `-NonInteractive` / `--non-interactive`, skills are skipped unless explicitly
selected. Check-only mode never copies skills. Build-only mode skips the prompt and
rejects a nonempty skill selection.

```powershell
# Windows: install the application and all connections/eligible current-user skills
.\install.ps1 -SourceDir . -McpClients all -Skills all
# Or choose only the clients you use (no prompt needed)
.\install.ps1 -SourceDir . -McpClients 'codex,claude' -Skills 'codex,claude' -NonInteractive
# Explicit opt-out
.\install.ps1 -SourceDir . -Skills none
```

```bash
# macOS / Linux
bash ./install.sh --source-dir "$PWD" --mcp-clients all --skills all
bash ./install.sh --source-dir "$PWD" --mcp-clients codex,claude --skills codex,claude --non-interactive
# The shared JDK installer also accepts --skills with the same values.
```

“Global” means the **current OS user's account**, not every user on the computer.
Run the installer as your normal account, not with sudo/administrator elevation.
The JDK installer needs no Node/npm dependency to copy skills.

| Client | Current-user destination |
|---|---|
| Codex | `~/.agents/skills/code-graph` |
| GitHub Copilot | `~/.copilot/skills/code-graph` |
| Claude Code | `~/.claude/skills/code-graph` |
| Windsurf | `~/.codeium/windsurf/skills/code-graph` |

These paths follow the official [Codex](https://learn.chatgpt.com/docs/build-skills),
[Copilot](https://docs.github.com/en/copilot/how-tos/copilot-cli/customize-copilot/add-skills),
[Claude Code](https://code.claude.com/docs/en/skills), and
[Windsurf](https://docs.windsurf.com/windsurf/cascade/skills) guidance. On Windows `~`
means your user profile directory. Documented absolute overrides are honored:
[`COPILOT_HOME`](https://docs.github.com/en/copilot/reference/copilot-cli-reference/cli-config-dir-reference)
and [`CLAUDE_CONFIG_DIR`](https://code.claude.com/docs/en/claude-directory).
Codex uses the shared `.agents/skills` user directory, independent of `CODEX_HOME`.
Client versions/surfaces control discovery; these locations do not enable skills in
every hosted product. Some clients also discover shared `.agents` skills, so copies
for multiple clients can be redundant; selection is not a permission boundary.

The skill remains **optional guidance**: prefer code-graph only when its MCP server
is available and useful for the task; otherwise use ordinary tools. Installation
of the skill itself does not require a running server, start one, edit MCP configuration, or grant any
tool/database permissions. Cloud-hosted agents still cannot reach your local
loopback endpoint merely by installing a skill.

One maintained source (`skills/code-graph`) and all supporting references are copied.
Identical copies are a no-op. Differing existing copies—including customized references—
prompt **Overwrite this skill? [y/N]** separately for each selected client. The prompt
shows the client and exact destination. Only `y` or `yes` approves replacement;
No, Enter or end-of-input preserves it and reports `skipped-existing`, not failure.
Without an interactive console, or with `-NonInteractive` / `--non-interactive`,
existing differing skills are also kept. Selecting `all` is not overwrite consent.

An approved update replaces the whole maintained folder, including references, and
reports `updated` plus a recoverable backup path. Backups are stored under
`<client configuration directory>/.code-graph-skill-backups/code-graph-<unique>/code-graph`,
outside that client's `skills` directory so the old copy is not discovered as a
second active skill. Custom files remain in the backup, not in the new active copy.
The installer never automatically deletes these backups. Compare/merge customizations
after the update, or restore the saved folder manually with the client closed.
If publication fails, the installer attempts to restore the old folder; if a
concurrent destination prevents restoration, it reports the retained backup path.

Linked destinations/backup directories are refused; changes made while a confirmation
is pending invalidate that approval. Declining one client does not prevent installation
for the others. Skills are copied only after the application installation succeeds;
actual filesystem/configuration errors are reported as partial installation with a
nonzero exit, not as a failed application build. Inspect per-client statuses: some
approved updates may have succeeded even if another optional setup step failed.
Rerunning with `none` does not uninstall previously selected skills.

For an update, compare the entire installed folder with `skills/code-graph`, not
just `SKILL.md`: most workflow details live in the references. A differing copy may
be an older stock release or a customization; the installer asks rather than guessing.

To install skills separately, without rebuilding the application, use the optional
Node.js helper from a checkout. It requires explicit scope and never downloads anything:

```text
node skills/install-skill.mjs --client all --global --dry-run
node skills/install-skill.mjs --client all --global
node skills/install-skill.mjs --client codex,claude --global
node skills/install-skill.mjs --client codex --project PATH
node skills/install-skill.mjs --client all --global --non-interactive
```

The Node helper uses the same confirmation/default/backup behavior. Its `--dry-run`
reports `would-update` with `requiresConfirmation: true` for differing copies and
never prompts, creates backups or writes files. CLI result JSON remains on stdout;
interactive replacement prompts are written to stderr. Without a terminal it skips
differing copies rather than hanging for input or automatically overwriting them.

Project installation remains supported: Codex `.agents/skills`, Copilot
`.github/skills`, Claude `.claude/skills`, Windsurf `.windsurf/skills`.
`--global` and `--project` are mutually exclusive. Restart/reload the relevant
client if necessary to discover installed skills; no code-graph server restart is needed.

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

For removal, use the Windows or macOS/Linux uninstaller below. Successful temporary builds/clones are ownership-checked
before cleanup; `-KeepBuild` / `--keep-build` retains them intentionally.

### Uninstall on Windows, macOS and Linux

Normal installs copy both `uninstall.ps1` and `uninstall.sh` into the installation
directory. They can also be run from this checkout for older installations.
They need no Java, Maven or npm. Custom existing uninstaller scripts are preserved.

```powershell
# Windows: verify without removing anything, then uninstall with confirmation
.\uninstall.ps1 -Check
.\uninstall.ps1
# Custom installation / explicit noninteractive confirmation
.\uninstall.ps1 -InstallDir 'D:\Applications\CodeGraph' -Yes
```

```bash
# macOS and Linux
bash ./uninstall.sh --check
bash ./uninstall.sh
bash ./uninstall.sh --install-dir /path/to/code-graph --yes
```

Without an explicit directory, a copied script detects its marked installation;
otherwise it uses the OS's normal install location above. The scripts verify the
resolved path and `.cgraph-install` marker, reject root/home/unrecognized directories
and unexpected top-level content, and require the installed process to be stopped.
No automatic process killing or elevation occurs. Windows refuses linked items;
POSIX removal does not follow nested symlinks. Check-only and declined confirmation
leave everything untouched; unattended removal requires `-Yes` / `--yes`.

Removal deletes the verified application directory, including its command shim and
retained binary releases. Binaries are recoverable by reinstalling, not by undo.
Windows removes only the exact matching current-user PATH entry. macOS/Linux removes
only the installer's exact marker/PATH block from `.bashrc`, `.zshrc` and `.profile`,
keeping adjacent backups of changed shell profiles. `-KeepPath` / `--keep-path`
leaves PATH untouched. Open a new terminal afterward.

**DBA profiles, OS-vault credentials, indexed source projects, Maven caches, MCP
connections and skills outside the installation are preserved.** Never store
personal data inside the application installation. The uninstaller does not guess
which database credentials or client policies are safe to delete. Disable/remove
the code-graph MCP entry in each client if no longer needed; remove optional skill
copies separately only after checking for personal modifications.

## Reproducible validation

From the repository, with JDK 25:

```text
javac -d target/installer-tests installer/McpConfigDocument.java installer/McpInstaller.java installer/McpInstallerTest.java installer/SkillInstaller.java installer/CgraphInstaller.java installer/CgraphInstallerTest.java installer/SkillInstallerTest.java installer/NativeSmokeTest.java installer/ProxySmokeTest.java
java -cp target/installer-tests CgraphInstallerTest
java -cp target/installer-tests SkillInstallerTest
java -cp target/installer-tests McpInstallerTest
node --test skills/install-skill.test.mjs
node --test installer/uninstall.test.mjs
java -cp target/installer-tests ProxySmokeTest PATH-TO-GIT PATH-TO-MAVEN
# Compile alongside the installer classes, then exercise loopback-only TLS fixtures:
javac -cp target/installer-tests -d target/installer-tests installer/TlsSmokeTest.java
java -cp target/installer-tests TlsSmokeTest PATH-TO-GIT PATH-TO-MAVEN
node --test installer/download-bootstrap.test.mjs installer/skill-bootstrap.test.mjs
```

```powershell
# Full build/test + packaging, isolated installation, no PATH changes
powershell -NoProfile -ExecutionPolicy Bypass -File installer/Test-Bootstrap.ps1
# Optional forwarding tests (Node + JDK 25); only temporary fixtures are used
$env:CGRAPH_JAVA_HOME = 'PATH-TO-JDK-25'
node --test installer/skill-bootstrap.test.mjs
.\install.ps1 -SourceDir . -InstallDir "$env:TEMP\cgraph-manual-test" -NoPath -NonInteractive -RunTests
# Use the exact native EXE path printed by installation, not the .cmd wrapper:
java -cp target/installer-tests NativeSmokeTest 'PATH-TO-cgraph.exe' desktop
# Optional third argument verifies uninstall refuses this running native image:
# java -cp target/installer-tests NativeSmokeTest 'PATH-TO-cgraph.exe' none 'INSTALL-DIRECTORY'
```

```bash
bash -n install.sh
bash installer/test-bootstrap.sh
CGRAPH_JAVA_HOME=/path/to/jdk-25 node --test installer/skill-bootstrap.test.mjs
bash ./install.sh --source-dir "$PWD" --install-dir /tmp/cgraph-manual-test --no-path --non-interactive --run-tests
java -cp target/installer-tests NativeSmokeTest /path/to/native/cgraph none
```

`NativeSmokeTest` uses ephemeral ports and disposable profile/home/temp directories, verifies
Graph/DBA/MCP, hybrid 1.5 GiB/admin defaults and zero projects, then stops only its own child process.
It deliberately supplies an invalid system JAVA_HOME to verify the bundled runtime is used.
`ProxySmokeTest` exercises real Git/Maven HTTPS requests through a local Basic-auth HTTP proxy,
which always rejects authenticated requests and never forwards anything externally. It verifies
authentication, environment-secret interpolation and failure behavior without pulling packages.

`TlsSmokeTest` uses real Git/Maven clients against a loopback-only HTTPS endpoint. It verifies default
certificate rejection, installer bypass of self-signed/expired/wrong-host certificates, and verified
access with an explicitly supplied PEM. The endpoint returns an intentional HTTP error: no software
is downloaded. Test certificates, repositories and temporary trust stores are owned fixtures and
are removed afterward. This is not a live test of every enterprise proxy or native macOS/Linux.

Corporate-network installer validation (2026-09-21): 52 installer, 80 skill and 150 MCP setup
checks passed. The Node installer/skill/uninstall suite passed 22 tests; two opt-in actual-client
checks were skipped. Final Windows and simulated Linux/macOS forwarding checks passed for
default settings, explicit overrides, test opt-in, PEM paths with spaces, legacy certificate aliases,
redacted errors and restored clone environment. Real Git/Maven TLS and authenticated HTTP-proxy
fixtures passed on Windows with JDK 25/Maven 3.9.11. No native macOS/Linux packaging, actual
corporate proxy, prerequisite-manager bypass or runtime TLS change is claimed by these tests.

Overwrite-prompt validation (2026-09-21): 80 JDK skill checks, 39 application-installer
checks and 150 MCP-setup checks passed, including approved backups, declined/default/EOF
decisions, per-client choices, changed-during-review rejection and an unattended
application upgrade that skips a customized skill without failing. The combined
Node skill/bootstrap/uninstall run passed 18 tests; two opt-in real-client tests
were skipped, not passed. Windows PowerShell 5.1 and Git Bash checks passed, as did
actual Windows terminal prompts for both Java and Node using disposable skills.
macOS/Linux forwarding used simulated OS detection; native runs remain unverified.
No real user skills, client settings, or running application were changed.

Validation status (2026-09-17): Windows/PowerShell 5.1 prerequisite checks, complete HTTP-runtime
reactor test/package, native image creation, bundled-runtime Graph/DBA/MCP smoke with desktop
approval mode, and local Git/Maven Basic-auth proxy checks passed. Installer unit
checks cover paths with spaces, proxy validation/redaction, bypass mapping, settings preservation,
safe replacement and cleanup. Bash syntax is checked on Git Bash; **native macOS/Linux builds,
PATH registration, package-manager installs, enterprise proxy authentication/TLS and signing remain
unverified** on actual environments. Conditional DBA integration/desktop tests are not made passed
merely by a successful headless Maven build. Current installers skip tests by default; use
`-RunTests` / `--run-tests` for the validation commands above.

Optional-skill validation (2026-09-18): 34 installer and 50 JDK skill-copy/selection
checks, nine Node skill tests, two end-to-end bootstrap forwarding tests, skill
frontmatter/reference validation, PowerShell 5.1/7 checks, and Git Bash syntax plus
mocked Linux/macOS checks pass using disposable user homes. Coverage includes complete
reference copying, all/subset/none, prompt default/EOF, unattended opt-out, documented
configuration overrides, idempotence, conflicts, linked paths, and unchanged client
settings. Bootstrap forwarding runs the real scripts with a harmless Java fixture
instead of Maven/jpackage; it also verifies partial-install failure reporting.
Actual macOS/Linux installers and discovery inside all four client UIs
remain unverified; filesystem tests are not claims of native-client certification.

MCP-first setup validation (2026-09-19): JDK configuration/selection tests cover
all five client surfaces, repeat installs, aliased loopback URLs, known Java stdio
entries, legacy VS Code settings, strict JSON/JSONC, quoted TOML sections, backups,
malformed/oversized files, lock conflicts, concurrent edits, junction rejection,
and skill gating for new/existing connections. All 149 MCP, 39 installer and 50
skill checks pass. Windows PowerShell 5.1 and Git Bash bootstrap checks plus 11
Node skill/forwarding tests pass; Linux/macOS forwarding uses mocked OS detection.
Two opt-in checks also pass using the installed Codex and Copilot CLI parsers with
temporary user homes, without launching agents or calling MCP tools:

```powershell
$env:CGRAPH_JAVA_HOME = 'PATH-TO-JDK-25'
$env:CGRAPH_CODEX_ENTRY = 'PATH-TO-NPM/node_modules/@openai/codex/bin/codex.js'
$env:CGRAPH_COPILOT_ENTRY = 'PATH-TO-NPM/node_modules/@github/copilot/npm-loader.js'
node --test installer/mcp-client-smoke.test.mjs
```

Those optional parser checks skip when the explicit paths/JDK are not supplied.
Actual VS Code/Windsurf/Claude UI discovery and native macOS/Linux installation
remain unverified. These checks do not change real user MCP/skill installations
or restart the user's application.

The uninstall tests exercise Windows PowerShell and the real Bash script with
mocked Linux/macOS process/OS detection and disposable user homes: ownership and
home/root protection, running-process refusal, Windows junction rejection,
noninteractive confirmation, repeat removal, copied-script removal, external-data
preservation, and exact PATH-block removal with backups. Native macOS/Linux
process detection and actual user PATH registration remain unverified.

Launcher/uninstall validation (2026-09-19): the isolated 29-module HTTP-runtime
reactor build/package passed (675 tests discovered, 634 passed, 41 conditional
tests skipped). The Windows native image passed Graph/DBA HTTP 200 and MCP
initialization with bundled Java, admin actions, no onboarded projects and the
**1.5 GiB** default; an explicit `512m` override remained intact. The copied
uninstaller rejected that actual running image, then successfully removed it
after the smoke test stopped it, with no user PATH changes. All 15 Node tests
(skills, bootstraps, client parsers and uninstall scenarios) passed. Windows
read-only jpackage cleanup is covered; temporary validation images/builds were
removed. No user's application was restarted or client configuration changed.
