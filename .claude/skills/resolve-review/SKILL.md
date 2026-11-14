---
name: resolve-review
description: Resolve a code review report. Triage each finding as valid or invalid by checking the code, fix every valid one with minimal edits and tests, and report what was fixed, rejected or deferred. Use when given review findings (from the review skill, a PR, or pasted) to act on.
---

# Instructions

Take a review and act on it. Do not accept findings blindly: judge each one against the actual code and the repo's rules, fix the valid ones, and explain the rest. Work from the findings only; do not expand into unrelated improvements.

## Steps

### 1. Load the findings and the rules
- Get the review: use what the user pasted or pointed to (a report in the conversation, a file, or PR review comments). If several exist, take the most recent; if none is available, ask.
- Normalize it into a numbered list of findings: id, severity, file and line, claim, evidence, suggested fix, and any status the review gave (for example Confirmed with a reproduction, Not reproduced, Static).
- Read `AGENTS.md`, `CLAUDE.md`, `docs/` standards and the READMEs of the touched components. Read the testing standards for the correct commands. Check `git status` so you know what is already changed in the tree. Find the baseline test result implement recorded (the plan's `## Implementation notes`, or the ticket's); without one, say that old and new test failures cannot be told apart.

### 2. Triage every finding
For each finding, open the cited code and decide independently:
- **Valid:** the problem is real in the current code. A finding the review marked Confirmed still gets a quick check that the code has not changed since.
- **Invalid:** the code does not do what the finding claims, the cited line or rule does not exist, the behavior is intentional and documented, the finding contradicts the repo's rules or the requirement, or it is purely subjective. Record the evidence for the rejection.
- **Valid but not safe to fix alone:** fixing it changes scope, public behavior or design, conflicts with a documented rule, needs a product decision, or is too large for a targeted fix. Do not guess; collect these to ask the user.
- For Not reproduced findings, judge from the code and try to reproduce it yourself if it is cheap. Keep it as valid when the code supports it, reject it when the code disproves it.
- **Pre-existing:** the problem is real but lives in code this change did not touch and does not make worse (the review may already mark it so). Defer it as a follow-up; do not fix it here unless the user asks.
- For disagreements (a suggested fix is wrong but the problem is real), the problem is valid; choose the correct fix yourself and note the difference.

Then present the triage report to the user in chat before changing anything: a table of every finding (id, severity, file:line, decision Valid / Invalid / Pre-existing / Needs your decision, and the reason or evidence), followed by the plan in one line ("fixing N, rejecting M, asking about K"). Continue straight into the fixes after showing it (under autopilot's automatic mode, the table goes to its run log and stage summary); do not wait for approval unless the report contains items that need the user's decision, which are handled in step 5.

### 3. Fix the valid findings
- Order by severity (BLOCKER, MAJOR, MINOR, NIT), then by file to keep edits coherent. Fix every valid finding, including MINOR and NIT ones, unless it falls under "not safe to fix alone" or is pre-existing.
- Make the smallest edit that resolves each finding (prefer `Edit` over `Write` for existing files). Follow the repo's standards and surrounding style. Read the surrounding code first; fix the cause, not the symptom, and check whether the same defect exists elsewhere in the diff.
- Add or update tests for each behavior fix, per the repo's testing standards. A new test must fail without the fix; check this by writing the test first and running it before applying the fix. Never revert files to check; say when you could not show it. Update comments, docstrings, READMEs and docs when a finding asks for it or the fix changes documented behavior.
- Do not make changes that no finding calls for. If a fix reveals a further defect, note it in the report rather than silently widening the work, unless it is needed for the fix to be correct.

### 4. Check the fixes
- Re-run any reproduction attached to a Confirmed finding and show that it no longer fails.
- Run the tests for each directory you changed, then its parent test directories, plus lint and type checks that are fast, using the repo's documented commands (interpreter, env vars).
- On failure, find the cause in your change and fix it, up to 3 attempts per failure. Never skip, delete or weaken tests, or mask errors, to get green. Report failures that existed before your change instead of fixing them.
- Re-read your own diff once for mistakes and leftovers.

### 5. Ask about the rest
- If there are valid findings you did not fix, ask the user how to proceed in one batch (AskUserQuestion), with a recommendation for each. Apply the answers, then check them as in step 4.

### 6. Report
- Summarize per finding id: Fixed (what changed, files), Rejected (evidence), or Deferred (why, what is needed; pre-existing ones listed as follow-ups).
- Say whether any fix changed runtime behaviour (as opposed to tests, comments or docs only), and list those files, so a caller knows whether live verification needs re-running.
- State the checks that ran and their results, any failures that remain, and extra defects noticed but not fixed.
- Do not commit or push unless the user asked.

## Ticket mode

Applies when the user gives a ticket ID (see the `ticket` skill, "Use from other skills"). Differences:
- **Epic IDs:** if the ID is an epic (`type: epic`), do not run on it. List its unfinished children (as `/ticket list epic <ID>`) and ask which child ticket to use (see the `ticket` skill, "Epics").
- **Step 1:** unless the user pasted a different review, the findings are the Open items in the latest round of the ticket's Review history (`R<N>-<k>` ids). Read the Plan and Implementation notes for context.
- **Step 2:** the triage table goes into that round of Review history (above the findings), which is where it is shown in automatic mode.
- **Steps 2 to 5:** as each finding is decided, update its status in that round in place: `Fixed`, `Rejected` or `Deferred`, with a one-line reason or evidence and the files changed. Write as you go.
- **Step 6:** add a `Resolution:` summary to the round (counts, checks run and results, remaining failures) and a Log line. Deferred findings stay visible in the round with what is needed to resolve them.

## Constraints & Rules

- **Judge, don't obey:** every finding is verified against the code before acting. Reject wrong findings with evidence. Do not "fix" something that is not broken.
- **Fix all valid findings:** none are skipped for being minor. The only exceptions are those needing a user decision, which are asked about, not dropped, and pre-existing ones, which are deferred as follow-ups.
- **Minimal, targeted edits:** change only what the valid findings require. No refactors, cleanups or features beyond them.
- **Repo rules win:** if a suggested fix would violate `AGENTS.md`, `CLAUDE.md` or `docs/`, choose a compliant fix or ask the user.
- **Prove fixes:** behavior fixes get tests, confirmed findings get their reproduction re-run, and results are reported exactly, including failures.
- **Triage report first:** always show the triage table to the user before the first edit.
- **Honest accounting:** every finding ends as Fixed, Rejected or Deferred, with no silent omissions. Never claim a fix that was not made or tested.
- **No git actions by default:** do not commit, push, or post PR comments unless asked.
- **No reviewing loop:** do not launch a new review; report and stop.
