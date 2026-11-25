---
name: ticket
description: Manage tickets and epics in Jira (site archit342000.atlassian.net, one project per repo). Actions - create, list, show, start, close, update, link, unlink, order, epic. Epics group tickets under a parent and track their progress. Tickets can be linked (blocks / blocked by, conflicts with, relates to, split from / into) to set the order of work and show what can be developed in parallel. A Jira issue is the single source of truth for a piece of work (description, acceptance criteria, plan, implementation notes, branch, verification, review history, log) so it can be continued in any conversation. Use when the user asks to create, view, pick up, update or close a ticket, or references a ticket key such as MA-12. Never create a ticket unprompted; suggest one instead.
argument-hint: "<create|list|show|start|close|update|link|unlink|order|epic> [KEY or text]"
---

# Instructions

Tickets are Jira issues, read and written through the Atlassian MCP connector. There is no local copy: a Jira write is the save. Anyone with access to the Jira site can read the tickets, so never write secrets, credentials or private data into one. The old markdown tickets on each repo's `tickets` branch were migrated and are now an archive: never read from or write to them.

## Rules

- **Never create a ticket on your own.** If work looks ticket-worthy, suggest it in one line and wait for the user.
- Statuses are `To Do`, `In Progress` and `Done`. `In Review` exists but is set only when the user asks. Do not invent others.
- The issue is the work record. Anything another conversation would need to continue the work goes in the issue: plans, decisions, implementation notes, verification results, review findings and their resolution. Append; do not delete history.
- Keep entries terse and factual. Use absolute dates (`YYYY-MM-DD`).
- Write one Jira call at a time, never several writes in parallel (Jira rate-limits and times out under parallel writes).
- One writer per issue at a time. An issue's Log comment and each field are replaced whole, so two sessions writing the same one lose each other's lines. When several sessions run at once (`batch-autopilot`), each writes only its own ticket; shared bookkeeping (an epic's status and Log, links between two tickets) is done by one session, as `batch-autopilot` says.

## Connection

- Site `archit342000.atlassian.net`, `cloudId` `82756d93-5299-404a-a104-d620b72d43bd`. Pass it as `cloudId` on every call.
- Tools (load their schemas with ToolSearch first if they are not available): `getJiraIssue`, `searchJiraIssuesUsingJql`, `createJiraIssue`, `editJiraIssue`, `addOrEditJiraIssueComment`, `transitionJiraIssue`, `listJiraIssueComments`. Through `executeRead` / `executeWrite` (`name` + `inputs`; use `discover` to look an operation up): `createJiraIssueLink`, `listJiraIssueTransitions`, and `searchJiraIssuesUsingJql` / `listJiraIssueComments` with `responseFields` to keep output small.
- The connector cannot delete issues, comments or links, and cannot create projects or custom fields. When one of those is needed, say so and tell the user what to do in Jira.

## Projects and fields

One Jira project per repo. `<repo>` = basename of the `origin` remote URL without `.git` (strip a trailing `/` first). With no remote, use the basename of the main checkout directory: the parent of `git rev-parse --path-format=absolute --git-common-dir`. Always resolve from the git common dir, never the cwd, so worktrees map to the same repo.

| Repo | Project key | Acceptance Criteria | Plan | Implementation Notes | Branch |
|---|---|---|---|---|---|
| `My-AI` | `MA` | `customfield_10062` | `customfield_10061` | `customfield_10060` | `customfield_10063` |
| `BrainRAG` | `BRAG` | `customfield_10046` | `customfield_10044` | `customfield_10045` | `customfield_10047` |
| `architanand.dev` | `AD` | `customfield_10052` | `customfield_10053` | `customfield_10054` | `customfield_10055` |
| `android_edge_ai_core` | `AEAC` | `customfield_10048` | `customfield_10050` | `customfield_10049` | `customfield_10051` |

A repo that is not in the table (for example `console-at-rest`) has no Jira project yet: say so and ask the user to create one in Jira (with the four text fields above) and give you its key; do not guess a key and do not put its tickets in another repo's project. Always use the ids: every project has fields with the same names, so a name can resolve to another project's field. Reads return these values under `fields.customFields`, keyed by name (`"Plan"`), or `"Plan (customfield_10061)"` when a search spans projects.

## How a ticket is stored

| Old section | Jira |
|---|---|
| title | Summary |
| Description | Description (markdown) |
| Acceptance criteria | `Acceptance Criteria` field. Items as `- ☐ text` / `- ☑ text` (Jira has no task-list syntax). |
| Plan | `Plan` field |
| Implementation notes, branch, PR | `Implementation Notes` field; the working branch name also in the `Branch` field |
| Verification | comments whose first line is `## Verification` |
| Review history | comments whose first line is `## Review history` |
| Log | one comment whose first line is `## Log`, a bullet list `- YYYY-MM-DD: <event>` |
| status | Jira status |
| `epic`, `links` | Jira `parent` and issue links |

A section made of comments is all comments with that first-line heading, in creation order. Append to it by adding a new comment with the same heading (`## Verification`, then `### Re-run 2 (YYYY-MM-DD)` underneath, and so on). Changing something already recorded (resolving a review finding, ticking a scenario) edits that comment in place (`addOrEditJiraIssueComment` with `commentId`). The Log is edited in place: read the Log comment, add the new line at the end, send the whole text back; create the comment if there is none. Jira already records status changes, so log only notable events (plan validated, implementation done, verification result, review round, PR opened, links).

Headings inside the Description, the fields and comments use `###` or deeper; `##` is for the comment section headings and the epic description sections.

### Writing rules

- `contentFormat: "markdown"` for descriptions and comments you write. If you read a body that came back as HTML (the response says `appliedContentFormat: html`, which happens when it holds panels, mentions or media), edit it in HTML, never in markdown, or those parts are lost. The four text fields are plain-text fields shown by Jira's older wiki renderer, so their markdown needs these workarounds:
  - A brace or bracket inside a code span is written as an entity: `&#123;` `&#125;` `&#91;` `&#93;` (for example `` `lib/&#123;a,b&#125;.ts` ``). Braces and brackets outside code spans may be left as they are. Write the entities literally; never decode them.
  - A space at the start or end of a code span is `&#160;`. An underscore pair that Jira would turn into emphasis (`__init__`) is written `&#95;&#95;init&#95;&#95;`, and `--` in a code span is `&#45;&#45;`, `*` is `&#42;`, and `+`, `^` and `~` are `&#43;`, `&#94;` and `&#126;`, or a human reading the ticket in Jira sees the wrong text (stray bold, strike-through or dashes). A checkbox written `[x]` outside a code span renders as an error mark; use `☑` / `☐`.
  - A `!` inside a code span next to other characters that could form Jira image syntax is `&#33;`.
  - Do not put a letter directly after a closing backtick (`` `id`s ``): fold it into the span or add a space.
  - Do not put a table inside a list item (Jira refuses it); dedent the table.
  - Plain Jira-incompatible constructs (`{{ }}`, `{code}`) are not used.
- Jira fields hold at most 32,767 characters; keep each field and each comment under 29,000. Field text that would be longer (a plan, the implementation notes) is split the same way: the field holds a short pointer (`The full plan is in the comments "Plan (part 1 of N)" ... "Plan (part N of N)".`) and each part is a comment whose first line is `## <Field name> (part k of N)` (`## Plan (part 1 of 2)`, `## Implementation Notes (part 2 of 2)`). Cut parts at section boundaries. The field may also keep its current content beside the pointer, with the parts holding the earlier material (`autopilot` does this for its notes); read the parts first, then the field. When a part is added later, renumber the earlier parts' headings (`of N`) and update the pointer. Readers collect the part comments together with the field. A comment section (Verification, Review history) that would pass the limit continues in a new comment with the same heading.
- Jira escapes some characters when it stores markdown (`*`, `_`, `[`, a blank line before `---`); that is expected. Relative links lose their target: write full URLs.
- Read-modify-write for fields: `editJiraIssue` replaces a whole field, so read the current value first and send the complete new value. Never send an empty string for a field that has content.

## Reading a ticket

- One issue: `getJiraIssue` with `fields: ["summary","status","issuetype","parent","labels","issuelinks","description", <Acceptance Criteria>, <Plan>, <Implementation Notes>, <Branch>]`, `view: "full"`, `responseContentFormat: "markdown"`. Comments: `listJiraIssueComments` (all of them, paging with `startAt` until `isLast`; pass `view: "full"` or read through `executeRead` with `responseFields: ["total","appliedContentFormat","comments.id","comments.created","comments.body"]`). `getJiraIssue` reports only how many comments there are. A search with `view: "full"` returns whole comment bodies; a compact search truncates them. Besides the sections of "How a ticket is stored", read the `## <Field name> (part k of N)` comments as part of their field. Migrated tickets carry placeholder comments (`## Verification` / `Not run yet.`, `## Review history` / `None yet.`): treat them as empty. Review history written before the migration may hold its rounds newest first; count `### Round N` headings, not comments.
- Many issues (list, order, epic children): `searchJiraIssuesUsingJql` (`maxResults` up to 100, page with `nextPageToken`). For links and statuses use `executeRead` with `name: "searchJiraIssuesUsingJql"`, `fields: ["summary","status","parent","issuelinks"]` and `responseFields: ["issues.key","issues.fields.summary","issues.fields.status.name","issues.fields.parent.key","issues.fields.issuelinks.type.name","issues.fields.issuelinks.outwardIssue.key","issues.fields.issuelinks.inwardIssue.key","issues.fields.issuelinks.outwardIssue.fields.status.name","issues.fields.issuelinks.inwardIssue.fields.status.name"]` to keep the output small (add the custom field ids to `fields` when the Implementation Notes or Branch are needed, as `batch-autopilot` does for recorded PRs). Never fetch comments for a whole project in one call.

## Ticket keys

- Jira assigns the key (`MA-12`): never choose a number. A bare number in the arguments (`12`) means `<KEY>-12` for the current repo's project.
- Migrated tickets carry a label `legacy-<OLDID>` (for example `legacy-MYAI-12`), and their text still mentions the old ID. The old prefixes were `MYAI` (My-AI), `BRAINRAG` (BrainRAG), `ARCHITANANDDEV` (architanand.dev) and `ANDROIDEDGEAICORE` (android_edge_ai_core); the old number equals the Jira number (`MYAI-12` is `MA-12`). A key whose prefix is not the project key is an old ID: resolve it with JQL `labels = "legacy-MYAI-12"` and use the Jira key from then on. A key that does not resolve: say so and run `list`.
- Branch names and PR text may carry the Jira key (`MA-12`).

## Links

Links use Jira's issue links. Only two link types are used:

| Link | Meaning | Jira |
|---|---|---|
| `blocks` / `blocked_by` | A hard dependency: the blocker's code or decision must exist (be merged) first. | type `Blocks` |
| `conflicts_with`, `relates_to`, `split_from`, `split_into` | Same files (expect a merge conflict: land one, rebase the other), background only, carved out of / moved to another ticket. | type `Relates` |

- Create with `executeWrite` `createJiraIssueLink`: `inputs: {"linkType": "Blocks", "inwardIssue": "<blocker>", "outwardIssue": "<blocked>"}` (the inward issue blocks the outward one). `Relates` takes either order.
- Reading `issuelinks` on issue X: a `Blocks` entry with `outwardIssue: Y` means X blocks Y; with `inwardIssue: Y` means X is blocked by Y.
- Jira cannot tell the `Relates` kinds apart, so every non-blocking link adds a Log line on both issues that names the kind: `- YYYY-MM-DD: linked conflicts_with MA-5 (<reason>)`. A `Relates` link with no such Log line is `relates_to`. In Log lines written before the migration the other ticket is named by its old ID (same number): `linked conflicts_with MYAI-5` on MA-3 is the `Relates` link to MA-5. Blocking links also get a Log line with the reason, on both issues.
- Only link keys that exist in the same project. No self-links, no duplicates (read the issue's links first; the connector cannot delete a link made by mistake). A pair may have links of different kinds when each adds meaning, but not `blocks` plus `conflicts_with`.
- `blocked_by` must stay acyclic. Before adding one, follow the blockers of the target; if that path reaches the ticket being linked, refuse and show the cycle.
- A blocker counts as satisfied when its status is `Done`. Links stay after close, as history.
- `blocks` is only for a real hard dependency. Shared files are `conflicts_with`, background is `relates_to`.
- `unlink`: the connector cannot delete links. Say so, name the link, and tell the user to remove it in Jira.

## Epics

- An epic is an issue of type `Epic` (`createJiraIssue` with `issueType: "Epic"`). A child names its parent with Jira's `parent` field (`createJiraIssue` `parent`, or `editJiraIssue` `fields: {"parent": {"key": "MA-20"}}`; `null` removes it). A ticket belongs to at most one epic; epics cannot be nested.
- An epic's Description holds its sections: the goal, then `## Scope` (in / out), `## Acceptance criteria` (`- ☐` / `- ☑` items), `## Breakdown` (how the work splits into children, and why; research or decisions that span children) and `## Notes` (dated). Epics have no Plan, Implementation Notes or Branch fields; those live in the children.
- Children of an epic: JQL `parent = <EPIC>`. Progress is `<Done children>/<all children> done`.
- Tickets without a parent are valid and the default. Never assign an epic on your own; suggest it in one line.
- An epic may be linked only with `Relates` (`relates_to`, `split_from`, `split_into`). `link` refuses `blocks`, `blocked_by` and `conflicts_with` when either side is an epic: dependencies are between the children.
- Wherever an action expects an epic (`create ... epic:<KEY>`, `list epic`, `order epic`, `epic`, `batch-autopilot epic <KEY>`) and the key is not an epic (check `issuetype`), say so and stop.
- An epic's status is set by hand. Starting a child of a `To Do` epic (via `start` or `autopilot`) moves the epic to `In Progress`. Closing an epic warns about children that are not `Done` and asks first.
- An epic's acceptance criteria are ticked by hand with `update`, when the children's results show them met.

## Actions

Arguments may give the action and a key (`MA-12` or just `12`). With no arguments, run `list`. Resolve the repo and project first ("Projects and fields").

### create `[epic] <title or description> [epic:<KEY>]`
1. Fill the Description (and the Acceptance Criteria field if the user gave or implied criteria) from what the user said. Ask only if the title is missing.
2. `create epic <title>` creates an `Epic`; it cannot take `epic:<KEY>`, so refuse that combination. `epic:<KEY>` (or "under/in epic <KEY>") sets `parent`; the key must be an existing epic. If that epic is `Done`, ask before adding to it. Otherwise create a normal issue (`issueType: "Story"`) with no parent.
3. `createJiraIssue` with `projectKey`, `issueType`, `summary`, `description`, `contentFormat: "markdown"`, `parent` if any, and the text fields in `additional_fields`. It starts in `To Do`. Add a `## Log` comment (`- YYYY-MM-DD: created`; for a child also a line on the epic: `- YYYY-MM-DD: added MA-21`). Report the key and the issue URL (`https://archit342000.atlassian.net/browse/<KEY>`).

### list `[status]`
JQL over the current project: `project = <KEY> AND statusCategory != Done ORDER BY key ASC` (`list all` / `list done` drop or invert the `statusCategory` clause; `list epics` adds `issuetype = Epic`; `list epic <KEY>` uses `parent = <KEY>`). Print one line each: `KEY  status  summary`, with a `[blocked by X, Y]` suffix when a blocker is not `Done` (the linked issue's status is in the search result), an `[epic 3/12 done]` suffix on epics, and an `(epic X)` suffix on tickets that have one. Order: `In Progress` first, then `In Review`, then `To Do`, then `Done`, lowest number first within each group (numeric). Hide `Done` unless asked. If there are no tickets, say so in one line.

### show `<KEY>`
Read the whole issue and its comments ("Reading a ticket") and print it, section by section in the old order (Description, Acceptance criteria, Plan including any `## Plan (part k of N)` comments, Implementation notes including any part comments, Verification, Review history, Log). Under the title list its links with each linked issue's status and summary, and its parent epic. For an epic also list its children (`KEY  status  summary`, same order as `list`, including `Done`), the progress count, and the `blocks` links between them.

### start `<KEY>`
If any blocker is not `Done`, name it and its status and ask before starting (the user may choose to start anyway). Mention `conflicts_with` tickets that are `In Progress`. Then transition to `In Progress` (`transitionJiraIssue`, transition id `21`), add a Log line, and read the whole ticket so its context (plan, notes, review history) drives the work. Summarise where the work stands and what is next. If the ticket's epic is `To Do`, move the epic to `In Progress` with a Log line. For an epic, set its status and summarise progress and the next wave of children (as `order epic`); do not change the children's statuses. Do not start implementing unless asked.

### update `<KEY> <what>`
Edit the relevant part: add criteria, record a plan or decision, append verification results, or log a review round ("How a ticket is stored"). Append to history sections rather than rewriting them. Epics use their Description sections (Scope, Acceptance criteria, Breakdown, Notes) and the Log; implementation, verification and review content belongs in a child, so ask which one.

### close `<KEY>`
Check the acceptance criteria and mention any that are not ticked. Transition to `Done` (transition id `41`) and add a Log line. Then list the tickets this one blocks that have no remaining unfinished blockers; they are now unblocked. If it has an epic, report the epic's progress and, when every child is `Done`, suggest closing the epic. For an epic, list children that are not `Done` and unchecked epic criteria, and ask before closing if there are any.

Transition ids in these projects are `11` To Do, `21` In Progress, `31` In Review, `41` Done. If a transition fails, read the valid ones with `listJiraIssueTransitions`.

### link `<KEY> <type> <KEY> [reason]`
Add a link of `<type>` (`blocks`, `blocked-by`, `conflicts-with`, `relates-to`, `split-from`, `split-into`; underscores accepted) following "Links". `<KEY> blocks <KEY2>` reads left to right: the first blocks the second. Several targets may be given (`link 24 blocked-by 10 6`). Report what was written.

### unlink `<KEY> <type> <KEY>`
The connector cannot delete links (see "Links"). Say whether the link exists and tell the user how to remove it in Jira.

### order `[KEYS | all | epic <KEY>]`
Plan the order of work for the given tickets, or all tickets that are not `Done` (the default). `epic <KEY>` uses that epic's unfinished children. Epics themselves are never placed in waves; an epic key in the given keys is reported and skipped (suggest `order epic <KEY>`). Blockers outside the set are kept only when they are not `Done`, and are shown as external.
1. Build the `Blocks` graph among unfinished tickets (one search for the set, reading `issuelinks`). If there is a cycle, report it and stop.
2. Group the tickets into waves (topological layers). Wave 1 is every ticket whose blockers are all `Done`. Wave N is every ticket whose blockers are all in earlier waves or `Done`.
3. Print each wave as one line: `Wave N: KEY summary, KEY summary, ...`. Tickets in the same wave can be developed in parallel.
4. Under each wave, list the `conflicts_with` pairs inside it (from the Log lines): `MA-5 / MA-11 conflict: develop in parallel, merge one then rebase the other`. Suggest the merge order (fewer conflicts first, then lower number).
5. End with the tickets that are `In Progress` and any blocked by tickets outside the set.
`order` only reads; it never changes tickets.

### epic `<EPIC> add|remove <KEYS>`
Add tickets to an epic or remove them. `<EPIC>` must be an epic. For each key:
- `add`: an epic key is refused (no nesting). A ticket already in `<EPIC>` is a no-op. A ticket in a different epic is moved only after the user confirms. Otherwise set `parent`.
- `remove`: a ticket whose parent is not `<EPIC>` is skipped and reported. Otherwise clear `parent`. If Jira refuses to clear it, say so and tell the user to do it in Jira.
Add a Log line on both the ticket and the epic (`- YYYY-MM-DD: added to epic MA-20` / `- YYYY-MM-DD: added MA-21`; `removed from epic` / `removed`). Report what changed and what was skipped. `epic <EPIC>` with no subaction runs `show <EPIC>`.

## Failed writes

- HTTP 429 (rate limited): nothing was written. Wait about 30 to 60 seconds (honour a `Retry-After` header if one is shown), then repeat the same call once. If it fails again, stop writing to that issue for now: keep the content in the conversation, carry on with the rest of the work, try again at the next write point, and list what is missing in the final report. "Stop" never means stop the skill that is calling.
- A call that times out may or may not have been applied. Read the issue (or its comments) before repeating it, so a comment is never posted twice and a link is never created twice.
- A write's response does not echo custom text fields. After a long or important write (a plan, the implementation notes), read the field back with `getJiraIssue` (`fields: [<field id>]`) when exact text matters, and always when a call failed or its outcome is unknown. An agent copying long text can drop or change words, so compare what was stored with the source.
- A write that cannot be completed is listed in the final report of the skill that made it (what is missing and why). Keep the content in the conversation so the user can retry.

## Use from other skills (ticket mode)

`plan`, `implement`, `verify`, `review`, `resolve-review`, `ship`, `autopilot` and `batch-autopilot` have a "Ticket mode" section that points here. A ticket is in play only when the user gives a key (to the skill or earlier in the conversation); skills never create or pick tickets themselves. Without a key they behave as before. In ticket mode:

- Resolve the ticket per "Ticket keys" and read all of it first ("Reading a ticket"). Description and Acceptance Criteria are the requirement; the other parts are prior context to continue from.
- Durable output goes into the matching part of the issue instead of a `plans/` file: plan to the Plan field, progress to Implementation Notes, scenarios and results to `## Verification` comments, findings and their resolution to `## Review history` comments.
- Write as you go (after each step or stage), not only at the end, so another conversation can resume mid-work. There is nothing to commit or push for a ticket: the Jira write is the save. A skill's "do not commit or push" rule is about the project repo, never the ticket.
- Append dated subsections; do not rewrite earlier content. The one exception is the Plan, which may be revised in place until validated, and amended by dated notes after that. Resolving a review finding updates that finding's status in place.
- On each write add a Log line for notable events (plan validated, implementation done, verification result, review round, PR opened).
- Large artifacts (screenshots, scripts, logs) go in `~/.claude/ticket-evidence/<KEY>/`. That folder stays on this machine, so the ticket itself must record the results (commands, outcomes, key output); a reader in Jira cannot open local paths. A small file may also be attached to the issue when the user asks.
- Tick an acceptance criterion (`☐` to `☑`) only when a PASS verification scenario covers it.
- The ticket key may appear in commit messages, branch names and PR text (e.g. `MA-12`), placed as the repo's own conventions allow, and a PR may link the ticket as `https://archit342000.atlassian.net/browse/<KEY>`. Local paths (`~/.claude/...`, evidence folders) never appear there, and ticket keys stay out of code and code comments.
- Writing the ticket is the only edit report-only skills (`review`, `verify`) make.
- **Epics.** Check `issuetype` after resolving a key. Given an epic, `implement`, `verify`, `review`, `resolve-review`, `ship` and `autopilot` do not run on it: they show its unfinished children and ask which one to use. `plan` writes the epic's `## Breakdown` instead of a Plan and suggests child tickets (never creates them). `batch-autopilot` expands an epic into its children. Skills never set or change a ticket's parent on their own, and never create tickets to fill an epic.
- **Epic bookkeeping.** Only `ticket start` and `autopilot` move a ticket to `In Progress`; when they do, apply the `start` rule (a `To Do` epic becomes `In Progress`; under `batch-autopilot` the batch chat does this once, not each item session). Skills never close an epic; when every child is `Done`, suggest `/ticket close <EPIC>`.
- **Links from planning.** When a plan finds that the ticket depends on another ticket's change, overlaps its files, or was split from it, record it with `link` (`blocked_by`, `conflicts_with`, `split_from`), with the reason in the Log line, as well as in the plan's text. Linking is a ticket edit, so report-only skills may do it too. Never create a new ticket to link to; suggest one.
