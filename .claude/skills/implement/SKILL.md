---
name: implement
description: Implement a plan file (for example one produced by the plan skill) step by step, then run the relevant tests as a smoke check. Use when asked to implement, build or execute a plan or feature. Does not do full verification; that is a separate skill.
---

# Instructions

Execute an existing plan faithfully. Implement the steps in order, make small edits, and finish by running the tests relevant to what changed so that anything obviously broken is caught. This skill does not re-plan and does not do the thorough verification pass; a separate skill handles that.

## Steps

### 1. Load the plan and the rules
- Find the plan: use the path the user gave, otherwise the most recent implementation plan in the plans directory (the one the repo's docs name, else `plans/`) (ignore `*-verification-*`, `*-autopilot-*` files and evidence folders). If none exists or it is ambiguous, ask the user.
- Read it fully. Stop and ask the user if its `Status` is `Draft - unresolved issues` and the unresolved issues affect what you will build.
- Read `AGENTS.md`, `CLAUDE.md` and the README of each component you will touch, plus the repo's testing standards (for example `docs/standards/testing.md`, if present). Follow them for conventions, interpreter, environment variables and test commands.
- **Resuming:** if the plan's `## Implementation notes` (or the ticket's) already hold a starting state, this is a resumed run: reuse it and the baseline, do not re-record, and continue from the first step whose Status is not `done` (in ticket mode: the first step without a `- [x] Step N` line in Implementation notes). Re-taking them now would count your own work as pre-existing.
- Otherwise record the starting state in `## Implementation notes`: current branch and `git status --short`. If the caller passed a starting state (as autopilot does), record that one instead of taking a new snapshot.
- If you are on the default or an integration branch (such as `develop`), create a working branch before editing, per the repo's naming rule (else `<type>/<short-topic>`), from the base its branching model says: `git switch -c <branch> <base>` (for git-flow features, the integration branch). Uncommitted files carry over; if git refuses because they conflict, stop and ask. This is the one git action implement takes. Files already modified before you start are not yours; do not edit or revert them unless the plan names them, and list them in the report so shipping can leave them out.
- Run a baseline of the tests you will rely on in step 3 (the directories the plan touches), so failures that pre-date your change are known rather than guessed. Write the result into the plan file under `## Implementation notes` (failing test names, or "all pass"), so later stages can tell old failures from new ones; skip only if the suite is very slow, and record "baseline skipped (slow)". If the repo has no tests for the area, record "no suite" and rely on the plan's step verifications.

### 2. Implement step by step
- Work through the steps in the order given. Before each step, read the files it names so edits fit the existing code.
- Make the smallest edit that achieves the step (prefer `Edit` over `Write` for existing files). Follow existing style and the repo's standards.
- Add or update the tests the plan calls for, and any docs or README changes it lists.
- After each step, check its stated verification if it is cheap (a command, a quick test). Set the step's `Status` in the plan file to `done` (or `done (deviation: ...)`); for plans without a Status line, add one.
- If the plan is wrong or incomplete (a path or symbol does not exist, a step contradicts a documented rule or the code), do not improvise silently. Make the minimal deviation needed, record it, and ask the user when it changes scope or design.

### 3. Smoke-test
- Run the tests for each directory you changed, then its parent test directories, using the repo's documented commands (correct interpreter and env vars). Also run the linter and type check if the repo has them and they are fast.
- Include the new tests you wrote. For frontend changes, run the typecheck, lint and unit tests.
- If something fails: read the failure, fix the cause in your change, and re-run. Allow up to 3 fix attempts per failure. Do not skip, delete or weaken tests to get green, and do not mask errors.
- Failures that also appear in the baseline already existed; report them instead of fixing them. A failure absent from the baseline is yours.

### 4. Self-review the diff
- Read your full diff (`git diff` plus new files) once against the plan: every step present, nothing extra, no debug prints, commented-out code, stray TODOs, secrets or local paths, and docs/README changes the plan required are there.

### 5. Report
- Summarize: steps completed, files changed, files that were already dirty before you started, tests and checks run with their results, deviations from the plan, steps left undone and why, and any failures that remain.
- State plainly that full verification has not been done and is the next skill's job.
- Do not commit or push unless the user asked.

## Ticket mode

Applies when the user gives a ticket ID (see the `ticket` skill, "Use from other skills"). Differences:
- **Epic IDs:** if the ID is an epic (`type: epic`), do not run on it. List its unfinished children (as `/ticket list epic <ID>`) and ask which child ticket to use (see the `ticket` skill, "Epics").
- **Step 1:** the plan is the ticket's `## Plan` section; do not look in `plans/`. If it is empty, say so and ask whether to run `plan` first. Read Implementation notes and Review history too, in case work is already underway, and continue from there. Record the baseline test result under `### Baseline (YYYY-MM-DD)` and pre-existing dirty files in Implementation notes (not the plan).
- **Step 2:** do not edit step Status lines in the ticket's Plan (it is frozen once validated); after each step, append to `### Implementation progress` in Implementation notes a line `- [x] Step N: <name> - <files>`, plus any deviation and the branch name once known. Write as you go so work can resume in a new conversation.
- **Steps 3 and 5:** record the smoke-test commands and results under `### Smoke test (YYYY-MM-DD)` in Implementation notes, including failures that remain. Add a Log line (`implemented, smoke tests pass` or the failures).

## Constraints & Rules

- **Implement, don't redesign:** the plan is the source of truth. No extra features, refactors or cleanups beyond it.
- **Order and completeness:** do every step, in order. Never mark a step done that you did not do.
- **Small, incremental edits:** no whole-file rewrites of existing files unless the plan requires it.
- **Repo rules win:** if the plan conflicts with `AGENTS.md`, `CLAUDE.md` or `docs/` standards, stop and ask rather than violating them.
- **Honest test results:** report exactly what ran and what failed. Never claim passing tests you did not run, and never hide or silence failures.
- **Smoke test only:** run the relevant existing and new tests; do not build an extensive verification suite or audit beyond that.
- **No git actions by default:** do not commit, push or open a PR unless asked. Creating a working branch (step 1) is the only exception.
- **Ask on real blockers:** missing information, contradictory plan, or a destructive action; otherwise keep going.
