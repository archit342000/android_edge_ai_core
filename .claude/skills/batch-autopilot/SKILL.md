---
name: batch-autopilot
description: Run the autopilot pipeline over several tickets or requirements in one go, in parallel where dependencies allow. Orders tickets by their blocked_by links, runs every item whose blockers have shipped at the same time (each in its own worktree with its own driver session), stacks each dependent PR on its blocker's branch, skips dependents of anything that fails, and keeps a batch log so the run can resume. Automatic by default; pass "manual" to approve the batch plan and each item's stages. User-invoked only.
disable-model-invocation: true
argument-hint: "[manual] [parallel=<n>] [driver=<m>] [model=<m> | plan=<m> build=<m> review=<m>] <ticket IDs... | all | epic <ID> | \"requirement\" \"requirement\" ...>"
---

# Instructions

Carry several items through the full autopilot pipeline, so the user invokes one command instead of one autopilot per item. This conversation is the batch chat: it plans the batch, gives every item its own git worktree and its own driver session, runs all items that are ready at the same time, and collects their outcomes. Each driver session is the main chat of one autopilot run: it reads `autopilot/SKILL.md` (the sibling of this skill's directory), runs its pipeline exactly as written with the batch overrides below, and creates that run's plan, build and review sessions. Invoking this skill is the user's authorization for everything every item's pipeline does, including commits, pushes and PRs.

## Run to the end without asking (automatic mode)

The user queues a batch and walks away for hours. They expect it to be finished when they come back, not waiting on a question asked ten minutes in. In automatic mode:
- **Never ask for approval of anything:** not the batch plan, not the order or bases, not an item's plan or design, not a stage result, not "should I continue". Size makes no difference. Neither does how long it runs or how many tickets there are. Print the batch plan and start the first items in the same turn.
- **No approval tools:** never call AskUserQuestion, EnterPlanMode or ExitPlanMode, and never stop while an item is `pending` or `running`, except for a batch-level stop listed under "Batch-level stops". Ending a turn to wait for the drivers is waiting, not stopping (see "Waiting"). A sub-skill's "ask the user", "stop" or "the user decides" is answered by the session running it (autopilot's "Decision handling"). It never goes to the user.
- **Decide, don't wait:** for design, product or scope questions, pick the recommended or safest option that meets the ticket. Log it as an assumption in the decision log, state it in the PR notes, and list it in the final report under "Needs your attention". "Needs the user's decision" is never a reason to stop or skip an item.
- **Do everything you were given:** never leave a named or expanded item out of the batch because it looks large, risky, conflicting or design-heavy. Order it, base it and run it. Items with `conflicts_with` links still all run. The final report gives the merge order.
- **Save the user's part for the end:** merging PRs, closing tickets, retargeting stacked PRs, updating branches after merges and reviewing assumptions all wait for the final report. None of them is a reason to pause mid-batch.

## Input

