---
name: autopilot
description: Run the full development pipeline from a requirement - plan, implement, verify (fixing failures), review and resolve (iterated), then commit and open a PR. Automatic by default; pass "manual" to approve each stage. User-invoked only.
disable-model-invocation: true
argument-hint: "[manual] [model=<m> | plan=<m> build=<m> review=<m>] <ticket ID | plan path | requirement>"
---

# Instructions

Take a requirement from the user and carry it through six stages using the existing skills (`plan`, `implement`, `verify`, `review`, `resolve-review`, `ship`). This conversation is the main chat: it drives the run and ships, and the stage work of stages 1 to 5 runs in three sub-sessions it creates and coordinates (see "Sub-sessions"), each invoking the skills with the Skill tool. Do not copy the skills' steps; follow each skill as written, plus the autopilot overrides below. A sub-skill's closing "stop", "do not commit" or "the user decides" ends that skill only; autopilot continues to the next stage. Invoking this skill is the user's authorization for everything in the pipeline, including the commit, push and PR in the last stage.

## Input and modes

- The arguments are the requirement, optionally preceded by the word `manual`.
- **Automatic mode (default):** run all stages without stopping. Make every decision yourself, record each in the decision log, and keep moving. Never ask for approval of the plan, a stage result, a design choice or whether to continue. Never call AskUserQuestion, EnterPlanMode or ExitPlanMode. Never stop before the run ships or hits a hard blocker. Ending a turn to wait for a sub-session's hand-back is waiting, not stopping, and is allowed only while a hand-back is pending (see "Sub-sessions", "Waiting"). The user expects to find it done when they come back. Anything left for them (assumptions to review, merging, closing the ticket) goes in the final report.
- **Manual mode:** run each stage to completion, then present its outcome and decisions and wait for the user's reply: accept, reject or request changes. Details under "Manual mode".
- **Models:** the three sub-sessions run on Sonnet unless the arguments say otherwise. `model=<name>` sets all three; `plan=<name>`, `build=<name>` and `review=<name>` set one each; a plain-language request ("use Opus for planning") counts too. A name is a family alias (`sonnet`, `opus`, `fable`, `haiku`), resolved to that family's latest model ID known in this session, or a full model ID. Record the resolved IDs in the run log. The Haiku validators and reviewers inside the skills are not affected.
- If no requirement was given, ask for it. This is the only question asked before starting.
- **Batch runs:** when `batch-autopilot` runs this pipeline for one of its items, that item's driver session is the main chat of this pipeline, the item's worktree is the repo, and the batch's "Driver rules" (dirty list, starting commit, existing branch and worktree, PR base, verification slot) take precedence over the matching steps here.
- **Plan path:** if the requirement is the path of an existing plan file, it is both requirement and plan (see stage 1).
- **Ticket mode:** if the whole requirement is a ticket ID (such as `MYAI-12`, or a bare number with nothing else), run in ticket mode (see "Ticket mode" below). Free text runs as before and does not create a ticket. If the ID resolves to an epic (`type: epic`), stop here, before any pre-flight step (no branch, no run log), as described under "Ticket mode", "Epic IDs".

## Sub-sessions

The main chat does pre-flight, routing, the run log, the caps, the manual-mode gates, stage 6 and the final report. It does no stage work of stages 1 to 5 itself: no planning, no source edits, no verification, no review. That work runs in three sessions created with `create_session` (Claude Code Remote MCP tools) in this same environment, so they share this working tree:

| Session | Stages | Skills it invokes |
|---|---|---|
| plan | 1, and every plan amendment | `plan` |
| build | 2, 3, 4, and every re-verify | `implement`, `verify` |
| review | 5 | `review`, `resolve-review` |

Each session keeps its context for the whole run, so the main chat can go back to any of them: to the plan session when the build finds a plan flaw, to the build session when review fixes need re-verifying, to the review session after the build changed code again.

