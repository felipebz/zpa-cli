#!/usr/bin/env bash
# Verifies the JReleaser changelog and that the release has Conventional Commit input.
# Run from the repository root with RELEASE_VERSION set.
# Usage: verify-release-changelog.sh [changelog-file]
set -euo pipefail

: "${RELEASE_VERSION:?RELEASE_VERSION must be set}"
changelog_file=${1:-build/jreleaser/release/CHANGELOG.md}

[[ -s ${changelog_file} ]] || {
  printf 'Generated changelog is missing or empty\n' >&2
  exit 1
}
changelog=$(<"${changelog_file}")
zpa_version=$(sed -n 's/^zpaVersion=//p' gradle.properties)
[[ ${changelog} == *"ZPA ${zpa_version}"* ]] || {
  printf 'Changelog does not contain zpaVersion %s\n' "${zpa_version}" >&2
  exit 1
}
[[ ${changelog} == *"https://github.com/felipebz/zpa/releases/tag/${zpa_version}"* ]] || {
  printf 'Changelog does not contain the rendered ZPA release link\n' >&2
  exit 1
}
assets=(
  "zpa-cli-${RELEASE_VERSION}.zip"
  "zpa-cli-${RELEASE_VERSION}.tar"
  "zpa-cli-${RELEASE_VERSION}-osx-x86_64.tar.gz"
  "zpa-cli-${RELEASE_VERSION}-osx-aarch_64.tar.gz"
  "zpa-cli-${RELEASE_VERSION}-linux-x86_64.tar.gz"
  "zpa-cli-${RELEASE_VERSION}-linux-aarch_64.tar.gz"
  "zpa-cli-${RELEASE_VERSION}-linux_musl-x86_64.tar.gz"
  "zpa-cli-${RELEASE_VERSION}-windows-x86_64.zip"
)
for asset in "${assets[@]}"; do
  [[ ${changelog} == *"${asset}"* ]] || {
    printf 'Changelog does not contain asset filename %s\n' "${asset}" >&2
    exit 1
  }
  url="https://github.com/felipebz/zpa-cli/releases/download/${RELEASE_VERSION}/${asset}"
  [[ ${changelog} == *"${url}"* ]] || {
    printf 'Changelog does not contain asset URL %s\n' "${url}" >&2
    exit 1
  }
done

# Release Please commits ("chore(main): release X.Y.Z") are always conventional and can
# appear several times in the range (release PR branch commit plus merge commit), so they
# must never count as release input: only other commits since the previous tag do.
# The patterns live in variables: an inline `[^)]` makes Bash's [[ ]] parser fail.
conventional_re='^[a-z]+(\([^)]+\))?!?:[[:space:]]+'
release_commit_re='^chore\(main\): release [0-9]+\.[0-9]+\.[0-9]+$'
previous_tag=$(git describe --tags --abbrev=0 HEAD^ 2>/dev/null) || {
  printf 'No previous tag found before the release commit\n' >&2
  exit 1
}
has_conventional=false
while IFS= read -r subject; do
  [[ ${subject} =~ ${release_commit_re} ]] && continue
  if [[ ${subject} =~ ${conventional_re} ]]; then
    has_conventional=true
    break
  fi
done < <(git log --format=%s "${previous_tag}..HEAD")
[[ ${has_conventional} == true ]] || {
  printf 'No Conventional Commit subject found between %s and the release commit\n' "${previous_tag}" >&2
  exit 1
}
