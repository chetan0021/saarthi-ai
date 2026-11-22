# Phase 1 checklist (deadline: 23 November 2026)

| By | Task | Done |
|---|---|---|
| 11 Nov | Run `model/setup_executorch.sh`, get Llama 3.2 access on Hugging Face | ☐ |
| 12 Nov | Export the `.pte` (`model/export_llama.sh`), note the file size | ☐ |
| 12 Nov | Build the release APK in Android Studio, install, push model, complete one full loop in airplane mode | ☐ |
| 13 Nov | Device tab benchmark ×3 sets, reference-runner benchmark (`benchmarks/`), memory sampling, `plot_results.py` | ☐ |
| 14 Nov | If below 22 t/s: release build? battery saver off? phone cool? Try thread counts with `bench_device.sh 5 4` vs `… 5 6`, or test on a phone with a faster SoC | ☐ |
| 15–16 Nov | Pedagogy validation: 10 goals × faculty/peer ratings, check every quiz answer key (report §4.5) | ☐ |
| 17 Nov | Test Hindi + one South Indian language end to end, and screenshot every tab | ☐ |
| 18 Nov | Record the demo video (`docs/DEMO_VIDEO_SCRIPT.md`) | ☐ |
| 19–20 Nov | Fill every [MEASURE] in `docs/TECHNICAL_REPORT.md`, add charts and screenshots, export to PDF | ☐ |
| 21 Nov | Push to a **public GitHub repo** (no `.pte`/weights), attach the APK as a GitHub Release asset | ☐ |
| 22 Nov | Submit the form: repo link, APK link, video link (YouTube unlisted or Drive), report PDF | ☐ |

Jury Q&A prep (likely questions):
* Why 1B and not 3B? → RAM budget and bandwidth-bound decode (report §3.1).
* How do you guarantee valid JSON? → 5-layer pipeline + measured reliability (report §5).
* How is it adaptive, concretely? → BKT + <70 % remediation + spaced repetition, shown live in the demo.
* How good are regional languages on a 1B model? → honest tiers, dual-pass, Phase 2 Indic LoRA.
* Where are Arm NEON / NPU used? → XNNPACK NEON/dotprod/i8mm kernels, own NEON/SDOT micro-benchmark, QNN NPU backend in Phase 2.