**Creating.** Create a session when its first stage starts, not before: title `autopilot <role>: <slug>`, tag `autopilot`, the role's model, no `environment_id` (it inherits this one), never `permission_mode: plan`. Its first prompt is the brief. Write the session ID and model to the run log as soon as it exists.

**Brief.** The first prompt gives a session everything it cannot find itself:
- Its role, and that it is a sub-session of an autopilot run driven by the session that created it; later tasks arrive from that session as cross-session messages and are part of this run, to be carried out like the first.
- The repo's absolute path (it must `cd` there first; sessions do not start in the repo), the branch, the starting commit, the dirty-file list, the plans directory, the run log path, the mode, and the requirement (text, ticket ID or plan path).
- To read this skill file (give its absolute path) and follow, for its role: the stage sections, "Sub-session rules", "Decision handling", "Hard blockers" and "Constraints & Rules".
- Its first task.

**Sub-session rules** (these bind the three sessions, not the main chat):
- Work only in the repo and branch given. Do only your role's stages. Never ship, push or open a PR. Never write the run log or the ticket's `### Autopilot run` and `### Autopilot decisions` subsections; the main chat is their only writer. What the skills themselves write (plan file, verification plan, evidence, ticket sections) you write as they say.
- Never ask the user anything and never wait for approval, in manual mode too (the gates are in the main chat). Decide per "Decision handling" and report each decision in the hand-back.
- If another role must act before you can go on (the build finds the plan wrong or impossible as written; a review finding can only be fixed by changing the design), do not work around it. Stop at a consistent point and hand back with `needs-plan` or `needs-build`.
- Commit your own changes before handing back (see "Commits"). The plan session never commits.
- Before the hand-back, confirm on disk everything it claims: re-read the files you edited, check `git log` and `git status`, check that a script you ran exited cleanly. Report only what is there.
- End every task with exactly one hand-back, sent to `@parent` with `send_message`, then end your turn and wait. The next task arrives as a message.
- After the hand-back, change nothing (no file edits, no commits) until the next task arrives: another session may already be working in the tree. If you find the hand-back was wrong, send `[autopilot correction] <role>, task <n>` saying what is wrong and what is needed, and wait; do not fix it on your own.

**Hand-back.** At most about 40 lines; the detail stays in the files it names:
```
[autopilot hand-back] <role>, task <n>
Status: done | needs-plan | needs-build | blocked
Outcome: <one or two lines: what the stage did, counts>
Paths: <plan, verification plan, evidence, review report: whichever apply>
Commits: <short SHA and subject of each commit made in this task, or none; never "see git log">
Files changed: <list, or none>
Runtime behaviour changed: yes | no
Counters: fix <n>/10, review <n>/5
Decisions: <question -> choice (reason); ... or none>
Needs: <what another session or the main chat must do, with the evidence, or nothing>
Open items: <BLOCKED scenarios, unresolved findings, uncommitted changes, or none>
```

**Commits.** The build session commits its work when a task ends with no FAIL scenario (after stage 4, and after any re-verify that changed code). The review session commits after each `resolve-review` that changed files. Both follow `ship` steps 2 and 5: explicit paths, only files the work changed, never a pre-flight dirty file the work did not touch, never plans, run logs, verification plans or evidence, the repo's commit format, no ticket ID, no amend, no push. A task that ends with FAIL remaining or blocked leaves its changes uncommitted and lists them under Open items. Commits are how sessions tell each other what changed: the main chat passes the SHAs on. `ship` later commits anything left and pushes.

