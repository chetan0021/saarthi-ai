// Compiled with -march=armv8.2-a+dotprod (see CMakeLists.txt).
// Only executed when getauxval(AT_HWCAP) reports HWCAP_ASIMDDP, so it is safe on older cores.
#include <cstdint>
#if defined(__aarch64__)
#include <arm_neon.h>
#endif

extern "C" int32_t saarthi_dot_i8_sdot(const int8_t* a, const int8_t* b, int n) {
#if defined(__aarch64__) && defined(__ARM_FEATURE_DOTPROD)
    int32x4_t acc0 = vdupq_n_s32(0), acc1 = vdupq_n_s32(0);
    int i = 0;
    for (; i + 32 <= n; i += 32) {
        acc0 = vdotq_s32(acc0, vld1q_s8(a + i), vld1q_s8(b + i));          // SDOT: 16 MACs / instr
        acc1 = vdotq_s32(acc1, vld1q_s8(a + i + 16), vld1q_s8(b + i + 16));
    }
    int32_t sum = vaddvq_s32(vaddq_s32(acc0, acc1));
    for (; i < n; ++i) sum += int32_t(a[i]) * int32_t(b[i]);
    return sum;
#else
    int32_t sum = 0;
    for (int i = 0; i < n; ++i) sum += int32_t(a[i]) * int32_t(b[i]);
    return sum;
#endif
}
