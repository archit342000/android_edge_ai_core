# Testing standards

## Rules

1. **Test mapping:** every new or changed source class has a test class with the same package in `app/src/test/java/com/aanand/edgeaicore/`
   (`TokenManager.kt` has `TokenManagerTest.kt`). Current test classes: `OpenAIModelsTest`, `AiEngineManagerTest`, `OpenAIServerTest`, `CancellableGenerationTest`, plus `CoroutinesPinTest` (a build guard, `docs/pitfalls.md` 26). The other classes are a known gap, filled as they are touched.
   Agreed exception: Android framework classes (`InferenceService`, `MainActivity`) and `LlmEngine.kt` (logic-free interfaces plus the thin LiteRT-LM adapters) have no test class; they are covered by plain-Kotlin helpers and device checks.
2. **Coverage:** test each public function you add or change, including failure paths.
3. **Keep tests current:** if a change breaks a test because the intended behaviour changed, update the test.
4. **No regressions:** a change must not break unrelated tests. A bug fix adds a test that fails without the fix.
5. **Report results:** summarise what you ran and the outcome when you finish a task.

## Kinds of test

| Kind | Location | Runs on | Use for |
|------|----------|---------|---------|
| Local unit (JUnit 4) | `app/src/test/...` | JVM | DTO serialisation, token rules, TTL logic, request parsing, anything without Android framework calls |
| Instrumented (AndroidX Test, Espresso) | `app/src/androidTest/...` | device or emulator | `SharedPreferences`, files, the service, the UI |
| Device check | `scripts/device.sh` on the test emulator or a phone (`docs/testing-on-device.md`) | emulator or device | real model load, REST and UI behaviour; GPU, NPU and vision only on a phone |

Local unit tests cannot call real `android.*` APIs. Keep logic in plain Kotlin where possible so it can be unit-tested, and fake the boundary
(LiteRT-LM `Engine`, `Context`, HTTP) instead of loading a model.
The engine seam is `LlmEngine` (`AiEngineManager` takes an `engineFactory`; tests pass a fake, see `FakeLlmEngine.kt`). The server seam is `OpenAIServer(aiEngineManager, isValidToken)` with `internal fun Application.module()`; route tests use Ktor `testApplication`. Unit tests run with `unitTests.isReturnDefaultValues = true`: `android.util.Log` calls are no-ops and `android.util.Base64.decode` returns null, so do not unit-test code that depends on them (image and audio parts); use a device check.
No test may need a model file, the network or a fixed port.

## Commands

| Check | Command | Before every PR |
|-------|---------|-----------------|
| Unit tests | `./gradlew testDebugUnitTest` | yes |
| Lint | `./gradlew lintDebug` (new issues only; existing ones are in `app/lint-baseline.xml`) | yes |
| Docs guardrails | `scripts/check_docs.sh` | yes |
| Instrumented tests | `./gradlew connectedDebugAndroidTest` (needs a connected device or emulator) | no |
| Device check | `scripts/device.sh up`, then `api`, `ui`, `logs` (`docs/testing-on-device.md`) | when behaviour changes |

Reports: `app/build/reports/tests/testDebugUnitTest/` and `app/build/reports/lint-results-debug.html`.
There is no CI: run the three "yes" checks locally and paste the results into the PR's Testing section.

## What a good test does

- Asserts behaviour and outputs, not implementation details.
- Is deterministic: no sleeps for timing, no dependence on test order or wall-clock time (inject time for TTL tests).
- Uses temporary directories for file I/O and never touches a real device's app data.
