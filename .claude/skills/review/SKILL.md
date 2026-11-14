---
name: review
description: Review code changes for correctness, quality, standards, security, tests, requirements, comments and documentation using Haiku sub-agents with clean context (one for small diffs, four parallel for large ones). Report only; never edits code. Use when asked to review a diff, branch, PR or set of files.
---

# Instructions

Review a change independently. The review is done by Haiku sub-agents that start with a clean context, so they are not biased by how the change was written. They get the requirement, the diff and read access to the repo, but not the author's reasoning or conversation. You gather the inputs, launch the reviewers, verify what they report, and present the findings. This skill only reports; it never edits code.

## Steps

### 1. Determine what to review
- Target, in order of preference: what the user named (PR number, branch, commit range, paths), otherwise the working-tree changes (`git status`, `git diff`, staged and untracked files), otherwise the current branch against the default branch.
- Leave out plan, verification and run-log files, evidence folders and temp scripts (typically under `plans/`) unless the user asks to review them; they are working notes, not the change.
- Collect the diff with `git diff` against the right base. `git diff` omits untracked files, so list them (`git status --short`) and pass them to reviewers explicitly. Record the base ref, the list of changed files, and the size (files and changed lines).
- Find the requirement to review against: text the user gave, a plan file they pointed to, or the PR/commit description. If none exists, say the review has no stated requirement and requirements adherence will be judged only from the commit messages and the code's apparent intent.
- Find the repo's rules: `AGENTS.md`, `CLAUDE.md`, `docs/` standards, and READMEs of the touched components. Note their paths for the reviewers; do not paste conclusions.

### 2. Choose the review mode by diff size
- **Small** (up to about 1000 changed lines and 30 files): one Haiku reviewer covering all areas.
- **Large** (more than either limit): four Haiku reviewers in parallel, one message with four Agent calls, each with a focus:
  1. **Correctness:** logic errors, edge cases, error handling, concurrency, resource handling, performance, compatibility of APIs, data and migrations.
  2. **Standards and security:** the repo's documented rules and conventions, naming and style, security issues (validation, auth, secrets, injection, paths, unsafe HTML), dependency changes.
  3. **Tests:** coverage of the new behavior, edge and failure cases, meaningful assertions, tests that were skipped, weakened or deleted, and whether the tests plausibly fail without the change.
  4. **Requirements, docs and comments:** every part of the requirement implemented, no scope creep or leftover debug/dead code, README and docs updated where behavior changed, comments and docstrings accurate and useful, commit message and version/changelog hygiene.
- When a huge diff would overwhelm a reviewer, split each focus by file groups too, keeping each reviewer's share reviewable.

### 3. Launch the reviewers
For each reviewer use the Agent tool with `subagent_type: "Explore"` and `model: "haiku"` (read-only, fresh agent). Give each:
- The repo root, the base ref, the exact command to see the diff, and the list of changed files. Use `git diff <base>...HEAD` for committed work; for uncommitted work use `git diff <base> -- <files>` (the caller may give a starting commit as the base), plus the untracked files listed by path.
- The requirement text (or the statement that none was given) and the paths of the repo's rules and docs, which it must read itself. When the requirement is a plan, paste its `Reviewer brief` section verbatim (if it has none, paste only its Objective, Approach, Out of scope and Success criteria); never pass the plan file path, its Validation log or Implementation notes, which hold the author's reasoning.
- Known pre-existing test failures (from the recorded baseline), listed as facts, so reviewers do not report them as new.
- Its focus checklist (or the full checklist for a single reviewer).
- Instructions to read the surrounding code, not only the diff lines, and to cite evidence.
- Do not include: the author's explanations, the conversation, prior review or verification results (the baseline list above excepted), or your opinion of the change.
- Require this output format, with nothing else:
  ```
  FINDINGS:
  1. [BLOCKER|MAJOR|MINOR|NIT] <category> - <file>:<line> - <what is wrong> | Evidence: <code quote or rule path> | Suggestion: <fix>
  SUMMARY: <one or two sentences on the overall state of the change in your focus area>
  ```
  If it finds nothing, it must say `FINDINGS: none` and say what it checked.
- Include these severity definitions so reviewers calibrate the same way:
  - **BLOCKER:** breaks behaviour, loses or corrupts data, is a security hole, or fails the build/tests; must not merge.
  - **MAJOR:** a real defect or requirement gap with limited reach (an unhandled edge case, a missing test for new behaviour, a documented rule broken).
  - **MINOR:** works but is fragile, unclear or inconsistent with conventions; worth fixing.
  - **NIT:** style or wording preference with no effect on behaviour.
- Tell reviewers to focus on lines the change adds or alters and the code they directly affect. Problems in untouched code are reported only if the change makes them reachable or worse.

