package com.bharatai.learning.llm

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.bharatai.learning.core.JsonStreamWatcher
import com.bharatai.learning.core.JsonValue
import com.bharatai.learning.core.LenientJson
import com.bharatai.learning.core.Prompt
import com.bharatai.learning.hw.NativeBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.pytorch.executorch.extension.llm.LlmCallback
import org.pytorch.executorch.extension.llm.LlmModule
import java.util.concurrent.Executors

enum class EngineStatus { NO_MODEL, UNLOADED, LOADING, READY, GENERATING, ERROR }

data class Telemetry(
    val status: EngineStatus = EngineStatus.NO_MODEL,
    val modelLabel: String = "",
    val modelSizeMb: Double = 0.0,
    val decodeTps: Double = 0.0,     // tokens/s during generation (the jury's "22+ t/s" metric)
    val prefillTps: Double = 0.0,
    val ttftMs: Long = 0,            // time to first token
    val loadMs: Long = 0,
    val genTokens: Int = 0,
    val promptTokens: Int = 0,
    val rssMb: Double = 0.0,
    val peakMb: Double = 0.0,        // VmHWM since last reset = peak during the last generation
    val sessionPeakRssMb: Double = 0.0, // max VmHWM since process start, no reset
    val fileMb: Double = 0.0,        // mmap'd weights (file-backed, reclaimable)
    val anonMb: Double = 0.0,
    val thermal: String = "-",
    val backend: String = "ExecuTorch · XNNPACK",
    val error: String = "",
    val statusMessage: String = "",
)

data class GenResult(val text: String, val json: JsonValue?, val tokens: Int, val decodeTps: Double, val ttftMs: Long, val stoppedEarly: Boolean)

/**
 * Owns the single ExecuTorch LlmModule instance.
 *
 * "Cascading memory": the model is mmap-loaded on demand, used, and released after
 * [idleUnloadMs] without requests, so the app's resident footprint drops back to ~100 MB
 * between generations (and the OS can reclaim the file-backed pages under pressure).
 */
