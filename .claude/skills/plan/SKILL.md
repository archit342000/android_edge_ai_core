---
name: plan
description: Plan a change from a requirement by reading the repo's docs and code, drafting a plan, and having two Haiku validators check it each round (requirement and standards; failure scenarios), with the main session judging every issue against the code. Max 5 validation rounds; stops at the first round with no valid blocking issues. Decisions that need the user are collected and asked once, after the loop. Use when asked to plan, design an approach, or scope work before implementing. Does not implement.
---

# Instructions

Turn a user requirement into a concrete, verified implementation plan grounded in the repository's own docs and code. This skill plans only; it never edits source code. The plan is drafted, validated by Haiku sub-agents, revised, and re-validated, up to 5 validation rounds. The user is not asked for decisions while the loop runs: decisions are collected in the plan and put to the user once, after the loop.

## Steps

### 1. Understand the requirement
- Restate the requirement in one or two sentences. List explicit constraints and success criteria.
- If a plan for this requirement already exists (a `plans/` file the user names, or the ticket's Plan), revise it instead of starting a new file, and validate the revision.
- Ask the user (AskUserQuestion) before drafting only when the requirement cannot be understood at all. Every other question becomes a recorded assumption, or an entry under `## Decisions for the owner` that is asked after the validation loop (step 6). Never stop mid-loop to ask.

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

## Decisions for the owner
<only when there are any (see step 5, "Owner decisions"); omit the section otherwise>

## Success criteria
- [ ] <checkable outcome>
```

Rules for the draft: `Status` starts as `todo` (implement sets `done` or `done (deviation: ...)`); every path must exist (or be marked "create"); every symbol must exist or be marked new; steps are ordered and each has a verification; no placeholders such as TBD or "etc."; do not plan work outside the requirement.

### 5. Validate with Haiku sub-agents (iteration loop)
Run at most 5 validation rounds. Do not ask the user anything during the loop.

**Choose the path first, and say which in one line:**
- **Pair** (default): two validators per round, one per lens below.
- **Single**: one generalist validator per round, only for a small, simple plan: at most 3 steps, at most 2 source files changed (tests and docs not counted), and no shared state, concurrency, data migration, authentication or external interface involved. When unsure, use the pair.

For each round:

1. Spawn the validators with the Agent tool: `subagent_type: "Explore"`, `model: "haiku"`, fresh agents each round, both in one message so they run in parallel. Give each the plan file path, the original requirement, and the repo root, and tell it to be read-only. Wait for every validator before going on.
   - **Pair path.** Tell each validator that the other covers everything outside its lens, so it reports only what its lens covers and goes deep on it.
     - **Lens 1, Requirement and standards.** Does the plan do everything asked, and follow the repo's rules?
       - Read the requirement line by line. Every part of it and every success criterion must be addressed by a step and be provable by a verification. Nothing the requirement asks for may sit under Out of scope. Nothing outside the requirement may be planned.
       - Read `AGENTS.md`, `CLAUDE.md`, the READMEs of the components touched and everything relevant under `docs/` (standards, architecture, pitfalls). Report every step that contradicts a documented rule, with the rule quoted.
       - Check that the tests the repo's standards require are planned for every piece of code that changes, and that the docs and READMEs the rules require to be updated are in a step.
       - Check that every file and symbol the plan names exists (or is marked to be created), and internal consistency: contradictions between sections, `Depends on` pointing to later steps, placeholders.
     - **Lens 2, Failure scenarios.** If the plan is implemented exactly as written, what goes wrong at runtime?
       - Read the code the plan changes and walk through each step as the implementer would, then run the result in your head: the normal path, then errors, timeouts, retries, restarts, empty or malformed input, and two requests at once.
       - Look for: wrong ordering of operations, race conditions, locks or resources never released, state left behind after a failure, clean-up that does not run on an error path, callers that see a changed behaviour, and changes that break an existing feature next to the one being changed.
       - Check each step's verification: would it actually fail if the step were done wrong? Report verifications that cannot detect the failure they are meant to catch.
       - Describe each issue as a concrete scenario (what happens, in what order, and the result), citing the code.
   - **Single path.** One validator checks, against the actual repo:
     - **Grounding:** every referenced file and symbol exists (or is explicitly marked to be created); described current behaviour matches the code.
     - **Coverage:** every part of the requirement and every stated success criterion is addressed by a step, and nothing the requirement asks for is listed under Out of scope.
     - **Standards:** no step contradicts `AGENTS.md`, `CLAUDE.md` or `docs/` rules; required tests, docs and README updates are included.
     - **Executability:** steps are ordered, specific, and each has a verification; `Depends on` only points to earlier steps.
     - **Scope and consistency:** nothing outside the requirement; no internal contradictions; no placeholders.
   - **From round 2**, also give each validator the headings of the sections you changed and the text of the previous round's valid BLOCKING issues, so it can scope its check.
2. Require this exact response format from every validator:
   ```
   VERDICT: PASS | FAIL
   ISSUES:
   1. [BLOCKING|MINOR] <check> - <plan section> - <what is wrong and the evidence (file path, line or quote)>
   ```
   `PASS` means zero BLOCKING issues. Any BLOCKING issue means `FAIL`. MINOR issues are advisory.
   - **BLOCKING** only if the plan cannot be executed as written, or executing it would break behaviour or a stated standard. Style, wording, and "could be more precise" are MINOR.
   - **Issue cap:** at most 7 issues per lensed validator (10 for the single generalist), BLOCKING first.
   - **Diff-scoped from round 2:** check only the sections changed since the previous round, plus the previous round's BLOCKING items, each validator through its own lens. Do not raise new issues in unchanged sections. A round run after a redesign (see below), or because you doubt a PASS, is a full check, not diff-scoped.
   - **Failed validator:** if an agent errors or its output does not follow the format, re-launch it once with the format restated; a failed launch is not a round. If one of the pair fails twice, judge the round on the one that answered and say so in the log. If no validator answers after the retry, stop validating, mark the plan `Draft - unresolved issues` with "validator unavailable", and say so.
3. Merge issues that make the same claim, then judge each distinct claim once. Do not follow a validator's verdict or its issue list blindly. Treat every issue as a claim to check: read the cited file and lines yourself, decide whether the claim is true, and record the decision: `valid` (fix it), `disproved` (say what the code shows), or `out of scope` (say why). Never apply a fix you have not verified against the repo.
4. The verdict is your call, not the validators'. Judge PASS by the remaining valid BLOCKING issues after your own checks. A FAIL whose BLOCKING issues are all disproved counts as a pass. A PASS you have reason to doubt is not a pass; spot-check its cited paths and run another round if needed.
5. Stop rule:
   - **Any round with no valid BLOCKING issues** (by your judgement): stop validating. Fix the valid MINOR issues inline without another round, but only with mechanical edits (a path, a missing verification line, a wording fix); log any MINOR whose fix would need new content as accepted or deferred to implement. The plan is Validated.
   - **Otherwise** (valid BLOCKING issues remain): revise and run the next round, until round 5.
   - **After round 5,** stop even if issues remain. Mark the plan `Draft - unresolved issues` and list them.
6. Each round: revise only the sections the valid issues touch, and only to fix those issues. Add no new steps, sections or detail beyond what the fix needs; new content is what the next round finds new problems in. Two kinds of valid BLOCKING issue are handled differently:
   - **Redesign rule.** If a round produces a valid BLOCKING issue in a plan section (or about a mechanism) that already had a valid BLOCKING issue in the previous round, stop patching it. Patches on a mechanism that keeps failing add moving parts, and each one brings new failure modes. Instead: rewrite that part of the Approach and its steps around the simplest mechanism that still meets the requirement (the fewest moving parts, one owner for each piece of shared state, one lock, one place where each outcome is decided; remove options and special cases rather than adding guards). State the invariants the mechanism must keep and, for each concurrent path, the order of events and who wins each race. The next round is a full check of the whole plan.
   - **Owner decisions.** If a valid issue is that the plan contradicts a documented repo rule whose deviation needs the owner's agreement, or that the requirement cannot be met without a choice only the user can make, first look for a simpler design that follows the rule and prefer it. If there is none, do not patch around it and do not declare it accepted. Add an entry under `## Decisions for the owner`: the rule (quoted, with its file) or the question, why the requirement conflicts with it, the options, and your recommendation. Mark the affected steps `blocked until the owner decides`. An issue handled this way no longer counts as BLOCKING for the stop rule, and the loop carries on with the rest. Do not ask the user now.

### 6. Finish
- **Owner decisions, after the loop only.** If `## Decisions for the owner` has entries, ask them now in one go with AskUserQuestion (each with its options, your recommendation first). Record each answer under its entry, revise the affected steps, remove their `blocked until the owner decides` marks, and run one more round scoped to those sections. It follows the same judging rules and does not count towards the 5-round cap; run at most two such rounds, and if valid BLOCKING issues still remain, list them as unresolved. If questions are not allowed (the caller forbids them, or nobody is there to answer), leave the entries open with your recommendation; the plan is then `Draft - unresolved issues` and the open decisions are its unresolved issues.
- Set `Status` in the plan to `Validated` (clean pass by your judgement), `Validated - minors logged` (passed with valid MINOR issues left unfixed), or `Draft - unresolved issues` (cap reached, validator unavailable, or owner decisions left open), and append a `## Validation log` section: the path chosen (pair or single) and, for each round, each validator's verdict, your judgement of the verdict, the issues found (merged), any redesign or owner decision, and what was changed or why an issue was dismissed.
- Append a `## Reviewer brief` section (ticket mode: `### Reviewer brief` in the Plan, placed before `### Validation log`) written from the final plan: Objective, the key decisions from Approach, Out of scope and Success criteria, in a few lines, with no reasoning, deviations or validation history. End it with: "The decisions above are intended; report a problem with one only if it breaks a requirement or a documented rule." The review skill passes it to reviewers verbatim. It is not validated; it only restates validated content.
- Other skills read any Status starting with `Validated` as ready (autopilot may set `Validated - assumptions logged` after settling open issues by recorded assumptions).
- Report to the user: the plan path, a short summary of the approach and steps, the number of validation rounds used, the outcome, the decisions the user made (or the ones still open), and any unresolved issues or open questions.
- Stop. Do not start implementing; the user decides when to execute the plan.

## Ticket mode

Applies when the user gives a ticket key such as `MA-12` (see the `ticket` skill, "Use from other skills"). Differences:
- **Jira:** read the whole issue first (`ticket` skill, "Reading a ticket") and write to it as you go. There is nothing to pull, commit or push: a Jira write is the save, and it is not a code commit, so this skill's "do not commit or push" rules do not cover it. Fields are replaced whole (read, then send the complete value), one write at a time. Say in the report if a write failed and what is missing (`ticket` skill, "Failed writes").
- **Step 1:** read the ticket. Its Description and Acceptance Criteria are the requirement; any existing Plan content (the Plan field and any `## Plan (part k of N)` comments) is prior work to build on.
- **Step 4:** write the plan into the ticket's `Plan` field, not `plans/`. Use the same structure with headings demoted: sections as `###`, steps as `####`, no `# Plan:` title, keep the Date/Status line. Follow the `ticket` skill's "Writing rules" for the field (entities for braces and brackets in code spans, the 29,000 character limit with the `## Plan (part k of N)` comment fallback). Jira is written once per round, not after every edit.
- **Step 5:** validators are read-only agents with no Jira access: paste the Description, Acceptance Criteria and the current Plan text into each validator's prompt (or save them to a scratch file and give the path) and tell it to check only the Plan against the Description and Acceptance Criteria. Revise the Plan between rounds and write it back to the field once per round. `Decisions for the owner` is a `###` subsection of the Plan.
- **Step 6:** set Status in the Plan, put the validation log in a `### Validation log` subsection, and add a Log line (`plan validated, N rounds` or `plan drafted, unresolved issues`).
- **Size gate:** if the requirement should be split, tell the user to create more tickets (suggest an epic to group them, or the ticket's existing epic); do not write several plans into one ticket.
- **Epic keys:** an epic gets no implementation plan. Instead, from its Description, Scope and Acceptance criteria (plus any children that already exist), write the proposed split into the `## Breakdown` section of the epic's Description (edit the description; add a dated `###` subsection): each proposed child as a numbered item with title, one-line scope, the epic criteria it covers, and its `blocked_by` / `conflicts_with` relations to the others by item number (the user adds them with `/ticket link` once the tickets exist). Skip the validator loop; this is a proposal. Add a Log line, then suggest the `/ticket create ... epic:<KEY>` commands and stop. Never create the tickets; once the user does, each child is planned on its own.

## Constraints & Rules

- **Planning only:** edit nothing except the plan file (and `plans/` if it must be created), or the ticket in ticket mode. No source, config or doc changes.
- **Docs before code, repo before memory:** every claim about the repo is verified by reading it. Cite paths. Never invent files, functions or APIs.
- **Respect project rules:** follow `AGENTS.md`/`CLAUDE.md` and `docs/` standards. If the requirement conflicts with a documented rule, record the conflict under `Decisions for the owner` and ask the user after the loop rather than overriding it.
- **Haiku validators, 5-round cap:** always use `model: "haiku"` with a read-only agent type; a stronger single validator tested no better than one Haiku, and two Haiku validators tested clearly better than either. Never exceed 5 validation rounds (the rounds that follow the owner's decisions in step 6 are the only extra ones), and do not add rounds beyond that without an explicit user instruction. Never skip validation (the one exception is an epic's Breakdown in ticket mode, which is a proposal and is not validated), and never declare the plan clean without a judged pass (valid BLOCKING issues resolved, or all disproved).
- **Judge the validators:** they are a check, not an authority. Do not follow a verdict or a fix without verifying it against the repo yourself. Record every accepted, disproved and out-of-scope issue in the validation log.
- **No scope creep:** plan what was asked. Put extras under "Risks and open questions", not in steps.
- **Keep the plan lean:** specific and actionable, no padding. Prefer a short plan that is correct to a long one that is vague.
- **No questions during the loop:** make and record sensible assumptions; collect what only the user can decide under `Decisions for the owner` and ask it all once, after the loop.
