#!/usr/bin/env bash
# Checks compiled classes against the oldest supported server API (default 1.21.1).
# Usage: tools/compat/check-compat.sh [--minimum 1.21.1] [--allow <file>] <classes-dir-or-jar>...
# Compile first (./gradlew classes); point it at build/classes/java/main or a jar.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
MIN=1.21.1
ALLOW=()
while [ $# -gt 0 ]; do
  case "$1" in
    --minimum) MIN=$2; shift 2 ;;
    --allow) ALLOW=(--allow "$2"); shift 2 ;;
    *) break ;;
  esac
done
[ $# -gt 0 ] || { echo "usage: $0 [--minimum <version>] <classes-dir-or-jar>..."; exit 2; }
API="$HERE/api/build/api-$MIN-R0.1-SNAPSHOT"
if [ ! -d "$API" ]; then
  "$HERE/../../gradlew" -q -p "$HERE/api" apiJars -Pminimum="$MIN" >/dev/null
fi
exec java "$HERE/CompatCheck.java" "${ALLOW[@]}" "$API" "$@"
