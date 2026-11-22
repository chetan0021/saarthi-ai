#!/usr/bin/env bash
# Runs the reference runner N times on the phone and records decode t/s, prefill t/s,
# TTFT and peak RSS (VmHWM sampled from /proc). Output: results/runner_bench.csv
# Usage: bash bench_device.sh [runs=5] [threads=-1(auto)]
set -euo pipefail
RUNS="${1:-5}"; THREADS="${2:--1}"
D=/data/local/tmp/llama
PTE=$(adb shell "ls $D/*.pte" | head -1 | tr -d '\r')
mkdir -p results
OUT=results/runner_bench.csv
echo "run,decode_tps,prefill_tps,ttft_ms,gen_tokens,prompt_tokens,peak_rss_mb,cpu_temp_c" > "$OUT"
PROMPT='<|begin_of_text|><|start_header_id|>user<|end_header_id|>\n\nExplain how a finite state machine works, with one everyday example.<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n\n'
for i in $(seq 0 "$RUNS"); do
  LOG=$(adb shell "cd $D && (./llama_main --model_path=$PTE --tokenizer_path=$D/tokenizer.model \
      --prompt=\"$PROMPT\" --seq_len=192 --temperature=0 --cpu_threads=$THREADS > run.log 2>&1 &) ; \
      sleep 0.3; PID=\$(pidof llama_main); PEAK=0; \
      while [ -d /proc/\$PID ]; do H=\$(grep VmHWM /proc/\$PID/status 2>/dev/null | tr -s ' ' | cut -d' ' -f2); \
        [ -n \"\$H\" ] && [ \"\$H\" -gt \"\$PEAK\" ] && PEAK=\$H; sleep 0.2; done; \
      echo PEAK_KB=\$PEAK; cat /sys/class/thermal/thermal_zone0/temp 2>/dev/null | sed 's/^/TEMP=/'; cat run.log")
  [ "$i" -eq 0 ] && { echo "warm-up done"; continue; }
  echo "$LOG" > "results/run_$i.log"
  python3 - "$i" "$OUT" "results/run_$i.log" <<'PY'
import json, re, sys
run, out, logf = sys.argv[1], sys.argv[2], sys.argv[3]
log = open(logf, errors="ignore").read()
m = re.search(r"PEAK_KB=(\d+)", log); peak = int(m.group(1)) / 1024 if m else 0
t = re.search(r"TEMP=(\d+)", log); temp = int(t.group(1)) / 1000 if t else ""
m = re.search(r"PyTorchObserver (\{.*\})", log)
s = json.loads(m.group(1)) if m else {}
gen, pt = s.get("generated_tokens", 0), s.get("prompt_tokens", 0)
first, end, start, pe = (s.get(k, 0) for k in ("first_token_ms", "inference_end_ms", "inference_start_ms", "prompt_eval_end_ms"))
dec = s.get("decode_token_per_sec") or ((gen - 1) * 1000 / (end - first) if end > first else 0)
pre = s.get("prefill_token_per_sec") or (pt * 1000 / (pe - start) if pe > start else 0)
row = f"{run},{dec:.2f},{pre:.1f},{first - start},{gen},{pt},{peak:.0f},{temp}"
print(row); open(out, "a").write(row + "\n")
PY
done
echo "Saved $OUT"
