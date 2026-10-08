#!/usr/bin/env bash
# Regression tests for verify-release-changelog.sh (no network, no Gradle, no release).
set -uo pipefail

script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
script=${script_dir}/verify-release-changelog.sh
tmp=$(mktemp -d)
trap 'rm -rf "${tmp}"' EXIT
failures=0

pass() { printf 'ok   - %s\n' "$1"; }
fail() { printf 'FAIL - %s\n' "$1" >&2; failures=$((failures + 1)); }

bash -n "${script}" && pass 'bash -n verify-release-changelog.sh' || fail 'bash -n verify-release-changelog.sh'

version=3.2.0
zpa=4.2.0

write_changelog() { # <file> [omit-pattern] [sed-expression]
  {
    printf 'ZPA %s\nhttps://github.com/felipebz/zpa/releases/tag/%s\n' "${zpa}" "${zpa}"
    for a in "${version}.zip" "${version}.tar" "${version}-osx-x86_64.tar.gz" \
      "${version}-osx-aarch_64.tar.gz" "${version}-linux-x86_64.tar.gz" \
      "${version}-linux-aarch_64.tar.gz" "${version}-linux_musl-x86_64.tar.gz" \
      "${version}-windows-x86_64.zip"; do
      printf '[zpa-cli-%s](https://github.com/felipebz/zpa-cli/releases/download/%s/zpa-cli-%s)\n' "${a}" "${version}" "${a}"
    done
  } | { if [[ -n ${2:-} ]]; then grep -v -- "$2"; else cat; fi; } | { if [[ -n ${3:-} ]]; then sed -e "$3"; else cat; fi; } > "$1"
}

# new_repo <dir> <tag> <subject>... : tag after first commit, then the given subjects,
# then the Release Please commit as HEAD.
new_repo() {
  local dir=$1 tag=$2
  shift 2
  mkdir -p "${dir}" && git init -q "${dir}"
  (
    cd "${dir}" || exit 1
    git config user.email t@t && git config user.name t && git config commit.gpgsign false
    printf 'zpaVersion=%s\n' "${zpa}" > gradle.properties
    git add . && git commit -q -m 'feat: initial'
    [[ ${tag} == none ]] || git tag "${tag}"
    for s in "$@"; do git commit -q --allow-empty -m "${s}"; done
    git commit -q --allow-empty -m "chore(main): release ${version}"
  )
}

# new_merge_repo <dir> <tag> <subject>... : like new_repo but shaped like a merged Release
# Please PR: a release commit on a side branch, then a merge commit with the same subject.
new_merge_repo() {
  local dir=$1 tag=$2
  shift 2
  mkdir -p "${dir}" && git init -q -b main "${dir}"
  (
    cd "${dir}" || exit 1
    git config user.email t@t && git config user.name t && git config commit.gpgsign false
    printf 'zpaVersion=%s\n' "${zpa}" > gradle.properties
    git add . && git commit -q -m 'feat: initial'
    [[ ${tag} == none ]] || git tag "${tag}"
    git checkout -q -b release-please
    git commit -q --allow-empty -m "chore(main): release ${version}"
    git checkout -q main
    for s in "$@"; do git commit -q --allow-empty -m "${s}"; done
    git merge -q --no-ff release-please -m "chore(main): release ${version}"
  )
}

# run_case <name> <expected-rc> <repo-dir> [changelog-omit-pattern] [changelog-sed-expression]
run_case() {
  local name=$1 want=$2 dir=$3 rc
  write_changelog "${dir}/CHANGELOG.md" "${4:-}" "${5:-}"
  (cd "${dir}" && RELEASE_VERSION=${version} bash "${script}" CHANGELOG.md) >/dev/null 2>"${tmp}/err"
  rc=$?
  if [[ ${want} == 0 && ${rc} == 0 ]] || [[ ${want} != 0 && ${rc} == 1 ]]; then
    pass "${name}"
  else
    fail "${name} (rc=${rc}, expected ${want})"; sed 's/^/     /' "${tmp}/err" >&2
  fi
}

# Conventional subjects accepted; malformed rejected. Release commit follows each one.
for s in 'fix: description' 'feat(cli): description' 'feat!: description' 'refactor(cli)!: description'; do
  new_repo "${tmp}/ok" 3.1.0 'Merge pull request #1' "${s}"; run_case "accepts '${s}'" 0 "${tmp}/ok"; rm -rf "${tmp}/ok"
