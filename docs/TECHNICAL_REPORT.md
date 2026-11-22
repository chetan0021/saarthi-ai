# Saarthi: An On-Device Personalised Learning Assistant on Arm with Llama 3.2 and ExecuTorch

Bharat AI-SoC Challenge 2026–27 · Problem Statement 4 · Phase 1 Technical Report
Team: ⟨team name⟩ · ⟨college⟩ · Faculty mentor: ⟨name⟩ · Repository: ⟨GitHub URL⟩

> Fields marked **[MEASURE]** must be filled with numbers from your own phone
> (`benchmarks/` scripts and Device tab → Export CSV). Do not submit estimates as measurements.

---

## Abstract

Saarthi is an Android application that acts as a personal learning mentor and runs entirely on an
Arm system-on-chip. A Llama 3.2 1B Instruct model, quantised to 4-bit weights (8da4w) and lowered to
the XNNPACK backend with ExecuTorch, performs the advisory and reasoning tasks: diagnostic test
generation, skill-gap analysis, roadmap planning, daily task planning, quiz generation, remediation
and conversational tutoring. A deterministic adaptive layer (Bayesian Knowledge Tracing, spaced
repetition and explicit remediation rules) closes the loop between quiz performance and the plan.
On ⟨phone model, SoC⟩ the system reaches **[MEASURE] tokens/s** decode with a peak resident memory of
**[MEASURE] MB**, with no network permission.

## 1. Problem and objectives

Classroom and video-course learning follows one fixed pace and syllabus. It does not adapt to an
individual's goal, prior knowledge, available time or language, and it gives no structured path when a
learner wants to go further. Cloud AI tutors address personalisation but require connectivity, send
personal data off-device and have recurring cost, which limits reach in India.

Objectives (from PS4 and the jury brief):

1. Understand learner goals, interests and current skills, including career, exam, certification,
   skill, language, hobby and lifelong-learning goals.
2. Identify skill gaps and generate a personalised roadmap and study plan.
3. Adapt the plan over time from quiz performance and learner feedback (weekly milestones, reviews and quizzes).
4. Run the Llama-family reasoning model on-device with ExecuTorch on an Arm Android phone.
5. Meet ≥ 22 tokens/s generation, < 1.8 GB RAM, 100 % offline operation, and support Indian languages.

## 2. System architecture

```
┌──────────────────────────── Android app (Kotlin, Jetpack Compose) ────────────────────────────┐
│  Goal · Path · Quiz · Mentor · Device tabs        Telemetry bar (t/s · RAM · TTFT · offline)  │
│        │                                                                                     │
│  AppViewModel ──► LearningRepository (closed adaptive loop)                                  │
│        │            ├─ PromptBuilder (Llama 3 template, schema-first prompts, prefill)        │
│        │            ├─ LenientJson + schema validators + retry + FallbackPlanner             │
│        │            ├─ AdaptiveEngine (BKT, SM-2-lite, remediation, feedback)                 │
│        │            ├─ ResourceCatalog (curated offline links)                               │
│        │            └─ Room / SQLite (profile, roadmap, mastery, attempts, chat, perf)        │
│        ▼                                                                                     │
│  LlamaEngine ── lazy mmap load · streaming callbacks · early stop · idle unload               │
└────────┬───────────────────────────────────────────────────────────┬────────────────────────┘
         │ JNI (fbjni)                                               │ JNI
┌────────▼────────────────────────────┐                ┌─────────────▼──────────────────────────┐
│ libexecutorch_jni.so (AAR 1.4.0)     │                │ libsaarthi_native.so (our C++17)        │
│  LLM runner · tiktoken tokenizer     │                │  getauxval HWCAP/HWCAP2 feature probe   │
│  sampler · KV cache · custom SDPA    │                │  big.LITTLE topology                    │
│  XNNPACK delegate ─► Arm NEON,       │                │  VmRSS / VmHWM / RssFile / RssAnon      │
│   dotprod, i8mm micro-kernels        │                │  NEON smull/sadalp + SDOT INT8 kernels  │
└────────┬────────────────────────────┘                └────────────────────────────────────────┘
         ▼
  saarthi_llama3_2_1b_int4.pte (mmap, file-backed pages) + tokenizer.model
```

### 2.1 Data flow of one roadmap generation

