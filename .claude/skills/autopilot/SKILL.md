---
name: autopilot
description: Run the full development pipeline from a requirement - plan, implement, verify (fixing failures), review and resolve (iterated), then commit and open a PR. Automatic by default; pass "manual" to approve each stage. User-invoked only.
disable-model-invocation: true
argument-hint: "[manual] <ticket key | plan path | requirement>"
---

# Instructions

Take a requirement from the user and carry it through six stages by invoking the existing skills (`plan`, `implement`, `verify`, `review`, `resolve-review`, `ship`) with the Skill tool, all in this one conversation (see "One session"). Do not copy their steps; follow each skill as written, plus the autopilot overrides below. A sub-skill's closing "stop", "do not commit" or "the user decides" ends that skill only; autopilot continues to the next stage. Invoking this skill is the user's authorization for everything in the pipeline, including the commit, push and PR in the last stage.

## Input and modes

- The arguments are the requirement, optionally preceded by the word `manual`.
- **Automatic mode (default):** run all stages without stopping. Make every decision yourself, record each in the decision log, and keep moving. Never ask for approval of the plan, a stage result, a design choice or whether to continue. Never call AskUserQuestion, EnterPlanMode or ExitPlanMode. Never end your turn before the run ships or hits a hard blocker. The user expects to find it done when they come back. Anything left for them (assumptions to review, merging, closing the ticket) goes in the final report.
- **Manual mode:** run each stage to completion, then present its outcome and decisions and wait for the user's reply: accept, reject or request changes. Details under "Manual mode".
- If no requirement was given, ask for it. This is the only question asked before starting.
- **Batch runs:** when `batch-autopilot` runs this pipeline for one of its items, that item's session runs this whole pipeline by itself, the item's worktree is the repo, and the batch's "Item session rules" (dirty list, starting commit, existing branch and worktree, PR base, verification slot) take precedence over the matching steps here.
- **Plan path:** if the requirement is the path of an existing plan file, it is both requirement and plan (see stage 1).
- **Ticket mode:** if the whole requirement is a ticket key (such as `MA-12`, or a bare number with nothing else), run in ticket mode (see "Ticket mode" below). Free text runs as before and does not create a ticket. If the key resolves to an epic (issue type `Epic`), stop here, before any pre-flight step (no branch, no run log), as described under "Ticket mode", "Epic keys".

## One session

Every stage runs in this conversation, one after another, each by invoking its skill with the Skill tool. Never create another session (`create_session`, `start_session`) and never hand a stage to a sub-agent or a background task: the hand-offs cost far more time than they save. The only sub-agents are the Haiku validators and reviewers the skills themselves specify. Parallel work across tickets is `batch-autopilot`'s job; it gives each item one session, and that session runs this skill the same way.
- **Plan flaws found later:** if implementation, verification or review shows the plan is wrong or impossible as written, do not work around it. Invoke `plan` again to amend the plan in place (it records the amendment in the Validation log and re-validates the amended part), log the decision, then continue from the stage that found the flaw. Stage 1's owner-decision rule applies to an amendment too. At most 3 plan amendments per run; needing a 4th is a hard blocker.
- **Context:** the run log (or the ticket) is written at the end of every stage, so the run survives context summarisation. After a summary, re-read it and the plan before continuing.
- **Commits:** nothing is committed to the project repo before stage 6; `ship` commits the whole change.

## Pipeline

Print one status line when each stage starts and ends (`[autopilot] 3/6 verify: started`, `... done: 12 pass, 1 fail`). Keep a run log (see "Run log").

