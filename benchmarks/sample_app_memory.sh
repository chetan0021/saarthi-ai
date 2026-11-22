#!/usr/bin/env bash
# Samples the Saarthi app's memory once per second while you use it (for the memory-profile
# chart in the report). Ctrl-C to stop. Output: results/app_memory.csv
set -euo pipefail
PKG=com.bharatai.learning
mkdir -p results; OUT=results/app_memory.csv
echo "t_s,total_pss_mb,total_rss_mb" > "$OUT"
T0=$(date +%s)
while true; do
  M=$(adb shell dumpsys meminfo $PKG | grep -E "^\s*TOTAL " | head -1)
  PSS=$(echo "$M" | awk '{print $2}'); RSS=$(echo "$M" | awk '{print $(NF-1)}')
  [ -n "$PSS" ] && echo "$(( $(date +%s) - T0 )),$(( PSS / 1024 )),$(( RSS / 1024 ))" | tee -a "$OUT"
  sleep 1
done
