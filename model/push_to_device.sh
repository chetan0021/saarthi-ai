#!/usr/bin/env bash
# Copies the model + tokenizer into the app's external files dir (no root needed).
# Install the APK once first so the package directory exists.
set -euo pipefail
WORK="${WORK:-$HOME/saarthi-work}"
PTE="${1:-$WORK/out/saarthi_llama3_2_1b_int4.pte}"
TOK="${2:-$WORK/out/tokenizer.model}"
PKG=com.bharatai.learning
DEST=/sdcard/Android/data/$PKG/files/models

adb shell mkdir -p "$DEST"
adb push "$PTE" "$DEST/"
adb push "$TOK" "$DEST/tokenizer.model"
# Also stage for the command-line runner used by benchmarks/bench_device.sh
adb shell mkdir -p /data/local/tmp/llama
adb push "$PTE" /data/local/tmp/llama/
adb push "$TOK" /data/local/tmp/llama/tokenizer.model
adb shell ls -l "$DEST"
echo "Done. Open Saarthi → Device tab → Rescan."
