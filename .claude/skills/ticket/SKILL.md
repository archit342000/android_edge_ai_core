---
name: ticket
description: Manage Jira-style tickets and epics stored as markdown on each repo's `tickets` branch on GitHub (local clone in ~/.claude/tickets/<repo>/). Actions - create, list, show, start, close, update, link, unlink, order, epic. Epics group tickets under a parent and track their progress. Tickets can be linked (blocks / blocked by, conflicts with, relates to, split from / into) to set the order of work and show what can be developed in parallel. A ticket body is the single source of truth for a piece of work (plan, implementation notes, verification, review history) so it can be continued in any conversation. Use when the user asks to create, view, pick up, update or close a ticket, or references a ticket ID. Never create a ticket unprompted; suggest one instead.
argument-hint: "<create|list|show|start|close|update|link|unlink|order|epic> [ID or text]"
---

# Instructions

Tickets are markdown files kept on a dedicated `tickets` branch of each repo and pushed to GitHub. The branch holds only tickets and is never merged into code branches, so ticket edits never appear in feature PRs. Anyone who can see the repo can read its tickets: never write secrets, credentials or private data into one.

## Rules

- **Never create a ticket on your own.** If work looks ticket-worthy, suggest it in one line and wait for the user.
- Statuses are only `todo`, `in-progress`, `done`. Any status may be set directly; do not invent others.
- The ticket body is the work record. Anything another conversation would need to continue the work goes in the ticket: plans, decisions, implementation notes, verification results, review findings and their resolution. Append; do not delete history.
- Keep entries terse and factual. Use absolute dates (`YYYY-MM-DD`).

## Locations

- Root: `~/.claude/tickets/`. Per repo: `~/.claude/tickets/<repo>/`, a clone of the repo's `tickets` branch (see "Storage and sync").
- `<repo>` = basename of the `origin` remote URL without `.git`. With no remote, use the basename of the main checkout directory: the parent of `git rev-parse --path-format=absolute --git-common-dir` (without `--path-format=absolute` it returns a relative `.git`). Always resolve from the git common dir, never the cwd, so worktrees map to the same repo. Strip a trailing `/` from the URL before taking the basename. If not inside a git repo, ask which repo directory under `~/.claude/tickets/` to use (`list` may show all repos).
- Prefix file: `~/.claude/tickets/<repo>/.prefix` holds the ID prefix. If missing, create it as the repo name uppercased with non-alphanumerics removed (`My-AI` becomes `MYAI`). It is committed with the tickets.
- Ticket file: `<PREFIX>-<n>-<slug>.md`, e.g. `MYAI-12-fix-login-redirect.md`. `<slug>` is the title transliterated to ASCII, lowercased, runs of non-alphanumerics collapsed to one `-`, no leading/trailing `-`, cut at 50 chars (then strip a trailing `-`). If nothing remains (e.g. a non-Latin title), use `ticket`. The title in the frontmatter keeps the original text.
- New `n` = highest existing number for that prefix plus 1, starting at 1. Compare numbers **numerically** (`MYAI-10` is higher than `MYAI-9`), not as strings. No counter file; pull before picking `n` (see "Storage and sync").
- Resolve an ID to a file by matching `<PREFIX>-<n>-*.md` (the trailing hyphen keeps `1` from matching `12`). If nothing matches, say so and run `list`.
- Changing a title later edits the frontmatter only; never rename the file, since the ID is what identifies it.

## Storage and sync

Each repo's tickets live on its `tickets` branch on `origin`: an orphan branch holding only ticket files, `.prefix` and `.gitignore`. `~/.claude/tickets/<repo>/` is a standalone clone of that branch with its own `.git` (not a worktree of the project checkout), so every checkout and worktree of the repo shares one copy. Run ticket git commands as `git -C ~/.claude/tickets/<repo> ...`. Never check out the `tickets` branch in the project checkout, and never merge it into or from a code branch.