1. The UI collects the goal, goal type, level, hours/week, duration, known skills, interests and language.
2. Optional diagnostic: Llama writes 5 MCQs, one per foundation skill, and per-skill scores are computed.
3. `PromptBuilder.roadmap` builds a ~400-token prompt. The assistant turn is pre-seeded with `{"summary":"`.
4. `LlamaEngine.generate` resets the KV cache, streams tokens through `LlmCallback.onResult`, re-parses the
   partial JSON about 12 times per second so the UI renders the roadmap live, and calls `stop()` as soon as the
   top-level object closes.
5. The output is parsed leniently, validated (≥ 2 units, every unit has ≥ 1 topic) and retried once at a
   higher temperature if invalid. A deterministic template plan is used if both attempts fail.
6. The roadmap is stored in SQLite and mastery rows are seeded from the diagnostic scores. For non-English
   learners, pass 2 translates the display strings.
7. After an idle period (45 s by default) the model is closed and its memory is returned to the OS.

## 3. Model selection and optimisation for the Arm SoC

### 3.1 Why Llama 3.2 1B (not 3B)

| | 1B Instruct | 3B Instruct |
|---|---|---|
| Parameters | 1.24 B | 3.21 B |
| INT4 weights (8da4w + 4-bit embeddings), approx. | ≈ 0.9 GB | ≈ 2.0 GB |
| Fits 1.8 GB RAM budget with KV cache + app | Yes | No |
| Decode-speed headroom over 22 t/s | Large | Small or negative on most phones |

On-device decoding is memory-bandwidth bound: every new token streams essentially all linear-layer
weights from DRAM once. Halving bytes per weight therefore roughly doubles achievable tokens/s, which is
why INT4 is the main lever for the 22 t/s target. The 3B model would break the RAM budget, so the
design compensates for the smaller model's reasoning with task decomposition, schema-first prompting
and deterministic validation (§5).

### 3.2 Export recipe (`model/configs/saarthi_llama3_2_1b_int4.yaml`)

| Option | Value | Effect |
|---|---|---|
| `quantization.qmode` | `8da4w`, group 64 | Linear weights INT4 (per-group scales), activations dynamically quantised to INT8 → integer GEMM on NEON |
| `quantization.embedding_quantize` | `4,32` | 128 256 × 2048 embedding table at 4-bit (the table alone is ≈ 21 % of parameters) |
| `model.use_kv_cache` + `use_sdpa_with_kv_cache` | on | Each new token attends to cached K/V. The fused SDPA custom op avoids materialising attention matrices |
| `backend.xnnpack` + `extended_ops` | on | Lowers the graph to XNNPACK, whose Arm micro-kernels use NEON, dot-product (`SDOT`) and `I8MM` instructions when present |
| `export.max_context_length` | 2048 | Bounds the KV cache (≈ 134 MB at fp32 for 16 layers × 8 KV heads × 64 dims) |
| Load mode | mmap | Weights are file-backed pages: fast load, reclaimable by the OS, not counted as anonymous heap |

Exported file size: **[MEASURE] MB** (`ls -lh saarthi_llama3_2_1b_int4.pte`).

### 3.3 Memory budget (target < 1.8 GB)

| Component | Estimate | Measured |
|---|---|---|
| INT4 weights (mmap, file-backed) | ≈ 0.9 GB | RssFile [MEASURE] |
| KV cache (2048 ctx, fp32) | ≈ 134 MB | |
| Activations, XNNPACK workspace, runtime | ≈ 100–200 MB | RssAnon [MEASURE] |
| App, Compose UI, SQLite | ≈ 80–120 MB | |
| **Peak RSS during generation** | **≈ 1.2–1.4 GB** | **VmHWM [MEASURE]** |
| Between generations (model released) | ≈ 0.1–0.2 GB | [MEASURE] |

"Cascading memory": the model is loaded lazily on the first request, kept warm for 45 s for follow-up
calls, and then closed. An "aggressive" switch releases it after every answer, for low-RAM phones.
Peak RSS is measured with `VmHWM`, reset before each run through `/proc/self/clear_refs`.

### 3.4 Arm-specific engineering

* **Feature probe:** `getauxval(AT_HWCAP/AT_HWCAP2)` reports NEON, FP16, DotProd, I8MM, BF16, SVE/SVE2 and SME.
  Per-core `cpuinfo_max_freq` identifies the performance cores. The probe is shown in the Device tab and logged.
