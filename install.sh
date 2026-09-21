#!/usr/bin/env bash
# macOS/Linux bootstrap. Only Git + full JDK 25 + Maven are needed to build; Java is bundled afterward.
set -euo pipefail

cgraph_confirm() {
  [[ ${non_interactive:-0} == 0 && ${check_only:-0} == 0 && -t 0 ]] || return 1
  local answer
  read -r -p "$1 [y/N] " answer
  [[ "$answer" == y || "$answer" == Y || "$answer" == yes ]]
}
cgraph_jdk() {
  local candidate version detected=''
  if command -v java >/dev/null 2>&1; then detected=$(java -XshowSettings:properties -version 2>&1 | sed -n 's/^[[:space:]]*java.home = //p' | head -n 1); fi
  for candidate in "${CGRAPH_JAVA_HOME:-}" "${JAVA_HOME:-}" "$detected"; do
    [[ -n "$candidate" && -x "$candidate/bin/java" && -x "$candidate/bin/javac" && -x "$candidate/bin/jpackage" && -x "$candidate/bin/jlink" ]] || continue
    version=$("$candidate/bin/java" -version 2>&1)
    if [[ "$version" =~ version\ \"25([.\"+\ -]) ]]; then printf '%s\n' "$candidate"; return 0; fi
  done
  if [[ -x /usr/libexec/java_home ]]; then
    candidate=$(/usr/libexec/java_home -v 25 2>/dev/null) || return 1
    [[ -x "$candidate/bin/jpackage" && -x "$candidate/bin/javac" ]] || return 1
    version=$("$candidate/bin/java" -version 2>&1)
    if [[ "$version" =~ version\ \"25([.\"+\ -]) ]]; then printf '%s\n' "$candidate"; return 0; fi
  fi
  return 1
}
cgraph_offer() {
  local dependency=$1 package=$2
  printf 'Missing/incompatible: %s\n' "$dependency"
  if command -v brew >/dev/null 2>&1; then
    if [[ "$dependency" == 'JDK 25' && $(uname -s) == Darwin ]]; then
      if cgraph_confirm 'Run brew install --cask temurin@25?'; then brew install --cask temurin@25; fi
    elif [[ "$dependency" != 'JDK 25' ]]; then
      if cgraph_confirm "Run brew install $package?"; then brew install "$package"; fi
    fi
  elif command -v apt-get >/dev/null 2>&1 && [[ "$dependency" != 'JDK 25' ]]; then
    if cgraph_confirm "Run sudo apt-get install $package? This may require administrator credentials"; then sudo apt-get install "$package"; fi
  elif command -v dnf >/dev/null 2>&1 && [[ "$dependency" != 'JDK 25' ]]; then
    if cgraph_confirm "Run sudo dnf install $package? This may require administrator credentials"; then sudo dnf install "$package"; fi
  fi
}
cgraph_urlencode() {
  local input=$1 out='' char hex i LC_ALL=C
  for ((i=0;i<${#input};i++)); do
    char=${input:i:1}
    case "$char" in [a-zA-Z0-9.~_-]) out+=$char;; *) printf -v hex '%%%02X' "'$char"; out+=$hex;; esac
  done
  printf '%s' "$out"
}
cgraph_diagnostic() {
  local output=$1 secret=${CGRAPH_PROXY_PASSWORD:-} encoded=''
  if [[ -n "$secret" ]]; then
    encoded=$(cgraph_urlencode "$secret")
    output=${output//"$secret"/'[REDACTED]'};output=${output//"$encoded"/'[REDACTED]'}
  fi
  printf '%s\n' "$output" | sed -E 's#(https?://)[^/@[:space:]]+@#\1[REDACTED]@#g; s#([Aa][Uu][Tt][Hh][Oo][Rr][Ii][Zz][Aa][Tt][Ii][Oo][Nn]:[[:space:]]*).*#\1[REDACTED]#'
}
cgraph_maven_version() {
  local output status=0
  output=$("$1" --version 2>&1) || status=$?
  if [[ $status != 0 ]]; then
    printf 'Maven prerequisite check failed (exit %s):\n' "$status" >&2
    cgraph_diagnostic "$output" >&2
    return "$status"
  fi
  printf '%s\n' "$output"
}
cgraph_clone() (
  # Subshell scopes authentication settings even when the bootstrap is sourced.
  export GIT_TERMINAL_PROMPT=0
  unset GIT_ASKPASS SSH_ASKPASS
  local output status=0 certificate=${4:-}
  local tls=()
  unset GIT_SSL_NO_VERIFY
  if [[ -n "$certificate" ]]; then
    export GIT_SSL_CAINFO="$certificate"
    tls=(-c http.sslVerify=true -c http.proxySSLVerify=true -c "http.sslCAInfo=$certificate" -c "http.proxySSLCAInfo=$certificate" -c http.schannelUseSSLCAInfo=true)
    printf 'Git certificate verification enabled using the supplied PEM bundle.\n'
  else
    tls=(-c http.sslVerify=false -c http.proxySSLVerify=false)
    printf 'WARNING — INSTALLATION ONLY: Git TLS verification is disabled; intercepted downloads can contain untrusted code.\n' >&2
  fi
  output=$(git "${tls[@]}" -c credential.helper= -c core.askPass= clone --no-hardlinks --branch "$1" -- "$2" "$3" 2>&1) || status=$?
  if [[ $status != 0 ]]; then
    cgraph_diagnostic "$output" >&2
    printf 'Git clone failed (exit %s). Check repository/ref, proxy and access permissions; interactive authentication is disabled.\n' "$status" >&2
    return "$status"
  fi
)
cgraph_cleanup() {
  if [[ -n ${clone_dir:-} ]]; then
    if [[ ${success:-0} == 1 && ${keep_build:-0} == 0 ]]; then
      local actual parent
      actual=$(cd "$clone_dir" && pwd -P); parent=$(dirname "$actual")
      if [[ "$parent" != "$clone_parent" || ${actual##*/} != cgraph-clone.* || -L "$clone_dir" || ! -f "$actual/.cgraph-clone-owner" || $(<"$actual/.cgraph-clone-owner") != cgraph-clone-v1 ]]; then
        printf 'Refusing cleanup: clone ownership/path verification failed.\n' >&2;return 1
      fi
      rm -rf -- "$actual"
    else printf 'Temporary clone retained for diagnostics: %s\n' "$clone_dir" >&2; fi
  fi
}
cgraph_validate_skills() {
  local selection=$1 only_build=$2 item seen=','
  [[ -n "$selection" ]] || return 0
  if [[ "$selection" != all && "$selection" != none ]]; then
    [[ "$selection" =~ ^(codex|copilot|claude|windsurf)(,(codex|copilot|claude|windsurf))*$ ]] || {
      printf 'Skills must be all, none, or comma-separated codex,copilot,claude,windsurf\n' >&2;return 1;
    }
    local items=()
    IFS=',' read -r -a items <<< "$selection"
    for item in "${items[@]}"; do
      [[ "$seen" != *",$item,"* ]] || { printf 'Duplicate skill client: %s\n' "$item" >&2;return 1; }
      seen+="$item,"
    done
  fi
  [[ "$only_build" == 0 || "$selection" == none ]] || {
    printf '%s\n' '--build-only cannot install skills; use --skills none or omit --skills' >&2;return 1;
  }
}
cgraph_validate_mcp() {
  local selection=$1 endpoint=$2 only_build=$3 item seen=','
  if [[ -n "$selection" && "$selection" != all && "$selection" != none ]]; then
    [[ "$selection" =~ ^(codex|copilot|copilot-vscode|claude|windsurf)(,(codex|copilot|copilot-vscode|claude|windsurf))*$ ]] || {
      printf 'MCP clients must be all, none, or comma-separated codex,copilot,copilot-vscode,claude,windsurf\n' >&2;return 1;
    }
    local items=()
    IFS=',' read -r -a items <<< "$selection"
    for item in "${items[@]}";do
      [[ "$seen" != *",$item,"* ]] || { printf 'Duplicate MCP client\n' >&2;return 1; };seen+="$item,"
    done
  fi
  [[ "$only_build" == 0 || -z "$selection" || "$selection" == none ]] || {
    printf '%s\n' '--build-only cannot configure MCP; use --mcp-clients none' >&2;return 1;
  }
  [[ "$endpoint" =~ ^http://(localhost|127\.0\.0\.1|\[::1\])(:[0-9]{1,5})?/mcp/?$ ]] || {
    printf 'MCP URL must use local HTTP /mcp, without credentials, query, or fragment\n' >&2;return 1;
  }
  local port=${endpoint%/mcp*};port=${port##*:}
  if [[ "$port" =~ ^[0-9]+$ ]];then
    [[ $((10#$port)) -gt 0 && $((10#$port)) -le 65535 ]] || { printf 'Invalid MCP port\n' >&2;return 1; }
  fi
}
cgraph_main() {
  local repository='https://github.com/doindev/code-graph.git' ref=main source_dir='' install_dir='' proxy='' proxy_user='' bypass_hosts='' git_ca='' settings=''
  local non_interactive=0 check_only=0 skip_tests=0 run_tests=0 no_path=0 keep_build=0 build_only=0 clone_dir='' clone_parent='' success=0
  local skills='' mcp_clients='' mcp_url='http://localhost:3000/mcp' mcp_url_set=0
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --repository|--ref|--source-dir|--install-dir|--proxy|--proxy-user|--no-proxy|--cert-pem|--git-ca-file|--maven-settings)
        [[ $# -ge 2 && -n "$2" ]] || { printf 'Missing value for %s\n' "$1" >&2;return 1; }
        case "$1" in --repository) repository=$2;; --ref) ref=$2;; --source-dir) source_dir=$2;; --install-dir) install_dir=$2;; --proxy) proxy=$2;; --proxy-user) proxy_user=$2;; --no-proxy) bypass_hosts=$2;; --cert-pem|--git-ca-file) [[ -z "$git_ca" ]] || { printf 'Supply only one --cert-pem / --git-ca-file\n' >&2;return 1; };git_ca=$2;; --maven-settings) settings=$2;; esac
        shift 2;;
      --non-interactive) non_interactive=1;shift;; --check) check_only=1;shift;; --skip-tests) skip_tests=1;shift;; --no-path) no_path=1;shift;; --keep-build) keep_build=1;shift;; --build-only) build_only=1;shift;;
      --run-tests) run_tests=1;shift;;
      --skills)
        [[ $# -ge 2 && -n "$2" && -z "$skills" ]] || { printf 'Expected one value for --skills\n' >&2;return 1; }
        skills=$2;shift 2;;
      --mcp-clients)
        [[ $# -ge 2 && -n "$2" && -z "$mcp_clients" ]] || { printf 'Expected one value for --mcp-clients\n' >&2;return 1; }
        mcp_clients=$2;shift 2;;
      --mcp-url)
        [[ $# -ge 2 && -n "$2" && "$mcp_url_set" == 0 ]] || { printf 'Expected one value for --mcp-url\n' >&2;return 1; }
        mcp_url=$2;mcp_url_set=1;shift 2;;
      --help|-h) printf 'Usage: bash install.sh [--check] [--non-interactive] [--source-dir DIR] [--install-dir DIR]\n  [--repository URL] [--ref BRANCH_OR_TAG] [--run-tests | --skip-tests] [--no-path] [--keep-build] [--build-only]\n  [--mcp-clients all|none|codex,copilot,copilot-vscode,claude,windsurf]\n  [--mcp-url http://localhost:3000/mcp] [--skills all|none|codex,copilot,claude,windsurf]\n  [--proxy http://HOST:PORT] [--proxy-user USER] [--no-proxy HOSTS] [--cert-pem PEM] [--maven-settings XML]\nTests are skipped by default; use --run-tests to enable them.\nWARNING: default installer Git/Maven downloads disable TLS certificate verification; runtime/global trust are unchanged.\nOptional --cert-pem enables verification for Git and Maven using your corporate certificate bundle.\nLegacy --git-ca-file is an alias for --cert-pem. Prerequisite managers retain their own certificate/signature checks.\nGit prompts/helpers are disabled. Interactive installs ask about MCP first, then offer optional skills only for detected code-graph connections.\nUnattended installs default to neither. Differing skills prompt before replacement (default No); approved updates keep backups.\nWithout an interactive console, differing skills are skipped. MCP settings and approvals are preserved.\n';return;;
      *) printf 'Unknown installer option: %s\n' "$1" >&2;return 1;;
    esac
  done
  [[ $run_tests == 0 || $skip_tests == 0 ]] || { printf 'Choose --run-tests or --skip-tests, not both. Tests are skipped by default.\n' >&2;return 1; }
  cgraph_validate_skills "$skills" "$build_only" || return 1
  cgraph_validate_mcp "$mcp_clients" "$mcp_url" "$build_only" || return 1
  case $(uname -s) in Darwin) install_dir=${install_dir:-"$HOME/Library/Application Support/CodeGraph"};; Linux) install_dir=${install_dir:-"${XDG_DATA_HOME:-$HOME/.local/share}/code-graph"};; *) printf 'Use install.ps1 on Windows.\n' >&2;return 1;; esac
  proxy=${proxy:-${HTTPS_PROXY:-${https_proxy:-${HTTP_PROXY:-${http_proxy:-}}}}}
  if [[ -n "$proxy" ]]; then
    [[ "$proxy" =~ ^https?://(\[[0-9A-Fa-f:]+\]|[A-Za-z0-9._-]+)(:[0-9]+)?/?$ ]] || { printf 'Use an http(s)://host:port proxy without credentials; use --proxy-user and CGRAPH_PROXY_PASSWORD instead.\n' >&2;return 1; }
    export HTTPS_PROXY="$proxy" HTTP_PROXY="$proxy"
    [[ -z "$proxy_user" ]] || export CGRAPH_PROXY_USER="$proxy_user"
    if [[ -n ${CGRAPH_PROXY_USER:-} && -z ${CGRAPH_PROXY_PASSWORD:-} && $check_only == 0 ]]; then
      [[ $non_interactive == 0 && $check_only == 0 && -t 0 ]] || { printf 'Set CGRAPH_PROXY_PASSWORD in the process environment for authenticated proxies.\n' >&2;return 1; }
      read -r -s -p 'Proxy password (installation only): ' CGRAPH_PROXY_PASSWORD;printf '\n';export CGRAPH_PROXY_PASSWORD
    fi
    local git_proxy=$proxy count=${GIT_CONFIG_COUNT:-0}
    if [[ -n ${CGRAPH_PROXY_USER:-} ]]; then git_proxy="${proxy%%://*}://$(cgraph_urlencode "$CGRAPH_PROXY_USER"):$(cgraph_urlencode "${CGRAPH_PROXY_PASSWORD:-}")@${proxy#*://}";fi
    [[ "$count" =~ ^[0-9]+$ ]] || { printf 'Invalid GIT_CONFIG_COUNT\n' >&2;return 1; }
    export "GIT_CONFIG_KEY_$count=http.proxy" "GIT_CONFIG_VALUE_$count=$git_proxy" GIT_CONFIG_COUNT=$((count+1))
    printf 'Proxy configured for Git and Maven; credentials are not saved in the application.\n'
  fi
  [[ -z "$bypass_hosts" ]] || export NO_PROXY="$bypass_hosts" no_proxy="$bypass_hosts"
  if [[ -n "$git_ca" ]]; then [[ -f "$git_ca" ]] || { printf 'Certificate PEM file not found\n' >&2;return 1; };git_ca=$(cd "$(dirname "$git_ca")" && pwd -P)/$(basename "$git_ca");fi
  if [[ -n "$settings" ]]; then [[ -f "$settings" ]] || { printf 'Maven settings not found\n' >&2;return 1; };settings=$(cd "$(dirname "$settings")" && pwd -P)/$(basename "$settings");fi
  local jdk='' maven='' version='' valid=1
  jdk=$(cgraph_jdk) || true
  command -v git >/dev/null 2>&1 || cgraph_offer Git git
  [[ -n "$jdk" ]] || cgraph_offer 'JDK 25' openjdk-25-jdk
  jdk=$(cgraph_jdk) || true
  if [[ -n "$jdk" ]]; then export JAVA_HOME="$jdk"; fi
  maven=$(command -v mvn) || true
  if [[ -n "$maven" && -n "$jdk" ]]; then version=$(cgraph_maven_version "$maven") || true;fi
  [[ "$version" =~ Apache\ Maven\ 3\.(9|[1-9][0-9])\. ]] || cgraph_offer 'Maven 3.9+' maven
  command -v git >/dev/null 2>&1 || valid=0
  [[ -n "$jdk" ]] || valid=0
  maven=$(command -v mvn) || true;version=''
  if [[ -n "$maven" && -n "$jdk" ]]; then version=$(cgraph_maven_version "$maven") || true;fi
  [[ "$version" =~ Apache\ Maven\ 3\.(9|[1-9][0-9])\. ]] || valid=0
  if [[ $valid != 1 ]]; then
    printf 'Requirements: Git, full JDK 25 (javac/jlink/jpackage), Maven 3.9+. No clone/build was performed.\nInstall guidance: https://git-scm.com/downloads https://adoptium.net/installation https://maven.apache.org/install.html\nSet CGRAPH_JAVA_HOME to JDK 25 and ensure Maven is on PATH. Package managers may require their own proxy/CA configuration.\n' >&2;return 1
  fi
  printf 'Prerequisites passed. Node.js/npm are not required.\n'
  [[ $check_only == 0 ]] || return 0
  if [[ -z "$source_dir" ]]; then
    [[ ! "$repository" =~ ^https?://[^/]*@ ]] || { printf 'URL credentials are not supported. Installer clones disable helpers/prompts; use --source-dir with a separately authenticated checkout.\n' >&2;return 1; }
    clone_parent=$(cd "${TMPDIR:-/tmp}" && pwd -P)
    clone_dir=$(mktemp -d "$clone_parent/cgraph-clone.XXXXXXXX")
    printf 'Temporary checkout: %s\n' "$clone_dir"
    printf '%s' cgraph-clone-v1 > "$clone_dir/.cgraph-clone-owner"
    trap cgraph_cleanup EXIT
    source_dir="$clone_dir/source"
    printf 'Cloning the selected repository/ref into a fresh temporary checkout...\n'
    cgraph_clone "$ref" "$repository" "$source_dir" "$git_ca" || return 1
  fi
  source_dir=$(cd "$source_dir" && pwd -P)
  [[ -f "$source_dir/installer/CgraphInstaller.java" ]] || { printf 'Selected repository/ref does not yet contain the installer. Select a published ref or --source-dir with the updated checkout.\n' >&2;return 1; }
  local arguments=("$source_dir/installer/CgraphInstaller.java" --source "$source_dir" --install-dir "$install_dir" --maven "$maven")
  [[ -z "$skills" ]] || arguments+=(--skills "$skills")
  [[ -z "$mcp_clients" ]] || arguments+=(--mcp-clients "$mcp_clients")
  arguments+=(--mcp-url "$mcp_url")
  [[ -z "$settings" ]] || arguments+=(--maven-settings "$settings")
  [[ -z "$git_ca" ]] || arguments+=(--cert-pem "$git_ca")
  [[ -z "$proxy" ]] || arguments+=(--proxy "$proxy")
  [[ -z "$bypass_hosts" ]] || arguments+=(--no-proxy "$bypass_hosts")
  if [[ $run_tests == 1 ]]; then arguments+=(--run-tests);else arguments+=(--skip-tests);fi
  [[ $no_path == 0 ]] || arguments+=(--no-path)
  [[ $non_interactive == 0 ]] || arguments+=(--non-interactive);[[ $keep_build == 0 ]] || arguments+=(--keep-build);[[ $build_only == 0 ]] || arguments+=(--build-only)
  local install_status=0
  "$jdk/bin/java" "${arguments[@]}" || install_status=$?
  if [[ $install_status == 2 ]]; then
    success=1;cgraph_cleanup;trap - EXIT
    printf 'Application installed, but optional MCP/skill setup was incomplete; inspect per-client results and any backup paths. Resolve reported errors before retrying.\n' >&2
    return 2
  elif [[ $install_status != 0 ]]; then return "$install_status";fi
  success=1
  cgraph_cleanup;trap - EXIT
}
if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then cgraph_main "$@";fi
