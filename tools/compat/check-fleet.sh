#!/usr/bin/env bash
# Compiles Exylia plugins and checks their bytecode against the oldest server
# each one supports: the api-version in its plugin.yml when it names a patch
# (api-version: '1.21.9'), 1.21.1 otherwise.
#
# Usage: tools/compat/check-fleet.sh [--branch-lib] <plugin-dir>...
#   --branch-lib  build against this checkout (see branch-lib.gradle) instead
#                 of the ExyliaLib in ~/.m2
# A plugin's compat-allow.txt, when present, is passed as --allow.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
INIT=()
if [ "${1:-}" = "--branch-lib" ]; then INIT=(-I "$HERE/branch-lib.gradle"); shift; fi
[ $# -gt 0 ] || { echo "usage: $0 [--branch-lib] <plugin-dir>..."; exit 2; }
LOGS="${TMPDIR:-/tmp}/exylia-compat"; mkdir -p "$LOGS"
failed=()
for dir in "$@"; do
  dir="$(cd "${dir%/}" && pwd)"; name="$(basename "$dir")"; gradle="$dir/gradlew"
  # A worktree (<repo>-worktrees/<name>): named after its repo, and borrowing
  # the repo's wrapper when the wrapper is not committed.
  parent="$(basename "$(dirname "$dir")")"
  if [[ "$parent" == *-worktrees ]]; then
    name="${parent%-worktrees}"
    [ -x "$gradle" ] || gradle="$(dirname "$(dirname "$dir")")/$name/gradlew"
  fi
  [ -x "$gradle" ] || { echo "FAIL  $name  (no gradlew)"; failed+=("$name"); continue; }
  min=1.21.1
  api=$(grep -hoE "^api-version: *'?[0-9.]+'?" "$dir"/src/main/resources/*plugin.yml 2>/dev/null | head -1 | grep -oE '[0-9]+(\.[0-9]+)+')
  [[ "$api" =~ ^1\.21\.[0-9]+$ ]] && min=$api
  allow=(); [ -f "$dir/compat-allow.txt" ] && allow=(--allow "$dir/compat-allow.txt")
  log="$LOGS/$name.log"
  if ! "$gradle" -p "$dir" compileJava -q --console=plain "${INIT[@]}" >"$log" 2>&1; then
    echo "FAIL  $name  (compile, $log)"; failed+=("$name"); continue
  fi
  if "$HERE/check-compat.sh" --minimum "$min" "${allow[@]}" "$dir/build/classes/java/main" >"$log" 2>&1; then
    echo "ok    $name  (>= $min)"
  else
    echo "FAIL  $name  (>= $min, $(wc -l <"$log") findings, $log)"; failed+=("$name")
  fi
done
[ ${#failed[@]} -eq 0 ] && echo "all compatible" || { echo "failed: ${failed[*]}"; exit 1; }
