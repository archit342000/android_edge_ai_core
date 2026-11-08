# Testing standards

## Rules

1. **Test mapping:** every new or changed source class has a test class with the same package in `app/src/test/java/com/aanand/edgeaicore/`
   (`TokenManager.kt` has `TokenManagerTest.kt`). Today only `OpenAIModelsTest` exists; the other classes are a known gap, filled as they are touched.
2. **Coverage:** test each public function you add or change, including failure paths.
3. **Keep tests current:** if a change breaks a test because the intended behaviour changed, update the test.
4. **No regressions:** a change must not break unrelated tests. A bug fix adds a test that fails without the fix.
5. **Report results:** summarise what you ran and the outcome when you finish a task.

## Kinds of test

| Kind | Location | Runs on | Use for |
|------|----------|---------|---------|
| Local unit (JUnit 4) | `app/src/test/...` | JVM | DTO serialisation, token rules, TTL logic, request parsing, anything without Android framework calls |
| Instrumented (AndroidX Test, Espresso) | `app/src/androidTest/...` | device or emulator | `SharedPreferences`, files, the service, the UI |
| Manual device check | the app's diagnostics buttons | device | real model load and inference on CPU, GPU or NPU |

Local unit tests cannot call real `android.*` APIs. Keep logic in plain Kotlin where possible so it can be unit-tested, and fake the boundary
(LiteRT-LM `Engine`, `Context`, HTTP) instead of loading a model. No test may need a model file, the network or a fixed port.

## Commands

| Check | Command | Before every PR |
|-------|---------|-----------------|
| Unit tests | `./gradlew testDebugUnitTest` | yes |
| Lint | `./gradlew lintDebug` (new issues only; existing ones are in `app/lint-baseline.xml`) | yes |
| Docs guardrails | `scripts/check_docs.sh` | yes |
| Instrumented tests | `./gradlew connectedDebugAndroidTest` (needs a connected device or emulator) | no |

Reports: `app/build/reports/tests/testDebugUnitTest/` and `app/build/reports/lint-results-debug.html`.
There is no CI: run the three "yes" checks locally and paste the results into the PR's Testing section.

## What a good test does

- Asserts behaviour and outputs, not implementation details.
- Is deterministic: no sleeps for timing, no dependence on test order or wall-clock time (inject time for TTL tests).
- Uses temporary directories for file I/O and never touches a real device's app data.
