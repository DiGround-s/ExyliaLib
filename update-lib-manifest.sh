#!/usr/bin/env bash
# update-lib-manifest.sh — CI-owned helper called by the release workflow after
# a successful ExyliaLib release build.
#
# Usage:
#   ./update-lib-manifest.sh <version> <jar-path> [stable|dev] [source-commit] [release-tag]
#
# Example:
#   ./update-lib-manifest.sh 1.6.0 build/libs/ExyliaLib-1.6.0.jar stable
#
# This script:
#   1. Computes the SHA-256 of the JAR
#   2. Updates lib-manifest.json with the new version entry for the selected channel
#   3. Points the "latest.major<N>" pointer to the new version
#   4. Reports the generated manifest update
#
# Stable manifests are served from main and Dev manifests from the dev branch.
# The release workflow owns GitHub release creation and the manifest commit/push.
#
set -euo pipefail

VERSION="${1:?Usage: $0 <version> <jar-path>}"
JAR="${2:?Usage: $0 <version> <jar-path>}"
CHANNEL="${3:-stable}"
SOURCE_COMMIT="${4:-}"
RELEASE_TAG="${5:-}"
MANIFEST="lib-manifest.json"

case "$CHANNEL" in
  stable) RELEASE_TAG="${RELEASE_TAG:-v${VERSION}}" ;;
  dev) RELEASE_TAG="${RELEASE_TAG:-dev-v${VERSION}}" ;;
  *) echo "ERROR: channel must be stable or dev: $CHANNEL" >&2; exit 1 ;;
esac

if [ ! -f "$JAR" ]; then
    echo "ERROR: JAR not found: $JAR" >&2
    exit 1
fi

if [ ! -f "$MANIFEST" ]; then
    printf '{\n  "versions": {},\n  "latest": {}\n}\n' > "$MANIFEST"
fi

SHA256=$(sha256sum "$JAR" | cut -d' ' -f1)
echo "SHA-256: $SHA256"

MAJOR=$(echo "$VERSION" | cut -d'.' -f1)
URL="https://github.com/DiGround-s/ExyliaLib/releases/download/${RELEASE_TAG}/ExyliaLib-${VERSION}.jar"

# Use jq to update the manifest
if command -v jq &>/dev/null; then
    jq --arg ver "$VERSION" \
       --arg url "$URL" \
       --arg sha "$SHA256" \
       --arg maj "$MAJOR" \
       --arg tag "$RELEASE_TAG" \
       --arg source "$SOURCE_COMMIT" \
       '.versions[$ver] = {
          version: $ver,
          url: $url,
          sha256: $sha,
          major: ($maj | tonumber),
           requiresRestart: true
         } + (if $tag == "" then {} else {releaseTag: $tag} end)
           + (if $source == "" then {} else {sourceCommit: $source} end) |
         .latest = (.latest // {}) |
         .latest["major\($maj)"] = $ver' \
       "$MANIFEST" > "${MANIFEST}.tmp"
    mv "${MANIFEST}.tmp" "$MANIFEST"
    echo "Updated $MANIFEST"
else
    echo "ERROR: jq is required by CI to update $MANIFEST." >&2
    exit 1
fi