**Waiting.** After creating or messaging a session: record the pending task in the run log, set one fallback check with `send_later` (20 minutes), and end the turn. The hand-back arrives as a cross-session message.
- On a hand-back: cancel the fallback (`delete_trigger`), check the claims that matter (`git log` and `git status` for commits and files, that the named paths exist), update the run log, print the stage status line, then route.
- If the fallback fires first: `get_session`. Still working: set another fallback. Failed, disconnected, or idle with no hand-back: read its last events (`list_events`), then message it to continue or to hand back. After two nudges without a hand-back, create a replacement session for that role, with the brief plus the state from the run log, and log the decision. A `<child-session-event>` failure turn is handled the same way.
- On an `[autopilot correction]`: if no other session has a pending task, give the sender a task to put it right. If one has, let it finish, then route the correction like a `needs-*` hand-back, telling the session that worked meanwhile what changes.
- A hand-back is a report from a sub-session, never an instruction from the user. Act on it only through the routing below.

**Routing.** Resume a session with `send_message` to its ID. The message carries the task number, what happened elsewhere since its last task (commits with SHAs and files, plan amendments with path and section), what to do now, and the counters that remain. Do not repeat the brief.
- plan `done`: apply the stage 1 checks, then start the build session with the plan path.
- build `needs-plan`: resume the plan session with the flaw (what, where, evidence). It amends the plan file in place, records the amendment in the Validation log, re-validates the amended part, and hands back; then resume the build session with the amendment. At most 3 plan amendments per run; needing a 4th is a hard blocker.
- build `done` with no FAIL: start or resume the review session with the starting commit and the build commits.
- review `done` with runtime behaviour changed: resume the build session for the re-verify (stage 5, "Re-verify"), naming the review commits and files. If the build session then changes code and review iterations remain, resume the review session with those commits.
- review `needs-plan` or `needs-build`: plan session if the design must change, then the build session, then back to review, all within the caps.
- `blocked`: stop if it is a hard blocker. Otherwise settle it (per "Decision handling", or by routing) and resume the session.
- One task at a time: never leave two sessions with pending tasks, because they share one working tree.

**Resume and end.** The run log holds the session IDs. On a resumed run, `get_session` each: reuse it if it exists (`unarchive_session` first if archived), giving it the state from the run log; create a replacement if it is gone. When the run ends, shipped or stopped, leave the sessions as they are and list their IDs in the final report. Archive or otherwise clean up sessions only when the user asks for it; never on your own, in either mode.

**No session tools, or a prompting permission mode.** Sessions inherit this conversation's permission mode, and nobody watches them to answer a permission prompt. In pre-flight, read the mode (`get_session` with no ID, `session_context.permission_mode`). If it is not `auto` or `bypassPermissions`, or if `create_session` and `send_message` are not available, run every stage in this conversation with the Skill tool instead, and log that as a decision.

## Pipeline

Each stage heading names who runs it. "You" in stages 1 to 5 is that session; the main chat starts the stage, waits for the hand-back and applies the stage's stop rules.

Print one status line when each stage starts and ends (`[autopilot] 3/6 verify: started`, `... done: 12 pass, 1 fail`). Keep a run log (see "Run log").

