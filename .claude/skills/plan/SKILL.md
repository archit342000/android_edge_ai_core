---
name: plan
description: Plan a change from a requirement by reading the repo's docs and code, drafting a plan, and having a Haiku sub-agent validate it (max 5 validation rounds; stops at the first round with no valid blocking issues, fixing minors inline). Use when asked to plan, design an approach, or scope work before implementing. Does not implement.
---

# Instructions

Turn a user requirement into a concrete, verified implementation plan grounded in the repository's own docs and code. This skill plans only; it never edits source code. The plan is drafted, validated by a Haiku sub-agent, revised, and re-validated, up to 5 validation rounds.

## Steps

### 1. Understand the requirement
- Restate the requirement in one or two sentences. List explicit constraints and success criteria.
- If a plan for this requirement already exists (a `plans/` file the user names, or the ticket's Plan), revise it instead of starting a new file, and validate the revision.
- Ask the user (AskUserQuestion) only when a question blocks the plan and cannot be answered from the repo or sensible defaults. Otherwise record assumptions in the plan.

### 2. Gather context (docs first, then code)
- Read, when they exist: `AGENTS.md`, `CLAUDE.md`, `README.md`, `docs/` (architecture, standards, pitfalls), and the README of every component you will touch.
- Then read the code the change touches: entry points, the modules to modify, their tests, and neighbouring code that sets conventions. Prefer targeted reads and searches over reading whole trees. For broad exploration, delegate to the `Explore` agent.
- Collect facts with exact file paths and symbol names. Note every documented rule or standard that applies, and any pitfall that is relevant.
- If a requirement depends on an external library or tool whose current behaviour you are unsure of, verify it in its official docs rather than from memory.

### 3. Scope and size gate (before drafting)
- If the requirement covers more than one independent area (for example, a report with many unrelated findings), split it into separate plan files, one per area, each validated on its own.
- Keep each plan to about 10 steps. Plans state approach, files and verification. Do not put test bodies, exact assertions or line-by-line edits in the plan; those belong to the implement stage.
- Cite file and symbol anchors, not every line in the plan.

### 4. Draft the plan
Write it to `plans/<topic>-<YYYY-MM-DD>.md`, or into the existing plan file when revising (use the plans directory the repo's docs name, else `plans/`; create it if missing). Use this structure:

```markdown
# Plan: <topic>
**Date:** <YYYY-MM-DD>  **Status:** Draft

## Objective
<what and why, one paragraph>

## Context
- Requirement and assumptions
- Relevant docs and rules (cite file paths)
- Current behaviour in the code (cite file paths and symbols)

## Approach
<chosen design and key decisions; alternatives rejected with a one-line reason>

## Out of scope
<related things deliberately not done, so implement and review can hold the line; "none" if nothing>

## Steps
### Step 1: <name>
- **Status:** todo
- **Depends on:** <step numbers, or "none">
- **Files:** `path/to/file` (create | modify), with the symbols affected
- **Change:** <what to do, specific enough to act on>
- **Verification:** <test, command or observable result that proves it>

## Tests
<tests to add or update, and the commands to run them, per the repo's standards>

## Risks and open questions
<risk, impact, mitigation; unresolved questions for the user>

## Success criteria
- [ ] <checkable outcome>
```

Rules for the draft: `Status` starts as `todo` (implement sets `done` or `done (deviation: ...)`); every path must exist (or be marked "create"); every symbol must exist or be marked new; steps are ordered and each has a verification; no placeholders such as TBD or "etc."; do not plan work outside the requirement.

### 5. Validate with a Haiku sub-agent (iteration loop)
Run at most 5 validation rounds. For each round:

1. Spawn one validator with the Agent tool: `subagent_type: "Explore"`, `model: "haiku"`. Use a fresh agent each round. Give it the plan file path, the original requirement, and the repo root, and tell it to be read-only. From round 2, also give it the headings of the sections you changed and the text of the previous round's valid BLOCKING issues, so it can scope its check. Ask it to check, against the actual repo:
   - **Grounding:** every referenced file and symbol exists (or is explicitly marked to be created); described current behaviour matches the code.
   - **Coverage:** every part of the requirement and every stated success criterion is addressed by a step, and nothing the requirement asks for is listed under Out of scope.
   - **Standards:** no step contradicts `AGENTS.md`, `CLAUDE.md` or `docs/` rules; required tests, docs and README updates are included.
   - **Executability:** steps are ordered, specific, and each has a verification; `Depends on` only points to earlier steps.
   - **Scope and consistency:** nothing outside the requirement; no internal contradictions; no placeholders.
2. Require this exact response format:
   ```
   VERDICT: PASS | FAIL
   ISSUES:
   1. [BLOCKING|MINOR] <check> - <plan section> - <what is wrong and the evidence (file path, line or quote)>
   ```
   `PASS` means zero BLOCKING issues. Any BLOCKING issue means `FAIL`. MINOR issues are advisory.
   - **BLOCKING** only if the plan cannot be executed as written, or executing it would break behaviour or a stated standard. Style, wording, and "could be more precise" are MINOR.
   - **Issue cap:** at most 10 issues per round, BLOCKING first.
   - **Diff-scoped from round 2:** check only the sections changed since the previous round, plus the previous round's BLOCKING items. Do not raise new issues in unchanged sections. A round run because you doubt a PASS is a full check, not diff-scoped.
   - **Failed validator:** if the agent errors or its output does not follow the format, re-launch it once with the format restated; a failed launch is not a round. If it fails twice, stop validating, mark the plan `Draft - unresolved issues` with "validator unavailable", and say so.
3. Do not follow the validator's verdict or its issue list blindly. Treat every issue as a claim to check. For each issue, read the cited file and lines yourself, decide whether the claim is true, and record the decision: `valid` (fix it), `disproved` (say what the code shows), or `out of scope` (say why). Never apply a fix you have not verified against the repo.
4. The verdict is your call, not the validator's. Judge PASS by the remaining valid BLOCKING issues after your own checks. A validator FAIL whose BLOCKING issues are all disproved counts as a pass. A validator PASS you have reason to doubt is not a pass; spot-check its cited paths and run another round if needed.
5. Stop rule:
   - **Any round with no valid BLOCKING issues** (by your judgement): stop validating. Fix the valid MINOR issues inline without another round, but only with mechanical edits (a path, a missing verification line, a wording fix); log any MINOR whose fix would need new content as accepted or deferred to implement. The plan is Validated.
   - **Otherwise** (valid BLOCKING issues remain): revise and run the next round, until round 5.
   - **After round 5,** stop even if issues remain. Mark the plan `Draft - unresolved issues` and list them.
6. Each round: revise only the sections the valid issues touch, and only to fix those issues. Add no new steps, sections or detail beyond what the fix needs; new content is what the next round finds new problems in.

### 6. Finish
- Set `Status` in the plan to `Validated` (clean pass by your judgement), `Validated - minors logged` (passed with valid MINOR issues left unfixed), or `Draft - unresolved issues` (cap reached or validator unavailable), and append a `## Validation log` section: for each round, the validator's verdict, your judgement of the verdict, the issues found, and what was changed or why an issue was dismissed.
- Append a `## Reviewer brief` section (ticket mode: `### Reviewer brief` in the Plan, placed before `### Validation log`) written from the final plan: Objective, the key decisions from Approach, Out of scope and Success criteria, in a few lines, with no reasoning, deviations or validation history. End it with: "The decisions above are intended; report a problem with one only if it breaks a requirement or a documented rule." The review skill passes it to reviewers verbatim. It is not validated; it only restates validated content.
- Other skills read any Status starting with `Validated` as ready (autopilot may set `Validated - assumptions logged` after settling open issues by recorded assumptions).
- Report to the user: the plan path, a short summary of the approach and steps, the number of validation rounds used, the outcome, and any unresolved issues or open questions.
- Stop. Do not start implementing; the user decides when to execute the plan.

## Ticket mode

Applies when the user gives a ticket ID (see the `ticket` skill, "Use from other skills"). Differences:
- **Step 1:** read the ticket. Its Description and Acceptance criteria are the requirement; any existing Plan content is prior work to build on.
- **Step 4:** write the plan into the ticket's `## Plan` section, not `plans/`. Use the same structure with headings demoted: sections as `###`, steps as `####`, no `# Plan:` title, keep the Date/Status line.
- **Step 5:** give the validator the ticket path and tell it to check only the `## Plan` section against the Description and Acceptance criteria. Revise the Plan in place between rounds.
- **Step 6:** set Status in the Plan, put the validation log in a `### Validation log` subsection, and add a Log line (`plan validated, N rounds` or `plan drafted, unresolved issues`).
- **Size gate:** if the requirement should be split, tell the user to create more tickets (suggest an epic to group them, or the ticket's existing epic); do not write several plans into one ticket.
- **Epic IDs:** an epic gets no implementation plan. Instead, from its Description, Scope and Acceptance criteria (plus any children that already exist), write the proposed split into the epic's `## Breakdown` as a dated `###` subsection: each proposed child as a numbered item with title, one-line scope, the epic criteria it covers, and its `blocked_by` / `conflicts_with` relations to the others by item number (the user adds them with `/ticket link` once the tickets exist). Skip the validator loop; this is a proposal. Add a Log line, then suggest the `/ticket create ... epic:<ID>` commands and stop. Never create the tickets; once the user does, each child is planned on its own.

## Constraints & Rules

- **Planning only:** edit nothing except the plan file (and `plans/` if it must be created), or the ticket in ticket mode. No source, config or doc changes.
- **Docs before code, repo before memory:** every claim about the repo is verified by reading it. Cite paths. Never invent files, functions or APIs.
- **Respect project rules:** follow `AGENTS.md`/`CLAUDE.md` and `docs/` standards. If the requirement conflicts with a documented rule, surface the conflict in the plan and ask the user rather than overriding it.
- **Haiku validator, 5-round cap:** always use `model: "haiku"` with a read-only agent type. Never exceed 5 validation rounds, and do not add rounds beyond that without an explicit user instruction. Never skip validation (the one exception is an epic's Breakdown in ticket mode, which is a proposal and is not validated), and never declare the plan clean without a judged pass (valid BLOCKING issues resolved, or all disproved).
- **Judge the validator:** it is a check, not an authority. Do not follow its verdict or its fixes without verifying each one against the repo yourself. Record every accepted, disproved and out-of-scope issue in the validation log.
- **No scope creep:** plan what was asked. Put extras under "Risks and open questions", not in steps.
- **Keep the plan lean:** specific and actionable, no padding. Prefer a short plan that is correct to a long one that is vague.
- **Clarify sparingly:** ask only blocking questions; make and record sensible assumptions for the rest.
