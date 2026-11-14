---
id: ANDROIDEDGEAICORE-18
title: "Verification debt from the engine upgrade batch"
status: done
created: 2026-11-09
updated: 2026-11-10
links:
  blocked_by: [ANDROIDEDGEAICORE-20]
  relates_to: [ANDROIDEDGEAICORE-4, ANDROIDEDGEAICORE-5, ANDROIDEDGEAICORE-7, ANDROIDEDGEAICORE-8, ANDROIDEDGEAICORE-9, ANDROIDEDGEAICORE-10, ANDROIDEDGEAICORE-11, ANDROIDEDGEAICORE-12, ANDROIDEDGEAICORE-24]
---

## Description
Things the batch could not check (emulator only, no phone connected):
- Phone GPU and CPU: speculative decoding speed comparison (ANDROIDEDGEAICORE-7 stays open for this), GPU vision/encoders (-5), GPU to CPU fallback on a real phone (-12), memory with 2 cached conversations and with MTP (-4, -7).
- Restore of stored settings after an app restart: context size (-10), speculative decoding (-7), encoder choices (-5). Only the first start was exercised.
- A real sticky restart after a rejected model (-11): the persisted preferences were checked, not the restart.
- The cached-token count on a reused conversation (-9 stays open for this, once -4 is merged).
- The latency cost of `ExperimentalFlags.enableBenchmark` (-9).
- The cancel timeout path (-8): the 2 s wait was always satisfied in about 50 ms; closing a conversation while native decoding still runs is unverified.
- Why Gemma 3n answered image requests on GPU vision on the emulator while the main GPU backend fails (-5).
- NPU, and a model whose encoders list only NPU.

Background: found while running the ANDROIDEDGEAICORE-4 to -14 batch.

## Acceptance criteria
- [ ] Each item run on a phone or the emulator and the result recorded in `docs/testing-on-device.md` or the owning ticket
- [ ] ANDROIDEDGEAICORE-7 and -9 closed once their pending criteria pass
- [ ] Failures turned into tickets

## Plan
**Date:** 2026-11-09  **Status:** Validated

### Objective
Run every check the engine batch could not, on the emulator where possible and on a phone with the owner for the rest, record each result, close ANDROIDEDGEAICORE-7 and -9 when their pending criteria pass, and turn failures into tickets.

