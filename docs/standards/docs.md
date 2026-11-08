# Documentation standards

## Where things live

| What | Where |
|------|-------|
| What the app does, how to use and integrate it | root `README.md` |
| Rules every agent must follow | `AGENTS.md` (short) |
| System overview, components, request flow | `docs/architecture.md` |
| Cross-cutting rules | `docs/standards/*.md` |
| Known traps | `docs/pitfalls.md` |
| Release procedure and versioning | `docs/release.md` |
| What each class and function does | KDoc in the code |
| User-facing release notes | `app/src/main/assets/changelog.json` |

Do not repeat information. If a fact belongs in a standards file, link to it instead of copying it.

## Keeping docs accurate

- Update `docs/architecture.md` when a component's role, ownership, storage or the request flow changes.
- Update `docs/standards/api.md` and the README together when the REST contract changes.
- If code and a doc disagree, trust the code and fix the doc in the same change.
- When you fix a trap listed in `docs/pitfalls.md`, remove or update its entry.
- If a change would contradict a documented rule, ask the owner before proceeding.

## KDoc

Public classes and non-obvious functions get a short KDoc (`/** ... */`): what it does, threading expectations and failure behaviour.
Do not restate the signature.

## Size budget and checks

`AGENTS.md` stays under 80 lines and each file in `docs/` under 200 lines. The root `README.md` is exempt (it carries the user and
integration guide). `scripts/check_docs.sh` enforces the budget, checks that every `docs/...` path named in `AGENTS.md` exists,
that `CLAUDE.md` and `GEMINI.md` still point to `AGENTS.md`, and that the version matches across `app/build.gradle.kts`,
`changelog.json` and the README. Run it before every PR (there is no CI).
