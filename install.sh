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
cgraph_main() {
  local repository='https://github.com/doindev/code-graph.git' ref=main source_dir='' install_dir='' proxy='' proxy_user='' bypass_hosts='' git_ca='' settings=''
  local non_interactive=0 check_only=0 skip_tests=0 no_path=0 keep_build=0 build_only=0 clone_dir='' clone_parent='' success=0
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --repository|--ref|--source-dir|--install-dir|--proxy|--proxy-user|--no-proxy|--git-ca-file|--maven-settings)
        [[ $# -ge 2 && -n "$2" ]] || { printf 'Missing value for %s\n' "$1" >&2;return 1; }
        case "$1" in --repository) repository=$2;; --ref) ref=$2;; --source-dir) source_dir=$2;; --install-dir) install_dir=$2;; --proxy) proxy=$2;; --proxy-user) proxy_user=$2;; --no-proxy) bypass_hosts=$2;; --git-ca-file) git_ca=$2;; --maven-settings) settings=$2;; esac
        shift 2;;
      --non-interactive) non_interactive=1;shift;; --check) check_only=1;shift;; --skip-tests) skip_tests=1;shift;; --no-path) no_path=1;shift;; --keep-build) keep_build=1;shift;; --build-only) build_only=1;shift;;
      --help|-h) printf 'Usage: bash install.sh [--check] [--non-interactive] [--source-dir DIR] [--install-dir DIR]\n  [--repository URL] [--ref BRANCH_OR_TAG] [--skip-tests] [--no-path] [--keep-build] [--build-only]\n  [--proxy http://HOST:PORT] [--proxy-user USER] [--no-proxy HOSTS] [--git-ca-file PEM] [--maven-settings XML]\n';return;;
      *) printf 'Unknown installer option: %s\n' "$1" >&2;return 1;;
    esac
  done
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
  if [[ -n "$git_ca" ]]; then [[ -f "$git_ca" ]] || { printf 'Git CA file not found\n' >&2;return 1; };export GIT_SSL_CAINFO="$git_ca";fi
  if [[ -n "$settings" ]]; then [[ -f "$settings" ]] || { printf 'Maven settings not found\n' >&2;return 1; };settings=$(cd "$(dirname "$settings")" && pwd -P)/$(basename "$settings");fi
  local jdk='' maven='' version='' valid=1
  jdk=$(cgraph_jdk) || true
  command -v git >/dev/null 2>&1 || cgraph_offer Git git
  [[ -n "$jdk" ]] || cgraph_offer 'JDK 25' openjdk-25-jdk
  jdk=$(cgraph_jdk) || true
  if [[ -n "$jdk" ]]; then export JAVA_HOME="$jdk"; fi
  maven=$(command -v mvn) || true
  if [[ -n "$maven" && -n "$jdk" ]]; then version=$("$maven" --version 2>&1) || true;fi
  [[ "$version" =~ Apache\ Maven\ 3\.(9|[1-9][0-9])\. ]] || cgraph_offer 'Maven 3.9+' maven
  command -v git >/dev/null 2>&1 || valid=0
  [[ -n "$jdk" ]] || valid=0
  maven=$(command -v mvn) || true;version=''
  if [[ -n "$maven" && -n "$jdk" ]]; then version=$("$maven" --version 2>&1) || true;fi
  [[ "$version" =~ Apache\ Maven\ 3\.(9|[1-9][0-9])\. ]] || valid=0
  if [[ $valid != 1 ]]; then
    printf 'Requirements: Git, full JDK 25 (javac/jlink/jpackage), Maven 3.9+. No clone/build was performed.\nInstall guidance: https://git-scm.com/downloads https://adoptium.net/installation https://maven.apache.org/install.html\nSet CGRAPH_JAVA_HOME to JDK 25 and ensure Maven is on PATH. Package managers may require their own proxy/CA configuration.\n' >&2;return 1
  fi
  printf 'Prerequisites passed. Node.js/npm are not required.\n'
  [[ $check_only == 0 ]] || return 0
  if [[ -z "$source_dir" ]]; then
    [[ ! "$repository" =~ ^https?://[^/]*@ ]] || { printf 'Use a Git credential helper instead of URL credentials.\n' >&2;return 1; }
    clone_parent=$(cd "${TMPDIR:-/tmp}" && pwd -P)
    clone_dir=$(mktemp -d "$clone_parent/cgraph-clone.XXXXXXXX")
    printf 'Temporary checkout: %s\n' "$clone_dir"
    printf '%s' cgraph-clone-v1 > "$clone_dir/.cgraph-clone-owner"
    trap cgraph_cleanup EXIT
    source_dir="$clone_dir/source"
    printf 'Cloning the selected repository/ref into a fresh temporary checkout...\n'
    if ! git clone --no-hardlinks --branch "$ref" -- "$repository" "$source_dir" >/dev/null 2>&1; then printf 'Git clone failed. Check repository/ref access, proxy authentication, NO_PROXY and Git CA trust. Network output is suppressed to avoid revealing credentials.\n' >&2;return 1;fi
  fi
  source_dir=$(cd "$source_dir" && pwd -P)
  [[ -f "$source_dir/installer/CgraphInstaller.java" ]] || { printf 'Selected repository/ref does not yet contain the installer. Select a published ref or --source-dir with the updated checkout.\n' >&2;return 1; }
  local arguments=("$source_dir/installer/CgraphInstaller.java" --source "$source_dir" --install-dir "$install_dir" --maven "$maven")
  [[ -z "$settings" ]] || arguments+=(--maven-settings "$settings")
  [[ -z "$proxy" ]] || arguments+=(--proxy "$proxy")
  [[ -z "$bypass_hosts" ]] || arguments+=(--no-proxy "$bypass_hosts")
  [[ $skip_tests == 0 ]] || arguments+=(--skip-tests);[[ $no_path == 0 ]] || arguments+=(--no-path)
  [[ $non_interactive == 0 ]] || arguments+=(--non-interactive);[[ $keep_build == 0 ]] || arguments+=(--keep-build);[[ $build_only == 0 ]] || arguments+=(--build-only)
  "$jdk/bin/java" "${arguments[@]}"
  success=1
  cgraph_cleanup;trap - EXIT
}
if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then cgraph_main "$@";fi
