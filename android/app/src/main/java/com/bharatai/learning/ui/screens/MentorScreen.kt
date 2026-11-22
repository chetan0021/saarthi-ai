package com.bharatai.learning.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.bharatai.learning.R
import com.bharatai.learning.core.PlanChangeParser
import com.bharatai.learning.core.ProposedPlanChange
import com.bharatai.learning.ui.AppViewModel

@Composable
fun MentorScreen(vm: AppViewModel) {
    val chat by vm.chat.collectAsState()
    val streaming by vm.chatStreaming.collectAsState()
    val busy by vm.busy.collectAsState()
    val roadmap by vm.roadmap.collectAsState()
    val pendingPlanChange by vm.pendingPlanChange.collectAsState()
    var input by rememberSaveable { mutableStateOf("") }
    val list = rememberLazyListState()
    val total = chat.size + if (streaming.isNotEmpty()) 1 else 0
    LaunchedEffect(total, streaming.length / 40) { if (total > 0) list.animateScrollToItem(total - 1) }

    val topic = roadmap?.weeks?.firstOrNull { w -> !w.expanded || w.tasks.any { !it.done } }?.topics?.firstOrNull()?.title
    val starters = listOfNotNull(
        topic?.let { "Explain $it simply" },
        topic?.let { "Give me a real-life example of $it" },
        "What should I study today?",
        "Make me a 10-minute revision plan",
    )

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Saarthi mentor · Llama 3.2 on this phone", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
            IconButton(onClick = { vm.clearChat() }, enabled = busy == null) { Icon(Icons.Outlined.DeleteSweep, "clear") }
        }
        LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(chat, key = { it.id }) { m -> Bubble(m.text, m.role == "user", if (m.role != "user" && m.tps > 0) "%.1f t/s".format(m.tps) else null) }
            if (streaming.isNotEmpty()) item { Bubble(streaming + " ▌", false, null) }
        }

        // Confirmation card when a plan change is detected
        pendingPlanChange?.let { change ->
            PlanConfirmationCard(
                change = change,
                onApply = { vm.applyPlanChange() },
                onCancel = { vm.cancelPlanChange() },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }

        if (chat.isEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            starters.forEach { s -> AssistChip(onClick = { vm.send(s) }, label = { Text(s) }, enabled = busy == null) }
        }

        // Quick-action chips for plan adjustments
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            PlanChangeParser.QUICK_ACTION_CHIPS.forEach { label ->
                AssistChip(
                    onClick = { vm.onPlanQuickAction(label) },
                    label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                    enabled = busy == null
                )
            }
        }

        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.ask_mentor)) }, maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (busy == null) { vm.send(input); input = "" } }),
            )
            FilledIconButton(onClick = { vm.send(input); input = "" }, enabled = busy == null && input.isNotBlank(), modifier = Modifier.padding(start = 8.dp)) {
                Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.send))
            }
        }
    }
}

@Composable
private fun PlanConfirmationCard(
    change: ProposedPlanChange,
    onApply: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val before = change.originalProfile
    val after = change.updatedProfile

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Adjust learning plan?", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Weeks", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${before.weeks} → ${after.weeks} weeks", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Hours/week", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${before.hoursPerWeek} → ${after.hoursPerWeek} hrs", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Level", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${before.levelLabel()} (${before.level}) → ${after.levelLabel()} (${after.level})", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.padding(end = 8.dp)) {
                    Text("Cancel")
                }
                Button(onClick = onApply) {
                    Text("Apply")
                }
            }
        }
    }
}

@Composable
private fun Bubble(text: String, mine: Boolean, meta: String?) {
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(18.dp))
                .background(if (mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                .padding(12.dp),
        ) {
            Text(text, color = if (mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium)
            if (meta != null) Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
