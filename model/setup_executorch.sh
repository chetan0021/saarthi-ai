#!/usr/bin/env bash
# One-time host setup (Linux / macOS / WSL2). Installs ExecuTorch at the SAME version as the
# Android runtime AAR used by the app (android/gradle/libs.versions.toml -> executorch).
# A .pte must be exported with the same ExecuTorch release that runs it.
set -euo pipefail
ET_VERSION="${ET_VERSION:-v1.4.0}"
WORK="${WORK:-$HOME/saarthi-work}"

mkdir -p "$WORK" && cd "$WORK"
if [ ! -d executorch ]; then
  git clone --branch "$ET_VERSION" --depth 1 https://github.com/pytorch/executorch.git
  (cd executorch && git submodule sync && git submodule update --init --recursive --depth 1)
fi

python3 -m venv .venv
source .venv/bin/activate
python -m pip install --upgrade pip

cd executorch
./install_executorch.sh
./examples/models/llama/install_requirements.sh
pip install "huggingface_hub[cli]"

echo
echo "ExecuTorch $ET_VERSION ready in $WORK/executorch"
echo "Next: source $WORK/.venv/bin/activate && bash $(dirname "$0")/export_llama.sh"