* **INT8 micro-benchmark:** three kernels on the same data: scalar, NEON (`smull`/`smull2` + `sadalp`) and
  Armv8.2 `SDOT` (`vdotq_s32`, compiled with `-march=armv8.2-a+dotprod` and dispatched only when HWCAP
  allows). This shows the instruction-level speed-up that XNNPACK's quantised GEMMs rely on.
  Results on ⟨phone⟩: scalar [MEASURE] GOPS, NEON [MEASURE] GOPS, SDOT [MEASURE] GOPS.
* **Threading:** ExecuTorch's threadpool targets the performance cores. Inference runs on a dedicated
  max-priority thread, separate from the UI thread.
* **Early stopping:** a streaming brace-depth watcher stops decoding when the JSON object closes, so no
  tokens (time, energy, heat) are wasted after the answer is complete.
* **Thermal awareness:** `PowerManager.getCurrentThermalStatus()` is shown live and recorded with every benchmark run.

## 4. Pedagogy engine

### 4.1 Goal understanding and skill-gap analysis

Seven goal types (career, exam, certification, skill, language, hobby, lifelong) with suggestions.
The diagnostic test produces a per-skill score s<sub>k</sub> ∈ [0, 1]. The roadmap prompt receives these
scores and the self-rated level, and returns up to 5 gaps with current and required levels (normalised
from any 0–1, 0–5, 0–10 or 0–100 scale the model uses) and a priority. Gaps are visualised as
current-versus-required bars.

### 4.2 Roadmap construction rules (in the prompt)

Prerequisites first, critical gaps closed early, 2–3 topics per unit sized to the learner's
hours (hours/week × weeks/unit), a Bloom level per topic and a demonstrable milestone per unit.
Long goals (> 8 weeks) are grouped into ≤ 8 units to keep generation short.

### 4.3 Daily micro-tasks

Each unit is expanded only when the learner opens it (lazy generation). The plan has 7 days, 1–2 tasks/day
with minutes ≈ hours/week ÷ 7, the pattern *learn → practice → mid-week quiz → review → milestone
project*, and the learner's current weak topics.

### 4.4 Adaptive loop

* **Mastery:** Bayesian Knowledge Tracing per topic (guess 0.25, slip 0.10, learn 0.15), initialised from
  the diagnostic score of the matching skill.
* **Adaptive difficulty:** quiz Bloom level is chosen from mastery. Previously missed questions are passed
  back so the same concept is re-tested differently.
* **Remediation:** score < 70 % → a remediation prompt diagnoses the missing prerequisite → 1–2 easier
  topics are inserted before the failed topic with tasks on the next open days, and the roadmap version
  increments (visible to the learner).
* **Acceleration:** score ≥ 90 % with mastery ≥ 85 % → the learner is told to move ahead faster.
* **Spaced repetition:** SM-2-lite intervals. Due topics are surfaced first in the Quiz tab.
* **Learner feedback:** too easy / too hard / need more time changes the level or duration and re-plans.
* **Weekly milestone review:** a generated summary, strengths, focus areas and one next action.

### 4.5 Pedagogical validation **[MEASURE]**

Suggested protocol (fill in before submission):

1. Generate roadmaps for 10 diverse goals (e.g. GATE ECE, VLSI engineer, AWS Cloud Practitioner, spoken
   Kannada, guitar basics, NEET Biology, Python for data science, UPSC Prelims, personal finance, Excel).
2. Ask 2–3 faculty or senior students to rate each roadmap 1–5 on: prerequisite order, coverage of the goal,
   realism of the time plan, and quiz correctness (check every generated answer key).
3. Report the mean scores, the percentage of correct quiz answer keys, and the most common error types.

| Metric | Result |
|---|---|
| Prerequisite order (1–5) | [MEASURE] |
| Goal coverage (1–5) | [MEASURE] |
| Time-plan realism (1–5) | [MEASURE] |
| Quiz answer-key accuracy | [MEASURE] % |

## 5. Structured-output reliability

A 1B model does not always produce clean JSON. Saarthi treats this as an engineering problem rather
than hoping for clean output:

1. Schema-first prompt with a filled example and English keys.
2. Assistant-turn prefill (`{"questions":[`) so generation starts inside the schema.
3. Lenient parser: finds the first JSON value inside prose or code fences, accepts trailing commas,
   single or smart quotes, unquoted keys and comments, and auto-closes strings, arrays and objects
   when output is cut off. It never throws (covered by unit tests).
4. Schema validators clamp and normalise every field: 4 options, answer index in range (also accepts
   "B" or the answer text), de-duplication, and re-indexed weeks.
