---
name: architect
description: Produce an architecture recommendation for a requirement using a panel of three Haiku architects (Pragmatist, Risk and Integrity, Longevity) moderated by the main session, which verifies every claim in the code. Takes user feedback and revises until approved, then records the approved design as an epic through the ticket skill. Use when asked for an architecture, a design, a technical direction, or to "ask the architects". Does not plan tickets or implement.
---

# Instructions

Turn a requirement into a verified architecture recommendation, revise it with the user until they approve it, then record it as an epic. You are the moderator: the architects propose and criticise; you run the process, check facts in the code and write the recommendation. This skill never edits source code and never splits work into tickets (the `plan` skill does that from the epic).

The moderator should be a strong model (Opus or better). If this session runs on a smaller model, say so in one line before starting and continue.

## Steps

### 1. Understand the requirement
- Restate the requirement in one or two sentences with its constraints (team, time, hosting, budget).
- If the user gives an epic or ticket key, read it first (`ticket` skill); its Description is the requirement.
- Ask (AskUserQuestion) only if the requirement cannot be understood at all. Otherwise record assumptions; challenging the requirement is part of the output.

### 2. Choose the path
- **Solo** when the repo is small (a few hundred lines in the area concerned) or the question is narrow (one component, one decision). Study the code yourself, then go to step 6.
- **Panel** otherwise (steps 3 to 5). When unsure, use the panel. Say in one line which path you chose.

### 3. Codebase brief (panel)
Start one agent (`subagent_type: "Explore"`, `model: "haiku"`). Ask for a factual brief of at most 600 words: purpose, stack, structure, data model, how the relevant flows work, deployment and operations facts, and any numbers stated in docs. Facts with file references only, no opinions or design suggestions. Tell it the area of interest but do not show it the requirement or any proposed solution.

### 4. Independent positions (panel)
Start three architects in parallel in one message (`subagent_type: "general-purpose"`, `model: "haiku"`). Give each: the requirement verbatim, the brief verbatim, its mandate, and these rules: it may use Read, Grep and Glob in the repo to check facts; it must not edit anything, run code or start agents; it must stay out of secrets, data and dependency folders. Do not give them your own opinion or each other's names for solutions.

Mandates:
- **Pragmatist:** whatever ships soonest with the fewest moving parts and least custom code.
- **Risk and Integrity:** correctness, data safety and failure scenarios, even at the cost of speed.
- **Longevity:** what will still be cheap to run, understand and change in five years.

Ask each for, in at most 350 words: PROPOSAL; EVIDENCE (facts from the code with file references); STACK FINDINGS (each labelled blocker / friction / better alternative, with evidence, including anything in the existing stack that should be replaced even if nobody asked); WEAKEST POINT of its own proposal; REQUIREMENT I WOULD CHALLENGE.

### 5. Cross-critique (panel)
When all three have reported, send each architect (SendMessage to its agent id; load the tool first if it is deferred) the other two positions and ask for, in at most 350 words: OBJECTION TO EACH (a concrete scenario, citing code where possible); WHAT I CONCEDE; REVISED PROPOSAL (5 bullets or fewer); OPEN DISAGREEMENT (the one decision the user must make). Tell them to concede only what they were argued out of.

One round only. The architects never message each other directly. If an architect has not answered after a reasonable wait, go on without it and say so in the report; never wait indefinitely.

### 6. Verify and synthesise
- List every claim the recommendation will rest on, open the file for each, and confirm the claim and its reference. Drop or correct anything that does not hold. Architects are often wrong on details; nothing of theirs goes in unverified.
- Ask what in the repo you have not opened that could change the recommendation, and read it.
- Write the recommendation in one voice, at most 700 words (a hard cap: keep only findings that drove a decision, one line each, and cut the rest before presenting), with these headings:
  1. Recommendation (5 bullets or fewer)
  2. What we found in the existing system (facts that drove it, each with a file reference)
  3. Problems with the requirement as stated ("None" if none)
  4. Alternatives considered and why they were rejected
  5. Decisions you must make (include **Could be dropped**: any recommended item that no verified finding or stated requirement forces, with what is lost without it)
  6. Confidence and unverified claims
- Keep stack findings that go against the existing system or the user's stated preference; put them under 3 or 4 with evidence, never silently drop them.

### 7. Present
Show the recommendation in the conversation, then one line on how it was produced (path, agents used, anything that went wrong). End by asking for feedback or approval. Do not create any ticket yet.

### 8. Feedback rounds
Handle feedback alone; do not restart the panel unless the user changes the requirement itself or asks for it.
- Feedback is input, not an order. Change the design where the feedback brings a new fact or constraint; keep it where the evidence in the repo still supports it; say which is which.
- Check each feedback point against the code as far as it needs, with the same verification pass as step 6.
- A push with no new fact behind it ("we still want X", "the CTO prefers X") changes nothing by itself. Do not meet it halfway: moving the design toward X, or reviving something you recommended against, needs a new fact or constraint, and you must name it.
- Reply in at most 600 words with: response to each point (accepted / adapted / not accepted, and why), the revised recommendation (6 bullets or fewer), what changed from the previous version, and new findings with file references. Do not repeat unchanged sections. Do not drop correct parts of the previous version without a reason.
- Repeat until the user approves.

### 9. Record the approved design as an epic
Only when the user explicitly approves the design. Approval of the design is the request to create the epic; say so in one line as you do it.
- Create one epic for the feature with the `ticket` skill (`create epic <title>`), following its epic and writing rules. If the user started from an existing epic, update that one instead of creating another.
- Fill it so the planner needs nothing from this conversation. An epic has only a Description, so all of this goes in the epic's Description as `##` sections (`## Scope`, `## Acceptance criteria`, `## Breakdown`, `## Notes`), with the goal and the approved recommendation first:
  - **Description:** the goal and why, then the approved recommendation.
  - **Scope:** in scope and out of scope, including the rejected alternatives in one line each.
  - **Acceptance criteria:** what must be true when the feature is done, derived from the requirement and the design.
  - **Breakdown:** leave empty for the `plan` skill.
  - **Notes:** a dated `### Architecture` note with the findings and their file references, the decisions the user made during feedback, and the unverified claims.
- Never create child tickets and never write the Breakdown. Report the epic key and suggest `/plan <KEY>` as the next step.

## Rules
- **No source changes.** The only write this skill makes is the epic, after approval.
- **Haiku architects.** Always `model: "haiku"` for the scout and the architects; stronger architects tested no better. Three architects, these three mandates, one critique round.
- **No direct debate, no assigned solutions.** Do not let architects message each other and do not hand them solution families to defend; both tested worse.
- **Verification is not optional.** Never present a recommendation or a revision whose load-bearing claims you have not opened in the code yourself.
- **Hold the line with evidence.** When the user pushes for something the code contradicts, say no, show the evidence and offer the nearest workable option.
- **One epic per feature.** If the requirement clearly covers unrelated features, say so and ask which to take first; do not write one epic for several.
