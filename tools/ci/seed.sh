#!/usr/bin/env bash
# A job's downloads, kept between runs. On GitHub a cache is readable only from the branch that wrote it and from main, so a
# run on an rs/** branch never sees what `restructure` cached; this carries the same files as an artifact, which any run in
# the repository may read. A seed is a tarball of the directories that hold what a job fetches from outside (the Gradle
# dependency cache, the Minecraft data RetroFuturaGradle downloads, the Gradle distribution, Playwright's browsers).
#   seed.sh restore <name>   fetch the newest seed of that name from a run on restructure or rs/**, unpack it, start the clock
#   seed.sh save <name>      list what this run fetched since the restore; when the seed is missing or short of it, and the
#                            branch is restructure or rs/**, pack a new seed and tell the workflow to upload it
# Never fails the job: no seed or an unreadable one means a cold run, which is what happens without this script. Every run
# prints what it fetched, so a seeded run's log shows the dependency set was complete.
# Env: GH_TOKEN and GITHUB_REPOSITORY (GitHub); CI_SEED_LOCAL_DIR (a directory standing in for GitHub, for tests on the box);
# CI_BRANCH or GITHUB_REF_NAME; RUNNER_TEMP; GITHUB_OUTPUT.
set -uo pipefail
. "$(dirname "$0")/lib.sh"
CI_JOB=seed

mode="${1:-}" name="${2:-}"
[ -n "$name" ] || fail "usage: seed.sh restore|save <jvm|web-core|playwright>"
art="ci-seed-$name"
state="${RUNNER_TEMP:-/tmp}/ci-seed-$name"
branch="${CI_BRANCH:-${GITHUB_REF_NAME:-$(git -C "$CI_ROOT" branch --show-current 2>/dev/null || true)}}"

# The directories of each seed, as "<base> <path under base>". The tarball keeps the part under the base, so a seed packed in one
# Gradle home unpacks into another. Each path is used only if something put it there. Not the JDKs: setup-java installs those,
# and Gradle's own provisioning (jdks/, 2 GB for the four) happens only when a toolchain is not found in JAVA_HOME_<n>_X64.
GRADLE_HOME_DIR="${GRADLE_USER_HOME:-$HOME/.gradle}"
seed_items() {
  case "$name" in
    jvm) printf '%s\n' "$GRADLE_HOME_DIR caches/modules-2" "$GRADLE_HOME_DIR caches/retro_futura_gradle" "$GRADLE_HOME_DIR caches/minecraft" \
           "$GRADLE_HOME_DIR wrapper/dists" "$CI_ROOT jvm/mod/build/rfg" ;;
    web-core) printf '%s\n' "$GRADLE_HOME_DIR caches/modules-2" "$GRADLE_HOME_DIR wrapper/dists" ;;
    playwright) printf '%s\n' "$HOME .cache/ms-playwright" ;;
    *) fail "unknown seed $name" ;;
  esac
}
# What counts as fetched: the files a download writes. Gradle's bookkeeping (metadata, locks, markers) and RetroFuturaGradle's
# derived rfg directory change on every run and say nothing about the network.
fetched_roots() {
  seed_items | while read -r base rel; do
    case "$rel" in
      caches/modules-2) echo "$base/$rel/files-2.1" ;;
      jvm/mod/build/rfg) ;;
      *) echo "$base/$rel" ;;
    esac
  done
}

# One archive holds paths from several bases: tar reads "-C <dir> <path>" pairs in order and stores each path as written.
tar_args() { seed_items | while read -r base rel; do [ -e "$base/$rel" ] && printf '%s\n%s\n%s\n' -C "$base" "$rel"; done; }
# Unpack to a scratch directory, then move each item to its base: a half-unpacked cache never sits where Gradle reads it.
unpack() {
  local dir="$state/unpack" base rel
  rm -rf "$dir"; mkdir -p "$dir"
  zstd -dc "$1" | tar -xf - -C "$dir" || return 1
  while read -r base rel; do
    [ -e "$dir/$rel" ] || continue
    mkdir -p "$base/$(dirname "$rel")" && rm -rf "${base:?}/$rel" && mv "$dir/$rel" "$base/$rel" || return 1
  done < <(seed_items)
  rm -rf "$dir"
}

human() { numfmt --to=iec --suffix=B "$1" 2>/dev/null || echo "$1 bytes"; }

