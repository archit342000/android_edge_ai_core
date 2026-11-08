# Review and definition of done

A change is done when every item below is true. Use it as the checklist for self-review and pull requests.

## Checklist

1. **Scope:** the change does one thing, with small incremental edits and no unrelated reformatting.
2. **Tests:** new or changed source classes have a test class in `app/src/test/...`; `./gradlew testDebugUnitTest` passes (`testing.md`).
3. **Lint:** `./gradlew lintDebug` passes with no new issues beyond `app/lint-baseline.xml`. There is no CI, so the author runs these locally.
4. **Device check:** behaviour changes to inference, the service or the UI were tried on a device or emulator, and the summary says how.
5. **Standards:** follows `kotlin.md`, `android.md`, `security.md`, `api.md` and `errors-and-logging.md` where relevant.
   Anything that contradicts a documented rule was agreed with the owner first.
6. **Docs:** README, `docs/architecture.md` or `docs/standards/api.md` updated where behaviour changed; `scripts/check_docs.sh` passes.
7. **Pitfalls:** a new entry in `docs/pitfalls.md` if the work uncovered a trap others could hit.
8. **Compatibility:** no change to API shapes, persisted conversation files, SharedPreferences names or keys, or the token backup format
   without updating every consumer and migrating existing data.
9. **Security:** no secrets or keystores, no tokens or prompts in logs, no new exported components or permissions without a reason, no unchecked paths.
10. **Commits:** conventional prefixes, reason in the body when not obvious (`git.md`); branch and PR base follow `gitflow.md`.
11. **Report:** the summary states what changed, what was run and the results, and anything left undone.

## Reviewing

- Check behaviour first (does it do what was asked, including failure paths), then tests, then standards compliance, then style.
- Comment on the code, not the author; say what to change and why. Mark non-blocking remarks as optional.
- Do not approve changes you have not read in full, or that leave failing tests.
