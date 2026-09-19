#!/usr/bin/env bash
# Pure mocked bootstrap checks; does not install prerequisites or contact external hosts.
set -euo pipefail
source "$(dirname "$0")/../install.sh"
[[ $(cgraph_urlencode 'domain\user:abc+ def/@') == 'domain%5Cuser%3Aabc%2B%20def%2F%40' ]]
uname(){ printf 'Linux\n'; }
cgraph_jdk(){ printf '/fixture/jdk\n'; }
mvn(){ printf 'Apache Maven 3.9.11\n'; }
git(){ printf 'Check mode must not invoke git\n' >&2;return 1; }
cgraph_offer(){ printf 'Check mode must not install packages\n' >&2;return 1; }
(
  unset HTTPS_PROXY HTTP_PROXY https_proxy http_proxy CGRAPH_PROXY_USER CGRAPH_PROXY_PASSWORD
  cgraph_main --check --non-interactive
  cgraph_main --check --non-interactive --skills all
  cgraph_main --check --non-interactive --mcp-clients all --mcp-url http://localhost:3333/mcp
  cgraph_main --check --non-interactive --mcp-clients codex,copilot-vscode --skills all
  cgraph_main --check --build-only --mcp-clients none
  cgraph_main --check --non-interactive --skills codex,claude
  cgraph_main --check --build-only --skills none
  uname(){ printf 'Darwin\n'; }
  cgraph_main --check --non-interactive --skills all
  cgraph_main --check --non-interactive --proxy http://proxy.example:8080 --proxy-user 'domain\user'
)
for invalid in unknown all,claude codex,codex codex,;do
  if (cgraph_main --check --skills "$invalid");then exit 1;fi
done
for invalid in unknown all,claude codex,codex codex,;do
  if (cgraph_main --check --mcp-clients "$invalid");then exit 1;fi
done
for invalid in http://remote.test:3000/mcp http://user:secret@localhost:3000/mcp http://localhost:0/mcp http://localhost:65536/mcp 'http://localhost:3000/mcp?secret=value';do
  if (cgraph_main --check --mcp-url "$invalid");then exit 1;fi
done
if (cgraph_main --check --mcp-clients all --build-only);then exit 1;fi
if (cgraph_main --check --mcp-clients);then exit 1;fi
if (cgraph_main --check --mcp-clients all --mcp-clients none);then exit 1;fi
if (cgraph_main --check --skills all --build-only);then exit 1;fi
if (cgraph_main --check --skills);then exit 1;fi
if (cgraph_main --check --skills all --skills none);then exit 1;fi
if (cgraph_main --check --non-interactive --proxy 'http://user:private_value@proxy.example:8080'); then exit 1;fi
if (cgraph_main --check --non-interactive --proxy 'http://bad proxy:8080'); then exit 1;fi
if (cgraph_main --check --non-interactive --proxy); then exit 1;fi
cgraph_jdk(){ return 1; }
cgraph_offer(){ printf 'Missing prerequisite reported without installation\n'; }
if (cgraph_main --check --non-interactive);then exit 1;fi
printf 'Bash bootstrap checks passed: encoding, check-only, proxies, invalid/missing arguments, missing prerequisites.\n'