if [ "$mode" = restore ]; then
  rm -rf "$state"; mkdir -p "$state"
  archive=""
  if [ -n "${CI_SEED_LOCAL_DIR:-}" ]; then
    [ -f "$CI_SEED_LOCAL_DIR/$art.tar.zst" ] && archive="$CI_SEED_LOCAL_DIR/$art.tar.zst" && from="local directory"
  elif [ -n "${GH_TOKEN:-}" ] && [ -n "${GITHUB_REPOSITORY:-}" ]; then
    # Newest unexpired seeds first, from restructure and rs/** only (the branches whose builds are trusted to fill it).
    rows=$(gh api "repos/$GITHUB_REPOSITORY/actions/artifacts?name=$art&per_page=100" --jq '
      .artifacts[] | select(.expired == false)
      | select((.workflow_run.head_branch // "") as $b | $b == "restructure" or ($b | startswith("rs/")))
      | "\(.workflow_run.id)\t\(.created_at)\t\(.size_in_bytes)\t\(.workflow_run.head_branch)"' 2>"$state/api.err" | head -3) \
      || say "could not list seeds: $(head -c 300 "$state/api.err")"
    while IFS=$'\t' read -r run created size src; do
      [ -n "$run" ] || continue
      for try in 1 2; do
        rm -rf "$state/dl"
        if gh run download "$run" --repo "$GITHUB_REPOSITORY" --name "$art" --dir "$state/dl" >"$state/dl.log" 2>&1 && [ -f "$state/dl/$art.tar.zst" ]; then
          archive="$state/dl/$art.tar.zst"; from="run $run on $src, made $created"; break 2
        fi
        say "download of the seed from run $run failed (try $try): $(tail -c 200 "$state/dl.log")"; sleep $((try * 5))
      done
    done <<< "$rows"
  else
    say "no GitHub token here; cold run"
  fi
  if [ -z "$archive" ]; then
    say "$name: no seed found; this run fetches everything from the servers"
    : > "$state/cold"
  else
    # A half-unpacked dependency cache would be trusted by Gradle, so a failed unpack removes what it wrote.
    if unpack "$archive"; then
      say "$name: restored $(human "$(stat -c %s "$archive")") from $from"
    else
      say "$name: the seed did not unpack; removing what it wrote and running cold"
      seed_items | while read -r base rel; do rm -rf "${base:?}/$rel"; done
      : > "$state/cold"
    fi
  fi
  rm -rf "$state/dl"
  sleep 1; touch "$state/marker"; sleep 1
  exit 0
fi

if [ "$mode" = save ]; then
  [ -f "$state/marker" ] || { say "$name: no restore ran before; nothing to compare"; exit 0; }
  mapfile -t targs < <(tar_args)
  [ ${#targs[@]} -gt 0 ] || { say "$name: none of the seed's directories exist; nothing to save"; exit 0; }
  roots=(); while IFS= read -r r; do [ -e "$r" ] && roots+=("$r"); done < <(fetched_roots)
  fetched=""
  [ ${#roots[@]} -eq 0 ] || fetched=$(find "${roots[@]}" -type f -newer "$state/marker" ! -name '*.lock' ! -name '*.ok' ! -name auth_uuid_cache.properties 2>/dev/null)
  n=$(printf '%s' "$fetched" | grep -c . || true)
  say "$name: $n downloaded files this run (0 means the seed was complete)"
  if [ "$n" -gt 0 ]; then
    # Dependency files sit at files-2.1/<group>/<artifact>/<version>/<hash>/<file>; show them as group:artifact:version.
    printf '%s\n' "$fetched" | grep '/files-2\.1/' | sed -E 's#^.*/files-2\.1/([^/]+)/([^/]+)/([^/]+)/[^/]+/[^/]+$#dependency \1:\2:\3#' | sort -u | head -40 | sed 's/^/[ci:seed]   /'
    others=$(printf '%s\n' "$fetched" | grep -vc '/files-2\.1/' || true)
    [ "$others" = 0 ] || say "  and $others files that are not dependencies (Minecraft data, the Gradle distribution, browsers)"
  fi
  upload=false
  if [ -f "$state/cold" ] || [ "$n" -gt 0 ]; then
    case "$branch" in
      restructure | rs/*)
        mkdir -p "$state/out"
        if tar -cf - "${targs[@]}" 2>/dev/null | zstd -T0 -3 -q -o "$state/out/$art.tar.zst"; then
          upload=true
          say "$name: new seed $(human "$(stat -c %s "$state/out/$art.tar.zst")") to upload"
        else
          say "$name: could not pack a seed"
        fi ;;
      *) say "$name: branch '$branch' does not write seeds" ;;
    esac
  fi
  if [ -n "${GITHUB_OUTPUT:-}" ]; then
    { echo "upload=$upload"; echo "file=$state/out/$art.tar.zst"; echo "name=$art"
      case "$branch" in restructure) echo "retention=60" ;; *) echo "retention=14" ;; esac; } >> "$GITHUB_OUTPUT"
  fi
  if [ -n "${CI_SEED_LOCAL_DIR:-}" ] && [ "$upload" = true ]; then mkdir -p "$CI_SEED_LOCAL_DIR"; cp "$state/out/$art.tar.zst" "$CI_SEED_LOCAL_DIR/"; fi
  exit 0
fi
fail "usage: seed.sh restore|save <name>"