### 4. Verify the findings
- If a reviewer errors or ignores the format, re-launch it once with the format restated. If it fails again, review that focus area yourself and mark it "self-reviewed, not clean-context" in the report.
- Check every finding against the actual code before reporting it: open the cited file and line, confirm the quoted evidence and the rule. Haiku can misread code, invent problems or cite lines that do not exist.
- Discard findings that are wrong, duplicate or purely speculative, and merge duplicates across reviewers.
- Mark findings that sit in code the change did not touch (and does not make worse) as **pre-existing**. Report them separately; they do not affect the verdict and are follow-ups, not part of this change.
- If earlier review rounds exist for this change (the ticket's Review history, a rounds record the caller points to such as an autopilot run log, or a previous report in the conversation), dismiss a finding that repeats one already rejected with evidence, deferred, or marked pre-existing, citing that round, unless the cited code has changed since. Do not pass earlier rounds to the reviewers; this check is yours. Adjust severity when the reviewer over- or understated it. Keep a short list of what you dismissed and why.
- Do a brief spot check for what the reviewers may have missed in the riskiest parts of the diff (new external calls, data writes, auth, deletions). Add anything you confirm, marked as found by you.

### 5. Reproduce findings
For each finding that remains after step 4 and can be demonstrated by running code (a bug, crash, wrong output, failing edge case, security issue, broken test, lint or type error), try to reproduce it locally:
- Write the smallest reproduction: an existing test, a one-off script or command, or a request against a locally started instance. Use the repo's documented interpreter, environment variables and commands. Run it and read the actual output.
- Run it in isolation: scratchpad or temp directories, throwaway databases and uniquely named containers, never real data, secrets or existing containers. Do not edit the project's source files; reproduction scripts are temporary and removed afterwards.
- Decide the outcome from what you observed:
  - **Confirmed:** the behavior occurs as the finding says. Keep it and attach the reproduction (command and output).
  - **Disproved:** the run shows the claimed problem does not happen. Reject it and list it under dismissed findings with the evidence.
  - **Not reproduced:** it could not be reproduced (needs unavailable services, hardware, production data or timing you cannot recreate, or the attempt was inconclusive). Keep it, marked as not reproduced, with what was tried and why it could not be shown.
- Findings that are not runnable by nature (design, naming, comments, missing docs, style, requirements gaps) are not reproduced. Keep them as verified in step 4 and mark them "static".
- Cap the effort: a few minutes per finding, BLOCKER and MAJOR first. If you run out of budget, mark the rest "not reproduced (not attempted)" rather than dropping them.

### 6. Report
Present in chat:
- **Verdict:** Approve, Approve with comments, or Request changes. Any kept BLOCKER or MAJOR (Confirmed, Not reproduced or Static) means Request changes; only MINOR or NIT means Approve with comments; none means Approve. Pre-existing findings do not count.
- **Scope:** what was reviewed (base, files, size), the mode used, and whether a requirement was available.
- **Findings** grouped by severity, each with file:line, the problem, evidence, a suggested fix and its status: Confirmed (with the reproduction), Not reproduced (with what was tried), or Static.
- **Pre-existing issues:** one line each, offered as follow-ups.
- **Dismissed findings:** one line each, with the reason, including those disproved by reproduction.
- **Not covered:** anything the review could not judge.
Do not apply fixes, create commits or post comments unless the user asks.

## Ticket mode

Applies when the user gives a ticket ID (see the `ticket` skill, "Use from other skills"). Differences:
- **Epic IDs:** if the ID is an epic (`type: epic`), do not run on it. List its unfinished children (as `/ticket list epic <ID>`) and ask which child ticket to use (see the `ticket` skill, "Epics").
- **Step 1:** the requirement is the ticket's Description and Acceptance criteria, with the Plan as intended design. Review the change for that ticket.
- **Step 3:** give reviewers the Description, Acceptance criteria and the Plan's `Reviewer brief` (or, without one, its Objective, Approach, Out of scope and Success criteria) only, never its `### Validation log` or dated amendment notes. Do not give them Implementation notes, Verification or Review history; those are the author's reasoning and prior results. The one exception is the names of pre-existing failing tests from the `### Baseline` subsection, passed as facts (step 3).
- **Step 6:** also append to Review history a `### Round N - YYYY-MM-DD` subsection: verdict, scope, and each kept finding as `R<N>-<k> [SEVERITY] file:line - problem - status (Confirmed/Not reproduced/Static) - Open`, then pre-existing issues as `R<N>-<k> ... - Pre-existing` (not Open, so they are not fixed in this change), then the dismissed ones with reasons. Number rounds from the existing history. Add a Log line with the verdict. Reproduction output goes in `~/.claude/tickets/<repo>/<ID>-evidence/` when long.

## Constraints & Rules

- **Report only:** never modify code, tests, docs or config (temporary reproduction scripts excepted, and removed afterwards; the ticket in ticket mode). The only output is the report.
- **Clean-context reviewers:** always Haiku (`model: "haiku"`), read-only, fresh agents. Never pass the author's reasoning, the conversation or prior results to them.
- **Size-based split:** one reviewer for small diffs, four parallel focused reviewers for large ones, as defined above.
- **Evidence for every finding:** file, line and quoted code or rule path. Findings without verifiable evidence are dropped.
- **Verify before reporting:** the reviewers are inputs, not authorities. Never forward an unchecked finding.
- **Reproduction is the only execution:** run code only to reproduce findings (step 5), in isolation, with temporary scripts removed afterwards. Do not run the app or test suites for general verification; runtime behavior beyond the findings is not covered, and the report says so.
- **Outcome rules for reproduction:** confirmed keeps the finding, disproved rejects it, not reproduced keeps it. Never drop a finding just because it could not be reproduced.
- **Judge against the repo's own rules:** cite its docs and standards, not personal preference. Label style-only remarks as NIT.
- **No padding:** no praise filler or repeating the diff. Say plainly when nothing is wrong.
- **Standalone:** this skill does not depend on or trigger any other skill.