**Setup** (when `~/.claude/tickets/<repo>/.git` is missing). Take `<url>` from the project repo's `origin`.
- `origin` already has a `tickets` branch (`git ls-remote --heads <url> tickets`): `git clone --single-branch --branch tickets <url> ~/.claude/tickets/<repo>`. If the directory already exists with local files, clone to a temporary directory instead, move its `.git` in, and run `git status`: commit tickets that exist only locally, and ask the user about any file that differs from the branch.
- No `tickets` branch yet: create the directory if missing, `git init -b tickets`, `git remote add origin <url>`, write `.prefix` and `.gitignore`, commit everything (`chore: start tickets branch`, or `chore: import local tickets` when tickets were already there) and `git push -u origin tickets`.
- The project repo has no `origin`: `git init -b tickets` without a remote and commit locally. Say once that tickets are not pushed until the repo has a remote; when it gets one, add it here and push.

`.gitignore` tracks only top-level markdown files, so evidence folders and anything else stay on this machine:

```
/*
!/.gitignore
!/.prefix
!/*.md
```

**Read.** Before the first ticket read of an action or skill run, `git pull --rebase` (skip it without a remote). If it fails for network reasons, continue with the local copy and say so.

**Write.** After each action, commit every ticket file it changed in one commit (`git add <those files> && git commit -m "<ID>: <what changed>"`, e.g. `MYAI-12: start`, `MYAI-12: plan validated`, `MYAI-12, MYAI-10: link blocked_by`) and `git push`. Stage by explicit path, since parallel sessions share this clone and may have their own edits in progress.
- `index.lock` exists: another session is committing; wait a few seconds and retry.
- Push rejected: `git pull --rebase`, then push again. Tickets are append-only, so resolve a conflict by keeping both sides' lines (in date order) and the later `updated`; for a conflicting `status`, keep the later change. If a new ticket's number was taken on the remote meanwhile, renumber the new ticket (file name and `id`) to the next free number before pushing.
- Push fails for network or auth reasons: keep the commit, carry on, and list the unpushed tickets in the final report.
- Never force-push or rewrite the branch's history.

## Ticket format

```markdown
---
id: MYAI-12
title: "<short title>"
status: todo
created: YYYY-MM-DD
updated: YYYY-MM-DD
links:
  blocked_by: [MYAI-10]
  conflicts_with: [MYAI-5]
---

## Description
<what and why>

## Acceptance criteria
- [ ] <criterion>

## Plan
<plan or link to the plan file; paste the validated plan here when one is produced>

## Implementation notes
<what was done, decisions, deviations from the plan, branch name>

## Verification
<scenarios and results>

## Review history
<one subsection per review round: date, findings, resolution (fixed, rejected, deferred)>

## Log
- YYYY-MM-DD: created
```

