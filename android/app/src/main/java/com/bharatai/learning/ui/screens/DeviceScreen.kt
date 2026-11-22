package com.bharatai.learning.ui.screens

import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bharatai.learning.hw.NativeBridge
import com.bharatai.learning.llm.ModelFiles
import com.bharatai.learning.ui.AppViewModel
import com.bharatai.learning.ui.components.Pill
import com.bharatai.learning.ui.components.SectionCard
import com.bharatai.learning.ui.components.TARGET_RAM_MB
import com.bharatai.learning.ui.components.TARGET_TPS
import com.bharatai.learning.ui.theme.Leaf
import com.bharatai.learning.ui.theme.Rose

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DeviceScreen(vm: AppViewModel) {
    val t by vm.telemetry.collectAsState()
    val perf by vm.perf.collectAsState()
    val busy by vm.busy.collectAsState()
    val neon by vm.neonBench.collectAsState()
    val importing by vm.importProgress.collectAsState()
    val ctx = LocalContext.current
    val cpu = NativeBridge.cpu
    val airplane = Settings.Global.getInt(ctx.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
    var aggressive by remember { mutableStateOf(vm.engine.aggressiveMemory) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.importModelFile(it) } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Hardware telemetry", style = MaterialTheme.typography.headlineSmall)

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = 2) {
            val ram = maxOf(t.peakMb, t.rssMb)
            Tile("Decode speed", if (t.decodeTps > 0) "%.1f".format(t.decodeTps) else "–", "tokens/s · target ≥ 22",
                if (t.decodeTps <= 0) null else t.decodeTps >= TARGET_TPS, Modifier.weight(1f))
            Tile("Peak RAM", "%.0f".format(ram), "MB · session: %.0f".format(t.sessionPeakRssMb), ram < TARGET_RAM_MB, Modifier.weight(1f))
            Tile("First token", if (t.ttftMs > 0) "${t.ttftMs}" else "–", "ms (TTFT)", null, Modifier.weight(1f))
            Tile("Prefill", if (t.prefillTps > 0) "%.0f".format(t.prefillTps) else "–", "tokens/s", null, Modifier.weight(1f))
            Tile("Model load", if (t.loadMs > 0) "${t.loadMs}" else "–", "ms (mmap)", null, Modifier.weight(1f))
            Tile("Thermal", t.thermal, "PowerManager status", t.thermal in setOf("Cool", "Light", "-"), Modifier.weight(1f))
        }

        SectionCard("Connectivity") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Airplane mode", Modifier.weight(1f)); Pill(if (airplane) "ON" else "off", if (airplane) Leaf else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("This app declares no INTERNET permission – every token is generated on this phone. Learner data stays in local SQLite.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        SectionCard("Arm SoC") {
            KV("ABI", cpu.arch)
            KV("Cores", "${cpu.cores} (${cpu.bigCores} performance)")
            if (cpu.maxKhz.isNotEmpty()) KV("Max freq", cpu.maxKhz.distinct().sortedDescending().joinToString(" / ") { "%.2f GHz".format(it / 1e6) })
            KV("Backend", t.backend)
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("NEON" to cpu.neon, "FP16" to cpu.fp16, "DotProd" to cpu.dotprod, "I8MM" to cpu.i8mm, "BF16" to cpu.bf16,
                    "SVE" to cpu.sve, "SVE2" to cpu.sve2).forEach { (n, on) -> Pill((if (on) "✓ " else "✗ ") + n, if (on) Leaf else Color.Gray) }
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = { vm.runNeonBench() }, enabled = busy == null) { Text("Run INT8 NEON micro-benchmark") }
            neon?.let { r ->
                KV("Scalar", "%.2f GOPS".format(r[0]))
                KV("NEON (smull/sadalp)", "%.2f GOPS  ×%.1f".format(r[1], if (r[0] > 0) r[1] / r[0] else 0.0))
                KV("SDOT (Armv8.2 dotprod)", if (r[2] > 0) "%.2f GOPS  ×%.1f".format(r[2], if (r[0] > 0) r[2] / r[0] else 0.0) else "not supported")
            }
        }

        val js by vm.repo.jsonStats.collectAsState()
        SectionCard("Structured JSON reliability (this session)") {
            KV("LLM JSON calls", "${js.total}")
            KV("Valid as generated", "${js.strict}")
            KV("Valid after auto-repair", "${js.repaired}")
            KV("Fell back to template", "${js.failed}")
            if (js.total > 0) KV("Usable output", "%.0f%%".format(100.0 * (js.strict + js.repaired) / js.total))
        }

        SectionCard("Memory (cascading load)") {
            KV("Resident now", "%.0f MB".format(t.rssMb))
            KV("  weights (mmap, file-backed)", "%.0f MB".format(t.fileMb))
            KV("  activations + KV cache (anon)", "%.0f MB".format(t.anonMb))
            KV("Per-run peak RSS", "%.0f MB".format(t.peakMb))
            KV("Session peak RSS (no reset)", "%.0f MB".format(t.sessionPeakRssMb))
            KV("Model status", if (t.statusMessage.isNotEmpty()) "${t.status.name} (${t.statusMessage})" else t.status.name)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Release model right after each answer", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(checked = aggressive, onCheckedChange = { aggressive = it; vm.setAggressiveMemory(it) })
            }
            OutlinedButton(onClick = { vm.unloadModel() }, enabled = busy == null) { Text("Unload model now") }
        }

        SectionCard("Benchmark (jury protocol)") {
            Text("1 warm-up + 3 runs × 128 new tokens, greedy decoding, fixed prompt. Median is reported.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.runBenchmark(3) }, enabled = busy == null && vm.engine.hasModel) { Text("Run benchmark") }
                OutlinedButton(onClick = { vm.exportCsv() }, enabled = perf.isNotEmpty()) { Text("Export CSV") }
            }
            if (perf.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Row { listOf("t/s", "prefill", "TTFT", "peak MB", "thermal").forEach { Text(it, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall) } }
                perf.take(8).forEach { p ->
                    Row {
                        Cell("%.1f".format(p.decodeTps), Modifier.weight(1f), if (p.decodeTps >= TARGET_TPS) Leaf else Rose)
                        Cell("%.0f".format(p.prefillTps), Modifier.weight(1f))
                        Cell("${p.ttftMs}", Modifier.weight(1f))
                        Cell("%.0f".format(p.peakRssMb), Modifier.weight(1f), if (p.peakRssMb < TARGET_RAM_MB) Leaf else Rose)
                        Cell(p.thermal, Modifier.weight(1f))
                    }
                }
            }
        }

        SectionCard("Model setup") {
            if (vm.engine.hasModel) {
                KV("Model", t.modelLabel)
                KV("Size", "%.0f MB".format(t.modelSizeMb))
            } else {
                Text("No model yet. Either push with adb (see README):", style = MaterialTheme.typography.bodyMedium)
                Text(ModelFiles.dirs(ctx).getOrNull(1)?.absolutePath ?: "", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
                Text("…or import the .pte and tokenizer.model files below.", style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = busy == null) { Text("Import file") }
                OutlinedButton(onClick = { vm.rescanModel() }) { Text("Rescan") }
            }
            importing?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) }
            if (t.error.isNotBlank()) Text(t.error, color = Rose, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = { vm.resetLearning() }, enabled = busy == null) { Text("Reset learning data") }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Tile(label: String, value: String, unit: String, pass: Boolean?, modifier: Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace,
                color = when (pass) { true -> Leaf; false -> Rose; null -> MaterialTheme.colorScheme.onSurface })
            Text(unit, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun KV(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(k, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(v, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Cell(v: String, modifier: Modifier, color: Color? = null) {
    Text(v, modifier, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, color = color ?: MaterialTheme.colorScheme.onSurface)
}
