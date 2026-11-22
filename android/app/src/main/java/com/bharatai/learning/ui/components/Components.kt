package com.bharatai.learning.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AirplanemodeActive
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bharatai.learning.R
import com.bharatai.learning.llm.EngineStatus
import com.bharatai.learning.llm.Telemetry
import com.bharatai.learning.ui.theme.Leaf
import com.bharatai.learning.ui.theme.Rose
import com.bharatai.learning.ui.theme.Saffron

const val TARGET_TPS = 22.0
const val TARGET_RAM_MB = 1800.0

/** Always-visible jury telemetry strip: t/s · peak RAM · TTFT · offline. */
@Composable
fun TelemetryBar(t: Telemetry, onClick: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            StatusDot(t.status)
            if (t.statusMessage.isNotEmpty()) {
                Text(t.statusMessage, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Saffron)
            }
            Metric(Icons.Outlined.Bolt, if (t.decodeTps > 0) "%.1f t/s".format(t.decodeTps) else "– t/s",
                if (t.decodeTps <= 0) null else if (t.decodeTps >= TARGET_TPS) Leaf else Rose)
            val ram = maxOf(t.peakMb, t.rssMb)
            Metric(Icons.Outlined.Memory, "%.0f MB".format(ram), if (ram < TARGET_RAM_MB) Leaf else Rose)
            Metric(Icons.Outlined.Timer, if (t.ttftMs > 0) "${t.ttftMs} ms" else "– ms", null)
            Metric(Icons.Outlined.AirplanemodeActive, stringResource(R.string.offline), Leaf)
        }
    }
}

@Composable
private fun StatusDot(s: EngineStatus) {
    val (c, label) = when (s) {
        EngineStatus.GENERATING -> Saffron to "GEN"
        EngineStatus.READY -> Leaf to "READY"
        EngineStatus.LOADING -> Saffron to "LOAD"
        EngineStatus.UNLOADED -> Color.Gray to "IDLE"
        EngineStatus.NO_MODEL -> Rose to "NO MODEL"
        EngineStatus.ERROR -> Rose to "ERROR"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(c))
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Metric(icon: ImageVector, text: String, tint: Color?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(14.dp), tint = tint ?: MaterialTheme.colorScheme.onPrimaryContainer)
        Spacer(Modifier.width(3.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun SectionCard(title: String? = null, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            if (title != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    action?.invoke()
                }
                Spacer(Modifier.height(10.dp))
            }
            content()
        }
    }
}

@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text, maxLines = 1, overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.labelSmall, color = color,
        modifier = modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.12f)).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** Horizontal bar: current level vs required level (skill-gap chart). */
@Composable
fun GapBar(label: String, current: Double, required: Double, priority: Int) {
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Pill(when (priority) { 1 -> "critical"; 2 -> "important"; else -> "nice to have" }, if (priority == 1) Rose else if (priority == 2) Saffron else Leaf)
        }
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            Box(Modifier.fillMaxWidth(required.toFloat().coerceIn(0f, 1f)).height(10.dp).clip(RoundedCornerShape(5.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)))
            Box(Modifier.fillMaxWidth(current.toFloat().coerceIn(0.02f, 1f)).height(10.dp).clip(RoundedCornerShape(5.dp))
                .background(MaterialTheme.colorScheme.primary))
        }
        Text("now ${(current * 100).toInt()}%  →  needed ${(required * 100).toInt()}%",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun BusyBanner(label: String?, onStop: () -> Unit) {
    if (label == null) return
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("$label · ${stringResource(R.string.generating)}", style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = onStop) { Text(stringResource(R.string.stop)) }
            }
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun EmptyState(text: String) {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
