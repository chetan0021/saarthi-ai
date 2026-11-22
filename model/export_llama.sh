#!/usr/bin/env bash
# Downloads Llama 3.2 1B Instruct (Meta original checkpoint format) and exports the INT4 .pte.
# Prerequisite: accept the Llama 3.2 licence on https://huggingface.co/meta-llama/Llama-3.2-1B-Instruct
# and run `huggingface-cli login` once.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="${WORK:-$HOME/saarthi-work}"
MODEL_ID="${MODEL_ID:-meta-llama/Llama-3.2-1B-Instruct}"
CKPT="$WORK/checkpoints/$(basename "$MODEL_ID")"
OUT="$WORK/out"
mkdir -p "$CKPT" "$OUT"

if [ ! -f "$CKPT/original/consolidated.00.pth" ]; then
  python3 -c "from huggingface_hub import snapshot_download; snapshot_download(repo_id='$MODEL_ID', allow_patterns=['original/*'], local_dir='$CKPT')"
fi

cd "$WORK/executorch"
python -m extension.llm.export.export_llm \
  --config "$HERE/configs/saarthi_llama3_2_1b_int4.yaml" \
  +base.checkpoint="$CKPT/original/consolidated.00.pth" \
  +base.params="$CKPT/original/params.json" \
  +export.output_dir="$OUT"

cp "$CKPT/original/tokenizer.model" "$OUT/tokenizer.model"
ls -lh "$OUT"
echo
echo "Exported: $OUT/saarthi_llama3_2_1b_int4.pte  (+ tokenizer.model)"
echo "Next: bash $HERE/push_to_device.sh"
