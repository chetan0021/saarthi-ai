#!/usr/bin/env bash
# Cross-compiles ExecuTorch's reference llama_main runner for arm64 Android (independent
# check of the app's numbers). Needs Android NDK r27+/r28 (ANDROID_NDK env var).
set -euo pipefail
WORK="${WORK:-$HOME/saarthi-work}"
: "${ANDROID_NDK:?set ANDROID_NDK to your NDK path, e.g. ~/Android/Sdk/ndk/28.0.13004108}"
cd "$WORK/executorch"
COMMON=(-DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK/build/cmake/android.toolchain.cmake" -DANDROID_ABI=arm64-v8a
        -DANDROID_PLATFORM=android-28 -DCMAKE_INSTALL_PREFIX=cmake-out-android -DCMAKE_BUILD_TYPE=Release
        -DPYTHON_EXECUTABLE=python -DEXECUTORCH_BUILD_XNNPACK=ON -DEXECUTORCH_BUILD_KERNELS_OPTIMIZED=ON
        -DEXECUTORCH_BUILD_KERNELS_QUANTIZED=ON -DEXECUTORCH_BUILD_KERNELS_LLM=ON)
cmake "${COMMON[@]}" -DEXECUTORCH_BUILD_EXTENSION_DATA_LOADER=ON -DEXECUTORCH_BUILD_EXTENSION_FLAT_TENSOR=ON \
  -DEXECUTORCH_BUILD_EXTENSION_MODULE=ON -DEXECUTORCH_BUILD_EXTENSION_TENSOR=ON \
  -DEXECUTORCH_BUILD_EXTENSION_NAMED_DATA_MAP=ON -DEXECUTORCH_BUILD_EXTENSION_LLM=ON \
  -DEXECUTORCH_BUILD_EXTENSION_LLM_RUNNER=ON -DEXECUTORCH_ENABLE_LOGGING=1 -Bcmake-out-android .
cmake --build cmake-out-android -j"$(nproc)" --target install --config Release
cmake "${COMMON[@]}" -DSUPPORT_REGEX_LOOKAHEAD=ON -Bcmake-out-android/examples/models/llama examples/models/llama
cmake --build cmake-out-android/examples/models/llama -j"$(nproc)" --config Release
adb push cmake-out-android/examples/models/llama/llama_main /data/local/tmp/llama/
echo "llama_main pushed to /data/local/tmp/llama/"