done
for s in 'description' 'Fix: description' 'fix:description' 'fix(): description' 'fix(cli) description' 'feat(cli: description'; do
  new_repo "${tmp}/bad" 3.1.0 "${s}"; run_case "rejects '${s}'" 1 "${tmp}/bad"; rm -rf "${tmp}/bad"
done

# The (always conventional) release commit must not satisfy the check by itself.
new_repo "${tmp}/only" 3.1.0; run_case 'release commit alone is rejected (empty range)' 1 "${tmp}/only"
new_repo "${tmp}/only2" 3.1.0 'Merge pull request #2'; run_case 'release commit + non-conventional is rejected' 1 "${tmp}/only2"
# Tag prefix does not matter; missing tag fails clearly.
new_repo "${tmp}/v" v3.1.0 'fix: x'; run_case 'v-prefixed previous tag' 0 "${tmp}/v"
new_repo "${tmp}/notag" none 'fix: x'; run_case 'missing previous tag is rejected' 1 "${tmp}/notag"

# Generated release commits never count, however many are in the range.
release_subject="chore(main): release ${version}"
new_repo "${tmp}/r1" 3.1.0 "${release_subject}" "${release_subject}" 'Merge pull request #3'
run_case 'several release commits + non-conventional is rejected' 1 "${tmp}/r1"
new_repo "${tmp}/r2" 3.1.0 "${release_subject}" "${release_subject}"
run_case 'only release commits is rejected' 1 "${tmp}/r2"
new_repo "${tmp}/r3" 3.1.0 'chore(main): release 3.1.5' 'Merge pull request #3'
run_case 'release commit of another version is ignored too' 1 "${tmp}/r3"
new_repo "${tmp}/r4" 3.1.0 "${release_subject}" 'fix: real change' "${release_subject}"
run_case 'real fix mixed with release commits is accepted' 0 "${tmp}/r4"
new_repo "${tmp}/r5" 3.1.0 "${release_subject}" 'feat(cli)!: breaking change'
run_case 'breaking change mixed with release commits is accepted' 0 "${tmp}/r5"
new_merge_repo "${tmp}/m1" 3.1.0 'Merge pull request #4'
run_case 'merged release PR + non-conventional is rejected' 1 "${tmp}/m1"
new_merge_repo "${tmp}/m2" 3.1.0 'Merge pull request #4' 'fix: real change'
run_case 'merged release PR + real fix is accepted' 0 "${tmp}/m2"
new_merge_repo "${tmp}/m3" 3.1.0
run_case 'merged release PR alone (empty range) is rejected' 1 "${tmp}/m3"
new_merge_repo "${tmp}/m4" none 'fix: real change'
run_case 'merged release PR without previous tag is rejected' 1 "${tmp}/m4"

# Pre-existing changelog checks still enforced.
new_repo "${tmp}/cl" 3.1.0 'fix: x'
run_case 'changelog valid' 0 "${tmp}/cl"
run_case 'changelog missing ZPA version' 1 "${tmp}/cl" '^ZPA '
run_case 'changelog missing ZPA release link' 1 "${tmp}/cl" 'felipebz/zpa/releases'
run_case 'changelog missing windows asset' 1 "${tmp}/cl" 'windows'
run_case 'changelog missing musl asset URL' 1 "${tmp}/cl" 'linux_musl'
# A matching asset filename is not enough: the download URL must be right too.
run_case 'changelog with wrong asset URL (filename present)' 1 "${tmp}/cl" '' 's#releases/download/3.2.0/zpa-cli-3.2.0-windows#releases/download/9.9.9/zpa-cli-3.2.0-windows#'
run_case 'changelog with wrong release in every URL' 1 "${tmp}/cl" '' 's#releases/download/3.2.0/#releases/download/3.1.0/#'
: > "${tmp}/cl/CHANGELOG.md"
(cd "${tmp}/cl" && RELEASE_VERSION=${version} bash "${script}" CHANGELOG.md) >/dev/null 2>&1
[[ $? == 1 ]] && pass 'empty changelog rejected' || fail 'empty changelog rejected'

(( failures == 0 )) || { printf '%d test(s) failed\n' "${failures}" >&2; exit 1; }
printf 'All tests passed\n'