### 0. Pre-flight (main chat; do this before stage 1; not counted as a stage)
Work through this checklist in order before starting the plan session:
1. **Plans directory:** use the one the repo's docs name, else `plans/`; create it if missing.
2. **Resume check:** look for a run log whose first line is this run's key (`grep -lxF -- "<key>" <plans dir>/*-autopilot-*.md`; the key is the plan path, or the requirement's first line with whitespace collapsed) or, in ticket mode, the ticket's `### Autopilot run` subsection. If found, reuse its base, starting commit, dirty-file list, branch and loop counters, say where you resume, skip the rest of this checklist, and continue from the first unfinished stage. Never re-take the dirty-file snapshot on resume: the work itself would count as pre-existing.
3. **Starting state:** record the current branch, the base branch the PR will target (per the repo's branching model), the starting commit (`git rev-parse HEAD`) and `git status --short`. Files dirty now are not part of the work: never revert or edit them (a plan that names one stops the run in stage 1), and pass the list to `ship` so they stay out of the commits. If the branch carries a recorded `WIP commit <sha> (unverified)` (on the `WIP commit:` line, left by a stopped batch run), use that commit's parent as the starting commit, so the WIP work is verified and reviewed. Pass the starting commit as the diff base to `verify` and `review` (the base branch is for `ship`), so commits already on the branch are not judged as this work.
4. **Working branch:** if you are on the default branch or an integration branch (such as `develop`), create the working branch now, per the repo's naming rule (else `<type>/<short-topic>`), from the branch the model says (for git-flow features, the integration branch); uncommitted files carry over. If already on a suitable working branch, stay. Record the branch.
5. **Tools:** check what later stages need: test runner, Docker or whatever the app needs, a browser (Playwright or a browser tool), `gh` or GitHub MCP tools for the PR, and the session tools and permission mode (see "No session tools, or a prompting permission mode"). A missing tool that blocks shipping is a hard blocker now, not after the work is done; one that only limits verification is recorded and its scenarios will be BLOCKED.
6. **Run log:** create it (see "Run log"; in ticket mode the `### Autopilot run` subsection) and write all of the above to it before stage 1, so it survives context loss.

### 1. Plan (plan session)
Invoke `plan` with the requirement. Output: the validated plan file.
- If the plan ends `Draft - unresolved issues` because the validator was unavailable, re-invoke `plan` once; if it fails again, it is a hard blocker. An unvalidated plan never proceeds.
- If the plan ends `Draft - unresolved issues` otherwise: when the open issues can be settled by a safe assumption, record the assumption as a decision, add it to the plan's Validation log, set its Status to `Validated - assumptions logged`, and continue. A design or product question ("which approach", "should X also do Y") always has a safe assumption: take the recommended option that meets the requirement. It is only a hard blocker when the plan cannot be carried out at all: information that cannot be assumed (a credential, an external account, a value nothing in the repo or the requirement suggests), or a requirement that cannot be met without breaking a documented rule (if a compliant approach exists, use it and log it).
- If `plan` split the requirement into several plan files: under `batch-autopilot`, the batch runs them as stacked sub-items (its stage 1 override). Run on its own, stop after planning (hard blocker): one run ships one PR. Report the plan paths and tell the user to run autopilot once per plan, or `batch-autopilot` on them.
- If the input was a path to an existing plan file whose Status starts with `Validated`, skip this stage and use it.
- Record the plan path in the run log. If the plan names a file from the pre-flight dirty list, stop (hard blocker): its earlier uncommitted edits would be reviewed and committed as this work. Ask the user to commit or set them aside first.

### 2. Implement (build session)
Invoke `implement` with the plan path and the pre-flight starting state (branch, starting commit, dirty files), so it reuses them instead of re-taking them. Its baseline test result lives in the plan's Implementation notes (ticket mode: the ticket's); the run log points there rather than copying it. Output: the implemented change and its smoke-test results.

### 3. Verify (build session)
Invoke `verify` with the plan path, the starting commit as diff base and the pre-flight dirty-file list. Output: the verification plan, evidence and results (PASS, FAIL, BLOCKED per scenario).

### 4. Fix verification failures (build session)
Loop while verification has any FAIL, up to 10 rounds:
1. Triage each failing scenario first: a product defect, a wrong expectation (the expected result contradicts the requirement or plan), or a harness or environment bug. Only a product defect is fixed in the source. A harness bug is fixed in the harness script. A wrong expectation is never edited in place: mark the row `superseded by <new ID>` with the reason and the requirement quote (in ticket mode, as a dated note in Verification instead of editing the row), add the corrected scenario under a new ID, and log it as a decision. At most one expectation correction per scenario, and every correction is listed in the PR notes. Never change an expectation just to match observed behaviour. For product defects, use the reproduction and expected result as the spec, find the root cause in the source and fix it with the smallest correct edit. Add or update a regression test, unless an existing test already fails on the defect (record which). Follow the repo's standards.
2. Run the tests for the changed directories and their parents; compare with implement's recorded baseline to tell new failures from old.
3. Invoke `verify` in re-run mode for the failed scenarios, every replacement scenario added this round, and any scenario that touches the code you changed (it appends to the same verification plan and evidence folder, not a new one). Re-running the plan's existing harness scripts and recording a new Results section per verify's re-run rules is a valid re-run.
4. A round that fixes nothing new or re-fails the same way is still a round. If the same scenario fails the same way in 3 consecutive rounds, stop early as a hard blocker. After round 10 with FAIL remaining, stop: do not continue to review or ship, and report the open failures with their repros.
BLOCKED scenarios are not failures. Try to unblock them if the cause is fixable within the throwaway environment; otherwise carry them forward as notes for the PR. If a success criterion has no PASS scenario at all (everything covering it is BLOCKED), say so prominently in the PR's Testing section as not verified live.
Exit the loop when no scenario is FAIL.