5. One retry at a higher temperature, then a deterministic template, so the UI always has data.

The Device tab counts, for every JSON call: valid as generated, valid after repair, fell back.
Results over ⟨N⟩ calls: **[MEASURE] % usable** (strict [MEASURE] %, repaired [MEASURE] %, fallback [MEASURE] %).

Planned for Phase 2: grammar-constrained decoding (logit masking from a JSON-schema automaton) inside
a custom C++ runner, so the valid-as-generated rate approaches 100 %.

## 6. Multilingual support

The language picker lists all 22 scheduled languages of India plus English, in three honest tiers:
*native* (English, Hindi, which Llama 3.2 officially supports), *good* (major languages that the model
handles with prompting) and *beta* (low-resource languages for a 1B model). UI strings are fully
translated into English, Hindi, Kannada, Tamil, Telugu, Bengali and Marathi.

**Dual-pass engine:** reasoning-heavy planning (gap analysis, roadmap) runs in English, where the 1B
model is most accurate. A second, small translation pass then localises only the display strings
(titles, objectives, milestones), which are cached in SQLite. Quizzes, reviews and mentor chat are
generated directly in the learner's language. Token budgets are multiplied by 2.2 for Indic scripts.

Phase 2: LoRA fine-tuning on Indic instruction data to move "beta" languages up a tier.

## 7. Privacy, offline operation and safety

* The manifest declares **no `INTERNET` permission**, so offline operation is enforced by the OS and is
  not just a claim. The demo is recorded in airplane mode.
* All learner data is in app-private SQLite, and `allowBackup="false"` keeps it out of cloud backups.
* Resource links come from a curated catalogue (NPTEL, SWAYAM, NCERT, DIKSHA, Spoken Tutorial and others),
  never from the model, so there are no hallucinated URLs.

## 8. Results **[MEASURE]**

Device: ⟨phone model⟩, ⟨SoC⟩, ⟨RAM⟩, Android ⟨version⟩. Protocol: release build, airplane mode,
battery > 50 %, battery saver off, 1 warm-up + 5 runs, 128 new tokens, greedy decoding, median reported.

| Metric | Target | App (median) | Reference runner (median) |
|---|---|---|---|
| Decode speed | ≥ 22 t/s | [MEASURE] | [MEASURE] |
| Prefill speed | — | [MEASURE] | [MEASURE] |
| Time to first token | — | [MEASURE] ms | [MEASURE] ms |
| Model load (mmap) | — | [MEASURE] ms | — |
| Peak RSS | < 1.8 GB | [MEASURE] MB | [MEASURE] MB |
| RSS when idle (model released) | — | [MEASURE] MB | — |
| Roadmap generation (8 units), end-to-end | — | [MEASURE] s | — |
| Network permission | none | none | — |

Insert charts: `runner_bench_tps.png`, `runner_bench_ram.png`, `app_memory.png` (from `benchmarks/plot_results.py`).

## 9. Limitations and Phase 2 plan

* A 1B model has limited depth on niche topics, and quiz keys can be wrong. Plan: answer-verification
  pass, faculty-reviewed question banks, and LoRA fine-tuning on curated syllabi (NCERT, GATE, NPTEL outlines).
* Low-resource Indic languages are "beta". Plan: Indic LoRA and evaluation with native speakers.
* NPU offload: the QNN (Qualcomm) and MediaTek ExecuTorch backends can move prefill to the NPU on supported
  SoCs. Plan: a per-SoC build flavour with CPU (XNNPACK) fallback.
* Constrained decoding and a custom C++ runner (§5).
* Speech input and output for learners who prefer voice.

## 10. References

1. ExecuTorch documentation and LLM export guide, PyTorch (docs.pytorch.org/executorch).
2. Llama 3.2 model card and Community License, Meta.
3. XNNPACK, Google: quantised micro-kernels for Arm NEON.
4. Corbett, A. T. and Anderson, J. R., "Knowledge tracing: Modeling the acquisition of procedural knowledge," *User Modeling and User-Adapted Interaction*, 1994.
5. Wozniak, P. A., SuperMemo SM-2 algorithm, 1990.
6. Anderson, L. W. and Krathwohl, D. R. (eds.), *A Taxonomy for Learning, Teaching, and Assessing* (revised Bloom's taxonomy), 2001.
7. Arm Architecture Reference Manual: Armv8.2 dot product (SDOT/UDOT) and Armv8.6 I8MM extensions.
