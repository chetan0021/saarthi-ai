# Demonstration video — shot list and voice-over (target 4:00)

**Setup before recording:** release APK installed, model pushed, phone charged >50 %, battery saver
off, phone cool, learning data reset (Device → Reset learning data), screen recording on
(Quick Settings → Screen record, with device audio off). Record your voice-over separately or live.
Keep the telemetry bar visible in every shot. It is the proof of the jury's numbers.

| Time | On screen | Voice-over |
|---|---|---|
| 0:00–0:20 | Title card: "Saarthi — On-device personalised learning · Llama 3.2 + ExecuTorch on Arm". Then the phone's Quick Settings: **turn on airplane mode**, show Wi-Fi and data off. | "Classroom teaching moves at one speed for everyone. Saarthi is a personal mentor that runs fully on this Arm phone. Airplane mode is on, and the app does not even have internet permission." |
| 0:20–0:35 | Cut to `AndroidManifest.xml` on GitHub: no `INTERNET` permission line. | "The operating system itself blocks network access, so your goals, scores and chats never leave the device." |
| 0:35–1:05 | **Goal tab.** Career → "VLSI design engineer", Elementary, 8 h/week, 8 weeks, skills "C, basic electronics", interest "chip design". Language picker: show the 22 languages and the native/good/beta tiers. Tap *Take 5-question diagnostic*. | "I enter my goal, level and available time. Llama 3.2 1B, quantised to 4-bit and running through ExecuTorch, writes a diagnostic test for the foundation skills this goal needs." |
| 1:05–1:25 | Answer the 5 questions (get 2 wrong on purpose). Submit. | "This measures my real starting point, skill by skill." |
| 1:25–1:55 | **Path tab** while the roadmap **streams in live**. Zoom on the telemetry bar: **t/s ≥ 22, RAM < 1800 MB**. Then the skill-gap bars. | "The model compares my level with what the goal requires and builds a prerequisite-ordered roadmap. It streams at over 22 tokens per second, using under 1.8 GB of RAM." |
| 1:55–2:15 | Open Week 1 → *Generate daily plan* → 7-day tasks appear. Tick one task. Show "Free resources" (NPTEL, HDLBits). | "Each unit expands into daily micro-tasks sized to my study hours, with curated free resources. Links come from a verified catalogue, never invented by the model." |
| 2:15–2:55 | Tap a topic chip → **Quiz tab**. Answer badly (1 of 3). Submit → red *remediate*, diagnosis, "Added to your roadmap: ↺ …". Go back to Path: remedial topic visible, version v2. | "I score below 70 percent. Saarthi updates my mastery with Bayesian Knowledge Tracing, asks Llama to find the missing prerequisite, and inserts a remedial module into my plan. That is the adaptive loop, entirely offline." |
| 2:55–3:20 | **Mentor tab:** "Explain state machines simply". The answer streams; t/s visible. Switch language to हिन्दी (or ಕನ್ನಡ) and ask again. | "The mentor knows my current week and weak topics, and answers in Indian languages." |
| 3:20–3:50 | **Device tab:** Arm feature chips (NEON, DotProd, I8MM), *Run INT8 NEON micro-benchmark* (show the ×speed-up), *Run benchmark* → table with 3 runs, JSON reliability card. | "Under the hood: the XNNPACK delegate uses Arm NEON and dot-product instructions. Here are three benchmark runs, peak memory, and the share of model outputs that were valid JSON." |
| 3:50–4:00 | End card: GitHub URL, team name, college. | "Saarthi: personal, private, and built for Bharat." |

**Tips:** record at 1080p; do two takes of the roadmap generation and keep the faster one honest
(no speed-up edits on the telemetry shots); add captions for numbers; mention the phone model on the end card.