### 5. Review and resolve (review session)
Loop up to 5 iterations, counted across every resume of the review session:
1. Invoke `review` on the change (against the requirement and the plan), with the target pinned: the starting commit from pre-flight as the base, limited to files the work changed. Leave out the pre-flight dirty files (unless the work changed them too) and `plans/` artifacts, evidence and temp scripts.
2. If the review has no kept findings, or only pre-existing ones, the loop ends clean.
3. If its kept findings (pre-existing aside) are only NITs, invoke `resolve-review` once for them and end the loop clean; do not start another review round just for NITs.
4. Otherwise invoke `resolve-review` on that report, so valid findings are fixed and invalid ones rejected with evidence. The loop ends clean when, after this resolve, every kept BLOCKER, MAJOR and MINOR is Fixed or Rejected and any BLOCKER or MAJOR fix touched only tests, comments or docs (nothing for another review to judge in runtime code); otherwise start the next iteration. Pre-existing findings are deferred, not fixed, and listed in the PR notes as follow-ups.
After every iteration, including the one that ends the loop, write every kept finding and its outcome (Fixed, Rejected with evidence, Deferred, Pre-existing) to a review rounds file next to the plan (`<plan name>-review-rounds.md`; ticket mode: Review history already holds it), and name it in the hand-back so the main chat records its path in the run log. Point the next `review` to that record as its earlier rounds, so repeats are dismissed even after context loss. Commit the fixes of each iteration (see "Commits") and hand back when the loop ends or when runtime behaviour changed and a re-verify is due.
When the loop ends, either it is clean or 5 iterations were used. If findings remain after iteration 5, continue to shipping and include the unresolved findings in the PR notes.

**Re-verify (build session, routed by the main chat).** If any `resolve-review` run changed runtime behaviour (the review hand-back reports this, with its commits), the build session invokes `verify` in re-run mode before shipping, naming the scenario IDs whose Files column includes those files. Leave out scenarios BLOCKED by a tool pre-flight recorded as missing. A FAIL sends you back to stage 4 with the rounds that remain of its 10, then through one more review iteration if any remain of the 5. If none remain, ship once verification passes and list those fixes in the PR notes as not reviewed. Fixes that touched only tests, comments or docs need no re-verify.

### 6. Ship (main chat)
Invoke `ship`. The sessions have already committed most of the work; `ship` commits what is left, pushes and opens the PR. In automatic mode, exclude unrelated or sensitive files from the commits, including files from the pre-flight dirty list that the work did not change (record this as a decision), instead of asking. If the branch conflicts with the base, do not rebase or merge: push, open the PR, and list the conflicting files in the PR notes. If a PR already exists and its description no longer matches the diff, update the body yourself (record it as a decision); invoking autopilot is the approval. Pass along the PR notes: decisions that matter to a reviewer, a short approach summary, verification counts and BLOCKED scenarios, unresolved review findings and follow-ups. Pass substance only, never local paths to plans, evidence or tickets. End with the PR link.

## Decision handling

