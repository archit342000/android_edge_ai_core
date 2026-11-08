# Coding standards

Language and area rules: `kotlin.md`, `android.md`, `security.md`, `api.md` in this directory.

1. **Constants:** keep them in the owning class's `companion object` (`TAG`, `PREFS_NAME`, intent `ACTION_*`/`EXTRA_*` keys). Never hardcode the same value in two places.
   Shared keys such as `InferenceService.EXTRA_MODEL_PATH` are referenced, not retyped.
2. **Logging:** use `android.util.Log` with the class `TAG` (`errors-and-logging.md`). No `println`.
3. **Errors:** do not mask failures with empty `catch` blocks or by returning `null`, `false` or empty lists.
   Handle errors explicitly or let them propagate so they can be debugged.
4. **Threading:** no blocking work on the main thread. Inference and file I/O run on `Dispatchers.IO` (`kotlin.md`).
5. **Edits:** prefer small, incremental edits over rewriting whole files. Do not replace more code than the change needs.
   `MainActivity.kt` is large; touch only the functions you need.
6. **Libraries:** confirm the current version and API of a library or tool from its official docs before using it.
   LiteRT-LM is an alpha; check its release notes before changing calls into it.
7. **Tool-call loops:** if repeated tool calls keep failing, change approach (different search terms,
   a different tool, a different way of exploring) instead of repeating them.
