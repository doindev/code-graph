#!/usr/bin/env bash
# cgraph-managed-uninstaller-v1
# macOS/Linux; no JDK, Maven, npm, or administrator access required.
set -euo pipefail

cgraph_uninstall_target() {
  local selected=$1 home_real parent leaf actual entry
  [[ "$selected" != *$'\n'* && "$selected" != *$'\r'* ]] || { printf 'Invalid installation path\n' >&2;return 1; }
  [[ -e "$selected" || -L "$selected" ]] || return 0
  [[ -d "$selected" && ! -L "$selected" ]] || { printf 'Refusing a linked/non-directory install target\n' >&2;return 1; }
  actual=$(cd "$selected" && pwd -P);home_real=$(cd "$HOME" && pwd -P)
  [[ "$actual" != / && "$actual" != "$home_real" ]] || { printf 'Refusing root or home removal\n' >&2;return 1; }
  # Detect an aliased parent path before relying on ownership; no linked roots.
  parent=$(cd "$(dirname "$selected")" && pwd -P);leaf=$(basename "$selected")
  [[ "$actual" == "$parent/$leaf" ]] || { printf 'Uninstall target changed during resolution\n' >&2;return 1; }
  [[ -f "$actual/.cgraph-install" && ! -L "$actual/.cgraph-install" && $(<"$actual/.cgraph-install") == code-graph-native-install-v1 ]] || {
    printf 'Not a verified code-graph installation; nothing removed\n' >&2;return 1;
  }
  for entry in "$actual"/* "$actual"/.[!.]* "$actual"/..?*;do
    [[ -e "$entry" || -L "$entry" ]] || continue
    case ${entry##*/} in .cgraph-install|bin|releases|uninstall.ps1|uninstall.sh) ;; *) printf 'Unrecognized installation content; move personal data out or review manually. Nothing removed.\n' >&2;return 1;; esac
  done
  printf '%s\n' "$actual"
}
cgraph_uninstall_stopped() {
  local directory=$1 snapshot pid command
  snapshot=$(ps -ax -o pid= -o command=) || { printf 'Cannot check running processes; refusing uninstall\n' >&2;return 1; }
  while read -r pid command;do
    [[ "$pid" == "$$" || "$pid" == "$PPID" ]] && continue
    case "$command" in *"$directory/"*) printf 'A process (%s) is using this installation. Stop it first; nothing removed.\n' "$pid" >&2;return 1;; esac
  done <<< "$snapshot"
}
cgraph_uninstall_path() {
  local directory=$1 profile expected quoted temp backup
  quoted=${directory//\'/\'\"\'\"\'}
  expected="export PATH='$quoted/bin':\"\$PATH\""
  for profile in "$HOME/.bashrc" "$HOME/.zshrc" "$HOME/.profile";do
    [[ -e "$profile" || -L "$profile" ]] || continue
    if [[ -L "$profile" || ! -f "$profile" ]];then printf 'Linked/non-file shell profile preserved; remove code-graph PATH manually: %s\n' "$profile";continue;fi
    if ! CGRAPH_UNINSTALL_PATH_LINE="$expected" awk '
      previous && $0 == ENVIRON["CGRAPH_UNINSTALL_PATH_LINE"] {found=1}
      {previous=($0 == "# cgraph native launcher")}
      END {exit !found}
    ' "$profile";then continue;fi
    # Remove only the exact managed marker + PATH pair. Unrelated shell content is retained.
    temp=$(mktemp "$profile.cgraph-uninstall.XXXXXXXX")
    cp -p "$profile" "$temp"
    CGRAPH_UNINSTALL_PATH_LINE="$expected" awk '
      pending { if ($0 == ENVIRON["CGRAPH_UNINSTALL_PATH_LINE"]) {pending=0; next} print "# cgraph native launcher"; pending=0 }
      $0 == "# cgraph native launcher" {pending=1; next}
      {print}
      END {if(pending)print "# cgraph native launcher"}
    ' "$profile" > "$temp"
    if cmp -s "$profile" "$temp";then rm -f -- "$temp";continue;fi
    backup=$(mktemp "$profile.cgraph-backup.XXXXXXXX");cp -p "$profile" "$backup"
    mv -f -- "$temp" "$profile"
    printf 'Removed managed PATH block; original shell profile backed up at %s\n' "$backup"
  done
}
cgraph_uninstall_main() {
  local directory='' yes=0 check=0 keep_path=0 script_dir target answer
  while [[ $# -gt 0 ]];do
    case "$1" in
      --install-dir) [[ $# -ge 2 && -n "$2" ]] || { printf 'Missing --install-dir\n' >&2;return 1; };directory=$2;shift 2;;
      --yes) yes=1;shift;; --check) check=1;shift;; --keep-path) keep_path=1;shift;;
      --help|-h) printf 'Usage: bash uninstall.sh [--install-dir DIR] [--check] [--yes] [--keep-path]\nRemoves only a verified, stopped native installation. Preserves user data, MCP connections and skills.\n';return;;
      *) printf 'Unknown uninstall option\n' >&2;return 1;;
    esac
  done
  script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)
  if [[ -z "$directory" ]];then
    if [[ -f "$script_dir/.cgraph-install" ]];then directory=$script_dir
    else case $(uname -s) in Darwin) directory="$HOME/Library/Application Support/CodeGraph";; Linux) directory="${XDG_DATA_HOME:-$HOME/.local/share}/code-graph";; *) printf 'Use uninstall.ps1 on Windows\n' >&2;return 1;; esac;fi
  fi
  target=$(cgraph_uninstall_target "$directory") || return 1
  [[ -n "$target" ]] || { printf 'No installation found; nothing changed.\n';return 0; }
  cgraph_uninstall_stopped "$target" || return 1
  printf 'Verified installation: %s\nRemoves application/releases/command shim; preserves database profiles, credentials, projects, MCP connections and skills outside this directory.\n' "$target"
  [[ "$check" == 0 ]] || { printf 'Check only; nothing changed.\n';return 0; }
  if [[ "$yes" == 0 ]];then
    [[ -t 0 ]] || { printf 'Noninteractive uninstall requires explicit --yes\n' >&2;return 1; }
    read -r -p 'Uninstall this application? [y/N] ' answer
    [[ "$answer" == y || "$answer" == Y || "$answer" == yes ]] || { printf 'Cancelled; nothing changed.\n';return 0; }
  fi
  [[ $(cgraph_uninstall_target "$target") == "$target" ]] || return 1
  cgraph_uninstall_stopped "$target" || return 1
  # Exact, resolved, marker-verified target only; rm does not follow nested symlinks.
  rm -rf -- "$target"
  [[ "$keep_path" == 1 ]] || cgraph_uninstall_path "$target"
  printf 'Application removed; reinstall to recover binaries. User data, MCP connections and skills remain. Disable/remove the code-graph connection in clients if no longer needed. Open a new terminal.\n'
}
if [[ "${BASH_SOURCE[0]}" == "$0" ]];then cgraph_uninstall_main "$@";fi