### Context
- Findings check (2026-11-09): every item is still unverified. The owning tickets confirm it: -7 has "Decode tokens per second compared with and without it on the phone's GPU and on CPU" open; -9 has "Cached prompt tokens are counted when a conversation is reused" open (needs #43); -9's Verification says the `enableBenchmark` latency cost was not measured; -8 records the cancel wait always met (about 50 ms) and the 2 s timeout path unexercised; -5 V4 records the unexplained Gemma 3n GPU-vision answer on the emulator (pitfall 20 says so once #46 is merged by ANDROIDEDGEAICORE-20); -10, -7, -5 exercised only the first start of their settings; -11 checked the persisted prefs, not a real sticky restart.
- No phone is connected (`adb devices` empty). Test models present: `~/.cache/edgeaicore-test-models/gemma-4-E2B-it.litertlm` and `Qwen3-0.6B.litertlm`; Gemma 3n is no longer in the cache and must be downloaded again (`litert-community` on Hugging Face) for the vision item.
- Depends on ANDROIDEDGEAICORE-20: the checks run on `develop` with every PR merged, so results describe the code that ships (and -9's cached-token check needs #43 and #38 together).
- Tools: `scripts/device.sh` (`up`, `start`, `api`, `logs`, `screenshot`; `SERIAL=<serial>` for a phone, `docs/testing-on-device.md` "Using a phone"); the evidence scripts under `ANDROIDEDGEAICORE-<n>-evidence/scripts/` (`mtp.sh`/`decode.py` for -7, `usage.sh` for -9, `reuse.sh` for -4, `disconnect.sh` for -8, `encoders.sh`/`media.py` for -5, `gpu.sh` for -12, `badmodels.sh` for -11, `limits.sh` for -10).
- Rules: `docs/standards/testing.md` (device checks; only the dedicated emulator, never the owner's app data), `docs/testing-on-device.md` (where results are recorded), `docs/standards/gitflow.md` (the docs update goes through a `feature/` branch and PR).

### Approach
- Emulator first (Steps 2 to 7), each run scripted and logged into `ANDROIDEDGEAICORE-18-evidence/`; then one owner-assisted phone session (Step 8) for GPU, NPU, MTP speed and memory.
- "App restart" means killing the process with `adb shell kill <pid>` (not `am kill`, which skips foreground services, and not `force-stop`, which blocks the sticky restart), checking that the pid changed, and, separately, relaunching `MainActivity`; both paths are checked.
- Phone safety: the owner's phone is never touched before the owner agrees how. A debug build cannot update a release install and uninstalling deletes tokens and models (pitfall 25), so the owner picks one: a phone without the release app, or uninstalling the release app knowingly. The emulator evidence scripts start with `device.sh down`/`up` and some hardcode `emulator-5584`, so the phone runs use adapted copies that drop `down`/`up` and the hardcoded serial and pass `SERIAL=<phone>` to every `device.sh` and `adb` call. Setup on the phone replaces `up` per `docs/testing-on-device.md` "Using a phone" (skip `boot` and `down`): `scripts/device.sh build`, then `install`, `model`, `start <backend>`, `token`, `forward`, each with `SERIAL=<phone> MODEL=...` set, after which `api` and the copied curl steps work. The emulator is brought `down` first so the host port forward is free.
- The `enableBenchmark` cost is measured with a throwaway local build that sets the flag off (never committed or pushed).
- Results go into `docs/testing-on-device.md` (a short "Verified behaviour" table) through one docs PR, and into each owning ticket's Verification; -7 and -9 are closed when their criteria pass; each failure becomes a suggested ticket.

### Out of scope
- Fixing anything found (suggested tickets instead).
- NPU runs when no NPU phone or NPU-built model is available: recorded as blocked with the reason.

### Steps
#### Step 1: Prerequisites
- **Status:** todo
- **Depends on:** none
- **Files:** none in the repo
- **Change:** confirm ANDROIDEDGEAICORE-20 is done; `git checkout develop && git pull`; download Gemma 3n `.litertlm` to `~/.cache/edgeaicore-test-models/`; `MODEL=... scripts/device.sh up`.
- **Verification:** `scripts/device.sh api GET /v1/health` returns 200 on the emulator.

#### Step 2: Settings survive a restart
- **Status:** todo
- **Depends on:** 1
- **Files:** script `ANDROIDEDGEAICORE-18-evidence/scripts/restore.sh`
- **Change:** in Backend Settings set a non-default context size, speculative decoding off, vision encoder CPU; start the service; `adb shell kill <pid>`, confirm a new pid, and wait for the sticky restart; then relaunch the activity.
- **Verification:** after the sticky restart, logcat shows the engine created with the saved context size, speculative setting and encoder backends; after the relaunch the UI shows the saved values (screenshot).

#### Step 3: Sticky restart after a rejected model
- **Status:** todo
- **Depends on:** 1
- **Files:** script in the evidence folder
- **Change:** with `MODEL=<path to ANDROIDEDGEAICORE-11-evidence/bad.litertlm>`, run `scripts/device.sh model` and `start CPU`, then `adb shell kill <pid>` and confirm a new pid.
- **Verification:** the restarted service does not try the rejected file (logcat); prefs show `crashed_during_init` false and the rejected path not saved (as in ANDROIDEDGEAICORE-11 V4, or the previous model kept if ANDROIDEDGEAICORE-19 has landed).

#### Step 4: Cached tokens on a reused conversation
- **Status:** todo
- **Depends on:** 1
- **Files:** script based on `ANDROIDEDGEAICORE-9-evidence/scripts/usage.sh` and `-4 .../reuse.sh`
- **Change:** a two-turn conversation where turn 2 extends turn 1, non-streaming and streaming with `include_usage`.
- **Verification:** turn 2's `usage.prompt_tokens_details.cached_tokens` is greater than 0 and logcat shows the reuse; on PASS tick -9's criterion and close -9.

#### Step 5: Cost of `enableBenchmark`
- **Status:** todo
- **Depends on:** 1
- **Files:** a throwaway local branch (not pushed) with `ExperimentalFlags.enableBenchmark = false`
- **Change:** same prompt on CPU with each build: 10 streaming requests for time to first token and 10 non-streaming requests for total time.
- **Verification:** medians and the difference recorded in -9's Verification and in Step 9's table.

#### Step 6: Cancel timeout path
- **Status:** todo
- **Depends on:** 1
- **Files:** script based on `ANDROIDEDGEAICORE-8-evidence/scripts/disconnect.sh`
- **Change:** disconnect a streaming client during the prefill of a long prompt (about 3,000 tokens) on CPU, where `cancelProcess` is most likely to be slow; repeat 5 times; then send a new request.
- **Verification:** logcat shows whether the 2 s wait expired; if it did, the conversation close while native decoding runs did not crash the process and the next request succeeds.

#### Step 7: Gemma 3n GPU vision on the emulator
- **Status:** todo
- **Depends on:** 1
- **Files:** script based on `ANDROIDEDGEAICORE-5-evidence/scripts/encoders.sh`
- **Change:** Gemma 3n, main backend CPU, vision Auto (GPU); image request with verbose logcat (all tags, not only the app's) to see which delegate the vision encoder actually ran on.
- **Verification:** the explanation (or "still unexplained" with the logs) is recorded for pitfall 20.

#### Step 8: Phone session with the owner
- **Status:** todo
- **Depends on:** 2, 3, 4, 5, 6, 7
- **Files:** phone copies of `ANDROIDEDGEAICORE-7-evidence/scripts/mtp.sh` and `decode.py`, `-12 .../gpu.sh`, `-5 .../encoders.sh` and `-4 .../reuse.sh` in `ANDROIDEDGEAICORE-18-evidence/scripts/phone/` (adapted as in Approach)
- **Change:** after the owner's agreement on the install (Approach), with the phone connected: decode tok/s with speculative decoding on and off, on GPU and CPU; vision and audio with GPU encoders; GPU start; `adb -s <phone> shell dumpsys meminfo com.aanand.edgeaicore` after the phone copy of the reuse script in `after` mode (two conversations A and B interleaved, so 2 conversations are cached) and again with MTP on; NPU only if the phone has one and an NPU-built model is available, including a model whose encoders list only NPU.
- **Verification:** each result recorded; on PASS of the tok/s comparison tick -7's criterion and close -7; the GPU-to-CPU fallback recorded as "not reachable" if the phone's GPU works (it is only triggered by an unusable GPU); NPU and the NPU-only-encoder model recorded as run or blocked with the reason.

#### Step 9: Record and follow up
- **Status:** todo
- **Depends on:** 8
- **Files:** `docs/testing-on-device.md` (modify: add a "Verified behaviour" table; update backend table rows for phone GPU/NPU/vision), `docs/pitfalls.md` (20 if Step 7 explains it); owning tickets' Verification sections
- **Change:** one `feature/` docs PR with the results; failures listed as suggested tickets to the owner.
- **Verification:** `./gradlew testDebugUnitTest lintDebug` and `scripts/check_docs.sh` pass on the docs branch and are pasted in the PR's Testing section, and the PR is reviewed against `docs/standards/review.md` (merge gate); every Description item has a recorded result (PASS, FAIL or BLOCKED with reason).

### Tests
No new code tests; device scripts as listed. The docs PR runs `./gradlew testDebugUnitTest lintDebug` and `scripts/check_docs.sh`.

### Risks and open questions
- The phone session depends on the owner's time and device; Steps 2 to 7 do not wait for it.
- A Gemma 3n download may need a Hugging Face login; if blocked, Step 7 is recorded as blocked.
- The prefill cancel may still finish within 2 s; then the timeout path is recorded as not reachable on the emulator.

### Success criteria
- [ ] Every Description item run (or blocked with a reason) and recorded in `docs/testing-on-device.md` or the owning ticket
- [ ] ANDROIDEDGEAICORE-7 and -9 closed if their pending criteria pass
- [ ] Failures listed as suggested tickets

### Owner decisions 2026-11-09
- Phone: the owner's Pixel 9 Pro XL (Tensor G4), option B: the release app is uninstalled after the owner backs up the token file (`Tokens` page export); copied models are lost.
- NPU: LiteRT-LM 0.19.1 knows `NpuBrand.GOOGLE_TENSOR`, but the app passes `Backend.NPU(nativeLibraryDir = "")` and no Tensor-G4-built `.litertlm` is available; Step 8 tries NPU only if such a model is found on `litert-community`, else records NPU and the NPU-only-encoder model as BLOCKED with that reason.
- Gemma 3n download: try anonymously; record Step 7 BLOCKED if a login is needed.

### Note 2026-11-09
- Step 5 (`enableBenchmark` cost) is done: no measurable overhead (ANDROIDEDGEAICORE-9 Verification, re-run 2); it is not repeated. Step 4 passed on a local #38+#43 merge; on `develop` it is a quick re-run of `ANDROIDEDGEAICORE-9-evidence/scripts/cached.sh` to confirm the merged code.

### Note 2026-11-09 (owner waiver)
- The phone speed comparison with and without speculative decoding is waived by the owner; ANDROIDEDGEAICORE-7 is closed. Step 8 drops the tok/s runs; the memory reading with MTP on stays (it is a separate Description item).

### Reviewer brief
Objective: run every unverified check from the engine batch and record each result. Key decisions: runs on `develop` after ANDROIDEDGEAICORE-20; emulator items first (settings restore after a process kill and a relaunch, sticky restart after a rejected model, cached tokens on reuse, `enableBenchmark` cost with a throwaway build, cancel during a long prefill, Gemma 3n vision on the emulator); then one owner-agreed phone session (MTP tok/s, GPU encoders, GPU start, memory with 2 cached conversations and MTP, NPU if available) with adapted phone scripts; results in a docs PR (full checks and review) and the owning tickets; -7 and -9 closed when their criteria pass. Out of scope: fixes (suggested tickets), NPU without hardware (recorded blocked). Success: every item PASS, FAIL or BLOCKED with reason.
The decisions above are intended; report a problem with one only if it breaks a requirement or a documented rule.

### Validation log
- Round 1: validator FAIL. Judged FAIL. (1) BLOCKING Gemma 3n vision on the emulator contradicts "vision only on a phone": disproved, the item investigates an emulator observation; the testing guidance names where GPU work is meaningful, it does not forbid emulator runs. (2) BLOCKING phone data without a guard: valid, owner agreement and pitfall 25 options added. (3) BLOCKING emulator-only scripts on a phone: valid, adapted phone copies. (4) BLOCKING docs PR without the full merge-gate checks: valid, added. Minors (5) NPU-only encoder model, (6) fallback not reachable on a working GPU, (7) pitfall 20 text from #46, (8) Step 3 expected state, (9) TTFT needs streaming, (10) `am kill` skips foreground services: valid, fixed.
- Round 2: validator FAIL. Judged FAIL. (1) BLOCKING phone copies drop `up` without its install/model/start/token/forward steps: valid, phone setup sequence added. Minors: reuse `after` mode, review in the merge gate, Step 3 kill method: valid, fixed.
- Round 3: validator PASS. Judged PASS. Minors fixed inline: `MODEL`/`SERIAL` on every call and emulator down first; Step 3 loads the bad file via `MODEL`; "add" the Verified behaviour table; Step 8 names the phone copy.

## Implementation notes

## Verification
Run 2026-11-10 on the merged branches (evidence in ANDROIDEDGEAICORE-18-evidence/ and ANDROIDEDGEAICORE-20-evidence/); results table in PR https://github.com/archit342000/android_edge_ai_core/pull/55.
- Settings survive a kill: PASS (emulator and phone: context 2048, MTP off, vision CPU restored).
- Sticky restart after a rejected model: PASS (emulator and phone; previous model restored, flag false).
- Cached tokens on reuse: PASS (emulator 320/339; phone GPU 17). -9 criterion met.
- enableBenchmark cost: PASS earlier (no measurable overhead), not repeated.
- Cancel timeout path: NOT REACHED (emulator and phone; a drop during prefill is not noticed until the first token write, so the 2 s wait never ran; non-stream cancel stops in 46-88 ms).
- Gemma 3n GPU vision on emulator: explained as most likely a silent CPU fallback (only the XNNPACK delegate is logged, no OpenCL error); phone GPU vision PASS.
- Phone GPU: PASS (no fallback needed; ready in 12-49 s). GPU->CPU fallback on the phone: not reachable.
- Memory with 2 cached conversations and MTP adaptive: phone GPU 3.67 GB PSS (2.6 GB graphics); emulator CPU 1.8 GB.
- MTP tok/s: waived by the owner.
- NPU: Gemma 4 E2B (not NPU-built) fails with a handled 'Failed to initialize'; process survives. NPU success and NPU-only encoders BLOCKED (no NPU-built model).

## Review history

## Log
- 2026-11-09: created from the follow-ups of the engine upgrade batch
- 2026-11-09: findings checked (all items still unverified; no phone connected; Gemma 3n no longer in the model cache); plan validated, 3 rounds
- 2026-11-09: linked blocked_by ANDROIDEDGEAICORE-20 (its code lives on the unmerged batch PRs)
- 2026-11-09: owner decisions recorded in the Plan
- 2026-11-09: Steps 4 and 5 run early on a local #38+#43 merge (ANDROIDEDGEAICORE-9 re-run 2)
- 2026-11-09: MTP phone speed comparison removed from Step 8 (owner waiver)
- 2026-11-10 batch-autopilot: stopped. Needs the Pixel 9 Pro XL connected (adb shows no phone); emulator-only items were covered by -20's pass. Nothing implemented.
- 2026-11-10: linked relates_to ANDROIDEDGEAICORE-24 (batch follow-ups)
- 2026-11-10: run on the emulator and the Pixel 9 Pro XL; PR #55. Open: NPU success/NPU-only encoders (blocked)
- 2026-11-10: closed (results recorded in PR #55; MTP tok/s measured on the phone (no speedup), NPU waived by the owner)
- 2026-11-10: MTP tok/s measured on the Pixel 9 Pro XL (evidence/mtp-phone.txt): GPU off 27.3 / on 26.7 / adaptive 26.9; CPU off 22.1 / on 22.1 / adaptive 21.6 (no speedup on the phone; emulator CPU 22.9 -> 33.0)