### 0. Pre-flight (do this before stage 1; not counted as a stage)
Work through this checklist in order before stage 1:
1. **Plans directory:** use the one the repo's docs name, else `plans/`; create it if missing.
2. **Resume check:** look for a run log whose first line is this run's key (`grep -lxF -- "<key>" <plans dir>/*-autopilot-*.md`; the key is the plan path, or the requirement's first line with whitespace collapsed) or, in ticket mode, the ticket's `### Autopilot run` subsection. If found, reuse its base, starting commit, dirty-file list, branch and loop counters, say where you resume, skip the rest of this checklist, and continue from the first unfinished stage. Never re-take the dirty-file snapshot on resume: the work itself would count as pre-existing.
3. **Starting state:** record the current branch, the base branch the PR will target (per the repo's branching model), the starting commit (`git rev-parse HEAD`) and `git status --short`. Files dirty now are not part of the work: never revert or edit them (a plan that names one stops the run in stage 1), and pass the list to `ship` so they stay out of the commits. If the branch carries a recorded `WIP commit <sha> (unverified)` (on the `WIP commit:` line, left by a stopped batch run), use that commit's parent as the starting commit, so the WIP work is verified and reviewed. If it is recorded as `on wip/<branch>` (pushed by a stopped cloud batch run, because its container did not survive), first run `git fetch origin wip/<branch>` and `git merge --ff-only FETCH_HEAD`, so the commit is on this branch. If the fast-forward fails, stop and report it. Pass the starting commit as the diff base to `verify` and `review` (the base branch is for `ship`), so commits already on the branch are not judged as this work.
4. **Working branch:** if you are on the default branch or an integration branch (such as `develop`), create the working branch now, per the repo's naming rule (else `<type>/<short-topic>`), from the branch the model says (for git-flow features, the integration branch); uncommitted files carry over. If already on a suitable working branch, stay. Record the branch.
5. **Tools:** check what later stages need: test runner, Docker or whatever the app needs, a browser (Playwright or a browser tool), `gh` or GitHub MCP tools for the PR. A missing tool that blocks shipping is a hard blocker now, not after the work is done; one that only limits verification is recorded and its scenarios will be BLOCKED.
6. **Run log:** create it (see "Run log"; in ticket mode the `### Autopilot run` subsection) and write all of the above to it before stage 1, so it survives context loss.

### 1. Plan
Invoke `plan` with the requirement. Output: the validated plan file.
- **Owner decisions:** `plan` collects what only the user can decide under `Decisions for the owner` and asks after its validation loop (its step 6). In automatic mode you are the one it asks: answer each entry yourself with its recommended option, record each as an assumption in the decision log, and let `plan` finish its step 6 (apply the answers, remove the `blocked until the owner decides` marks, run its post-decision round). The plan then ends `Validated - assumptions logged`. Do not leave entries open and do not skip that round. The one exception is an entry whose every option breaks a documented rule: that is the hard blocker described below. In manual mode `plan` asks the user itself, after the loop and before the stage gate.
- If the plan ends `Draft - unresolved issues` because the validators were unavailable, re-invoke `plan` once; if it fails again, it is a hard blocker. An unvalidated plan never proceeds.
- If the plan ends `Draft - unresolved issues` otherwise: when the open issues can be settled by a safe assumption, record the assumption as a decision, add it to the plan's Validation log, set its Status to `Validated - assumptions logged`, and continue. A design or product question ("which approach", "should X also do Y") always has a safe assumption: take the recommended option that meets the requirement. It is only a hard blocker when the plan cannot be carried out at all: information that cannot be assumed (a credential, an external account, a value nothing in the repo or the requirement suggests), or a requirement that cannot be met without breaking a documented rule (if a compliant approach exists, use it and log it).
- If `plan` split the requirement into several plan files: under `batch-autopilot`, the batch runs them as stacked sub-items (its stage 1 override). Run on its own, stop after planning (hard blocker): one run ships one PR. Report the plan paths and tell the user to run autopilot once per plan, or `batch-autopilot` on them.
- If the input was a path to an existing plan file whose Status starts with `Validated`, skip this stage and use it.
- Record the plan path in the run log. If the plan names a file from the pre-flight dirty list, stop (hard blocker): its earlier uncommitted edits would be reviewed and committed as this work. Ask the user to commit or set them aside first.

### 2. Implement
Invoke `implement` with the plan path and the pre-flight starting state (branch, starting commit, dirty files), so it reuses them instead of re-taking them. Its baseline test result lives in the plan's Implementation notes (ticket mode: the ticket's); the run log points there rather than copying it. Output: the implemented change and its smoke-test results.

### 3. Verify
Invoke `verify` with the plan path, the starting commit as diff base and the pre-flight dirty-file list. Output: the verification plan, evidence and results (PASS, FAIL, BLOCKED per scenario).

