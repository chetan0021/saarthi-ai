// Saarthi native layer (C++17, Android NDK).
//
// The LLM itself runs inside ExecuTorch's own JNI runtime (libexecutorch_jni.so from the
// executorch-android AAR, XNNPACK backend -> Arm NEON / dotprod / i8mm / KleidiAI kernels).
// This library adds the Arm-hardware side the jury wants to *see*:
//   * CPU feature probe (getauxval HWCAP/HWCAP2): NEON, dotprod, i8mm, SVE, BF16 ...
//   * big.LITTLE topology (per-core max frequency)
//   * process memory telemetry (VmRSS / VmHWM peak / anon / file-backed mmap)
//   * peak-RSS reset so each benchmark run measures its own peak
//   * a NEON INT8 dot-product micro-benchmark (the same primitive INT4/INT8 GEMM uses)

#include <jni.h>
#include <android/log.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/auxv.h>
#include <cstdio>
#include <cstring>
#include <cstdint>
#include <string>
#include <vector>
#include <chrono>

#if defined(__aarch64__)
#include <asm/hwcap.h>
#include <arm_neon.h>
#endif

#define LOG_TAG "SaarthiNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// Implemented in neon_dot.cpp (compiled with +dotprod; only called when HWCAP says so).
extern "C" int32_t saarthi_dot_i8_sdot(const int8_t* a, const int8_t* b, int n);

namespace {

long readStatusKb(const char* key) {
    FILE* f = fopen("/proc/self/status", "r");
    if (!f) return -1;
    char line[256];
    size_t klen = strlen(key);
    long value = -1;
    while (fgets(line, sizeof(line), f)) {
        if (strncmp(line, key, klen) == 0 && line[klen] == ':') {
            value = strtol(line + klen + 1, nullptr, 10);
            break;
        }
    }
    fclose(f);
    return value;
}

long readLongFile(const std::string& path) {
    FILE* f = fopen(path.c_str(), "r");
    if (!f) return -1;
    long v = -1;
    if (fscanf(f, "%ld", &v) != 1) v = -1;
    fclose(f);
    return v;
}

int32_t dotI8Neon(const int8_t* a, const int8_t* b, int n) {
#if defined(__aarch64__)
    int32x4_t acc = vdupq_n_s32(0);
    int i = 0;
    for (; i + 16 <= n; i += 16) {
        int8x16_t va = vld1q_s8(a + i);
        int8x16_t vb = vld1q_s8(b + i);
        int16x8_t lo = vmull_s8(vget_low_s8(va), vget_low_s8(vb));
        int16x8_t hi = vmull_high_s8(va, vb);
        acc = vpadalq_s16(acc, lo);
        acc = vpadalq_s16(acc, hi);
    }
    int32_t sum = vaddvq_s32(acc);
    for (; i < n; ++i) sum += int32_t(a[i]) * int32_t(b[i]);
    return sum;
#else
    int32_t sum = 0;
    for (int i = 0; i < n; ++i) sum += int32_t(a[i]) * int32_t(b[i]);
    return sum;
#endif
}

int32_t dotI8Scalar(const int8_t* a, const int8_t* b, int n) {
    int32_t sum = 0;
#if defined(__clang__)
#pragma clang loop vectorize(disable)
#endif
    for (int i = 0; i < n; ++i) sum += int32_t(a[i]) * int32_t(b[i]);
    return sum;
}

bool hasDotProd() {
#if defined(__aarch64__) && defined(HWCAP_ASIMDDP)
    return (getauxval(AT_HWCAP) & HWCAP_ASIMDDP) != 0;
#else
    return false;
#endif
}

}  // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_bharatai_learning_hw_NativeBridge_cpuFeaturesJson(JNIEnv* env, jclass) {
    unsigned long hw = getauxval(AT_HWCAP);
    unsigned long hw2 = getauxval(AT_HWCAP2);
    auto bit = [](unsigned long v, unsigned long mask) { return (v & mask) ? "true" : "false"; };
    std::string j = "{";
#if defined(__aarch64__)
    j += "\"arch\":\"arm64-v8a\"";
    j += std::string(",\"neon\":") + bit(hw, HWCAP_ASIMD);
    j += std::string(",\"fp16\":") + bit(hw, HWCAP_FPHP);
#ifdef HWCAP_ASIMDDP
    j += std::string(",\"dotprod\":") + bit(hw, HWCAP_ASIMDDP);
