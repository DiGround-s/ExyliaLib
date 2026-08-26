#!/usr/bin/env bash
# Publishes one built jar to Modrinth as a new version of the project.
#
#   MODRINTH_TOKEN=... MODRINTH_PROJECT=... ./publish-modrinth.sh <version> <jar> <changelog-file>
#
# Called by the release workflow, and runnable by hand against a jar downloaded
# from a GitHub release when a release went out but the upload did not: the
# version number is the only thing Modrinth requires to be unique, so a retry
# needs no new release.
set -euo pipefail

version="${1:?version}"
jar="${2:?jar}"
changelog_file="${3:-}"

: "${MODRINTH_TOKEN:?MODRINTH_TOKEN is not set}"
: "${MODRINTH_PROJECT:?MODRINTH_PROJECT is not set}"

# Everything the build runs on. Widen as new Minecraft versions ship. Assigned
# before the expansion below because a default written inline would have its
# quotes eaten by the surrounding ones, and reach jq as invalid JSON.
default_game_versions='["1.21.4","1.21.5","1.21.6","1.21.7","1.21.8","1.21.9","1.21.10","1.21.11","26.1","26.1.1","26.1.2","26.2"]'
default_loaders='["folia","paper","purpur"]'
game_versions="${GAME_VERSIONS:-$default_game_versions}"
loaders="${LOADERS:-$default_loaders}"

jq -e . >/dev/null <<< "$game_versions"
jq -e . >/dev/null <<< "$loaders"

test -f "$jar"
user_agent="DiGround-s/ExyliaLib/${version} (release workflow)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# The upload wants the base62 project ID, and a slug in its place does not fail
# as a slug: it parses as an ID, matches no project, and comes back as "You
# don't have permission to upload this version" — which reads as a token
# problem and is not one. So the slug is resolved here, and only what this
# returns is ever sent.
if ! curl -sS -o "$work/project.json" -f \
  -H "Authorization: ${MODRINTH_TOKEN}" \
  -H "User-Agent: ${user_agent}" \
  "https://api.modrinth.com/v2/project/${MODRINTH_PROJECT}"; then
  echo "Could not read project ${MODRINTH_PROJECT}. The token needs the Read projects scope." >&2
  exit 1
fi
project="$(jq -r '.id' "$work/project.json")"
echo "Publishing to $(jq -r '.title' "$work/project.json") (${project})."

# Modrinth does not enforce unique version numbers: uploading 1.2.3 twice leaves
# two of them on the page, which is what a re-run of the release job does. This
# listing is the only thing standing between a retry and a duplicate, so it is a
# gate rather than a hint, and not being able to read it stops the upload.
if ! curl -sS -o "$work/versions.json" -f \
  -H "Authorization: ${MODRINTH_TOKEN}" \
  -H "User-Agent: ${user_agent}" \
  "https://api.modrinth.com/v2/project/${project}/version"; then
  echo "Could not list existing versions. The token needs the Read versions scope." >&2
  echo "Uploading without this check would publish a second copy of a version that is already there." >&2
  exit 1
fi
if jq -e --arg v "$version" 'any(.[]; .version_number == $v)' "$work/versions.json" >/dev/null; then
  echo "Modrinth already has version ${version}; nothing to do."
  exit 0
fi

changelog=""
if [[ -n "$changelog_file" && -f "$changelog_file" ]]; then
  changelog="$(cat "$changelog_file")"
fi

# Written to a file rather than passed inline: curl splits an inline -F value
# on ';', and release notes are arbitrary text.
jq -nc \
  --arg name "ExyliaLib ${version}" \
  --arg number "$version" \
  --arg changelog "$changelog" \
  --arg project "$project" \
  --argjson game_versions "$game_versions" \
  --argjson loaders "$loaders" \
  '{
     name: $name,
     version_number: $number,
     changelog: $changelog,
     project_id: $project,
     dependencies: [],
     game_versions: $game_versions,
     loaders: $loaders,
     version_type: "release",
     featured: true,
     file_parts: ["file"],
     primary_file: "file"
   }' > "$work/version.json"

# The body carries Modrinth's reason for refusing — which scope is missing, which
# field it did not like. Losing it costs a whole release to diagnose, so the
# status code is read by hand rather than letting curl fail and swallow it.
http="$(curl -sS -o "$work/response.json" -w '%{http_code}' -X POST \
  "https://api.modrinth.com/v2/version" \
  -H "Authorization: ${MODRINTH_TOKEN}" \
  -H "User-Agent: ${user_agent}" \
  -F "data=<$work/version.json;type=application/json" \
  -F "file=@${jar};type=application/java-archive;filename=ExyliaLib-${version}.jar")"

if [[ "$http" != "200" ]]; then
  echo "Modrinth refused the upload (HTTP ${http}):" >&2
  cat "$work/response.json" >&2
  echo >&2
  if [[ "$http" == "401" ]]; then
    echo "401 has two shapes here. 'Invalid Authentication Credentials' is the token itself:" >&2
    echo "the PAT needs Create versions, plus Read projects and Read versions for the check above." >&2
    echo "'You don't have permission to upload this version' means the token is fine but the" >&2
    echo "project it names is not one it can write to — the wrong account, or an ID that" >&2
    echo "matches no project at all." >&2
  fi
  exit 1
fi

echo "Published ${version} to Modrinth as version $(jq -r '.id' "$work/response.json")."
