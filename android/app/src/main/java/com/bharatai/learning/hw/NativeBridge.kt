package com.bharatai.learning.hw

import android.util.Log
import com.bharatai.learning.core.JsonValue
import com.bharatai.learning.core.LenientJson

/** Kotlin side of libsaarthi_native.so (src/main/cpp). Every call degrades gracefully. */
object NativeBridge {
    private val loaded: Boolean = try {
        System.loadLibrary("saarthi_native"); true
    } catch (t: Throwable) {
        Log.w("NativeBridge", "native lib not loaded: ${t.message}"); false
    }

    @JvmStatic private external fun cpuFeaturesJson(): String
    @JvmStatic private external fun memoryKb(): LongArray
    @JvmStatic private external fun resetPeakRss(): Boolean
    @JvmStatic private external fun int8DotBench(n: Int, iters: Int): DoubleArray

    data class CpuInfo(
        val arch: String, val neon: Boolean, val dotprod: Boolean, val i8mm: Boolean, val sve: Boolean,
        val sve2: Boolean, val bf16: Boolean, val fp16: Boolean, val cores: Int, val bigCores: Int, val maxKhz: List<Long>,
    ) {
        fun accelSummary(): String = buildList {
            if (neon) add("NEON")
            if (fp16) add("FP16")
            if (dotprod) add("DotProd")
            if (i8mm) add("I8MM")
            if (bf16) add("BF16")
            if (sve2) add("SVE2") else if (sve) add("SVE")
        }.joinToString(" · ").ifEmpty { "scalar" }
    }

    data class Memory(val rssMb: Double, val peakMb: Double, val anonMb: Double, val fileMb: Double)

    val cpu: CpuInfo by lazy {
        val j = if (loaded) runCatching { LenientJson.parse(cpuFeaturesJson()) }.getOrNull() else null
        fun b(k: String) = (j?.get(k) as? JsonValue.Bool)?.value ?: false
        CpuInfo(
            arch = j?.str("arch") ?: System.getProperty("os.arch") ?: "?",
            neon = b("neon"), dotprod = b("dotprod"), i8mm = b("i8mm"), sve = b("sve"), sve2 = b("sve2"),
            bf16 = b("bf16"), fp16 = b("fp16"),
            cores = j?.int("cores", Runtime.getRuntime().availableProcessors()) ?: Runtime.getRuntime().availableProcessors(),
            bigCores = j?.int("big_cores", 0) ?: 0,
            maxKhz = j?.arr("max_khz")?.mapNotNull { (it as? JsonValue.Num)?.value?.toLong() } ?: emptyList(),
        )
    }

    @Volatile private var _sessionPeakMb: Double = 0.0
    val sessionPeakMb: Double get() = _sessionPeakMb

    fun memory(): Memory {
        if (!loaded) {
            val rt = Runtime.getRuntime()
            val used = (rt.totalMemory() - rt.freeMemory()) / 1048576.0
            _sessionPeakMb = maxOf(_sessionPeakMb, used)
            return Memory(used, used, used, 0.0)
        }
        val kb = memoryKb()
        val rss = kb[0] / 1024.0
        val peak = kb[1] / 1024.0
        _sessionPeakMb = maxOf(_sessionPeakMb, peak, rss)
        return Memory(rss, peak, kb[2] / 1024.0, kb[3] / 1024.0)
    }

    fun resetPeak(): Boolean {
        memory()
        return loaded && runCatching { resetPeakRss() }.getOrDefault(false)
    }

    /** [scalar, NEON, SDOT] INT8 GOPS on the calling thread. */
    fun neonBench(): DoubleArray = if (loaded) int8DotBench(4096, 20000) else doubleArrayOf(0.0, 0.0, 0.0)
}
