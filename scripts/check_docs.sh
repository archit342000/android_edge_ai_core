#!/bin/sh
# Docs guardrails (docs/standards/docs.md). Usage: scripts/check_docs.sh [repo-root]
set -u
root=${1:-.}
fail=0
err() { echo "check_docs: $*" >&2; fail=1; }

# Size budget
n=$(wc -l < "$root/AGENTS.md") || err "AGENTS.md missing"
[ "${n:-0}" -lt 80 ] || err "AGENTS.md has $n lines (limit 79)"
for f in $(find "$root/docs" -name '*.md'); do
  n=$(wc -l < "$f")
  [ "$n" -lt 200 ] || err "$f has $n lines (limit 199)"
done

# Agent pointer files
for f in CLAUDE.md GEMINI.md; do
  grep -q 'AGENTS.md' "$root/$f" 2>/dev/null || err "$f must point to AGENTS.md"
done

# Every docs/ path named in AGENTS.md exists
for p in $(grep -o '`docs/[^`]*\.md`' "$root/AGENTS.md" | tr -d '`' | sort -u); do
  [ -f "$root/$p" ] || err "AGENTS.md links missing file $p"
done

# Version consistency: build.gradle.kts, changelog.json, README download link
gradle_v=$(sed -n 's/.*versionName *= *"\([^"]*\)".*/\1/p' "$root/app/build.gradle.kts" | head -n 1)
changelog_v=$(sed -n 's/.*"version" *: *"\([^"]*\)".*/\1/p' "$root/app/src/main/assets/changelog.json" | head -n 1)
readme_v=$(sed -n 's/.*Download Latest APK (v\([^)]*\)).*/\1/p' "$root/README.md" | head -n 1)
[ -n "$gradle_v" ] || err "versionName not found in app/build.gradle.kts"
[ "$changelog_v" = "$gradle_v" ] || err "changelog.json top version '$changelog_v' != versionName '$gradle_v'"
[ "$readme_v" = "$gradle_v" ] || err "README download version '$readme_v' != versionName '$gradle_v'"

[ "$fail" -eq 0 ] && echo "check_docs: OK (version $gradle_v)"
exit "$fail"
