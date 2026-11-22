# Prompts and JSON schemas

All prompts live in `android/app/src/main/java/com/bharatai/learning/core/PromptBuilder.kt` and use
the Llama 3 chat template (`<|begin_of_text|><|start_header_id|>system…`). Design rules for a 1B model:

1. **One job per prompt.** Gap analysis + skeleton roadmap, week expansion, quiz, remediation,
   review and translation are separate calls with small outputs (fewer tokens → faster, fewer errors).
2. **Show the exact JSON shape with a filled example**, English keys only.
3. **Pre-seed the assistant turn** with the opening of the JSON (e.g. `{"questions":[`), so the
   first generated token is already inside the schema.
4. **Stop early:** a brace-depth watcher stops generation the moment the top-level object closes.
5. **Bounded output:** ≤8 units × ≤3 topics, 3-question quizzes, 7-day plans.
6. **Low temperature** (0.2–0.4) for structured output, 0.6 for the mentor chat. The retry raises temperature by 0.2.

Every response then passes through `LenientJson` (extract → repair → auto-close) and the schema
validators in `Models.kt` (clamp, normalise scales, de-duplicate, require ≥2 weeks / 4 options / valid
answer index). Invalid output is retried once, then replaced by `FallbackPlanner` (deterministic).

| Call | Output schema (abridged) | Token budget (en) |
|---|---|---|
| `diagnostic` | `{"questions":[{"skill","question","options"[4],"answer":0-3,"explanation"}]}` ×5 | 520 |
| `roadmap` | `{"summary","gaps":[{"skill","current":0-1,"required":0-1,"priority":1-3,"reason"}],"weeks":[{"index","title","objective","topics":[{"title","skill","hours","bloom"}],"milestone"}]}` | 200 + 80/unit |
| `weekDetail` | `{"tasks":[{"day":1-7,"title","minutes","type":"learn|practice|quiz|project|review","topicId"}]}` | 420 |
| `quiz` | `{"questions":[{"question","options"[4],"answer","explanation","bloom"}]}` | 120 + 90/question |
| `remediation` | `{"diagnosis","topics":[{"title","skill","hours","bloom"}]}` | 200 |
| `weeklyReview` | `{"summary","strengths"[],"focus"[],"next_step"}` | 220 |
| `translate` | `{"t":["line 1", …]}` | 60 + 30/line |
| `mentor` | plain text, ≤150 words, ends with a check-for-understanding question | 300 |

Non-English budgets are multiplied by 2.2 because Indic scripts need more tokens per word.

## Adaptive rules (deterministic, auditable)

* **Mastery:** Bayesian Knowledge Tracing per topic, P(guess)=0.25 (4 options), P(slip)=0.10, P(learn)=0.15.
* **Quiz difficulty:** mastery <40% → remember/understand; 40–75% → understand/apply; >75% → apply/analyse.
* **Remediation:** score <70% → LLM diagnoses the missing prerequisite → 1–2 easier topics are inserted
  before the failed topic, with tasks on the next open days; roadmap version +1.
* **Acceleration:** score ≥90% and mastery ≥85% → "move ahead faster".
* **Spaced repetition:** SM-2-lite intervals 1 → 3 → interval × ease days; due topics appear first in the Quiz tab.
* **Feedback:** Too easy → level +1 and re-plan; Too hard → level −1 and re-plan; Need more time → +2 weeks.