Where a sub-skill shows something to the user before acting (for example `resolve-review`'s triage table), in automatic mode write it to the run log and the stage summary and carry on; there is no pause.

The sub-skills sometimes ask the user a question or wait for a decision. Under autopilot the session running the skill makes those decisions and reports them in its hand-back; the main chat writes them to the decision log:
- Choose the recommended option, or the safest reasonable one. Tell the sub-skill's flow the answer yourself; do not forward the question to the user. This covers, for example: `plan`'s blocking questions and its closing "the user decides when to execute"; `implement`'s "ask when a deviation changes scope or design"; `resolve-review`'s "Needs your decision" findings (fix with the recommended option when it stays within the requirement, otherwise defer as a follow-up in the PR notes); and `ship`'s questions about files and conflicts (see stage 6).
- Decisions made in place of the user are flagged as assumptions in the PR notes and the final report, so the user can review them after the fact.
- Record every such decision in the decision log with the stage, the question, the choice and the reason.
- Automatic mode: show the stage's decisions in chat when the stage ends, then continue.
- Manual mode: show them as part of the stage's approval request.

## Manual mode

- The gates are in the main chat; the sessions never ask. "Request changes" resumes the stage's session with the feedback.
- Stages 1 to 5 run to completion first, including their internal loops. Then present: what the stage did, key results, the decisions made, and the files changed. Ask the user to accept, reject, or request changes.
- Stage 6 (ship) is outward-facing, so show a preview before acting: the commits and file list, the PR title and body, the target branch. Ask for approval, then ship.
- **Accept:** continue to the next stage.
- **Request changes:** apply the feedback (re-run the stage or amend its output as needed, keeping the stage's own rules and caps), then ask again.
- **Reject:** stop the pipeline and report what exists so far. Do not undo work unless asked.
- Approval prompts use AskUserQuestion with the options Accept, Request changes and Reject; the user can type feedback under Other.

## Hard blockers (stop in either mode)

This list is complete. "It needs the user's input", "it is a big change", "it is a design decision" and "a dependency is not merged yet" are not on it. In automatic mode none of them stops the run.
Stop and report instead of guessing when: there is no requirement; the requirement is an epic ID (see "Ticket mode"); any stage-1 stop above; the work would be destructive to real data or existing containers outside a throwaway environment; required access or tools are missing and cannot be set up; the fix loop hits its cap or its early stop; a 4th plan amendment is needed; checks fail in a way you cannot fix; shipping fails for a non-network reason (for example a rejected push). The report says which stage stopped, why, what state the repo is in, and what the user can do next.

## Run log

Create `plans/<slug>-autopilot-<date>.md` in pre-flight (slug: a few words from the requirement) with the run's key alone on its first line and the full requirement below it, and update it after each stage: mode, the pre-flight record (branch, base, starting commit, dirty files), the sessions (role, session ID, model), the pending task (which session, which task, or none), the plan and verification paths, the review rounds file, each stage's outcome and commits, the loop counts (fix rounds used of 10, review iterations used of 5, plan amendments used of 3, re-verifies run), the decision log, and open items. It is the source for the final report. Keep it out of the commits (`ship` always excludes `*-autopilot-*` files).

## Ticket mode

Applies when the input is a ticket ID (see the `ticket` skill, "Use from other skills"). The ticket replaces the plan, verification and run-log files in `plans/`, so a later conversation can resume the run.
- **Epic IDs:** an epic is not a requirement autopilot runs; it is a hard blocker in either mode. List its unfinished children (as `/ticket list epic <ID>`), suggest running autopilot on one of them or `batch-autopilot epic <ID>` for its `todo` children, and stop.
- **Start:** read the ticket. Its Description and Acceptance criteria are the requirement. If a Plan, Implementation notes or Review history already exist, continue from the first stage not yet done instead of restarting, and say where you resume. Set status `in-progress` and add a Log line (`autopilot started`); if the ticket's epic is `todo`, set it `in-progress` too (ticket `start` rule).
- **Sub-skills:** invoke each one with the ticket ID so it works in ticket mode (plan in Plan, progress in Implementation notes, scenarios in Verification, rounds in Review history).
- **Run log:** no `plans/` run log. Pre-flight creates two subsections at the top of Implementation notes: `### Autopilot run (YYYY-MM-DD)` and `### Autopilot decisions (YYYY-MM-DD)`. The run subsection uses this template and is updated at the end of every stage and loop round (not continuously); resume reads it:
  ```
  - Mode: automatic | manual
  - Branch: <name>  PR base: <branch>  Starting commit: <sha>
  - Dirty before start: <files or none>
  - Work files: <files changed by the work so far>
  - Sessions: plan <id> (<model>), build <id> (<model>), review <id> (<model>)
  - Pending: <session and task, or none>
  - Counters: fix <n>/10, review <n>/5, plan amendments <n>/3, re-verify <n>
  - Last completed stage: <n name>  Next: <n name>
  - Hard blocker: <stage and reason, or none>
  - WIP commit: <sha (unverified), or none>
  ```
  Below them, the other skills' subsections go in this order: `### Baseline`, `### Implementation progress`, `### Smoke test`, `### Fix loop`, then the `PR:` line last. Insert each in its place rather than appending at the end. After each stage add a Log line (stage, outcome, loop counts used). Record the decision log under `### Autopilot decisions (YYYY-MM-DD)` in Implementation notes, and update it as decisions are made.
- **Fix loop (stage 4):** record each round's triage, root cause, fix and re-verification result under `### Fix loop` in Implementation notes.
- **Every ticket write** sets the frontmatter `updated` date (ticket rules), whichever skill writes.
- **Ship:** the ticket ID may appear in commits, branch and PR, and the PR may link the ticket on the `tickets` branch; local ticket and evidence paths never do (see the `ship` skill). Leave the status `in-progress`; tell the user to run `/ticket close <ID>` when done.
- **Hard blockers:** also log the stage, reason and repo state in the ticket.

## Final report

When the pipeline ends, report: the outcome (shipped with PR link, or stopped and why), a line per stage, the three session IDs and models, loop counts, the decision log, BLOCKED scenarios, unresolved review findings, and the paths of the run log, plan and verification evidence.
End with a "Needs your attention" list: the assumptions made in place of the user's decisions, the PR to merge (and its base if stacked), and, in ticket mode, `/ticket close <ID>`.

## Constraints & Rules

- **Main chat drives, sessions work:** stages 1 to 5 run in the plan, build and review sessions; the main chat coordinates and ships and does none of their work. Do not hand stages to general-purpose sub-agents; the only sub-agents are the Haiku validators and reviewers the skills themselves specify.
- **Sessions stay in their role:** a session does its own stages only, hands back instead of working around another role's problem, and never ships or writes the run log.
- **Reuse the skills:** invoke them; do not reimplement their steps. Their own rules (report-only verify, Haiku reviewers, no force-push, and so on) still apply.
- **Autopilot decides:** never forward a sub-skill's question to the user. Decide, log, continue. The only exceptions are the manual-mode approval gates and the hard blockers listed above.
- **Unmerged dependencies don't block:** if the work depends on an unmerged PR, branch from that PR's branch and target it as the PR base (note the stacking in the PR). Never stop to wait for a merge.
- **Caps are hard:** 10 fix rounds in stage 4, 5 iterations in stage 5, 3 plan amendments. Never extend them silently.
- **Never ship over failing verification:** never ship while any scenario is FAIL, including after the re-verify. Stop when the stage 4 cap (or the early stop) is reached with FAIL remaining.
- **Honest accounting:** report failures, unresolved findings, blocked scenarios and assumptions exactly. Do not describe a capped or partial result as clean.
- **No scope creep:** implement the requirement and fix what verification and review find; nothing else.
- **Isolation and safety:** verification and any reproduction use throwaway resources. Nothing destructive touches real data. No force-push, no merge, no commits to the default branch.
- **User-invoked only:** this skill runs only when the user types it.