class LlamaEngine(private val ctx: Context) {
    private val thread = Executors.newSingleThreadExecutor { r ->
        Thread(r, "llama-runner").apply { priority = Thread.MAX_PRIORITY }
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var module: LlmModule? = null
    private var paths: ModelPaths? = null
    private var idleJob: Job? = null
    @Volatile private var stopRequested = false

    private var startedActivities = 0
    private var isForeground = true
    private val backgroundUnloadMs: Long = 5 * 60 * 1000L   // 5 minutes

    var idleUnloadMs: Long = 45_000
    var aggressiveMemory: Boolean = false   // unload right after every generation

    private val _t = MutableStateFlow(Telemetry())
    val telemetry: StateFlow<Telemetry> = _t.asStateFlow()

    init {
        (ctx.applicationContext as? Application)?.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivities++
                if (startedActivities == 1) {
                    isForeground = true
                    idleJob?.cancel()
                }
            }
            override fun onActivityStopped(activity: Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
                if (startedActivities == 0) {
                    isForeground = false
                    scheduleBackgroundUnload()
                }
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
        refreshModel()
        scope.launch { // ambient memory + thermal sampling for the telemetry bar
            while (isActive) {
                sampleMemory()
                delay(if (_t.value.status == EngineStatus.GENERATING) 250 else 1500)
            }
        }
    }

    fun refreshModel(): ModelPaths? {
        paths = ModelFiles.find(ctx)
        val p = paths
        _t.update {
            it.copy(
                status = if (p == null) EngineStatus.NO_MODEL else if (module != null) EngineStatus.READY else EngineStatus.UNLOADED,
                modelLabel = p?.label ?: "", modelSizeMb = p?.sizeMb ?: 0.0,
                backend = "ExecuTorch · XNNPACK · " + NativeBridge.cpu.accelSummary(),
                sessionPeakRssMb = maxOf(it.sessionPeakRssMb, NativeBridge.sessionPeakMb),
            )
        }
        return p
    }

    val hasModel get() = paths != null

    private fun sampleMemory() {
        val m = NativeBridge.memory()
        _t.update {
            it.copy(
                rssMb = m.rssMb,
                peakMb = if (it.status == EngineStatus.GENERATING) maxOf(it.peakMb, m.peakMb) else it.peakMb,
                sessionPeakRssMb = maxOf(it.sessionPeakRssMb, NativeBridge.sessionPeakMb, m.peakMb, m.rssMb),
                fileMb = m.fileMb,
                anonMb = m.anonMb,
                thermal = thermalStatus()
            )
        }
    }

    private fun thermalStatus(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return "-"
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        return when (pm.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "Cool"
            PowerManager.THERMAL_STATUS_LIGHT -> "Light"
            PowerManager.THERMAL_STATUS_MODERATE -> "Moderate"
            PowerManager.THERMAL_STATUS_SEVERE -> "Severe"
            else -> "Critical"
        }
    }

    private fun ensureLoaded(): LlmModule {
        module?.let { return it }
        val p = paths ?: refreshModel() ?: throw IllegalStateException("No .pte model found. See Device tab → Model setup.")
        _t.update { it.copy(status = EngineStatus.LOADING, error = "", statusMessage = "Preparing on-device model") }
        val t0 = SystemClock.elapsedRealtime()
        val m = LlmModule(p.pte.absolutePath, p.tokenizer.absolutePath, 0.3f)
        m.load()
        val ms = SystemClock.elapsedRealtime() - t0
        Log.i(TAG, "Loaded ${p.pte.name} (${"%.0f".format(p.sizeMb)} MB) in $ms ms")

        // Hidden warm-up generation of ~24 tokens with a short fixed prompt, not recorded in telemetry
        val warmPrompt = "<|begin_of_text|><|start_header_id|>user<|end_header_id|>\n\nHi<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n\n"
        var warmTokens = 0
        val warmCb = object : LlmCallback {
            override fun onResult(result: String) {
                if (result.isNotEmpty()) {
                    warmTokens++
                    if (warmTokens >= 24) m.stop()
                }
            }
            override fun onStats(stats: String) {}
        }
        runCatching {
            m.generate(warmPrompt, 64, warmCb, false, 0.0f, 0, 0)
        }
        runCatching { m.resetContext() }

        module = m
        _t.update { it.copy(status = EngineStatus.READY, loadMs = ms, statusMessage = "") }
        return m
    }

    fun stop() { stopRequested = true; module?.stop() }

    /**
     * Runs one generation. [onPartial] receives prefill + text so far (throttled ~12 Hz).
     * For JSON prompts generation stops as soon as the top-level object closes.
     */
    suspend fun generate(
        prompt: Prompt,
        temperature: Float,
        json: Boolean,
        maxNewTokens: Int = prompt.maxNewTokens,
        onPartial: (String) -> Unit = {},
    ): GenResult = withContext(thread) {
        idleJob?.cancel()
        stopRequested = false
        val m = try { ensureLoaded() } catch (e: Throwable) {
            _t.update { it.copy(status = if (paths == null) EngineStatus.NO_MODEL else EngineStatus.ERROR, error = e.message ?: "load failed") }
            throw e
        }
        runCatching { m.resetContext() } // each request is an independent prompt
        NativeBridge.resetPeak()
        _t.update { it.copy(status = EngineStatus.GENERATING, peakMb = 0.0, genTokens = 0) }

        val sb = StringBuilder(prompt.prefill)
        val watcher = JsonStreamWatcher().also { it.feed(prompt.prefill) }
        var tokens = 0
        var firstTokenAt = 0L
        var lastEmit = 0L
        var stoppedEarly = false
        var statsJson: JsonValue? = null
        val start = SystemClock.elapsedRealtime()
        // conservative budget: ~1 token per 3 UTF-8 bytes of prompt + the new-token budget, model context 2048
        val seqLen = (prompt.text.toByteArray().size / 3 + maxNewTokens).coerceAtMost(MAX_CONTEXT)

        val cb = object : LlmCallback {
            override fun onResult(result: String) {
                if (result.isEmpty()) return
                val now = SystemClock.elapsedRealtime()
                if (tokens == 0) {
                    firstTokenAt = now
                    _t.update { it.copy(ttftMs = now - start) }
                }
                tokens++
                sb.append(result)
                if (json && watcher.feed(result)) { stoppedEarly = true; m.stop() }
                if (tokens >= maxNewTokens || stopRequested) { stoppedEarly = true; m.stop() }
                if (now - lastEmit > 80) {
                    lastEmit = now
                    val dt = (now - firstTokenAt).coerceAtLeast(1)
                    _t.update { it.copy(genTokens = tokens, decodeTps = if (tokens > 1) (tokens - 1) * 1000.0 / dt else 0.0) }
                    onPartial(sb.toString())
                }
            }

            override fun onStats(stats: String) {
                statsJson = LenientJson.parse(stats)
            }
        }

        try {
            m.generate(prompt.text, seqLen, cb, false, temperature, 0, 0)
        } catch (e: Throwable) {
            if (!stoppedEarly) {
                _t.update { it.copy(status = EngineStatus.ERROR, error = e.message ?: "generation failed") }
                throw e
            }
        }
        val end = SystemClock.elapsedRealtime()
        onPartial(sb.toString())

        // Single decode t/s definition everywhere: generated tokens after first token / decode time
        val decode = if (tokens > 1 && firstTokenAt > 0) (tokens - 1) * 1000.0 / (end - firstTokenAt).coerceAtLeast(1) else 0.0
        val s = statsJson
        val promptTok = s?.int("prompt_tokens", 0) ?: (prompt.text.toByteArray().size / 3)
        val prefill = if (promptTok > 0 && firstTokenAt > start) promptTok * 1000.0 / (firstTokenAt - start)
            else (s?.num("prefill_token_per_sec", 0.0) ?: 0.0)
        val mem = NativeBridge.memory()
        val ttft = if (firstTokenAt > 0) firstTokenAt - start else 0L
        _t.update {
            it.copy(
                status = EngineStatus.READY,
                decodeTps = decode,
                prefillTps = prefill,
                genTokens = tokens,
                promptTokens = promptTok,
                ttftMs = ttft,
                peakMb = maxOf(it.peakMb, mem.peakMb),
                sessionPeakRssMb = maxOf(it.sessionPeakRssMb, NativeBridge.sessionPeakMb, mem.peakMb, mem.rssMb),
                rssMb = mem.rssMb
            )
        }
        scheduleUnload()
        val text = sb.toString()
        GenResult(text, if (json) LenientJson.parse(text) else null, tokens, decode, ttft, stoppedEarly)
    }

    private fun scheduleBackgroundUnload() {
        idleJob?.cancel()
        if (module == null) return
        idleJob = scope.launch {
            delay(backgroundUnloadMs)
            if (!isForeground) {
                unload()
            }
        }
    }

    private fun scheduleUnload() {
        idleJob?.cancel()
        if (aggressiveMemory) {
            idleJob = scope.launch {
                delay(500)
                unload()
            }
            return
        }
        if (isForeground) {
            // Disabled while app is in foreground
            return
        }
        scheduleBackgroundUnload()
    }

    suspend fun unload() = withContext(thread) {
        module?.let {
            runCatching { it.close() }
            module = null
            Log.i(TAG, "Model released (cascading memory)")
        }
        System.gc()
        _t.update { it.copy(status = if (paths == null) EngineStatus.NO_MODEL else EngineStatus.UNLOADED) }
    }

    companion object {
        private const val TAG = "LlamaEngine"
        const val MAX_CONTEXT = 2048
    }
}
