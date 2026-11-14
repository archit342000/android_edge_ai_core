---
id: ANDROIDEDGEAICORE-2
title: "Research LiteRT-LM and update the app into an efficient LLM server"
status: done
created: 2026-11-09
updated: 2026-11-09
links:
  split_into: [ANDROIDEDGEAICORE-3, ANDROIDEDGEAICORE-4, ANDROIDEDGEAICORE-5, ANDROIDEDGEAICORE-6, ANDROIDEDGEAICORE-7, ANDROIDEDGEAICORE-8, ANDROIDEDGEAICORE-9, ANDROIDEDGEAICORE-10, ANDROIDEDGEAICORE-11, ANDROIDEDGEAICORE-12, ANDROIDEDGEAICORE-13, ANDROIDEDGEAICORE-14]
---

## Description
Research the latest LiteRT-LM release in depth, then update the app to use it well as an efficient on-device LLM server.

Phase 1: research (findings recorded in this ticket, nothing changed). Topics:
- Versions: the latest stable or preview release of `com.google.ai.edge.litertlm:litertlm-android` and its changes since the app's `0.9.0-alpha02`, including Gemma 4 support (today it aborts with `Unknown model type: tf_lite_end_of_vision`).
- Architecture: the Engine, Conversation/Session and Message APIs, threading model, lifecycle, and how they map to `AiEngineManager`.
- Model loading: `.litertlm` format and sections, load time, memory mapping, model and engine caching, multimodal vs text-only models (vision and audio encoders being optional).
- KV cache: how it is allocated and reused, prefix caching, multi-turn reuse, context length (`maxNumTokens`), several conversations at once, and cache eviction.
- Quantization: supported schemes (int4, int8, dynamic), the quality, speed and memory trade-offs, and which variants exist for target devices.
- Backends: CPU, GPU (OpenCL/OpenGL), NPU (vendor-specific builds) and fallback behaviour; per-modality backends.
- Generation: sampling parameters, streaming, cancellation, stop tokens, token counting (for `usage`), constrained or structured output, function calling if supported.
- Concurrency and throughput: parallel requests, batching, request queueing, prefill and decode performance, benchmark tooling.
- Errors: how load and inference failures surface (exceptions vs native aborts).
- Memory and power: footprint per model, low-memory behaviour, background or foreground limits.

Sources: official LiteRT-LM docs, the GitHub repo (README, samples, release notes, source where needed), the Maven artifacts, and model cards on Hugging Face (`litert-community`).

