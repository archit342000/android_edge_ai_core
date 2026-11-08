## Summary
What changed and why.

## Changes
-

## Testing
Commands run and their results, devices or emulators tried, or what was not run.

## Checklist
- [ ] Base branch follows `docs/standards/gitflow.md` (`develop` for feature/bugfix, `main` for release/hotfix)
- [ ] Tests added or updated; `./gradlew testDebugUnitTest` passes
- [ ] `./gradlew lintDebug` and `scripts/check_docs.sh` pass
- [ ] Behaviour changes tried on a device or emulator
- [ ] README, `docs/architecture.md` or `docs/standards/api.md` updated where behaviour changed
- [ ] No tokens, prompts or secrets in logs; no keystores, `local.properties` or build outputs committed
- [ ] No breaking change to the REST API, SharedPreferences keys or persisted files (or consumers and migrations updated)
- [ ] Release/hotfix only: `versionName`/`versionCode`, `changelog.json`, README version and `app/release/` updated

## Notes
Risks, follow-ups, migrations, breaking changes.