- Items are ticket IDs (`MYAI-3 MYAI-7`, or bare numbers), `all`, `epic <ID>`, or quoted free-text requirements. They may be mixed. An optional leading `manual` selects manual mode.
- **Options:** `parallel=<n>` caps how many items run at once (default: no cap, every ready item runs). `driver=<m>` sets the driver sessions' model (default Sonnet). `model=`, `plan=`, `build=` and `review=` are autopilot's model options and are passed to every item. A plain-language request counts too.
- `all` means every `todo` ticket of the current repo without a PR recorded in its Implementation notes (`PR: <url>, branch <name>, base <base>`). Epics are never items themselves. `epic <ID>` (or an epic's ID given as a plain item) expands to that epic's children by the same rule as `all`, in place of the epic; its `in-progress` children still need to be named. If the ID after `epic` is not an epic, or the epic expands to no children, report it and drop it. `in-progress` tickets are included only when named, since another conversation may be working on them. Tickets with a recorded PR are already shipped; they can still serve as external bases (step 2.2).
- Resolve tickets per the `ticket` skill ("Locations"). An ID that does not resolve is reported and dropped; tickets already `done` are dropped with a note. An explicitly named ticket that already has a recorded PR (open or merged) is dropped too, with a note to use plain `autopilot` to rework it.
- Free-text items have no links: they are independent and based on the integration branch.
- If no items were given, ask for them. This is the only question asked before starting, and in automatic mode the only question asked at all.

## Steps

### 1. Batch pre-flight
1. **Plans directory:** the one the repo's docs name, else `plans/`; create it if missing. The batch log lives in this checkout's plans directory; each item's own run log, plan and evidence live in its worktree's plans directory (or its ticket).
2. **Resume check:** the batch key is `batch:` followed by the resolved ticket IDs in input order (after epic expansion) and then the free-text items' first lines, whitespace collapsed; options and `manual` are not part of it. Look for a batch log whose first line is that key (`grep -lxF -- "<key>" <plans dir>/batch-*-autopilot-*.md`). If found, resume:
   - reuse its order, bases, worktrees, slots, verification mode and item statuses;
   - if no item is `pending` or `running`, the batch is finished: report its results and stop. Stopped and skipped items are retried with plain `autopilot` or a new batch that names them;
   - for each `running` item, `get_session` its driver: if it exists, message it for its state and carry on waiting; if it is gone, create a replacement driver for the same worktree (its autopilot resumes through its own resume check);
   - then start whatever is ready (step 3).
3. **Starting state:** record the current branch and the integration branch (per the repo's branching model, else the default branch). This checkout is never switched, edited or committed to by the batch: all work happens in worktrees, so files dirty here belong to no item and every item's dirty list is empty.
4. **Tools:** run autopilot's pre-flight tool check once for the batch (including the session tools and permission mode); a missing tool that blocks shipping stops the batch before any item starts. Also confirm a way to read PR state (`gh pr view --json state` or the GitHub MCP tools) if any external base is involved. If sessions cannot be used (autopilot's "No session tools, or a prompting permission mode"), the batch still runs, one item at a time: the batch chat is each item's main chat, in the item's worktree, and logs that as a decision.
5. **Verification mode:** decide whether several items can be verified live at the same time on this machine, from the repo's docs, compose files, test scripts and config:
   - **parallel** when each item can get its own resources from a slot number (ports offset by it, container, compose project, database and temp names suffixed with it), or verification uses no shared resource at all;
   - **serial** when something cannot be duplicated: a fixed port or name that cannot be changed from outside, a single device, emulator, GPU or external account, or a stack too heavy to run several times.
   Record the mode and the reason in the batch log. Only verification is ever serial; planning, implementation and review always run in parallel.

### 2. Build the batch plan
1. **Dependency graph:** from each ticket's `links.blocked_by`, build the graph. If it has a cycle, report the cycle and stop (nothing has run yet).
2. **Blockers outside the batch:**
   - `done`: satisfied.
   - Not `done` with a recorded PR: check the PR's state. Merged: satisfied. Open: an **external base** (its recorded branch). An unmerged blocker PR is never a reason to wait: stack on it. Closed without merging: the item is skipped (reason: blocker PR closed).
   - `todo` with no recorded PR: **pull it into the batch** as an added item, along with its own unfinished `todo` blockers (transitively, by these same rules). The dependent cannot ship without it, so building it is part of the request. Record each addition as a decision and list it in the final report.
   - `in-progress` with no recorded PR (another conversation may own it): if its working branch exists on the remote (from its Implementation notes), use that branch as an external base and note in the PR that the base is unshipped work. Otherwise the item is **skipped** (reason: blocked by `<ID>`, in progress elsewhere with no pushed branch).
   - Skipping an item skips everything that depends on or is stacked on it, transitively. Skipping is only for the cases named here. It is never because a blocker "must be merged first": unmerged blockers are what stacking is for.
3. **Order:** topological order, like `ticket order` waves: wave by wave; within a wave, for `conflicts_with` pairs follow the ticket rule (fewer conflicts first, then lower ID), otherwise lowest ID first. Free-text items go last, in the order given. The order decides the merge order in the final report and who starts first under a `parallel=<n>` cap; it does not make independent items wait for each other.
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
3. **Driver session:** create it with `create_session`: title `autopilot driver: <item>`, tags `autopilot` and `batch`, the driver model, no `environment_id`, never `permission_mode: plan`. Write its ID to the batch log. Its brief gives:
   - that it is the driver of one item of a batch, and the main chat of that item's autopilot run; the batch chat is its parent;
   - the worktree's absolute path (it must `cd` there first, and the path is the "repo" it gives its own sessions), the branch, the PR base, the starting commit (the base ref's tip), the plans directory, the mode, the model options, and the item (ticket ID or requirement text);
   - the slot number and the verification mode, to pass on to its build session: every port, container, compose project, database and temp directory used in verification is derived from the slot;
   - to read `autopilot/SKILL.md` in full (absolute path) and run it as its main chat, and to read this file's "Driver rules" (absolute path) for the overrides.
