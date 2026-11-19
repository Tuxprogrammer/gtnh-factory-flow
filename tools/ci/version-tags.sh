#!/usr/bin/env bash
# The website's version and its release tag. APP_VERSION in src/lib/version.ts is what players see; a handoff is tagged
# web-<APP_VERSION> on the commit that carries it. This checks the two agree.
#   version-tags.sh              every web-* tag on HEAD must be web-<APP_VERSION>; no such tag is fine (HEAD is not released)
#   version-tags.sh --tag-name   print web-<APP_VERSION>, the tag to create at a handoff (the caller runs `git tag`)
#   version-tags.sh --check TAG  the same check for a tag about to be created or pushed, whatever HEAD carries
set -euo pipefail
. "$(dirname "$0")/lib.sh"
CI_JOB=version-tags

file="$CI_ROOT/src/lib/version.ts"
[ -f "$file" ] || fail "$file is missing"
version=$(sed -nE 's/^export const APP_VERSION = "([^"]+)";.*/\1/p' "$file")
[ -n "$version" ] || fail "no APP_VERSION line in src/lib/version.ts"
expected="web-$version"

case "${1:-}" in
  --tag-name) echo "$expected"; exit 0 ;;
  --check)
    tag="${2:?usage: version-tags.sh --check <web-tag>}"
    [ "$tag" = "$expected" ] || fail "tag $tag does not match APP_VERSION $version (expected $expected)"
    say "$tag matches APP_VERSION"; exit 0 ;;
  "") ;;
  *) fail "usage: version-tags.sh [--tag-name | --check <web-tag>]" ;;
esac

tags=$(git -C "$CI_ROOT" tag --points-at HEAD --list 'web-*')
if [ -z "$tags" ]; then
  say "no web-* tag on HEAD; APP_VERSION is $version"; exit 0
fi
bad=0
while IFS= read -r t; do
  if [ "$t" = "$expected" ]; then say "$t matches APP_VERSION"; else say "tag $t is on HEAD but APP_VERSION is $version (expected $expected)"; bad=1; fi
done <<< "$tags"
[ "$bad" = 0 ] || fail "a web-* tag on this commit does not match APP_VERSION"
