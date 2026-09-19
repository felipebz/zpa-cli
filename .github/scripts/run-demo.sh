#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  printf 'Usage: %s <image-ref> <utplsql-directory>\n' "${0##*/}" >&2
  exit 2
fi

image_ref=$1
utplsql_directory=$2

[[ ${image_ref} =~ ^felipebz/zpa-cli@sha256:[0-9a-f]{64}$ ]] || {
  printf 'Image reference must be an immutable zpa-cli digest: %s\n' "${image_ref}" >&2
  exit 1
}

[[ -d ${utplsql_directory} ]] || {
  printf 'utPLSQL directory does not exist: %s\n' "${utplsql_directory}" >&2
  exit 1
}

utplsql_directory=$(realpath -- "${utplsql_directory}")
docker run --rm \
  --volume "${utplsql_directory}:/src" \
  "${image_ref}" \
  --sources . \
  --output-format sq-generic-issue-import \
  --output-file zpa-issues.json