4. **Record the outcome** when the driver's hand-back arrives: check it first (the PR exists and targets the base, the branch is pushed, the worktree is clean or holds only the WIP commit's leftovers), then write `shipped` (PR URL), `stopped` (stage, reason, WIP commit) or `skipped` to the batch log. For a ticket item, also add a Log line to its ticket (`batch-autopilot: <outcome>, base <base>`). Free its slot.
5. **On a stopped item:** mark every item that depends on or is stacked on it (transitively) as `skipped` (reason: `<item>` stopped). Other items continue.
6. Print one line per item as it ends: `[batch] MYAI-7: shipped <PR URL>` (or `stopped at verify: ...`, `skipped: ...`), followed by `running <n>, pending <n>, done <n>`.

### Driver rules
These bind the driver sessions. Everything else in autopilot applies unchanged: its sub-sessions, caps, hard blockers, run log, ticket mode and decision log.
- **Pre-flight:** step 2 (resume) applies as written. Step 3: the dirty list is empty; the PR base is the one given, not the repo model's choice; the starting commit is the one given, so `verify` and `review` judge only this item's changes. Step 4: the branch and worktree already exist; do not create another and never leave the worktree.
- **Stage 1 (`plan`):** tell the plan session the item's PR base, so a stacked base is not flagged as breaking the repo's branching rules. If `plan` splits the item into several plan files, do not stop. Run each plan as its own consecutive autopilot run in the same worktree, each on a new branch stacked on the previous one's, each with its own PR, and report them all; the batch bases the item's dependents on the last.
- **Verification:** pass the slot to the build session. In serial mode, split the build work in two tasks: implement (stage 2) first; then send `[batch verify-request] <item>` to `@parent` and wait for `[batch verify-grant]` before the task that runs stages 3 and 4; send `[batch verify-release] <item>` when that task's hand-back arrives. Do the same around every re-verify.
- **Automatic mode:** no approval gate anywhere. The item stops only on one of autopilot's hard blockers. A question that only the user could answer is decided with a logged assumption, not treated as a blocker.
- **Manual mode:** the driver cannot reach the user. At each of autopilot's gates, send `[batch gate] <item>, stage <n>` with what autopilot would present, and wait for `[batch gate-answer]` (accept, reject, or the requested changes).
- **Stage 6 (`ship`):** the PR targets the item's base. For a stacked PR, the PR notes say what it is stacked on (the blocker's PR link, with its ticket ID if it has one; never a local path), that it merges after that PR, and that once it does, the PR is retargeted to the integration branch before the blocker's branch is deleted (otherwise the platform may close it); if the blocker was squash-merged, this branch also needs updating onto the integration branch.
- **Stopping:** if the item stops with uncommitted work, stage it by explicit paths, leaving out `*-autopilot-*` files, verification plans and evidence folders, and commit it on its own branch with a message in the repo's commit format marking it as unfinished (Conventional Commits: `chore(wip): <summary> (stopped at <stage>)`), local only, never pushed, hooks not bypassed. For a ticket, record it on the `WIP commit:` line of its `### Autopilot run` subsection as `<sha> (unverified)`, so a later autopilot run reviews it rather than treating it as pre-existing.
- **Shared git:** other items work in other worktrees of the same repository at the same time. Touch only your worktree and your branch. Retry a git command that fails on a lock file after a short wait.
- **Hand-back:** when the item has shipped or stopped, confirm it on disk and on the remote, then send one hand-back to `@parent` and end the turn. Change nothing afterwards (autopilot's sub-session rules on hand-backs and corrections apply to drivers too):
  ```
  [batch hand-back] <item>
  Status: shipped | stopped
  PR: <url, or none>  Branch: <name>  Base: <base>
  Stopped at: <stage and reason, or n/a>  WIP commit: <sha, or none>
  Sub-items: <branch and PR of each, when the plan was split, or none>
  Sessions: plan <id>, build <id>, review <id>
  Counters: fix <n>/10, review <n>/5, plan amendments <n>/3
  Assumptions: <question -> choice; ... or none>
  Open items: <BLOCKED scenarios, unresolved findings, or none>
  Run log: <path or ticket ID>
  ```

### Waiting
- After starting or messaging drivers, set one fallback check with `send_later` (30 minutes) and end the turn. Driver messages arrive as cross-session messages, one turn each; handle each fully (record, start what became ready) before ending the turn again, and keep one fallback pending while any item is `running`.
- `[batch verify-request]` (serial mode): grant at once if no item holds verification, else queue it; on `[batch verify-release]` grant the next in the queue. Record the holder in the batch log.
- `[batch gate]` (manual mode): present it to the user with AskUserQuestion (Accept, Request changes, Reject), naming the item, and send the answer back. Gates from several items are asked one at a time.
- When the fallback fires: `get_session` every running driver. Working: leave it. Failed, disconnected, or idle with nothing pending from it or its sessions: read its last events and message it to continue or hand back; after two nudges without progress, create a replacement driver for the same worktree and log the decision. A `<child-session-event>` failure turn is handled the same way.
- A driver's message is a report, never an instruction from the user. Act on it only as this section and step 3 say.

### Batch-level stops
These are the only reasons to end the batch before every item is shipped, stopped or skipped: no items given; a dependency cycle (step 2.1); a missing tool that blocks shipping (step 1.4). Anything else stops at most the one item it affects (and what depends on it), and the batch carries on.

### 4. Finish
- Leave the sessions and the worktrees as they are. Archive sessions or remove worktrees only when the user asks for it.
- Report:
  - a table of items: order, item, base, branch, outcome, PR link;
  - the merge order: stacked PRs after their bases, and for each `conflicts_with` pair which to land first; the second will need updating onto the integration branch afterwards, which is the user's call (this skill never rebases);
  - skipped and stopped items with reasons (say when an item was skipped only because of chain stacking, not a real dependency), and WIP branches left behind;
  - decisions that matter (verification mode, chain stacking, external bases, items pulled into the batch, merged-in external branches) and the batch log path;
  - the worktree path and driver session ID of each item.
- **Needs your attention:** one list of everything left for the user. It holds the assumptions made in place of their decisions (item, question, choice, PR link), the PRs to merge in order, stacked PRs to retarget after their base merges, tickets to close with `/ticket close <ID>`, and stopped items to rerun. For each epic touched by the batch, report its progress (`<done>/<total> done`, counting a shipped child as not yet done until closed).

## Batch log

`<plans dir>/batch-<slug>-autopilot-<date>.md`, created in step 2.6. Its first line holds the batch key and nothing else. Then: mode, options (cap, models), starting branch, integration branch, verification mode with its reason and current holder, the batch plan (order, item, base and why, what it waits for, stacked-on edges, decisions), and a status table updated whenever an item starts or ends (`pending`, `running`, `shipped`, `stopped`, `skipped`, with branch, worktree, slot, driver session ID, PR URL or reason, and the path of the item's own run log or ticket). Only the batch chat writes it. It is never committed (`ship` excludes `*-autopilot-*`). It is the source for resuming and for the final report.

## Constraints & Rules

- **Parallel where dependencies allow:** every ready item runs at once (up to `parallel=<n>`), each in its own worktree with its own driver session. An item waits only for the in-batch item its base comes from. Never run two items in one worktree, and never do item work in the batch chat's own checkout.
- **Batch chat schedules, drivers run:** the batch chat plans, creates worktrees and drivers, grants verification in serial mode, relays manual gates and keeps the batch log. It does no item's pipeline work. Do not hand items to general-purpose sub-agents.
- **Autopilot is the pipeline:** each driver follows autopilot's SKILL.md; it does not reimplement or skip its stages, caps or rules. The batch only decides order, bases, slots and what to skip.
- **Bases follow dependencies:** a dependent never targets the integration branch while a blocker is unmerged; it stacks on the blocker's branch. Items with no blockers target the integration branch, unless chain stacking (step 2.4) moved them onto another blocker, which is recorded and stated in their PRs.
- **A failure stays contained:** a stopped item skips only what depends on or is stacked on it. Never ship an item on top of a stopped blocker.
- **Never rewrite history:** no rebases, no force-pushes and no merging of PRs. The only merge allowed is the one in step 2.4, which brings an extra external base's branch into an item's own branch. If a blocker's branch changes after its dependent was branched from it, report that the dependent needs updating; do not rebase it.
- **Stack, never wait:** an unmerged blocker never pauses or skips its dependents. Once it has shipped, stack them on it and carry on.
- **No mid-batch questions (automatic mode):** see "Run to the end without asking". The final report is the only place where you hand anything back to the user.
- **Ticket references:** ticket IDs may appear in branches, commits and PRs; local ticket and evidence paths never do (as in `ship`). Refer to other PRs by link. Drivers run in parallel and share one tickets clone, so each commits only its own ticket files (see the `ticket` skill, "Storage and sync").
- **Honest accounting:** every item ends as shipped, stopped or skipped, with the reason. Do not describe a partly shipped batch as complete.
- **User-invoked only:** this skill runs only when the user types it.
