#!/usr/bin/env bash
# update-lib-manifest.sh — called by CI or publish-to-lukittu.sh after a
# successful ExyliaLib release build.
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
#   4. Outputs the remaining publish steps
#
# The manifest is served straight from the repository's default branch at
# https://raw.githubusercontent.com/DiGround-s/ExyliaLib/main/lib-manifest.json
# so pushing it is what publishes it — there is no site to deploy.
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
    echo "WARNING: jq not installed — cannot auto-update manifest."
    echo "Add this entry manually to $MANIFEST:"
    echo ""
    echo "  \"${VERSION}\": {"
    echo "    \"version\": \"${VERSION}\","
    echo "    \"url\": \"${URL}\","
    echo "    \"sha256\": \"${SHA256}\","
    echo "    \"major\": ${MAJOR},"
    echo "    \"requiresRestart\": true"
    echo "  }"
    echo ""
    echo "  And set latest.major${MAJOR} = \"${VERSION}\""
fi

echo ""
echo "Next steps:"
echo "  1. Publish the JAR as a GitHub Release asset:"
echo "     gh release create v${VERSION} \"$JAR\" --title \"ExyliaLib ${VERSION}\" --notes \"ExyliaLib ${VERSION}\""
echo "  2. Commit and push $MANIFEST — the push is what publishes it:"
echo "     git add $MANIFEST && git commit -m \"chore: point the manifest at ${VERSION}\" && git push"
echo ""
echo "Release first: a manifest naming a version nobody can download yet"
echo "would send every loader to a 404."
