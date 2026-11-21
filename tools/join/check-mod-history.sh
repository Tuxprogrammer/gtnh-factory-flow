#!/usr/bin/env bash
# Checks the joined history: the mod's history came in under jvm/mod/ with its authors and renames, its tags are
# named mod-*, the website's commits are the ones that were pushed, and no imported message carries something
# GitHub turns into a link or a mention (#N, owner/repo#N, GH-N, an issue or pull request URL, @login).
# Usage (from inside the repository): tools/join/check-mod-history.sh <website commit before the join> [<rev>]
# <rev> defaults to HEAD. The join is the merge on <rev>'s first-parent line whose first parent is the website commit.
set -euo pipefail
web="$(git rev-parse --verify "${1:?website commit before the join}^{commit}")"
rev="$(git rev-parse --verify "${2:-HEAD}^{commit}")"
fail=0
ok() { echo "ok    $*"; }
bad() { echo "FAIL  $*"; fail=1; }

join=""
for c in $(git rev-list --first-parent --merges "$rev"); do
  if [ "$(git rev-parse "$c^1")" = "$web" ]; then join="$c"; break; fi
done
[ -n "$join" ] || { echo "FAIL  no merge on the first-parent line of $rev has $web as its first parent"; exit 1; }
mod="$(git rev-parse "$join^2")"
ok "join $(git log -1 --format='%h %s' "$join")"

# The website's commits are unchanged: the join's first parent is the given commit, and it is an ancestor of rev.
git merge-base --is-ancestor "$web" "$rev" && ok "website commit $(git rev-parse --short "$web") is an ancestor, unchanged" \
  || bad "website commit is not an ancestor of $rev"
[ -z "$(git merge-base "$web" "$mod" 2>/dev/null || true)" ] && ok "mod history is unrelated to the website's (no shared commit)" \
  || bad "mod side shares history with the website"
n_mod="$(git rev-list --count "$mod")"
ok "imported mod commits: $n_mod, tip $(git log -1 --format='%h %s' "$mod")"

# Every imported commit only touches jvm/mod/.
outside="$(git log --format= --name-only "$mod" | grep -v '^$' | grep -cv '^jvm/mod/' || true)"
[ "$outside" = 0 ] && ok "every imported commit touches only jvm/mod/" || bad "$outside imported paths outside jvm/mod/"

# blame and log --follow on a mod file whose history has a rename.
f=jvm/mod/src/main/java/com/gtnhplanner/data/flowchart/Summary.java
if git cat-file -e "$rev:$f" 2>/dev/null; then
  follow="$(git log --follow --format=%h "$rev" -- "$f" | wc -l)"
  plain="$(git log --format=%h "$rev" -- "$f" | wc -l)"
  [ "$follow" -gt "$plain" ] && [ "$follow" -gt 5 ] && ok "log --follow $f: $follow commits (plain path log: $plain)" \
    || bad "log --follow $f: $follow commits, plain $plain"
  authors="$(git blame --line-porcelain "$rev" -- "$f" | sed -n 's/^author //p' | sort -u | wc -l)"
  joinlines="$(git blame -s "$rev" -- "$f" | grep -c "^$(git rev-parse --short=8 "$join")" || true)"
  oldest="$(git blame --line-porcelain "$rev" -- "$f" | sed -n 's/^author-time //p' | sort -n | head -1)"
  [ "$authors" -ge 2 ] && [ "$joinlines" = 0 ] && ok "blame $f: $authors authors, oldest line $(date -u -d "@$oldest" +%F), 0 lines from the join" \
    || bad "blame $f: $authors authors, $joinlines lines from the join"
  git blame -s -L1,2 "$rev" -- "$f" | sed 's/^/      /'
else
  bad "$f is missing at $rev"
fi

# Tags: every tag on the mod side is mod-*, names inside annotated tag objects agree with their refs.
tags="$(git tag --merged "$mod" | sort -V)"
echo "      tags on the mod side: $(echo "$tags" | tr '\n' ' ')"
[ -n "$tags" ] || bad "no tag on the mod side"
tagfail=0
for t in $tags; do
  case "$t" in mod-*) ;; *) tagfail=1; bad "tag $t on the mod side has no mod- prefix";; esac
  if [ "$(git cat-file -t "refs/tags/$t")" = tag ]; then
    inner="$(git cat-file -p "refs/tags/$t" | sed -n 's/^tag //p')"
    [ "$inner" = "$t" ] || { tagfail=1; bad "annotated tag $t names itself $inner"; }
  fi
done
[ "$tagfail" = 0 ] && ok "$(echo "$tags" | wc -l) tags, all mod-*"
others="$(git tag --merged "$web" | head -5)"
[ -z "$others" ] && ok "no tag on the website side" || echo "      website-side tags: $others"

# Scrub: every message not on the website's side (the imported history, the join, and everything after it),
# and the messages of the mod-* tags.
msgs="$(mktemp)"; trap 'rm -f "$msgs"' EXIT
git log --format='%H%n%B%n' "$rev" --not "$web" > "$msgs"
for t in $tags; do git for-each-ref --format='%(contents)' "refs/tags/$t" >> "$msgs"; done
echo "      scanned $(git rev-list --count "$rev" --not "$web") commit messages and $(echo "$tags" | wc -l) tag messages"
scan() {
  local name="$1" pat="$2" n
  n="$(grep -cP -- "$pat" "$msgs" || true)"
  if [ "$n" = 0 ]; then ok "0 messages lines with $name"; else bad "$n message lines with $name:"; grep -nP -- "$pat" "$msgs" | head -5 | sed 's/^/      /'; fi
}
scan '#N' '#\d'
scan 'owner/repo#N' '[\w.-]+/[\w.-]+#\d'
scan 'GH-N' '\bGH-\d'
scan 'issue or pull request URL' 'github\.com/[^\s]*/(issues|pull|discussions)/\d'
scan '@login' '(^|[^\w.+-])@[A-Za-z0-9]'

[ "$fail" = 0 ] && echo "JOIN-CHECK: PASS" || { echo "JOIN-CHECK: FAIL"; exit 1; }
