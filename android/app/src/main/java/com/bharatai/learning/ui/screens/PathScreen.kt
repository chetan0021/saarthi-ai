package com.bharatai.learning.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Quiz
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bharatai.learning.R
import com.bharatai.learning.core.AdaptiveEngine
import com.bharatai.learning.core.DayTask
import com.bharatai.learning.core.Roadmap
import com.bharatai.learning.core.TaskType
import com.bharatai.learning.core.Week
import com.bharatai.learning.ui.AppViewModel
import com.bharatai.learning.ui.components.EmptyState
import com.bharatai.learning.ui.components.GapBar
import com.bharatai.learning.ui.components.Pill
import com.bharatai.learning.ui.components.SectionCard
import com.bharatai.learning.ui.theme.Leaf
import com.bharatai.learning.ui.theme.Rose
import com.bharatai.learning.ui.theme.Saffron
import com.bharatai.learning.ui.theme.masteryColor

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PathScreen(vm: AppViewModel) {
    val saved by vm.roadmap.collectAsState()
    val live by vm.liveRoadmap.collectAsState()
    val l10n by vm.l10n.collectAsState()
    val masteries by vm.mastery.collectAsState()
    val review by vm.weekReview.collectAsState()
    val diff by vm.roadmapDiff.collectAsState()
    val busy by vm.busy.collectAsState()
    val r: Roadmap = live ?: saved ?: run {
        EmptyState(stringResource(R.string.no_roadmap)); return
    }
    fun tr(s: String) = l10n[s]?.takeIf { it.isNotBlank() } ?: s
    val streaming = live != null
    val masteryMap = masteries.associateBy { it.topicId }
    var open by rememberSaveable { mutableStateOf(setOf(1)) }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
        item {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.your_roadmap), style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                    Pill(if (r.source == "llm") "Llama 3.2 · on-device" else "offline template", if (r.source == "llm") Leaf else Saffron)
                    Spacer(Modifier.width(6.dp))
                    Pill("v${r.version}", MaterialTheme.colorScheme.primary)
                }
                Text(r.goal.ifEmpty { "…" }, style = MaterialTheme.typography.titleLarge)
                if (r.summary.isNotBlank()) Text(tr(r.summary), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                val prog = AdaptiveEngine.overallProgress(r, masteries)
                LinearProgressIndicator(progress = { prog }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape))
                Text("${(prog * 100).toInt()}% complete", style = MaterialTheme.typography.labelSmall)
                if (!streaming) {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.how_is_pace), style = MaterialTheme.typography.labelSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(AdaptiveEngine.Feedback.TOO_EASY to R.string.too_easy, AdaptiveEngine.Feedback.JUST_RIGHT to R.string.just_right,
                            AdaptiveEngine.Feedback.TOO_HARD to R.string.too_hard, AdaptiveEngine.Feedback.NEED_MORE_TIME to R.string.more_time)
                            .forEach { (f, s) -> SuggestionChip(onClick = { vm.feedback(f) }, enabled = busy == null, label = { Text(stringResource(s)) }) }
                    }
                }
            }
        }
        diff?.let { d ->
            item {
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Plan updated: v${d.oldVersion} → v${d.newVersion}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        IconButton(onClick = { vm.clearRoadmapDiff() }) {
                            Icon(Icons.Outlined.ExpandLess, contentDescription = "Dismiss")
                        }
                    }
                    if (d.addedUnits.isNotEmpty()) {
                        Text("Units added (${d.addedUnits.size}):", style = MaterialTheme.typography.labelSmall, color = Leaf, fontWeight = FontWeight.Bold)
                        d.addedUnits.forEach { Text("  + $it", style = MaterialTheme.typography.bodySmall) }
                    }
                    if (d.removedUnits.isNotEmpty()) {
                        Text("Units removed (${d.removedUnits.size}):", style = MaterialTheme.typography.labelSmall, color = Rose, fontWeight = FontWeight.Bold)
                        d.removedUnits.forEach { Text("  – $it", style = MaterialTheme.typography.bodySmall) }
                    }
                    if (d.changedUnits.isNotEmpty()) {
                        Text("Changed titles (${d.changedUnits.size}):", style = MaterialTheme.typography.labelSmall, color = Saffron, fontWeight = FontWeight.Bold)
                        d.changedUnits.forEach { Text("  • $it", style = MaterialTheme.typography.bodySmall) }
                    }
                    if (d.addedUnits.isEmpty() && d.removedUnits.isEmpty() && d.changedUnits.isEmpty()) {
                        Text("Duration and pace updated. Unit structure retained.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (r.gaps.isNotEmpty()) item {
            SectionCard(stringResource(R.string.skill_gaps)) {
                r.gaps.sortedBy { it.priority }.forEach { g -> GapBar(tr(g.skill), g.current, g.required, g.priority) }
            }
        }
        items(r.weeks, key = { it.index }) { w ->
            WeekCard(
                vm, w, tr = ::tr, expanded = w.index in open, streaming = streaming,
                masteryOf = { id -> masteryMap[id]?.takeIf { it.attempts > 0 }?.mastery },
                onToggle = { open = if (w.index in open) open - w.index else open + w.index },
            )
        }
        if (streaming) item { Text("Llama is still writing your plan…", style = MaterialTheme.typography.bodyMedium) }
        item { Spacer(Modifier.height(24.dp)) }
    }

    review?.let { (idx, j) ->
        AlertDialog(
            onDismissRequest = { vm.weekReview.value = null },
            confirmButton = { TextButton(onClick = { vm.weekReview.value = null }) { Text("OK") } },
            title = { Text("${stringResource(R.string.weekly_review)} · ${stringResource(R.string.week)} $idx") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(j.str("summary"))
                    j.strList("strengths").takeIf { it.isNotEmpty() }?.let { Text("Strengths: " + it.joinToString(", "), color = Leaf) }
                    j.strList("focus").takeIf { it.isNotEmpty() }?.let { Text("Focus next: " + it.joinToString(", "), color = Saffron) }
                    j.str("next_step").takeIf { it.isNotEmpty() }?.let { Text("Next step: $it", fontWeight = FontWeight.SemiBold) }
                }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WeekCard(
    vm: AppViewModel, w: Week, tr: (String) -> String, expanded: Boolean, streaming: Boolean,
    masteryOf: (String) -> Double?, onToggle: () -> Unit,
) {
    val liveTasks by vm.liveTasks.collectAsState()
    val busy by vm.busy.collectAsState()
    val uri = LocalUriHandler.current
    val profile by vm.profile.collectAsState()

    SectionCard {
        Row(Modifier.fillMaxWidth().clickable(onClick = onToggle), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                Text("${w.index}", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(tr(w.title), style = MaterialTheme.typography.titleMedium)
                if (w.objective.isNotBlank()) Text(tr(w.objective), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (w.expanded) Text("${(w.progress * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = Leaf)
            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
        }
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            w.topics.forEach { t ->
                val m = masteryOf(t.id)
                AssistChip(
                    onClick = { if (!streaming) vm.startQuiz(t) },
                    label = { Text(tr(t.title) + (if (t.remedial) " ↺" else "") + (m?.let { "  ${(it * 100).toInt()}%" } ?: "")) },
                    leadingIcon = { Box(Modifier.size(8.dp).clip(CircleShape).background(m?.let { masteryColor(it) } ?: MaterialTheme.colorScheme.outline)) },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.EmojiEvents, null, Modifier.size(16.dp), tint = Saffron)
            Spacer(Modifier.width(4.dp))
            Text("${stringResource(R.string.milestone)}: ${tr(w.milestone)}", style = MaterialTheme.typography.bodyMedium)
        }

        AnimatedVisibility(expanded && !streaming) {
            Column {
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                val streamingTasks = liveTasks?.takeIf { it.first == w.index }?.second
                val tasks = streamingTasks ?: w.tasks
                if (tasks.isEmpty()) {
                    Button(onClick = { vm.expandWeek(w.index) }, enabled = busy == null, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.plan_week))
                    }
                } else {
                    tasks.groupBy { it.day }.toSortedMap().forEach { (day, list) ->
                        Text("Day $day", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 6.dp))
                        list.forEach { t -> TaskRow(t) { if (streamingTasks == null) vm.toggleTask(w.index, w.tasks.indexOf(t)) } }
                    }
                }
                val res = w.topics.flatMap { t -> vm.repo.catalog.recommend(t.title, t.skill, profile?.goal ?: "", 2) }.distinctBy { it.url }.take(4)
                if (res.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.resources), style = MaterialTheme.typography.labelSmall)
                    res.forEach { rsc ->
                        Row(Modifier.fillMaxWidth().clickable { runCatching { uri.openUri(rsc.url) } }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Link, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(6.dp))
                            Text("${rsc.title} · ${rsc.provider}", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { w.topics.minByOrNull { masteryOf(it.id) ?: 0.0 }?.let { vm.startQuiz(it) } }, enabled = busy == null) {
                        Icon(Icons.Outlined.Quiz, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.quiz_me))
                    }
                    OutlinedButton(onClick = { vm.reviewWeek(w.index) }, enabled = busy == null) {
                        Text(stringResource(R.string.weekly_review))
                    }
                    if (w.expanded) IconButton(onClick = { vm.expandWeek(w.index) }, enabled = busy == null) { Icon(Icons.Outlined.Replay, "re-plan") }
                }
            }
        }
    }
}

@Composable
private fun TaskRow(t: DayTask, onToggle: () -> Unit) {
    val icon: ImageVector = when (t.type) {
        TaskType.LEARN -> Icons.AutoMirrored.Outlined.MenuBook
        TaskType.PRACTICE -> Icons.Outlined.FitnessCenter
        TaskType.QUIZ -> Icons.Outlined.Quiz
        TaskType.PROJECT -> Icons.Outlined.Build
        TaskType.REVIEW -> Icons.Outlined.Replay
    }
    Row(Modifier.fillMaxWidth().clickable(onClick = onToggle), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = t.done, onCheckedChange = { onToggle() })
        Icon(icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
        Text(t.title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text("${t.minutes}m", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