### 4. Fix verification failures
Loop while verification has any FAIL, up to 10 rounds:
1. Triage each failing scenario first: a product defect, a wrong expectation (the expected result contradicts the requirement or plan), or a harness or environment bug. Only a product defect is fixed in the source. A harness bug is fixed in the harness script. A wrong expectation is never edited in place: mark the row `superseded by <new ID>` with the reason and the requirement quote (in ticket mode, as a dated note in Verification instead of editing the row), add the corrected scenario under a new ID, and log it as a decision. At most one expectation correction per scenario, and every correction is listed in the PR notes. Never change an expectation just to match observed behaviour. For product defects, use the reproduction and expected result as the spec, find the root cause in the source and fix it with the smallest correct edit. Add or update a regression test, unless an existing test already fails on the defect (record which). Follow the repo's standards.
2. Run the tests for the changed directories and their parents; compare with implement's recorded baseline to tell new failures from old.
3. Invoke `verify` in re-run mode for the failed scenarios, every replacement scenario added this round, and any scenario that touches the code you changed (it appends to the same verification plan and evidence folder, not a new one). Re-running the plan's existing harness scripts and recording a new Results section per verify's re-run rules is a valid re-run.
4. A round that fixes nothing new or re-fails the same way is still a round. If the same scenario fails the same way in 3 consecutive rounds, stop early as a hard blocker. After round 10 with FAIL remaining, stop: do not continue to review or ship, and report the open failures with their repros.
BLOCKED scenarios are not failures. Try to unblock them if the cause is fixable within the throwaway environment; otherwise carry them forward as notes for the PR. If a success criterion has no PASS scenario at all (everything covering it is BLOCKED), say so prominently in the PR's Testing section as not verified live.
Exit the loop when no scenario is FAIL.

### 5. Review and resolve
Loop up to 5 iterations:
1. Invoke `review` on the change (against the requirement and the plan), with the target pinned: the starting commit from pre-flight as the base, limited to files the work changed. Leave out the pre-flight dirty files (unless the work changed them too) and `plans/` artifacts, evidence and temp scripts.
2. If the review has no kept findings, or only pre-existing ones, the loop ends clean.
3. If its kept findings (pre-existing aside) are only NITs, invoke `resolve-review` once for them and end the loop clean; do not start another review round just for NITs.
4. Otherwise invoke `resolve-review` on that report, so valid findings are fixed and invalid ones rejected with evidence. The loop ends clean when, after this resolve, every kept BLOCKER, MAJOR and MINOR is Fixed or Rejected and any BLOCKER or MAJOR fix touched only tests, comments or docs (nothing for another review to judge in runtime code); otherwise start the next iteration. Pre-existing findings are deferred, not fixed, and listed in the PR notes as follow-ups.
After every iteration, including the one that ends the loop, write every kept finding and its outcome (Fixed with the files and functions, components or sections the fix changed; Rejected with evidence; Deferred; Pre-existing) to a review rounds file next to the plan (`<plan name>-review-rounds.md`; ticket mode: Review history already holds it) and record its path in the run log. Point the next `review` to that record as its earlier rounds, so repeats are dismissed even after context loss and so `review` can count the round: it covers the whole change in rounds 1 and 2 and only what the fixes changed from round 3.
When the loop ends, either it is clean or 5 iterations were used. If findings remain after iteration 5, continue to shipping and include the unresolved findings in the PR notes.

**Re-verify.** If any `resolve-review` run changed runtime behaviour, invoke `verify` in re-run mode before shipping, naming the scenario IDs whose Files column includes the files it changed. Leave out scenarios BLOCKED by a tool pre-flight recorded as missing. A FAIL sends you back to stage 4 with the rounds that remain of its 10, then through one more review iteration if any remain of the 5. Before that review iteration, add the stage-4 fixes to the review rounds record as `Verification fix` entries (each file and the function, component or section changed): from round 3 `review` looks only at the fixes listed there, so a fix missing from the record would go unreviewed. If none remain, ship once verification passes and list those fixes in the PR notes as not reviewed. Fixes that touched only tests, comments or docs need no re-verify.

### 6. Ship
Invoke `ship`: it commits the change, pushes and opens the PR. In automatic mode, exclude unrelated or sensitive files from the commits, including files from the pre-flight dirty list that the work did not change (record this as a decision), instead of asking. If the branch conflicts with the base, do not rebase or merge: push, open the PR, and list the conflicting files in the PR notes. If a PR already exists and its description no longer matches the diff, update the body yourself (record it as a decision); invoking autopilot is the approval. Pass along the PR notes: decisions that matter to a reviewer, a short approach summary, verification counts and BLOCKED scenarios, unresolved review findings and follow-ups. Pass substance only, never local paths to plans, evidence or tickets. End with the PR link.

