#!/usr/bin/env bash
set -euo pipefail

usage() {
  printf 'Usage: %s <image-name> <digest> <target-ref> [immutable]\n' "${0##*/}" >&2
  exit 2
}

[[ $# -ge 3 && $# -le 4 ]] || usage
image_name=$1
digest=$2
target_ref=$3
immutable=${4:-false}

[[ ${digest} =~ ^sha256:[0-9a-f]{64}$ ]] || {
  printf 'Invalid digest: %s\n' "${digest}" >&2
  exit 1
}

case ${immutable} in
  true|false) ;;
  *)
    printf 'Immutable flag must be true or false: %s\n' "${immutable}" >&2
    exit 2
    ;;
esac

source_ref="${image_name}@${digest}"

inspect_digest() {
  local ref=$1
  local output line
  if ! output=$(docker buildx imagetools inspect "${ref}" 2>&1); then
    if [[ ${output} == *'not found'* || ${output} == *'MANIFEST_UNKNOWN'* || ${output} == *'404'* ]]; then
      return 1
    fi
    printf '%s\n' "${output}" >&2
    return 2
  fi

  while IFS= read -r line; do
    if [[ ${line} =~ ^[[:space:]]*Digest:[[:space:]]*(sha256:[0-9a-f]{64}) ]]; then
      printf '%s\n' "${BASH_REMATCH[1]}"
      return 0
    fi
  done <<< "${output}"

  printf 'No manifest digest found for %s\n' "${ref}" >&2
  return 2
}

existing_digest=
inspect_status=0
if existing_digest=$(inspect_digest "${target_ref}"); then
  :
else
  inspect_status=$?
fi

if [[ ${immutable} == true && ${inspect_status} -eq 0 ]]; then
  [[ ${existing_digest} == "${digest}" ]] || {
    printf 'Immutable target %s resolves to %s, requested %s\n' "${target_ref}" "${existing_digest}" "${digest}" >&2
    exit 1
  }
elif [[ ${immutable} == true && ${inspect_status} -eq 1 ]]; then
  docker buildx imagetools create --tag "${target_ref}" "${source_ref}"
elif [[ ${immutable} == false ]]; then
  docker buildx imagetools create --tag "${target_ref}" "${source_ref}"
else
  printf 'Unable to inspect immutable target %s\n' "${target_ref}" >&2
  exit 1
fi

result_digest=$(inspect_digest "${target_ref}")
[[ ${result_digest} == "${digest}" ]] || {
  printf 'Target %s resolves to %s, requested %s\n' "${target_ref}" "${result_digest}" "${digest}" >&2
  exit 1
}
