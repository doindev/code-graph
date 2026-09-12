#!/bin/sh
set -eu
export DBA_VAULT_TEST_ID="code-graph-dba-test-$(cat /proc/sys/kernel/random/uuid)"
printf 'disposable-keyring-password\n' | gnome-keyring-daemon --unlock --components=secrets >/dev/null
java -cp /qa/classes:/qa/lib/jna.jar io.doindev.codegraph.dba.LinuxVaultProbe roundtrip
java -cp /qa/classes:/qa/lib/jna.jar io.doindev.codegraph.dba.LinuxVaultProbe remove
java -cp /qa/classes:/qa/lib/jna.jar io.doindev.codegraph.dba.LinuxVaultProbe roundtrip
gdbus call --session --dest org.freedesktop.secrets --object-path /org/freedesktop/secrets --method org.freedesktop.Secret.Service.Lock "['/org/freedesktop/secrets/collection/login']"
java -cp /qa/classes:/qa/lib/jna.jar io.doindev.codegraph.dba.LinuxVaultProbe locked
DBUS_SESSION_BUS_ADDRESS=unix:path=/tmp/nonexistent-dba-bus java -cp /qa/classes:/qa/lib/jna.jar io.doindev.codegraph.dba.LinuxVaultProbe missing