#endif
#ifdef HWCAP_SVE
    j += std::string(",\"sve\":") + bit(hw, HWCAP_SVE);
#endif
#ifdef HWCAP2_SVE2
    j += std::string(",\"sve2\":") + bit(hw2, HWCAP2_SVE2);
#endif
#ifdef HWCAP2_I8MM
    j += std::string(",\"i8mm\":") + bit(hw2, HWCAP2_I8MM);
#endif
#ifdef HWCAP2_BF16
    j += std::string(",\"bf16\":") + bit(hw2, HWCAP2_BF16);
#endif
#ifdef HWCAP2_SME
    j += std::string(",\"sme\":") + bit(hw2, HWCAP2_SME);
#endif
#else
    (void)hw; (void)hw2; (void)bit;
    j += "\"arch\":\"non-arm\",\"neon\":false";
#endif
    long ncpu = sysconf(_SC_NPROCESSORS_CONF);
    j += ",\"cores\":" + std::to_string(ncpu);
    j += ",\"max_khz\":[";
    long top = 0;
    std::vector<long> freqs;
    for (long c = 0; c < ncpu; ++c) {
        long f = readLongFile("/sys/devices/system/cpu/cpu" + std::to_string(c) + "/cpufreq/cpuinfo_max_freq");
        freqs.push_back(f);
        if (f > top) top = f;
        j += (c ? "," : "") + std::to_string(f);
    }
    j += "]";
    // "performance" cores = cores within 80% of the fastest core's max frequency
    int big = 0;
    for (long f : freqs) if (top > 0 && f >= top * 8 / 10) ++big;
    j += ",\"big_cores\":" + std::to_string(big);
    j += "}";
    return env->NewStringUTF(j.c_str());
}

// [VmRSS, VmHWM(peak), RssAnon, RssFile] in kB
JNIEXPORT jlongArray JNICALL
Java_com_bharatai_learning_hw_NativeBridge_memoryKb(JNIEnv* env, jclass) {
    jlong v[4] = {readStatusKb("VmRSS"), readStatusKb("VmHWM"), readStatusKb("RssAnon"), readStatusKb("RssFile")};
    jlongArray out = env->NewLongArray(4);
    env->SetLongArrayRegion(out, 0, 4, v);
    return out;
}

// Resets VmHWM to current RSS (writing "5" to clear_refs), so each run reports its own peak.
JNIEXPORT jboolean JNICALL
Java_com_bharatai_learning_hw_NativeBridge_resetPeakRss(JNIEnv*, jclass) {
    int fd = open("/proc/self/clear_refs", O_WRONLY);
    if (fd < 0) return JNI_FALSE;
    bool ok = write(fd, "5", 1) == 1;
    close(fd);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// Returns [scalar GOPS, NEON GOPS, SDOT GOPS (or 0 if unsupported)].
JNIEXPORT jdoubleArray JNICALL
Java_com_bharatai_learning_hw_NativeBridge_int8DotBench(JNIEnv* env, jclass, jint n, jint iters) {
    if (n < 64) n = 64;
    n &= ~63;
    std::vector<int8_t> a(n), b(n);
    for (int i = 0; i < n; ++i) { a[i] = int8_t((i * 37) % 255 - 127); b[i] = int8_t((i * 91) % 255 - 127); }
    using clk = std::chrono::steady_clock;
    volatile int32_t sink = 0;
    auto run = [&](int32_t (*fn)(const int8_t*, const int8_t*, int)) {
        sink += fn(a.data(), b.data(), n);  // warm-up
        auto t0 = clk::now();
        for (int it = 0; it < iters; ++it) sink += fn(a.data(), b.data(), n);
        double s = std::chrono::duration<double>(clk::now() - t0).count();
        return s > 0 ? (2.0 * n * iters) / s / 1e9 : 0.0;  // multiply + add per element
    };
    jdouble r[3];
    r[0] = run(dotI8Scalar);
    r[1] = run(dotI8Neon);
    r[2] = hasDotProd() ? run(saarthi_dot_i8_sdot) : 0.0;
    LOGI("int8 dot GOPS scalar=%.2f neon=%.2f sdot=%.2f (sink=%d)", r[0], r[1], r[2], int(sink));
    jdoubleArray out = env->NewDoubleArray(3);
    env->SetDoubleArrayRegion(out, 0, 3, r);
    return out;
}

}  // extern "C"
