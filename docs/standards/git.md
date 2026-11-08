# Git and versioning

- **Branches** follow git-flow (`feature/`, `bugfix/`, `release/`, `hotfix/` off `develop` and `main`); see `docs/standards/gitflow.md`.
  Older version-prefixed branches (for example `1.10.5-fix-crash`) and the `migrat*` 2.0.0 branches are legacy.
- **Commits** use short conventional prefixes: `feat:`, `fix:`, `docs:`, `refactor:`, `test:`, `chore:`.
  Put the reason for the change in the body when it is not obvious.
- **Version** has one source of truth: `versionName` (and `versionCode`) in `app/build.gradle.kts`. The top entry of
  `app/src/main/assets/changelog.json` and the README download link text must match it (`scripts/check_docs.sh` checks this).
- **Changelog:** `changelog.json` is written by hand for users ("What's New"), one entry per release (`docs/release.md`).
- **Generated files:** never commit `build/`, `.gradle/`, `local.properties` or keystores. `app/release/` is committed only in release commits.