Phase 2: changes, only after the research is reviewed and a plan is approved. Planned in this ticket with the `plan` skill, implemented on feature branches into `develop`, and verified on the test emulator and a phone (`docs/testing-on-device.md`). Likely areas, to be confirmed by the research:
- the LiteRT-LM upgrade;
- KV-cache and session reuse over REST (today every request rebuilds context, `docs/pitfalls.md` #6);
- request handling and concurrency;
- correct `usage` token counts;
- configurable vision and audio backends;
- clean failure on unsupported models.

## Acceptance criteria
- [x] Research findings recorded per topic above, with source links and the version they apply to
- [x] Gap analysis: current app behaviour vs what LiteRT-LM offers, ranked by impact on server efficiency
- [ ] The user has reviewed the findings and agreed the scope of phase 2
- [ ] Phase 2 plan validated and approved before any code change
- [ ] Changes implemented, merged into `develop` via PRs, and verified on the test emulator (CPU) and, where needed, a phone (GPU, NPU, vision)
- [ ] Docs updated alongside the changes (`docs/architecture.md`, `docs/standards/api.md`, `docs/pitfalls.md`, `docs/testing-on-device.md` as relevant)

## Plan

## Implementation notes
### 2026-11-09: Phase 1 research findings (LiteRT-LM v0.19.1)
All findings apply to **v0.19.1** (released 2026-11-05, the latest on Maven) unless marked otherwise. The app uses **0.9.0-alpha02**.

Sources:
- Maven `com.google.ai.edge.litertlm:litertlm-android` (metadata, POM, AAR).
- GitHub releases.
- Kotlin API: `https://github.com/google-ai-edge/LiteRT-LM/blob/v0.19.1/kotlin/java/com/google/ai/edge/litertlm/` (`Config.kt`, `Engine.kt`, `Conversation.kt`, `Session.kt`, `ExperimentalFlags.kt`, `Benchmark.kt`).
- Docs: `https://github.com/google-ai-edge/LiteRT-LM/blob/v0.19.1/docs/api/kotlin/getting_started.md`, `https://github.com/google-ai-edge/LiteRT-LM/blob/v0.19.1/docs/api/cpp/conversation.md`.
- Official OpenAI-compatible server: `https://github.com/google-ai-edge/LiteRT-LM/blob/v0.19.1/python/litert_lm_cli/commands/serve/` (`util.py`, `openai_chat_completions.py`, `openai_common.py`).
- Model card: https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm

**1. Versions and upgrade cost**
- Releases since the app's version: 0.9.0, 0.10, 0.11, then 0.12 to 0.19.1, roughly monthly. Gemma 4 support arrived around 0.13 to 0.14, with multimodal Gemma 4 on Artisan GPU and MTP added later. Release notes mention a breaking C API 1.0.0; the Kotlin API stays largely source-compatible.
- Build requirements, from the AAR:

  | | 0.9.0-alpha02 (app today) | 0.19.1 |
  |---|---|---|
  | Bytecode | class major 55 (Java 11) | class major 65 (Java 21) |
  | Kotlin metadata | 2.2.0 | 2.4.0 |
  | Dependencies | kotlin 2.2.21, coroutines 1.9.0 | `kotlin-reflect` 2.4.0, `kotlinx-coroutines-android` 1.11.0, `gson` 2.14.0 |
  | AAR size | 16 MB | 21 MB |

- 0.19.1 has `minSdk` 24 and ships arm64-v8a and x86_64 `liblitertlm_jni.so` (23 MB for arm64).
- This explains today's `-Xskip-metadata-version-check`: the app compiles with Kotlin 2.1 against a library built with Kotlin 2.2.
- Upgrading needs Kotlin 2.4 (or the skip flag again, which is risky) and probably `jvmTarget` 17 or 21 instead of 1.8, because of inline functions compiled for Java 21. AGP 9 and D8 handle Java 21 bytecode.

**2. Architecture**
- `Engine(EngineConfig)` then `initialize()`, which is blocking and slow ("up to 10 s"); run it off the main thread. `close()` frees native memory. `AutoCloseable`.
- `Engine.createConversation(ConversationConfig)` gives a high-level chat object that applies the model's Jinja chat template, keeps history, handles tool calls, channels (thinking) and constrained output.
- `Engine.createSession(SessionConfig)` gives a low-level object: `runPrefill(List<InputData>)`, `runDecode()`, `generateContent` and `generateContentStream`, with raw text, image (PNG or JPG) and audio (WAV) input.
- Both have `cancelProcess()` and `clone()`, which shares the current KV cache state. `Conversation` also has `getTokenCount()`, `getBenchmarkInfo()` and `renderMessageIntoString()`.
- `sendMessage` (blocking) and `sendMessageAsync` (a `MessageCallback`, or a Kotlin `Flow`) take per-call overrides: `maxOutputToken`, `repetitionPenaltyConfig` (repetition, presence and frequency penalty, OpenAI style), `noRepeatNgramConfig`, `suppressTokensConfig`, `thinkingConfig` and `responseFormat`.
- Errors: `LiteRtLmJniException` (native) and `IllegalStateException` (lifecycle). Async errors go to `onError`, with `CancellationException` on cancel.
- Mapping to the app: `AiEngineManager` uses `Engine` and `Conversation` (`createConversation`, `sendMessageAsync`) with one active conversation behind a `Mutex`.

**3. Model loading**
- `EngineConfig`:
  - `modelPath`, `backend`;
  - `visionBackend` and `audioBackend`, **nullable; null means that encoder is not initialised**;
  - `maxNumTokens`, which equals the KV-cache size (input plus output);
  - `maxNumImages`;
  - `cacheDir`, a writable directory for caching (it "can improve 2nd load time"; the default is the model's directory, and `":nocache"` disables it);
  - `activationDataType` (FLOAT32 is the CPU default, FLOAT16 the GPU default);
  - `speculativeDecodingConfig`.
- Effect on the app: hard-coding `visionBackend = GPU`, `audioBackend = CPU` is why text-only models fail. Setting them only when the model has the encoders, and choosing the vision backend, fixes pitfalls 19 and 20. The official server picks per-modality backends from the model's sections (`TF_LITE_VISION_ENCODER`, `TF_LITE_AUDIO_ENCODER_HW`).
- `.litertlm` is a container of sections: LLM metadata proto, tokenizer, prefill and decode TFLite models, optional vision and audio encoders, and an optional MTP drafter. It is built with `litert-lm-builder`.
- Gemma 4 E2B: a generic file (2.6 GB), a `-gpu` file (2.0 GB), and NPU builds per SoC (Google Tensor G5 and G6, Qualcomm QCS8275, Intel). It supports up to 32k context; the benchmarks use 2048.

**4. KV cache**
- One KV cache per Conversation or Session, sized by `EngineConfig.maxNumTokens` (default from the model). `getTokenCount()` gives the tokens currently in the cache.
- Multi-turn reuse is automatic inside one `Conversation`: each `sendMessage` prefills only the new turn.
- `clone()` forks a conversation together with its KV cache. It enables prefix caching, for example a warmed system-prompt conversation cloned per client.
- `ConversationConfig.prefillPrefaceOnInit` prefills the system prompt and tools at creation time.
- `ExperimentalFlags.filterChannelContentFromKvCache` keeps thinking text out of the cache.
- Release notes: a LiteRT-based KV cache, on-demand KV growth on NPU, local-attention memory cuts, and `use_autosized_ringbuffers` for long context.
- The official OpenAI server (`serve/util.py` `get_or_create_server_conversation`):
  - keeps one cached conversation, keyed by (prior messages, tools, sampler config, thinking config, constrained-decoding config);
  - when a stateless request's `messages[:-1]` equal the previous turn's `[*prior, prompt, assistant reply]` and the settings match, it reuses the conversation and prefills only the last message;
  - otherwise it closes it and builds a new one from `messages[:-1]` (`initialMessages`).
  This is the pattern for making the stateless `/v1/chat/completions` efficient.
- Effect on the app: `OpenAIServer` gives every request a random conversation id, so `AiEngineManager`'s reuse check never matches, and every request rebuilds the whole context (pitfall 6).

**5. Quantization**
- Quantization is fixed when the model is built; there is no runtime choice.
- Gemma 4 uses a quantization-aware-training mix of 2-, 4- and 8-bit weights. Older community models are int4, int8 or dynamic (for example Qwen3 `dynamic_wi4b32_afp32`).
- The runtime choice is `activationDataType`. INT8 or INT16 work only if the model supports them; otherwise `initialize()` throws.
- Pick the file per backend: generic for CPU, the `-gpu` variant for GPU, and an SoC-specific build for NPU.

**6. Backends**
- `Backend.CPU(threadCount)`, `Backend.GPU()`, `Backend.NPU(nativeLibraryDir)` (for app-bundled NPU libraries use `context.applicationInfo.nativeLibraryDir`), and `Backend.GOOGLE_TENSOR()` (Artisan, the Pixel GPU/TPU path used for multimodal Gemma 4).
- GPU needs `uses-native-library` `libOpenCL.so` and `libvndksupport.so` (the app already has them). There is no automatic fallback; the app has to catch the failure and retry on CPU.
- Gemma 4 E2B on an S26 Ultra:

  | Backend | Prefill (tok/s) | Decode (tok/s) | Time to first token | Memory |
  |---|---|---|---|---|
  | CPU, 4 threads | 557 | 47 | 1.8 s | 1.7 GB |
  | GPU | 3808 | 52 | 0.3 s | 0.7 GB |

  So GPU is much better for prefill (time to first token) and memory.

**7. Generation features**
- `SamplerConfig(topK, topP, temperature, seed)`, set per conversation.
- Per call: `maxOutputToken` (maps to `max_tokens`), repetition, presence and frequency penalties, n-gram ban, suppressed tokens.
- Streaming through the callback or a Flow. `cancelProcess()` stops a request when the client disconnects.
- Thinking channel (`ThinkingConfig`, channels). Tools with automatic or manual calling (the official server uses manual calling and returns OpenAI `tool_calls`).
- `ResponseFormat` for constrained decoding with LLGuidance (JSON schema). Per-conversation Jinja `chatTemplate` override. LoRA.
- Speculative decoding (MTP) for Gemma 4, mode OFF, ON or ADAPTIVE: GPU decode goes from 52 to 66–92 tok/s, CPU from 41 to 36–48 (it can be slower on CPU; ADAPTIVE limits that).
- Token usage, as the official server does it:
  - completion tokens = `BenchmarkInfo.lastDecodeTokenCount`;
  - prompt tokens = `getTokenCount() − prefill − decode` (the cached tokens) + `lastPrefillTokenCount`;
  - this needs `ExperimentalFlags.enableBenchmark = true` before the engine is created.
  The app returns `usage` 0 today.

**8. Concurrency and throughput**
- The official server is a plain single-threaded `http.server.HTTPServer`: one engine, one cached conversation, requests handled one after another.
- There is no batching API in Kotlin. Several conversations can exist on one engine (each with its own KV memory), but decoding is effectively serial on a mobile device. Release notes mention fixing re-entrancy when engines initialise at the same time.
- Practical model: one engine, a request queue that runs one request at a time, a small set of cached conversations (or clones) by prefix, and cancellation on disconnect. The app already serialises with `inferenceMutex`; `/v1/load` reports queue depth.
- `Benchmark.kt` and `ExperimentalFlags.enableBenchmark` give time to first token and prefill/decode tokens per second, usable for the app's metrics.

**9. Errors**
- API errors come back as exceptions.
- Model-format problems can still abort natively (`Check failed` then SIGABRT), as observed with 0.9.0-alpha02 and Gemma 4; it is unconfirmed whether 0.19.1 fixed this for unknown sections.
- The app cannot catch a native abort. Options: validate the model before loading (the Kotlin `ModelInfo.kt` reads model metadata), or load in a separate process.

**10. Memory and power**
- Gemma 4 E2B needs 0.7–1.7 GB of RAM (GPU vs CPU) for a 2.6 GB file, since weights are memory-mapped. The KV cache grows with `maxNumTokens`.
- Each extra live conversation costs KV memory, so cap cached conversations and close idle ones (the app already has TTL cleanup in `ConversationManager`).
- The engine runs inside a `dataSync` foreground service (already in place). `cacheDir` should point at `context.cacheDir` to speed up the second load.

### 2026-11-09: Gap analysis (app vs LiteRT-LM 0.19.1), ranked by effect on server efficiency
| # | Gap | Effect | Effort |
|---|-----|--------|--------|
| 1 | No KV/conversation reuse across REST requests: the whole history is re-prefilled on every call (pitfall 6) | Very high: time to first token grows with history | Medium: cache conversations by message prefix, as the official server does |
| 2 | LiteRT-LM 0.9.0-alpha02 is 10 releases behind: no Gemma 4, no MTP, older KV cache, fewer API features | High: unlocks everything below | Medium: Kotlin 2.4, `jvmTarget` 17/21, remove the skip flag, API changes |
| 3 | Vision and audio encoders always on (vision fixed to GPU): text-only models fail, encoders cost memory and load time | High for model choice and memory | Low: set them from the model's sections and a setting |
| 4 | No `cacheDir`, so the second load is slower | Medium | Low |
| 5 | No speculative decoding (MTP) for Gemma 4 | Medium to high on GPU (1.3–1.8× decode) | Low after #2 |
| 6 | No cancellation when a client disconnects: a dropped stream keeps decoding and blocks the queue | Medium | Low: `cancelProcess()` |
| 7 | `usage` always 0 | Medium for clients | Low: benchmark info plus `getTokenCount()` |
| 8 | `maxNumTokens` not set; `max_tokens` and penalties ignored | Medium: control of memory and latency | Low |
| 9 | Native abort on an unsupported model (crash loop) | Medium for robustness | Medium: validate with `ModelInfo` before loading |
| 10 | No GPU-to-CPU fallback: GPU "loads" then fails | Medium | Low |
| 11 | No tools, `response_format` or thinking in the API | Feature work (OpenAI compatibility), not efficiency | Medium |
| 12 | System prompt fixed ("You are a helpful assistant."); `system` role mapped to user | Correctness | Low |

Open questions to settle in phase 2:
- Does 0.19.1 abort or throw on unknown model sections?
- The exact Kotlin and JVM settings needed (to confirm in a branch build).
- Gemma 4 `-gpu` vs generic file on the target phone.
- How many cached conversations fit in memory on the phone.

## Verification

## Review history

## Log
- 2026-11-09: created
- 2026-11-09: phase 1 research started
- 2026-11-09: phase 1 research recorded (findings per topic + ranked gap analysis); waiting on user review
- 2026-11-09: linked split_into ANDROIDEDGEAICORE-3..ANDROIDEDGEAICORE-14 (one ticket per gap)
- 2026-11-09: closed at the owner's request; criteria 3-6 left unchecked (phase 2 was split into ANDROIDEDGEAICORE-3 to -14; their PRs #36-#46 are not merged; landing them is ANDROIDEDGEAICORE-20)
