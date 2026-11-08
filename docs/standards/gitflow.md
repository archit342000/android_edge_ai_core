# Git-flow

The repository follows strict git-flow. Commit message rules and the version source are in
`docs/standards/git.md`; the release procedure is in `docs/release.md`.

## Branches

| Branch | Created from | Merges into | Purpose |
|--------|--------------|-------------|---------|
| `main` | (permanent) | (none) | Production. Every commit is a release and is tagged `vX.Y.Z`. |
| `develop` | `main` | (none) | Integration branch for the next release. Always builds and passes CI. |
| `feature/<topic>` | `develop` | `develop` | New functionality or a larger change. |
| `bugfix/<topic>` | `develop` | `develop` | Non-urgent fix for unreleased or released code. |
| `release/X.Y.Z` | `develop` | `main` and `develop` | Stabilization and version bump for a release. |
| `hotfix/X.Y.Z` | `main` | `main` and `develop` | Urgent fix to production. |

- `<topic>` is lowercase kebab-case and short (`feature/bind-localhost`).
- `X.Y.Z` is the SemVer version being released (`docs/release.md`).
- Nobody commits directly to `main` or `develop`. Everything arrives through a pull request.

## Rules

1. **Feature and bugfix branches** start from the latest `develop`, stay focused on one change, and open a PR into `develop`.
   Keep them short-lived; merge `develop` into the branch (never rebase pushed work) to stay current.
2. **Release branches** start from `develop` when it contains everything planned for the release.
   Only bug fixes, documentation and the version bump go in. No new features.
3. **Hotfix branches** start from the release tag on `main`. Fix, bump the patch version, and release like a release branch.
   If a release branch is open, merge the hotfix into it instead of `develop`.
4. **Finishing a release or hotfix:**
   1. PR into `main`, merged with a merge commit (`--no-ff`) once the merge gate passes.
   2. Tag the merge commit on `main` with an annotated tag `vX.Y.Z` and push the tag.
   3. Merge `main` (or the release/hotfix branch) back into `develop` through a PR, so release fixes are never lost.
   4. Delete the release or hotfix branch.
5. **Merge method:** always a merge commit (`--no-ff`), never squash or fast-forward. The PR title uses the conventional commit format.
6. **History:** never force-push, never rewrite history on a pushed branch, and never delete `main` or `develop`.
   Undo a bad merge with `git revert -m 1 <merge-commit>`.
7. **Conflicts** are resolved on the working branch by merging the target into it. `app/release/` binaries conflict often:
   take the version from the branch being released and rebuild if unsure.
8. **Delete merged branches** from the remote after merging.

## Merge gate

A PR may merge only when:
- CI (`.github/workflows/ci.yml`) and the branch policy check are green on the PR.
- The checklist in `docs/standards/review.md` is met and the PR has been reviewed.
- The base branch is correct: `develop` for feature and bugfix, `main` for release and hotfix.
- Release and hotfix PRs also have the version bumped and the `changelog.json` entry written.

## Command reference

```bash
# Feature
git checkout develop && git pull origin develop
git checkout -b feature/<topic>
# ...commit, push, open PR into develop...

# Release
git checkout develop && git pull origin develop
git checkout -b release/X.Y.Z          # bump version, changelog, fixes only
# ...PR into main; after merge:
git checkout main && git pull origin main
git tag -a vX.Y.Z -m "Release X.Y.Z" && git push origin vX.Y.Z
# ...PR main into develop, delete release/X.Y.Z...

# Hotfix
git checkout -b hotfix/X.Y.Z vX.Y.(Z-1)   # from the last release tag
```

## Adoption

The repository began with version-prefixed branches (`1.2.0-sessions-and-API-keys`, `1.10.5-fix-crash`) and the 2.0.0
`migrate-aidl-to-rest-api-*` / `migration-2.0.0-*` branches, all cut from and merged into `main`. They may finish under their current names.
To adopt this model: merge the 2.0.0 REST migration into `main`, create `develop` from `main`, set `develop` as the default branch
for feature and bugfix PRs, and protect `main` and `develop` (PR required, CI required, no force-push) in the GitHub settings.
There are no `vX.Y.Z` tags yet; tag the first release made under this model.
Until `develop` exists, a PR may be stacked on an open legacy branch (the branch policy check warns instead of failing).
