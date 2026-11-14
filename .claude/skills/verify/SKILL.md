---
name: verify
description: Verify an implemented change with live runtime evidence. First writes a verification plan (scenarios, edge cases, evidence, isolation), then runs it against the real app using throwaway databases/containers, API calls and the browser (Claude in Chrome or Playwright), and reports pass/fail per scenario with evidence. Use after implementing a plan or when asked to verify, test live or prove a feature works.
---

# Instructions

Prove that a change works by running it, not by reading it. Work in two phases: write a verification plan that covers the change systematically, then execute exactly that plan and collect evidence. This skill observes and reports; it does not fix source code.

## Steps

### 1. Establish what is being verified
- Locate the plan (user-given path, otherwise the newest implementation plan in the plans directory (the one the repo's docs name, else `plans/`), ignoring `*-verification-*` and `*-autopilot-*` files) and read its Objective, Steps and Success criteria. If there is no plan, derive the intended behaviour from the user's description and the diff, and say so.
- Inspect the actual change: `git status`, `git diff` against the base (the one the caller gave, otherwise the branch the PR will target), and new files. The diff is the ground truth of what changed; the plan is the intent.
- If a verification plan for this change already exists (`<topic>-verification-*.md` in the implementation plan's directory, for the same topic, whatever its date; take the newest), this is a re-run: see "Re-run mode" below.
- Read `AGENTS.md`, `CLAUDE.md`, `docs/` and the component READMEs for how to run the app, test commands, ports, environment variables and rules (for example: use the repo's interpreter, never modify existing containers, where data lives).

### 2. Write the verification plan (before running anything)
Create `plans/<topic>-verification-<YYYY-MM-DD>.md` (beside the implementation plan) with:

```markdown
# Verification plan: <topic>
**Date:** <date>  **Plan:** <path>  **Diff base:** <ref>

## Scope
<what changed (files, behaviours) and what is explicitly out of scope>

## Environment and isolation
- How the app is started, ports, env vars
- Throwaway resources to create (temp DATA_DIR/DB copy or fresh DB, temp dirs, uniquely named containers, test accounts) and how they are torn down
- What must never be touched (real data, secrets, existing containers, external services with side effects)

## Scenarios
| ID | Requirement / success criterion | Scenario | Type | Method | Files | Expected result | Evidence |
|----|--------------------------------|----------|------|--------|-------|-----------------|----------|
| V1 | ... | happy path ... | happy | browser / API / CLI / DB / unit | changed source files this scenario exercises | ... | screenshot, response body, DB rows, log lines |

## Coverage check
<map each success criterion and each changed file/behaviour to scenario IDs; list anything not coverable and why>
```

**Size the plan to the change.** For a small change (roughly under 100 changed source lines and 5 source files; tests and docs do not count), use the light form: one happy-path scenario per success criterion, the edge and failure cases that apply to the changed inputs, and one regression scenario; skip the coverage-check prose (the Files and criterion columns are the coverage check). Keep the per-file hash record: while work is uncommitted, HEAD does not move, and the hashes are the only way a re-run can tell what changed. Isolation, evidence and teardown rules do not change. Use the full form below for anything larger or touching data, auth or migrations.

Build the scenario list deliberately. For every success criterion and every changed behaviour include:
- **Happy path:** the main flow end to end, through the real entry point a user would use (UI or API), not just internal functions.
- **Edge cases:** empty, missing, null, maximum and minimum sizes, duplicates, unicode and special characters, very long input, boundary values, repeat/idempotent actions, concurrent or rapid actions, ordering and timing.
- **Failure paths:** invalid input, unauthorised access, missing dependencies or unreachable services, partial failure, cancellation, restart/reload mid-operation. The system must fail cleanly with a useful message and no corrupted state.
- **Regression:** adjacent features that share the changed code, existing tests for the touched areas, data written by older versions where relevant.
- **Non-functional where relevant:** errors in logs or console, security (input handling, auth), performance sanity, accessibility basics for UI.

When no existing tool covers a scenario, plan a temporary script or harness for it (a driver script, request replayer, load or concurrency generator, fixture seeder, log or DB inspector, fake upstream service, mock server for an external dependency). List each one in the plan with its purpose.

Choose the method that gives real evidence: the actual running app over mocks, a browser for anything user-visible, HTTP calls for APIs (status code and body), direct inspection of stored state (queries on the throwaway DB) for persistence, container/CLI output for infrastructure. Self-review the plan against the coverage check and fill gaps before continuing. Do not execute scenarios that are not in the plan; if a new idea arises mid-run, add it to the plan first.

### 3. Prepare an isolated environment
- Snapshot what exists before you create anything: `docker ps -a` and networks (if Docker is used), listening ports, and `git status --short`. Teardown is checked against this snapshot.
- Create everything needed for destructive or state-changing scenarios: a copy or fresh instance of the database in a temp location, temp data directories, uniquely named containers and networks, alternative ports, seeded test data. Point the app at them through configuration (env vars or config), never by editing real files.
- Before running, prove isolation: print the resolved DB path, data directory, ports and container names, and confirm they are the throwaway ones. If isolation cannot be guaranteed, do not run that scenario; mark it BLOCKED and say why.
- Write any planned temporary scripts and harnesses now. Keep them outside the source tree, in the verification evidence folder (`plans/<topic>-verification-<date>-evidence/scripts/`) or the session scratchpad. They must target only the throwaway environment (take its URL, DB path and ports as arguments or env vars, with no real paths hardcoded) and print their own inputs and outputs so the run is reproducible. Run them to confirm they behave as intended before relying on their results.
- Start the app per the repo's documented method, in the background with its output going to a log file in the evidence folder, and record the process ID. Confirm it is healthy (status endpoint or page loads, polled with a timeout rather than a fixed sleep) before testing.
- Check the environment's constraints (network allow-list, available browsers, available ports). If something needed is missing, install or configure it inside the throwaway scope, or mark the affected scenarios BLOCKED.

### 4. Execute the plan and collect evidence
- Run scenarios in order. For UI scenarios use a real browser: read the matching skill first (`chrome-browser` for Claude in Chrome, `built-in-browser` for the in-app browser), or use Playwright (pre-installed in cloud sessions, headless, `--no-sandbox`) when no browser tool is available. Work in a new tab, avoid dialogs that block automation, and capture screenshots, visible text, console errors and network failures.
- For each scenario record the observed result and the evidence: exact command and output, HTTP status and body, screenshot path, console and server log excerpts, database rows before and after. Store artefacts in `plans/<topic>-verification-<date>-evidence/` (or the repo's reports directory if it has one).
- Compare observed against expected. Mark each scenario PASS, FAIL or BLOCKED. A PASS requires evidence you actually captured. If a result is surprising, re-run once to rule out flakiness and note it.
- Check server and browser logs after each group of scenarios for errors, warnings and stack traces that the happy output hid.
- On FAIL: capture a minimal reproduction (steps, input, actual versus expected, logs). Do not fix source code and do not change expectations to fit the result. Continue with remaining scenarios unless the failure blocks them.
- Also run the repo's existing test suites for the touched areas as regression evidence, with the documented commands.

### 5. Tear down and report
- Remove all throwaway resources (containers, temp DBs and directories, background processes by their recorded PIDs, fake services) and confirm they are gone by comparing against the step 3 snapshot. Leave real data and existing containers exactly as found; check with `git status` that no stray files were added except the plan and evidence folder (which holds the scripts). List the scripts in the report.
- Fill the results into the verification plan (a Results section that starts with the commit verified, `git rev-parse HEAD`, plus each uncommitted file from `git status --short -uall` with its content hash (`git hash-object <file>`); leave out files recorded as dirty before the work started (passed by the caller, or in the plan's Implementation notes), unless the work changed them, then the table of scenario IDs, status and evidence links) and report to the user:
  - Overall verdict: verified, partly verified, or failed.
  - Counts of PASS, FAIL and BLOCKED, with each FAIL and BLOCKED explained and the repro for each FAIL.
  - Coverage gaps and residual risks.
  - Where the plan and evidence are.
- Recommend next actions (fix failures, re-run failed scenarios) but do not make source changes or commit unless asked.

## Re-run mode

Applies when a verification plan for this change already exists (for example after fixes, or when asked to re-run some scenarios).
- Reuse that plan; do not write a new one. Re-run the scenarios asked for (default: every FAIL and BLOCKED one, unless the caller names the IDs), every scenario with no recorded result yet, plus every scenario whose Files column includes code changed since the latest Results section (its recorded uncommitted files whose `git hash-object` now differs, files modified or untracked now (`git status --short -uall`) that were not in the recorded list, recorded files now deleted, and files in `git diff --name-only <recorded HEAD> HEAD` if commits were made; ignore `plans/` and the evidence folder).
- If the fixes added behaviour, add scenarios for it to the plan first, with new IDs.
- Prepare a fresh isolated environment (step 3); never reuse a previous run's state.
- Scenarios marked `superseded by <ID>` (a caller corrected a wrong expectation by adding a replacement; in a ticket the mark is a dated note in Verification, not an edit to the row) are skipped; never edit an existing scenario's expected result in place.
- Append a `Results (re-run N, YYYY-MM-DD)` section at the same heading level as the first Results section (`##` in a plan file, `###` in a ticket), starting with the same HEAD and file-hash record, and keep earlier ones. The verdict covers the latest status of every scenario: carry earlier PASSes forward only for scenarios whose code did not change.

## Ticket mode

Applies when the user gives a ticket ID (see the `ticket` skill, "Use from other skills"). Differences:
- **Epic IDs:** if the ID is an epic (`type: epic`), do not run on it. List its unfinished children (as `/ticket list epic <ID>`) and ask which child ticket to use (see the `ticket` skill, "Epics").
- **Step 1:** a re-run is detected by the ticket's Verification section already holding a plan, not by `plans/` files. The plan and intent come from the ticket's Plan and Acceptance criteria; Implementation notes say what was built. The diff is still the ground truth.
- **Step 2:** write the verification plan into the ticket's `## Verification` section (`###` headings), not a `plans/` file.
- **Steps 3 and 4:** put evidence and temporary scripts in `~/.claude/tickets/<repo>/<ID>-evidence/` (scripts in `scripts/`), not the repo's `plans/` folder, and link them from the ticket.
- **Step 5:** append the results table under `### Results (YYYY-MM-DD)`; a re-run adds a new Results subsection and leaves earlier ones. Tick an acceptance criterion only when every scenario covering it passes in the latest results. Add a Log line with the verdict and counts.
- Writing the ticket and its evidence folder is the only change verify makes outside the throwaway environment.

## Constraints & Rules

- **Plan first:** no live testing before the verification plan exists and covers the success criteria and the diff. Execute the plan, not random exploration.
- **Evidence or it did not happen:** a scenario is PASS only with captured evidence. Never infer a pass from reading code, from a unit test alone for UI/API behaviour, or from a mock. Report unrun scenarios as BLOCKED, never as passed.
- **Real runtime:** exercise the actual running app through its real interfaces. Prefer browser and HTTP over calling internals. For a library or CLI with no app to run, the real interface is its public API or command line, driven by a harness script in the evidence folder.
- **Isolation for anything destructive or stateful:** use throwaway databases, directories, containers and ports; verify the isolation before running; tear everything down afterwards. Never touch real user data, real secrets, existing containers, or the user's own browser data and sessions beyond the app under test.
- **Temporary scripts are allowed, source changes are not:** write whatever throwaway scripts, fixtures or fake services are needed for evidence, but keep them in the evidence folder or scratchpad, never among the project's source files, point them only at the throwaway environment, and do not leave them behind as repo changes unless the user asks. A script's output counts as evidence only if the script exercises the real app, not a reimplementation of its logic.
- **Respect repo rules:** follow `AGENTS.md`/`CLAUDE.md` and `docs/` (interpreter, env vars, container rules). Do not weaken tests, edit expectations or change config permanently to get passes.
- **Observe, do not fix:** this skill never edits the project's source code (temporary verification scripts excluded). Failures are reported with reproductions; the user decides what to fix.
- **No outward-facing side effects:** do not send messages, make purchases, call paid or production external services, or push code without explicit permission. Stub or point at throwaway services instead.
- **Be honest and specific:** report exact results, include failures and flakiness, and state what was not covered. Do not pad the report.
- **Stay in scope:** verify the change and its regressions; note unrelated defects you notice but do not chase them.
