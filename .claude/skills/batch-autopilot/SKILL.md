---
name: batch-autopilot
description: Run the autopilot pipeline over several tickets or requirements in one go, in parallel where dependencies allow. Orders tickets by their Blocks links, runs every item whose blockers have shipped at the same time (each in its own worktree with its own session), stacks each dependent PR on its blocker's branch, skips dependents of anything that fails, and keeps a batch log so the run can resume. Automatic by default; pass "manual" to approve the batch plan and each item's stages. User-invoked only.
disable-model-invocation: true
argument-hint: "[manual] [parallel=<n>] [model=<m>] <ticket keys... | all | epic <KEY> | \"requirement\" \"requirement\" ...>"
---

# Instructions

Carry several items through the full autopilot pipeline, so the user invokes one command instead of one autopilot per item. This conversation is the batch chat: it plans the batch, gives every item its own git worktree and its own item session, runs all items that are ready at the same time, and collects their outcomes. One session per item, no more: each item session reads `autopilot/SKILL.md` (the sibling of this skill's directory) and runs that whole pipeline by itself, every stage in that one session, with the batch overrides below. Item sessions never create sessions of their own. Invoking this skill is the user's authorization for everything every item's pipeline does, including commits, pushes and PRs.

## Run to the end without asking (automatic mode)

The user queues a batch and walks away for hours. They expect it to be finished when they come back, not waiting on a question asked ten minutes in. In automatic mode:
- **Never ask for approval of anything:** not the batch plan, not the order or bases, not an item's plan or design, not a stage result, not "should I continue". Size makes no difference. Neither does how long it runs or how many tickets there are. Print the batch plan and start the first items in the same turn.
- **No approval tools:** never call AskUserQuestion, EnterPlanMode or ExitPlanMode, and never stop while an item is `pending` or `running`, except for a batch-level stop listed under "Batch-level stops". Ending a turn to wait for the item sessions is waiting, not stopping (see "Waiting"). A sub-skill's "ask the user", "stop" or "the user decides" is answered by the session running it (autopilot's "Decision handling"). It never goes to the user.
- **Decide, don't wait:** for design, product or scope questions, pick the recommended or safest option that meets the ticket. Log it as an assumption in the decision log, state it in the PR notes, and list it in the final report under "Needs your attention". "Needs the user's decision" is never a reason to stop or skip an item.
- **Do everything you were given:** never leave a named or expanded item out of the batch because it looks large, risky, conflicting or design-heavy. Order it, base it and run it. Items with `conflicts_with` links still all run. The final report gives the merge order.
- **Save the user's part for the end:** merging PRs, closing tickets, retargeting stacked PRs, updating branches after merges and reviewing assumptions all wait for the final report. None of them is a reason to pause mid-batch.

## Input

