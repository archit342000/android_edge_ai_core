# Dependencies

## Where they are declared

- Plugins and their versions: root `build.gradle.kts` (AGP `com.android.application`, Kotlin `org.jetbrains.kotlin.android`).
- Libraries: `dependencies { }` in `app/build.gradle.kts`, as exact string coordinates. There is no version catalog.
- Gradle itself: `gradle/wrapper/gradle-wrapper.properties`. Always build with `./gradlew`, never a system Gradle.
- Repositories: `settings.gradle.kts` (`google()`, `mavenCentral()`, JitPack). `RepositoriesMode.FAIL_ON_PROJECT_REPOS` forbids module-level repositories.

## Rules

- Pin exact versions. No `+` or dynamic versions.
- To add a library: confirm the current version and API in its official docs, prefer an existing dependency or the platform first,
  add it, then run `./gradlew testDebugUnitTest lintDebug` and try the affected feature on a device.
- To upgrade: read the release notes, bump one library or one tightly coupled group per commit (for example all `io.ktor:*` together, at one version).
- **LiteRT-LM** (`com.google.ai.edge.litertlm:litertlm-android`) is an alpha. Upgrades can change the `Engine` and `Conversation` API and native libraries;
  test model load and inference on CPU and GPU on a real device before merging.
- Ktor 2.x and Netty bring large transitive trees; keep the `packaging { resources { excludes } }` entries in sync when upgrading them.
- Remove a dependency when its last use goes away. Do not vendor or copy library code into the repo.
- Review outdated and vulnerable dependencies before each release (`docs/release.md`).
