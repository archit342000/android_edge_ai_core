# AGENTS.md

Guide for AI agents working on `android_edge_ai_core` (Edge AI Core). Details live in `docs/`; this file only holds the rules that always apply.

## Always

1. **Read `README.md`** before changing behaviour it describes, and keep it accurate.
   If code and README disagree, trust the code and fix the README in the same change.
2. **Run the checks** before calling a task done: `./gradlew testDebugUnitTest lintDebug` and `scripts/check_docs.sh`.
   New or changed source classes get a matching test class in `app/src/test/...`. See `docs/standards/testing.md`.
3. **Keep constants in the class `companion object`** (as `TAG`, `PREFS_NAME` and the `ACTION_*` keys are today). Never hardcode a value twice.
4. **Log with `android.util.Log` and the class `TAG` constant.** Never log tokens, token prefixes, prompts or model output.
5. **Do not change the REST contract** (routes, request and response shapes, status codes) without updating `docs/standards/api.md` and `README.md`.
6. **Inference stays on-device** through LiteRT-LM. Never add cloud calls or send user data off the device.
7. **Do not mask errors** with empty `catch` blocks or silent fallbacks. Make small, incremental edits.
8. **Stop and ask** before doing anything that contradicts a documented rule.

## Where to look

| Need | Read |
|------|------|
| System overview, components, request flow | `docs/architecture.md` |
| Coding rules | `docs/standards/coding.md` |
| Kotlin rules | `docs/standards/kotlin.md` |
| Android components, lifecycle, permissions, UI | `docs/standards/android.md` |
| Security rules | `docs/standards/security.md` |
| REST API and SSE contract | `docs/standards/api.md` |
| Errors and logging | `docs/standards/errors-and-logging.md` |
| Dependencies | `docs/standards/dependencies.md` |
| Review checklist, definition of done | `docs/standards/review.md` |
| Testing rules and commands | `docs/standards/testing.md` |
| Commits, version, changelog | `docs/standards/git.md` |
| Branching model, merge gate | `docs/standards/gitflow.md` |
| Writing and maintaining docs | `docs/standards/docs.md` |
| Releasing | `docs/release.md` |
| Known traps | `docs/pitfalls.md` |