## Decision handling

Where a sub-skill shows something to the user before acting (for example `resolve-review`'s triage table), in automatic mode write it to the run log and the stage summary and carry on; there is no pause.

The sub-skills sometimes ask the user a question or wait for a decision. Under autopilot you make those decisions:
- Choose the recommended option, or the safest reasonable one. Tell the sub-skill's flow the answer yourself; do not forward the question to the user. This covers, for example: `plan`'s `Decisions for the owner` (see stage 1) and its closing "the user decides when to execute"; `implement`'s "ask when a deviation changes scope or design"; `resolve-review`'s "Needs your decision" findings (fix with the recommended option when it stays within the requirement, otherwise defer as a follow-up in the PR notes); and `ship`'s questions about files and conflicts (see stage 6).
- Decisions made in place of the user are flagged as assumptions in the PR notes and the final report, so the user can review them after the fact.
- Record every such decision in the decision log with the stage, the question, the choice and the reason.
- Automatic mode: show the stage's decisions in chat when the stage ends, then continue.
- Manual mode: show them as part of the stage's approval request.

## Manual mode

- Stages 1 to 5 run to completion first, including their internal loops. Then present: what the stage did, key results, the decisions made, and the files changed. Ask the user to accept, reject, or request changes.
- Stage 6 (ship) is outward-facing, so show a preview before acting: the commits and file list, the PR title and body, the target branch. Ask for approval, then ship.
- **Accept:** continue to the next stage.
- **Request changes:** apply the feedback (re-run the stage or amend its output as needed, keeping the stage's own rules and caps), then ask again.
- **Reject:** stop the pipeline and report what exists so far. Do not undo work unless asked.
- Approval prompts use AskUserQuestion with the options Accept, Request changes and Reject; the user can type feedback under Other.

## Hard blockers (stop in either mode)

This list is complete. "It needs the user's input", "it is a big change", "it is a design decision" and "a dependency is not merged yet" are not on it. In automatic mode none of them stops the run.
Stop and report instead of guessing when: there is no requirement; the requirement is an epic key (see "Ticket mode"); any stage-1 stop above; the work would be destructive to real data or existing containers outside a throwaway environment; required access or tools are missing and cannot be set up; the fix loop hits its cap or its early stop; a 4th plan amendment is needed; checks fail in a way you cannot fix; shipping fails for a non-network reason (for example a rejected push). The report says which stage stopped, why, what state the repo is in, and what the user can do next.

## Run log

Create `plans/<slug>-autopilot-<date>.md` in pre-flight (slug: a few words from the requirement) with the run's key alone on its first line and the full requirement below it, and update it after each stage: mode, the pre-flight record (branch, base, starting commit, dirty files), the plan and verification paths, the review rounds file, each stage's outcome and commits, the loop counts (fix rounds used of 10, review iterations used of 5, plan amendments used of 3, re-verifies run), the decision log, and open items. It is the source for the final report. Keep it out of the commits (`ship` always excludes `*-autopilot-*` files).

## Ticket mode

Applies when the input is a ticket key (see the `ticket` skill, "Use from other skills"). The Jira issue replaces the plan, verification and run-log files in `plans/`, so a later conversation can resume the run.
- **Epic keys:** an epic is not a requirement autopilot runs; it is a hard blocker in either mode. List its unfinished children (as `/ticket list epic <KEY>`), suggest running autopilot on one of them or `batch-autopilot epic <KEY>` for its `To Do` children, and stop.
- **Start:** read the ticket (`ticket` skill, "Reading a ticket"). Its Description and Acceptance Criteria are the requirement. If a Plan, Implementation Notes or Review history already exist, continue from the first stage not yet done instead of restarting, and say where you resume. Move the issue to `In Progress` and add a Log line (`autopilot started`); if the ticket's epic is `To Do`, move it to `In Progress` too (ticket `start` rule).
- **Sub-skills:** invoke each one with the ticket key so it works in ticket mode (plan in the Plan field, progress in Implementation Notes, scenarios in Verification comments, rounds in Review history comments).
- **Run log:** no `plans/` run log. Pre-flight creates two subsections at the top of the Implementation Notes field: `### Autopilot run (YYYY-MM-DD)` and `### Autopilot decisions (YYYY-MM-DD)`. The run subsection uses this template and is updated at the end of every stage and loop round (not continuously); resume reads it:
  ```
  - Mode: automatic | manual
  - Branch: <name>  PR base: <branch>  Starting commit: <sha>
  - Dirty before start: <files or none>
  - Work files: <files changed by the work so far>
  - Counters: fix <n>/10, review <n>/5, plan amendments <n>/3, re-verify <n>
  - Last completed stage: <n name>  Next: <n name>
  - Hard blocker: <stage and reason, or none>
  - WIP commit: <sha (unverified), or none>
  ```
  Below them, the other skills' subsections go in this order: `### Baseline`, `### Implementation progress`, `### Smoke test`, `### Fix loop`, then the `PR:` line last. Insert each in its place rather than appending at the end. After each stage add a Log line (stage, outcome, loop counts used). Record the decision log under `### Autopilot decisions (YYYY-MM-DD)` in Implementation notes, and update it as decisions are made.
- **Fix loop (stage 4):** record each round's triage, root cause, fix and re-verification result under `### Fix loop` in Implementation notes.
- **Field size:** Implementation Notes is one field of at most 29,000 characters (`ticket` skill, "Writing rules"). If the run's notes would outgrow it, move the oldest finished subsections (`### Baseline`, `### Smoke test`, closed rounds of `### Fix loop`) into a comment whose first line is `## Implementation notes (part k)` and keep the run and decisions subsections in the field.
- **Ticket sync:** the ticket is the Jira issue, which is shared and always current: there is nothing to pull, commit or push. Read it before the first step in pre-flight. At the end of pre-flight, of every stage, and of every fix round and review iteration, write the run subsection and the Log line (`autopilot <stage> <outcome>`) to the issue, together with whatever the stage's skill had not yet written. Never start the next stage with the issue out of date. A Jira write that fails (rate limit, timeout) does not stop the run: follow the `ticket` skill's "Failed writes", keep the content in the conversation and write it again at the next stage end.
- **Before the final report:** check that the issue holds the last stage's results (read the Implementation Notes and the Log). Write anything missing. If a write still fails, say so under "Needs your attention" with what is missing.
- **Ship:** the ticket key may appear in commits, branch and PR, and the PR may link the issue (`https://archit342000.atlassian.net/browse/<KEY>`); local ticket and evidence paths never do (see the `ship` skill). Leave the status `In Progress`; tell the user to run `/ticket close <KEY>` when done.
- **Hard blockers:** also log the stage, reason and repo state in the ticket (Implementation Notes and a Log line).

## Final report

When the pipeline ends, report: the outcome (shipped with PR link, or stopped and why), a line per stage, loop counts, the decision log, BLOCKED scenarios, unresolved review findings, and the paths of the run log, plan and verification evidence. In ticket mode, say whether every Jira write succeeded and list any that did not.
End with a "Needs your attention" list: the assumptions made in place of the user's decisions, the PR to merge (and its base if stacked), and, in ticket mode, `/ticket close <KEY>`.

## Constraints & Rules

- **One session:** every stage runs in this conversation. Never create sessions or hand stages to general-purpose sub-agents or background tasks; the only sub-agents are the Haiku validators and reviewers the skills themselves specify.
- **Reuse the skills:** invoke them; do not reimplement their steps. Their own rules (report-only verify, Haiku reviewers, no force-push, and so on) still apply.
- **Autopilot decides:** never forward a sub-skill's question to the user. Decide, log, continue. The only exceptions are the manual-mode approval gates and the hard blockers listed above.
- **Unmerged dependencies don't block:** if the work depends on an unmerged PR, branch from that PR's branch and target it as the PR base (note the stacking in the PR). Never stop to wait for a merge.
- **Caps are hard:** 10 fix rounds in stage 4, 5 iterations in stage 5, 3 plan amendments. Never extend them silently.
- **Never ship over failing verification:** never ship while any scenario is FAIL, including after the re-verify. Stop when the stage 4 cap (or the early stop) is reached with FAIL remaining.
- **Honest accounting:** report failures, unresolved findings, blocked scenarios and assumptions exactly. Do not describe a capped or partial result as clean.
- **No scope creep:** implement the requirement and fix what verification and review find; nothing else.
- **Isolation and safety:** verification and any reproduction use throwaway resources. Nothing destructive touches real data. No force-push, no merge, no commits to the default branch.
- **User-invoked only:** this skill runs only when the user types it.