`links` is optional; omit it when a ticket has none (see "Links"). `epic` (the parent epic's ID) is optional; omit it when the ticket has no epic (see "Epics"). When present it goes right after `status`.

Always wrap `title` in double quotes (escape any inner `"` as `\"`); unquoted titles containing `:` or `#` break the YAML. Sections stay in this order. Empty sections keep their heading. Update `updated` on every change and add a `Log` line for status changes and major events.

## Epics

An epic groups related tickets under one parent and tracks their progress, like a Jira epic.

- An epic is a ticket file with `type: epic` in its frontmatter, right after `id`. A ticket with no `type` is a normal ticket; never write `type: task`.
- Epics share the repo's prefix, ID counter and filename pattern, so the Locations rules apply unchanged.
- A child names its parent with `epic: <ID>` in its own frontmatter. The epic does not list its children; find them by scanning the repo's tickets for that `epic` value. This is one-sided on purpose (unlike `links`), so it cannot drift.
- A ticket belongs to at most one epic. Epics cannot have an `epic` field (no nesting).
- Tickets without an `epic` field have no parent. That is valid and the default: existing tickets stay that way unless the user asks to add them to an epic. Never assign an epic on your own; suggest it in one line instead.
- An epic may be linked (to an epic or a ticket) only with `relates_to`, `split_from` or `split_into`. `link` refuses `blocks`, `blocked_by` and `conflicts_with` when either side is an epic, and says why: dependencies are between the children. In an epic's frontmatter `links` goes after `updated`, as for tickets.
- Wherever an action expects an epic (`create ... epic:<ID>`, `list epic`, `order epic`, `epic`, and `batch-autopilot epic <ID>`) and the ID is not one, say so and stop.
- An epic's status is set by hand with the same three values. Starting a child of a `todo` epic (via `start` or `autopilot`) sets the epic to `in-progress` (with a Log line). Closing an epic warns about children that are not `done` and asks before closing.
- An epic's Acceptance criteria are ticked by hand with `update` (no verification scenario runs on an epic), when the children's results show them met.
- Progress is `<done>/<total> done` over the epic's children.

Epic format:

```markdown
---
id: MYAI-20
type: epic
title: "<short title>"
status: todo
created: YYYY-MM-DD
updated: YYYY-MM-DD
---

## Description
<goal and why>

## Scope
<in scope / out of scope>

## Acceptance criteria
- [ ] <criterion for the epic as a whole>

## Breakdown
<how the work is split into child tickets and why; research or decisions that span children>

## Notes
<dated notes>

## Log
- YYYY-MM-DD: created
```

Implementation, verification and review history live in the child tickets, not the epic.

## Links

Links record how tickets relate, like Jira issue links. They live only in the `links` map in the frontmatter. Each key is a link type, and its value is a YAML flow list of IDs in the same repo, sorted numerically (`[MYAI-3, MYAI-10]`). Omit empty keys, and omit `links` when nothing is left.

| Type | Inverse | Meaning |
|---|---|---|
| `blocks` | `blocked_by` | This ticket must be done (merged) before the other can start. |
| `blocked_by` | `blocks` | The other ticket must be done first. |
| `conflicts_with` | `conflicts_with` | Both change the same files or functions. They can be developed in parallel, but expect a merge conflict: land one, then rebase the other. |
| `relates_to` | `relates_to` | Related context only; no ordering. |
| `split_from` | `split_into` | This ticket was carved out of the other. |
| `split_into` | `split_from` | Part of this ticket moved to the other. |

Rules:
- **Always write both sides.** Adding or removing a link updates the other ticket's inverse entry too. Both files get `updated` and a Log line (`- YYYY-MM-DD: linked blocked_by MYAI-10 (<reason>)`). The reason, if given, goes only in the Log line.
- Only link IDs that exist in the same repo. No self-links and no duplicates. A pair may have links of different types when each adds meaning (`split_from` plus `conflicts_with`), but not `blocked_by` plus `conflicts_with`: a blocker is already sequential.
- `blocked_by` must stay acyclic. Before adding one, follow `blocked_by` from the target. If that path reaches the ticket being linked, refuse and show the cycle.
- A blocker counts as satisfied when its status is `done`. Links stay after close, as history.
- `blocks`/`blocked_by` is only for a real hard dependency: the other ticket's code or decision must exist first. Shared files are `conflicts_with`, and background is `relates_to`.

## Actions

Arguments may give the action and an ID (`MYAI-12` or just `12`). Resolve a bare number against the current repo's prefix. With no arguments, run `list`.

### create `[epic] <title or description> [epic:<ID>]`
1. Resolve the repo and prefix; run Setup ("Storage and sync") if the clone is missing, otherwise pull.
2. Fill Description (and Acceptance criteria if the user gave or implied them) from what the user said. Ask only if the title is missing.
3. `create epic <title>` writes an epic (Epics format); it cannot take `epic:<ID>` (no nesting), so refuse that combination. `epic:<ID>` (or "under/in epic <ID>") sets the parent; the ID must be an existing epic. If that epic is `done`, ask before adding to it (the user may reopen it). Otherwise write a normal ticket with no `epic` field.
4. Write the file with status `todo`. When a parent is set, add a Log line to the epic too (`- YYYY-MM-DD: added MYAI-21`). Report the ID and path.

### list `[status]`
Read the frontmatter of each ticket in the current repo. Print one line each: `ID  status  title`, with a `[blocked by X, Y]` suffix when a `blocked_by` ticket is not `done`, an `[epic 3/12 done]` suffix on epics, and an `(epic X)` suffix on tickets that have one, `in-progress` first, then `todo`, then `done`, lowest number first within each group (numeric order). Hide `done` unless asked (`list all` or `list done`). If there are no tickets, say so in one line. `list epics` shows only epics. `list epic <ID>` shows only that epic's children (same ordering and `done` rule).

### show `<ID>`
Print the full ticket. Under the title, list its links, with each linked ticket's status and title, and its parent epic if any. For an epic, also list its children (`ID  status  title`, same order as `list`, including `done`), the progress count, and any `blocked_by` / `conflicts_with` links between them.

### start `<ID>`
If any `blocked_by` ticket is not `done`, name it and its status, and ask before starting (the user may choose to start anyway). Mention `conflicts_with` tickets that are `in-progress`. Then set status `in-progress`, add a Log line, and read the whole ticket so its context (plan, notes, review history) drives the work. Summarise where the work stands and what is next. If the ticket's epic is `todo`, set the epic to `in-progress` with a Log line. For an epic, set its status and summarise progress and the next wave of children (as `order epic <ID>`); do not change the children's statuses. Do not start implementing unless asked.

### update `<ID> <what>`
Edit the relevant section: add criteria, record a plan or decision, append verification results, or log a review round. Append to history sections rather than rewriting them. Epics use their own sections (Description, Scope, Acceptance criteria, Breakdown, Notes, Log); implementation, verification and review content belongs in a child, so ask which one.

### close `<ID>`
Set status `done`, add a Log line, and confirm the acceptance criteria are checked; mention any that are not. Then list the tickets this one `blocks` that have no remaining unfinished blockers; they are now unblocked. If it has an epic, report the epic's progress and, when every child is `done`, suggest closing the epic. For an epic, list children that are not `done` and unchecked epic Acceptance criteria, and ask before closing if there are any.

### link `<ID> <type> <ID> [reason]`
Add a link of `<type>` (a key from the Links table, also accepted as `blocks`, `blocked-by`, `conflicts-with`, `relates-to`, `split-from`, `split-into`) and its inverse on the other ticket, following the Links rules. `<ID> blocks <ID2>` reads left to right: the first ticket blocks the second. Several targets may be given (`link 24 blocked-by 10 6`). Report both sides written.

### unlink `<ID> <type> <ID>`
Remove that link and its inverse. Say so if it didn't exist.

### order `[IDs | all | epic <ID>]`
Plan the order of work for the given tickets, or all tickets that aren't `done` (the default). `epic <ID>` uses that epic's unfinished children. Epics themselves are never placed in waves; an epic ID in the given IDs is reported and skipped (suggest `order epic <ID>`). Blockers outside the set are kept only when they aren't `done`, and are shown as external.
1. Build the `blocked_by` graph among unfinished tickets. If there's a cycle, report it and stop.
2. Group the tickets into waves (topological layers). Wave 1 is every ticket whose blockers are all `done`. Wave N is every ticket whose blockers are all in earlier waves or `done`.
3. Print each wave as one line: `Wave N: ID title, ID title, ...`. Tickets in the same wave can be developed in parallel.
4. Under each wave, list the `conflicts_with` pairs inside it: `MYAI-5 / MYAI-11 conflict: develop in parallel, merge one then rebase the other`. Suggest the merge order (fewer conflicts first, then lower ID).
5. End with the tickets that are `in-progress` and any blocked by tickets outside the set.
`order` only reads; it never changes tickets.

### epic `<EPIC> add|remove <IDs>`
Add tickets to an epic or remove them. `<EPIC>` must be an epic. For each ID:
- `add`: an epic ID is refused (no nesting). A ticket already in `<EPIC>` is a no-op. A ticket in a different epic is moved only after the user confirms (Log lines on all three files). Otherwise set `epic: <EPIC>`.
- `remove`: a ticket whose `epic` is not `<EPIC>` is skipped and reported. Otherwise delete its `epic` field, leaving it with no parent.

Update `updated` and add a Log line on both the ticket and the epic (`- YYYY-MM-DD: added to epic MYAI-20` / `- YYYY-MM-DD: added MYAI-21`; `- YYYY-MM-DD: removed from epic MYAI-20` / `- YYYY-MM-DD: removed MYAI-21`). Report what changed and what was skipped. `epic <EPIC>` with no subaction runs `show <EPIC>`.


## Use from other skills (ticket mode)

`plan`, `implement`, `verify`, `review`, `resolve-review`, `ship`, `autopilot` and `batch-autopilot` have a "Ticket mode" section that points here. A ticket is in play only when the user gives an ID (to the skill or earlier in the conversation); skills never create or pick tickets themselves. Without an ID they behave as before. In ticket mode:

- Resolve the ticket per "Locations" and read all of it first. Description and Acceptance criteria are the requirement; the other sections are prior context to continue from.
- Durable output goes into the matching section instead of a `plans/` file: plan to Plan, progress to Implementation notes, scenarios and results to Verification, findings and their resolution to Review history.
- Write as you go (after each step or stage), not only at the end, so another conversation can resume mid-work. Commit each write on the `tickets` branch and push at least at the end of each stage (see "Storage and sync").
- Append dated subsections; do not rewrite earlier content. The one exception is the Plan, which may be revised in place until validated, and amended by dated notes after that. Resolving a review finding updates that finding's status in place.
- Use `###` or deeper for headings inside a section, so the `##` section order holds.
- On each write, set `updated` and add a Log line for notable events (plan validated, implementation done, verification result, review round, PR opened).
- Large artifacts (screenshots, scripts, logs) go in `~/.claude/tickets/<repo>/<ID>-evidence/`, linked from the ticket. That folder is git-ignored and stays on this machine, so the ticket body itself must record the results (commands, outcomes, key output); a reader on GitHub cannot open evidence links.
- Tick an acceptance criterion only when a PASS verification scenario covers it.
- The ticket ID may appear in commit messages, branch names and PR text (e.g. `MYAI-12`), placed as the repo's own conventions allow, and a PR may link the ticket as `https://github.com/<owner>/<repo>/blob/tickets/<file>`. Local paths (`~/.claude/tickets/...`, evidence folders) never appear there, and ticket IDs stay out of code and code comments.
- Writing the ticket is the only edit report-only skills (`review`, `verify`) make.
- **Epics.** Check `type` after resolving an ID. Given an epic, `implement`, `verify`, `review`, `resolve-review`, `ship` and `autopilot` do not run on it: they show its unfinished children and ask which one to use. `plan` writes the epic's `## Breakdown` instead of a Plan and suggests child tickets (never creates them). `batch-autopilot` expands an epic into its children. Skills never set or change a ticket's `epic` field on their own, and never create tickets to fill an epic.
- **Epic bookkeeping.** Only `ticket start` and `autopilot` set a ticket `in-progress`; when they do, apply the `start` rule (a `todo` epic becomes `in-progress`). Skills never close an epic; when every child is `done`, suggest `/ticket close <EPIC>`.
- **Links from planning.** When a plan finds that the ticket depends on another ticket's change, overlaps its files, or was split from it, record it with `link` (`blocked_by`, `conflicts_with`, `split_from`), with the reason in the Log line, as well as in the plan's text. Linking is a ticket edit, so report-only skills may do it too. Never create a new ticket to link to; suggest one.
