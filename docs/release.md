# Releasing

## Versioning

The app uses Semantic Versioning, `vX.Y.Z`:

- **Major (X):** significant milestones, major overhauls, or breaking changes that fundamentally alter the user experience,
  the architecture or the REST contract (for example 2.0.0, AIDL to REST).
- **Minor (Y):** new features or substantial improvements compatible with the current major version.
- **Patch (Z):** bug fixes, performance improvements and minor tweaks that add no significant features.

`versionCode` is a plain integer that increases by one for every release, whatever part of the version changed.

## Steps

1. **Release branch:** cut `release/X.Y.Z` from `develop` once the planned work is merged (`docs/standards/gitflow.md`). Only fixes, docs and the version bump go in.
2. **Verify:** `./gradlew testDebugUnitTest lintDebug` and `scripts/check_docs.sh`. Install a debug build on a device and run the diagnostics buttons
   (Test Inference, Multi-Turn Test, Health Check, Get Load) with a real model on each backend you support.
3. **Audit dependencies:** check `app/build.gradle.kts` coordinates for updates and known issues (`docs/standards/dependencies.md`).
4. **Bump the version** in `app/build.gradle.kts`: `versionName = "X.Y.Z"` and `versionCode` + 1.
5. **What's New:** add an entry at the top of `app/src/main/assets/changelog.json` for `X.Y.Z`. Users see it in the "What's New" dialog on first launch after the update.
6. **README:** update the download link text, `(vX.Y.Z)`, in `README.md`. `scripts/check_docs.sh` fails if it, `versionName` and the top `changelog.json` entry differ.
7. **Build the signed APK** in Android Studio: **Build > Generate Signed Bundle / APK...**, choose APK, the release variant, your local keystore, and destination `app/release/`.
   Check that `app/release/output-metadata.json` shows the new `versionCode` and `versionName`.
   `./gradlew assembleRelease` alone gives an unsigned APK under `app/build/`, because the build has no `signingConfig`. Never commit the keystore (`*.jks` and `*.keystore` are gitignored).
   **CLI alternative** (same signing Android Studio uses, no build file change): put the keystore details in `~/.gradle/gradle.properties` (outside the repo) as
   `android.injected.signing.store.file`, `.store.password`, `.key.alias` and `.key.password`, then run `./gradlew assembleRelease`. The signed APK is
   `app/build/outputs/apk/release/app-release.apk`; check it with `apksigner verify --print-certs`, then copy it and `output-metadata.json` to `app/release/`.
   Never type the passwords into a shared shell or pass them in a command an agent runs.
8. **Commit** `chore(release): X.Y.Z` with the version, changelog, README and `app/release/` changes.
9. **Merge, tag, back-merge:** open a PR into `main`, merge with a merge commit, tag `vX.Y.Z` on `main` and push the tag,
   then merge back into `develop` and delete the release branch. Hotfixes use `hotfix/X.Y.Z` the same way.

## Distribution

The APK committed under `app/release/` is the download linked from the README. Publish only from tagged releases on `main`.

## Signing key

Every release must be signed with the same key, or it cannot update an existing install. If the keystore or its password is lost, a new key is
the only option, and every user must uninstall once (losing tokens, models and conversations) before installing the new build. Key rotation (APK
Signature Scheme v3) also needs the old key. Keep the keystore and its passwords backed up outside the repo.

## Back out

Check out the previous tag and install its `app/release/app-release.apk`. Android blocks a lower `versionCode` over a newer install,
so uninstall first (this deletes the app's tokens, model copy and conversations), or ship a fix as a new patch release instead.
