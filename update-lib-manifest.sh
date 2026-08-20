#!/usr/bin/env bash
# update-lib-manifest.sh — CI-owned helper called by the release workflow after
# a successful ExyliaLib release build.
#
# Usage:
#   ./update-lib-manifest.sh <version> <jar-path>
#
# Example:
#   ./update-lib-manifest.sh 1.6.0 build/libs/ExyliaLib-1.6.0.jar
#
# This script:
#   1. Computes the SHA-256 of the JAR
#   2. Updates lib-manifest.json with the new version entry
#   3. Points the "latest.major<N>" pointer to the new version
#   4. Reports the generated manifest update
#
# The manifest is served straight from the repository's default branch at
# https://raw.githubusercontent.com/DiGround-s/ExyliaLib/main/lib-manifest.json
# The release workflow owns GitHub release creation and the manifest commit/push.
#
set -euo pipefail

VERSION="${1:?Usage: $0 <version> <jar-path>}"
JAR="${2:?Usage: $0 <version> <jar-path>}"
MANIFEST="lib-manifest.json"

if [ ! -f "$JAR" ]; then
    echo "ERROR: JAR not found: $JAR" >&2
    exit 1
fi

SHA256=$(sha256sum "$JAR" | cut -d' ' -f1)
echo "SHA-256: $SHA256"

MAJOR=$(echo "$VERSION" | cut -d'.' -f1)
URL="https://github.com/DiGround-s/ExyliaLib/releases/download/v${VERSION}/ExyliaLib-${VERSION}.jar"

# Use jq to update the manifest
if command -v jq &>/dev/null; then
    jq --arg ver "$VERSION" \
       --arg url "$URL" \
       --arg sha "$SHA256" \
       --arg maj "$MAJOR" \
       '.versions[$ver] = {
          version: $ver,
          url: $url,
          sha256: $sha,
          major: ($maj | tonumber),
          requiresRestart: true
        } |
        .latest["major\($maj)"] = $ver' \
       "$MANIFEST" > "${MANIFEST}.tmp"
    mv "${MANIFEST}.tmp" "$MANIFEST"
    echo "Updated $MANIFEST"
else
    echo "ERROR: jq is required by CI to update $MANIFEST." >&2
    exit 1
fi
