---
id: ANDROIDEDGEAICORE-24
title: "Follow-ups from the engine upgrade batch PRs #47-#54"
status: todo
created: 2026-11-10
updated: 2026-11-10
links:
  relates_to: [ANDROIDEDGEAICORE-15, ANDROIDEDGEAICORE-16, ANDROIDEDGEAICORE-17, ANDROIDEDGEAICORE-18, ANDROIDEDGEAICORE-19, ANDROIDEDGEAICORE-20, ANDROIDEDGEAICORE-21, ANDROIDEDGEAICORE-22, ANDROIDEDGEAICORE-23]
---

## Description
Findings from the batch-autopilot run on ANDROIDEDGEAICORE-15 to -23 (PRs #47 to #54 on archit342000/android_edge_ai_core) that need a decision or follow-up work.

1. **Model metadata flags are unreliable.** Gemma 4 E2B reports `supportsFunctionCalling=false` and `supportsThinking=false` through `ModelInfo` but handles tools and reasoning. The planned capability pre-check (400 for tools or reasoning on an unsupported model) was therefore not added, and a model without support still fails as a plain engine error. Find a dependable probe (a trial request at load, or a per-model allowlist) or document the limitation.
2. **Thinking budgets and the context clamp are unverified.** Budgets (256/1024/4096/16384/unlimited) are advisory: `low` and `xhigh` gave the same thinking length on Gemma 4 E2B on CPU. The clamp of the context size to `maxContextTokens` never fired because Gemma 4 reports a dynamic context. Verify both on a model that honours them, or document them as inert.
3. **Usage after a stop sequence.** `usage` can include a few tokens past the match (14 completion tokens for 12 characters). Check whether the engine can report exact counts after `cancelProcess`.
4. **A dropped stream is noticed only when a token write fails** (pitfall 27). The `NettyDisconnect` `closeFuture` watcher from -22 could also cover the prefill phase.
5. **Gaps.**
   - No formal review/resolve loop was run on PRs #48 to #54.
   - ANDROIDEDGEAICORE-18's phone items remain: GPU encoders, MTP speed and memory, a real sticky restart after a rejected model, NPU, cached tokens and benchmark cost on the phone.
   - The GPU to CPU fallback and the crash guard were only tested on the emulator.
6. **Minor.**
   - A mid-session GPU failure stays a 500 with no fallback (pitfall 22).
   - `Log.e` stack traces for rejected request bodies still print at error level.
   - The outer 400 and the 500/SSE paths still echo exception text (pitfall 2).

Background: found while running the batch-autopilot on -15 to -23. Nothing here blocks the PRs.

## Acceptance criteria
- [ ] Each finding decided (fix, document or drop) with the result recorded here
- [ ] Findings that need code get their own plan (and ticket, when the owner asks for one)

## Plan

## Implementation notes

## Verification

## Review history

## Log
- 2026-11-10: created
- 2026-11-10: item 5 partly done: phone items run (see ANDROIDEDGEAICORE-18 Verification, PR #55); NPU success/NPU-only encoders still blocked; no formal review loop yet on #48-#55
- 2026-11-10: PRs #47-#56 merged into develop. Review round 1 done on #48-#55 (fixes in #56). Remaining: findings 1-4, NPU success/NPU-only encoders (waived), 2.1.0 release prep; superseded PRs #39,#44,#45,#46 and merged branches still to close/delete