- Items are ticket keys (`MA-3 MA-7`, or bare numbers), `all`, `epic <KEY>`, or quoted free-text requirements. They may be mixed. An optional leading `manual` selects manual mode.
- **Options:** `parallel=<n>` caps how many items run at once (default: no cap, every ready item runs). `model=<m>` sets the item sessions' model (default Sonnet): a family alias (`sonnet`, `opus`, `fable`, `haiku`), resolved to that family's latest model ID known in this session, or a full model ID; record the resolved ID in the batch log. The Haiku validators and reviewers inside the skills are not affected. A plain-language request counts too.
- `all` means every `To Do` ticket of the current repo (JQL `project = <KEY> AND status = "To Do"`) without a PR recorded in its Implementation notes (`PR: <url>, branch <name>, base <base>`). Epics are never items themselves. `epic <KEY>` (or an epic's key given as a plain item) expands to that epic's children (JQL `parent = <KEY>`) by the same rule as `all`, in place of the epic; its `In Progress` children still need to be named. If the key after `epic` is not an epic, or the epic expands to no children, report it and drop it. `In Progress` tickets are included only when named, since another conversation may be working on them. Tickets with a recorded PR are already shipped; they can still serve as external bases (step 2.2).
- Resolve tickets per the `ticket` skill ("Ticket keys"), reading them from Jira (one search for the set, `ticket` skill "Reading a ticket"), so statuses, links and recorded PRs are current. A key that does not resolve is reported and dropped; tickets already `Done` are dropped with a note. An explicitly named ticket that already has a recorded PR (open or merged) is dropped too, with a note to use plain `autopilot` to rework it.
- Free-text items have no links: they are independent and based on the integration branch.
- If no items were given, ask for them. This is the only question asked before starting, and in automatic mode the only question asked at all.

## Steps

### 1. Batch pre-flight
**Where it runs** (before step 1): call `get_environment` with no ID. If it describes this session's environment, this conversation runs in an Anthropic cloud environment: use cloud mode (see "Cloud mode") and record it in the batch log. If it refuses because this session's environment is not an Anthropic cloud environment, or the tool is not available, run locally as written.

1. **Plans directory:** the one the repo's docs name, else `plans/`; create it if missing. The batch log lives in this checkout's plans directory; each item's own run log, plan and evidence live in its worktree's plans directory (or its ticket).
2. **Resume check:** the batch key is `batch:` followed by the resolved ticket keys in input order (after epic expansion) and then the free-text items' first lines, whitespace collapsed; options and `manual` are not part of it. Look for a batch log whose first line is that key (`grep -lxF -- "<key>" <plans dir>/batch-*-autopilot-*.md`; in cloud mode, search the batch-logs clone after pulling it). If found, resume:
   - reuse its order, bases, worktrees, slots, verification mode and item statuses;
   - if no item is `pending` or `running`, the batch is finished: report its results and stop. Stopped and skipped items are retried with plain `autopilot` or a new batch that names them;
   - for each `running` item, `get_session` its item session: if it exists, message it for its state and carry on waiting; if it is gone, create a replacement session for the same worktree (its autopilot resumes through its own resume check);
   - then start whatever is ready (step 3).
3. **Starting state:** record the current branch and the integration branch (per the repo's branching model, else the default branch). This checkout is never switched, edited or committed to by the batch: all work happens in worktrees, so files dirty here belong to no item and every item's dirty list is empty.
4. **Tools:** run autopilot's pre-flight tool check once for the batch; a missing tool that blocks shipping stops the batch before any item starts. Also confirm a way to read PR state (`gh pr view --json state` or the GitHub MCP tools) if any external base is involved. Item sessions inherit this conversation's permission mode, and nobody watches them to answer a permission prompt: read the mode (`get_session` with no ID, `session_context.permission_mode`). If it is not `auto` or `bypassPermissions`, or if `create_session` and `send_message` are not available, the batch still runs, one item at a time: the batch chat runs each item's autopilot itself, in the item's worktree, and logs that as a decision.
5. **Verification mode:** decide whether several items can be verified live at the same time on this machine, from the repo's docs, compose files, test scripts and config:
   - **parallel** when each item can get its own resources from a slot number (ports offset by it, container, compose project, database and temp names suffixed with it), or verification uses no shared resource at all;
   - **serial** when something cannot be duplicated: a fixed port or name that cannot be changed from outside, a single device, emulator, GPU or external account, or a stack too heavy to run several times.
   Record the mode and the reason in the batch log. Only verification is ever serial; planning, implementation and review always run in parallel.

### 2. Build the batch plan
1. **Dependency graph:** from each ticket's `Blocks` issue links (`ticket` skill, "Links"), build the graph. If it has a cycle, report the cycle and stop (nothing has run yet).
2. **Blockers outside the batch:**
   - `done`: satisfied.
   - Not `done` with a recorded PR: check the PR's state. Merged: satisfied. Open: an **external base** (its recorded branch). An unmerged blocker PR is never a reason to wait: stack on it. Closed without merging: the item is skipped (reason: blocker PR closed).
   - `To Do` with no recorded PR: **pull it into the batch** as an added item, along with its own unfinished `To Do` blockers (transitively, by these same rules). The dependent cannot ship without it, so building it is part of the request. Record each addition as a decision and list it in the final report.
   - `In Progress` with no recorded PR (another conversation may own it): if its working branch exists on the remote (from its Implementation Notes or `Branch` field), use that branch as an external base and note in the PR that the base is unshipped work. Otherwise the item is **skipped** (reason: blocked by `<KEY>`, in progress elsewhere with no pushed branch).
   - Skipping an item skips everything that depends on or is stacked on it, transitively. Skipping is only for the cases named here. It is never because a blocker "must be merged first": unmerged blockers are what stacking is for.
3. **Order:** topological order, like `ticket order` waves: wave by wave; within a wave, for `conflicts_with` pairs (named in the Log lines of the `Relates` links) follow the ticket rule (fewer conflicts first, then lower number), otherwise lowest number first. Free-text items go last, in the order given. The order decides the merge order in the final report and who starts first under a `parallel=<n>` cap; it does not make independent items wait for each other.
4. **Base per item** (where its branch starts and what its PR targets):
   - No unfinished blockers: the integration branch.
   - One unfinished blocker (in the batch, or an external base): that blocker's working branch. The PR is stacked on it.
   - Several unfinished blockers: a PR has one base, so they must form a chain. If one blocker already depends (transitively) on all the others, use it. Otherwise build one chain: take the blockers plus their unfinished in-batch ancestors, put them in a single topological order (lowest ID first among ties), with an external base first. With two or more external bases, chain on the first (lowest ID). Right after creating the item's worktree in step 3.2, merge each other external base's branch into its branch with a normal merge commit (`git merge --no-ff origin/<branch>`). Never rebase. If that merge conflicts, resolve it when the resolution is clear, otherwise stop the item. The PR notes say which other PRs were merged in and that its diff shrinks once they land. Each chain member is based on the one before it, and the item on the last. The blockers moved onto another blocker get a **stacked-on** edge: for readiness, for skipping and for PR notes it counts like a dependency, and their PRs say the stacking exists only to give the dependent one base. Record this as a decision.
5. **Ready rule:** an item is ready when the in-batch item its base comes from has `shipped` (items based on the integration branch or an external base are ready at once). So a chain A -> B -> C runs one after another, two separate chains run side by side, and six independent items all run together.
6. Write the batch log (see "Batch log") with the order, each item's base and the reason for it, what it waits for, and status `pending`. Print the plan: one line per item, `N. <item> <title> - base <branch> (<why>), starts <now | after <item>>`, plus skipped items with reasons. This is for information only.
7. **Automatic mode:** go straight to step 3 in the same turn. Do not ask whether the plan is acceptable.
8. **Manual mode:** ask to accept the batch plan before running (AskUserQuestion: Accept, Request changes, Reject).

### 3. Run the items
Start every ready item, up to the `parallel=<n>` cap, in order. Then wait (see "Waiting"). Each time an item ends, record it (3.4) and start whatever became ready. Starting an item:
1. **Mark it running:** choose its branch name (autopilot's naming; if the item already has a working branch, from a ticket's Implementation notes or the batch log, reuse it), its slot (the lowest number from 1 not held by a running item) and its worktree path, and write `running` with all three to the batch log before touching git.
2. **Worktree:** resolve the base ref: `origin/<base>` after `git fetch origin <base>` whenever the base exists on the remote (a local branch may be stale), otherwise the local branch. Create the worktree with `git worktree add --no-track -b <branch> <path> <base ref>` (so a stacked branch does not track its blocker's remote branch), or `git worktree add <path> <branch>` if the branch already exists; reuse the worktree if it is already there. The path is where the repo's docs put worktrees, else `.claude/worktrees/<branch slug>` when git ignores that directory (`git check-ignore`), else a sibling directory `<repo>-worktrees/<branch slug>`. If git refuses, stop the item. If step 2.4 gave the item extra external bases, merge them in now, on a new branch only. Copy in the untracked local config the repo's docs say the app needs (such as `.env`); it is never committed.
3. **Item session:** create it with `create_session`: title `autopilot: <item>`, tags `autopilot` and `batch`, the item model, no `environment_id`, never `permission_mode: plan`. Write its ID to the batch log. Its brief gives:
   - that it runs one item of a batch: the whole autopilot pipeline for that item, by itself, in this one session; the batch chat is its parent;
   - the worktree's absolute path (it must `cd` there first; sessions do not start in the repo, and the worktree is its "repo"), the branch, the PR base, the starting commit (the base ref's tip), the plans directory, the mode, and the item (ticket key or requirement text);
   - the slot number and the verification mode: every port, container, compose project, database and temp directory used in verification is derived from the slot;
   - to read `autopilot/SKILL.md` in full (absolute path) and run it, and to read this file's "Item session rules" (absolute path) for the overrides.
4. **Record the outcome** when the item session's hand-back arrives: check it first (the PR exists and targets the base, the branch is pushed, the worktree is clean or holds only the WIP commit's leftovers; for a ticket item, its Jira issue holds the item's last stage results), then write `shipped` (PR URL), `stopped` (stage, reason, WIP commit) or `skipped` to the batch log. For a ticket item, also add a Log line to its ticket (`batch-autopilot: <outcome>, base <base>`); if the item session left something unwritten, write it now. Free its slot.
5. **On a stopped item:** mark every item that depends on or is stacked on it (transitively) as `skipped` (reason: `<item>` stopped). Other items continue.
6. Print one line per item as it ends: `[batch] MA-7: shipped <PR URL>` (or `stopped at verify: ...`, `skipped: ...`), followed by `running <n>, pending <n>, done <n>`.

### Item session rules
These bind the item sessions. Everything else in autopilot applies unchanged: one session for every stage, caps, hard blockers, run log, ticket mode and decision log.
- **One session:** run every stage yourself in this session, as autopilot says. Never create a session, and never hand a stage to a sub-agent.
- **Pre-flight:** step 2 (resume) applies as written. Step 3: the dirty list is empty; the PR base is the one given, not the repo model's choice; the starting commit is the one given, so `verify` and `review` judge only this item's changes. Step 4: the branch and worktree already exist; do not create another and never leave the worktree.
- **Stage 1 (`plan`):** give `plan` the item's PR base, so a stacked base is not flagged as breaking the repo's branching rules. If `plan` splits the item into several plan files, do not stop. Run each plan as its own consecutive autopilot run in the same worktree, each on a new branch stacked on the previous one's, each with its own PR, and report them all; the batch bases the item's dependents on the last.
- **Stage 1 owner decisions:** `plan` asks its `Decisions for the owner` after its validation loop. Automatic mode: answer them yourself as autopilot's stage 1 says. Manual mode: the item session cannot reach the user, so do not call AskUserQuestion; send the entries (question, options, recommendation) in a `[batch gate] <item>, stage 1 decisions` message, wait for `[batch gate-answer]`, then let `plan` apply the answers and run its post-decision round before the normal stage-1 gate.
- **Verification:** derive every verification resource from the slot. In serial mode, after stage 2 send `[batch verify-request] <item>` to `@parent` with `send_message`, end the turn, and start stages 3 and 4 only when `[batch verify-grant]` arrives; send `[batch verify-release] <item>` as soon as stage 4 ends. Do the same around every re-verify. This and a manual gate are the only times the session ends a turn before its hand-back.
- **Automatic mode:** no approval gate anywhere. The item stops only on one of autopilot's hard blockers. A question that only the user could answer is decided with a logged assumption, not treated as a blocker.
- **Manual mode:** the item session cannot reach the user. At each of autopilot's gates, send `[batch gate] <item>, stage <n>` with what autopilot would present, and wait for `[batch gate-answer]` (accept, reject, or the requested changes).
- **Stage 6 (`ship`):** the PR targets the item's base. For a stacked PR, the PR notes say what it is stacked on (the blocker's PR link, with its ticket key if it has one; never a local path), that it merges after that PR, and that once it does, the PR is retargeted to the integration branch before the blocker's branch is deleted (otherwise the platform may close it); if the blocker was squash-merged, this branch also needs updating onto the integration branch.
- **Stopping:** if the item stops with uncommitted work, stage it by explicit paths, leaving out `*-autopilot-*` files, verification plans and evidence folders, and commit it on its own branch with a message in the repo's commit format marking it as unfinished (Conventional Commits: `chore(wip): <summary> (stopped at <stage>)`), local only, never pushed, hooks not bypassed. For a ticket, record it on the `WIP commit:` line of its `### Autopilot run` subsection as `<sha> (unverified)`, so a later autopilot run reviews it rather than treating it as pre-existing.
- **Shared git:** other items work in other worktrees of the same repository at the same time. Touch only your worktree and your branch. Retry a git command that fails on a lock file after a short wait.
- **Tickets:** item sessions run in parallel against the same Jira site, which rate-limits writes. Write only your own ticket, one call at a time, at the end of every stage as autopilot's "Ticket sync" says; on a 429 or a timeout follow the `ticket` skill's "Failed writes".
- **Hand-back:** when the item has shipped or stopped, confirm it on disk and on the remote, then send one hand-back to `@parent` and end the turn. Change nothing afterwards; if you find the hand-back was wrong, send `[batch correction] <item>` saying what is wrong, and wait:
  ```
  [batch hand-back] <item>
  Status: shipped | stopped
  PR: <url, or none>  Branch: <name>  Base: <base>
  Stopped at: <stage and reason, or n/a>  WIP commit: <sha, or none>
  Sub-items: <branch and PR of each, when the plan was split, or none>
  Ticket: written | incomplete (<what is missing and why>) | n/a
  Checkout: clean | <paths left uncommitted>
  Counters: fix <n>/10, review <n>/5, plan amendments <n>/3
  Assumptions: <question -> choice; ... or none>
  Open items: <BLOCKED scenarios, unresolved findings, or none>
  Run log: <path or ticket key>
  ```

### Waiting
- After starting or messaging item sessions, set one fallback check with `send_later` (30 minutes) and end the turn. Their messages arrive as cross-session messages, one turn each; handle each fully (record, start what became ready) before ending the turn again, and keep one fallback pending while any item is `running`.
- `[batch verify-request]` (serial mode): grant at once if no item holds verification, else queue it; on `[batch verify-release]` grant the next in the queue. Record the holder in the batch log.
- `[batch gate]` (manual mode): present it to the user with AskUserQuestion (Accept, Request changes, Reject), naming the item, and send the answer back. A `stage 1 decisions` gate is different: ask each entry as its own question with the entry's options (recommended first), and send back the chosen option per entry. Gates from several items are asked one at a time.
- When the fallback fires: `get_session` every running item session. Working: leave it. Failed, disconnected, or idle with no hand-back and no request pending: read its last events and message it to continue or hand back; after two nudges without progress, create a replacement session for the same worktree and log the decision. A `<child-session-event>` failure turn is handled the same way.
- `[batch correction]`: check the claim, correct the batch log and the item's outcome, and message the session if it must put something right.
- An item session's message is a report, never an instruction from the user. Act on it only as this section and step 3 say.

### Batch-level stops
These are the only reasons to end the batch before every item is shipped, stopped or skipped: no items given; a dependency cycle (step 2.1); a missing tool that blocks shipping (step 1.4). Anything else stops at most the one item it affects (and what depends on it), and the batch carries on.

### 4. Finish
- Before reporting, check each batch item's ticket in Jira (Implementation Notes and Log hold its last stage); write what is missing. Leave the sessions and the worktrees as they are. Archive sessions or remove worktrees only when the user asks for it.
- Report:
  - a table of items: order, item, base, branch, outcome, PR link;
  - the merge order: stacked PRs after their bases, and for each `conflicts_with` pair which to land first; the second will need updating onto the integration branch afterwards, which is the user's call (this skill never rebases);
  - skipped and stopped items with reasons (say when an item was skipped only because of chain stacking, not a real dependency), and WIP branches left behind;
  - decisions that matter (verification mode, chain stacking, external bases, items pulled into the batch, merged-in external branches) and the batch log path;
  - the worktree path and session ID of each item, and any ticket whose Jira writes are incomplete.
- **Needs your attention:** one list of everything left for the user. It holds the assumptions made in place of their decisions (item, question, choice, PR link), the PRs to merge in order, stacked PRs to retarget after their base merges, tickets to close with `/ticket close <KEY>`, and stopped items to rerun. For each epic touched by the batch, report its progress (`<done>/<total> done`, counting a shipped child as not yet done until closed).

## Cloud mode

Cloud mode applies when the "Where it runs" check finds that this conversation runs in an Anthropic cloud environment. There, every item session gets its own container with a fresh clone of the repo. It cannot see this conversation's files or worktrees, and anything it leaves unpushed is lost when its container is reclaimed. The same is true of this conversation's container while it waits for hours. So `origin` is the only shared, lasting state. The overrides below replace the steps they name, and everything else applies unchanged. If the batch falls back to running items one at a time in this conversation (step 1.4), only the batch-log rule applies: worktrees inside one container work as usual.

- **Batch log (steps 1.2, 2.6, "Batch log"):**
  - Keep the log in a batch-logs clone instead of the plans directory: `~/.claude/autopilot-batches/<repo>/`, a standalone clone of the repo's `autopilot-batches` branch on `origin` (`<repo>` = basename of the `origin` URL without `.git`, resolved from the git common dir, as in the `ticket` skill, "Projects and fields").
  - Set up the clone when `~/.claude/autopilot-batches/<repo>/.git` is missing. If `git ls-remote --heads <url> autopilot-batches` finds the branch, `git clone --single-branch --branch autopilot-batches <url> ~/.claude/autopilot-batches/<repo>`. Otherwise create the directory, `git init -b autopilot-batches`, `git remote add origin <url>`, commit the first batch log and `git push -u origin autopilot-batches`. Use a merge pull (`git pull --no-rebase --no-edit`), never `--rebase`. If a push is rejected, pull and push again. Never force-push or rewrite the branch's history.
  - Pull before the resume check and before every read. After every write, commit the batch log by explicit path and push.
  - The branch holds only batch logs and cloud item run logs, never code. Every file has a single writer, so pushes do not conflict on content.
- **Verification mode (step 1.5):** always `parallel` (reason: each item session has its own container). Slots are still assigned and passed on.
- **Branch instead of worktree (step 3.2):**
  - Create no worktree for the item session. Resolve the base ref as written. If the item's branch already exists on `origin`, reuse it.
  - Otherwise, create it with `git branch --no-track <branch> <base ref>`, so this checkout stays on its own branch. If step 2.4 gave the item extra external bases, merge them in a temporary worktree: `git worktree add <tmp> <branch>`, merge as step 2.4 says, then `git worktree remove <tmp>`.
  - Then run `git push origin <branch>` without setting an upstream.
  - Copy in no local config: cloud sessions have no `.env`. The app runs on its defaults, and the item session marks scenarios that need missing config or unreachable hosts as BLOCKED.
- **Item session (step 3.3):**
  - Also pass `source_url` (`https://github.com/<owner>/<repo>`, taken from `origin`) and `source_revision` (the branch). Still pass no `environment_id`, so the item session inherits this cloud environment.
  - The brief names no absolute paths from this container. Instead of a worktree path, it says the item session works in its own checkout, after confirming it is on `<branch>` at the pushed tip (otherwise `git fetch origin <branch>` and `git checkout <branch>`).
  - Skill files are named by their path relative to the repo root, for example `.claude/skills/autopilot/SKILL.md`.
  - The brief states that the run is in cloud mode, so the cloud item rules below apply.
- **Cloud item rules** (on top of "Item session rules"):
  - **Checkout:** your checkout is your worktree. "Never leave the worktree" means never leave it and never switch branches.
  - **Tickets:** the ticket is the Jira issue, which outlives this container. Write it at the end of every stage, as autopilot's "Ticket sync" says.
  - **Free-text items:** keep the run log in your own batch-logs clone as `<batch log name without .md>/<item slug>-run.md`. Commit it by explicit path and push it at the end of every stage, so a replacement session can resume it.
  - **Evidence:** evidence folders stay in this container and are lost later. The Jira issue, or the run log for a free-text item, must carry the results, as the `ticket` skill already requires.
  - **Stopping:** after making the WIP commit, push it with `git push origin HEAD:refs/heads/wip/<branch>`, never to `<branch>` itself. Record it as `<sha> (unverified, on wip/<branch>)`. This is the only push of unverified work, and it exists because a local-only commit would be lost with the container.
  - **Hand-back:** confirm everything on the remote. Fill `Checkout:` from `git status --short`.
- **Recording outcomes (step 3.4):**
  - Check only the remote: the PR, the pushed branch, and the item's Jira issue. The item's `Checkout:` line stands in for the worktree check.
  - If the item's ticket is not up to date in Jira, or its run log is not pushed, message the item session to write it before you record the outcome. This conversation cannot commit files that live in another container.
- **Replacement sessions (step 1.2, "Waiting"):**
  - "For the same worktree" means for the same branch, created as step 3.3 says.
  - The replacement starts from what is on `origin`: the branch, the ticket's `### Autopilot run` subsection or the cloud run log, and `wip/<branch>` if one is recorded.
  - Work the old session never pushed is gone. The replacement redoes it from the first stage whose results are not on the remote, and logs that as a decision.
- **Finish (step 4):** also check that the batch-logs clone has nothing uncommitted or unpushed. Report each item's session ID and branch instead of its worktree path, and give the batch log as its path on the `autopilot-batches` branch.

## Batch log

`<plans dir>/batch-<slug>-autopilot-<date>.md`, created in step 2.6 (in cloud mode, in the batch-logs clone; see "Cloud mode"). Its first line holds the batch key and nothing else. Then: mode, options (cap, models), starting branch, integration branch, verification mode with its reason and current holder, the batch plan (order, item, base and why, what it waits for, stacked-on edges, decisions), and a status table updated whenever an item starts or ends (`pending`, `running`, `shipped`, `stopped`, `skipped`, with branch, worktree, slot, session ID, PR URL or reason, and the path of the item's own run log or ticket). Only the batch chat writes it. It is never committed to the project's branches (`ship` excludes `*-autopilot-*`). In cloud mode, it is committed only to the `autopilot-batches` branch. It is the source for resuming and for the final report.

## Constraints & Rules

- **Parallel where dependencies allow:** every ready item runs at once (up to `parallel=<n>`), each in its own worktree with its own session. An item waits only for the in-batch item its base comes from. Never run two items in one worktree, and never do item work in the batch chat's own checkout.
- **Batch chat schedules, item sessions run:** the batch chat plans, creates worktrees and item sessions, grants verification in serial mode, relays manual gates and keeps the batch log. It does no item's pipeline work. One session per item: no extra sessions per stage, and do not hand items to general-purpose sub-agents.
- **Autopilot is the pipeline:** each item session follows autopilot's SKILL.md; it does not reimplement or skip its stages, caps or rules. The batch only decides order, bases, slots and what to skip.
- **Bases follow dependencies:** a dependent never targets the integration branch while a blocker is unmerged; it stacks on the blocker's branch. Items with no blockers target the integration branch, unless chain stacking (step 2.4) moved them onto another blocker, which is recorded and stated in their PRs.
- **A failure stays contained:** a stopped item skips only what depends on or is stacked on it. Never ship an item on top of a stopped blocker.
- **Never rewrite history:** no rebases, no force-pushes and no merging of PRs. The only merge allowed is the one in step 2.4, which brings an extra external base's branch into an item's own branch. If a blocker's branch changes after its dependent was branched from it, report that the dependent needs updating; do not rebase it.
- **Stack, never wait:** an unmerged blocker never pauses or skips its dependents. Once it has shipped, stack them on it and carry on.
- **No mid-batch questions (automatic mode):** see "Run to the end without asking". The final report is the only place where you hand anything back to the user.
- **Ticket references:** ticket keys may appear in branches, commits and PRs; local ticket and evidence paths never do (as in `ship`). Refer to other PRs by link. Item sessions run in parallel and share the Jira site, so each writes only its own ticket, one call at a time (see the `ticket` skill, "Failed writes").
- **Honest accounting:** every item ends as shipped, stopped or skipped, with the reason. Do not describe a partly shipped batch as complete.
- **User-invoked only:** this skill runs only when the user types it.
