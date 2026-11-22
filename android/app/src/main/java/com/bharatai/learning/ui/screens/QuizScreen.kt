package com.bharatai.learning.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bharatai.learning.R
import com.bharatai.learning.core.AdaptiveEngine
import com.bharatai.learning.core.Topic
import com.bharatai.learning.core.TopicMastery
import com.bharatai.learning.ui.AppViewModel
import com.bharatai.learning.ui.components.EmptyState
import com.bharatai.learning.ui.components.Pill
import com.bharatai.learning.ui.components.SectionCard
import com.bharatai.learning.ui.theme.Leaf
import com.bharatai.learning.ui.theme.Rose
import com.bharatai.learning.ui.theme.Saffron
import com.bharatai.learning.ui.theme.masteryColor
import java.text.DateFormat
import java.util.Date

@Composable
fun QuizScreen(vm: AppViewModel) {
    val quiz by vm.quiz.collectAsState()
    val q = quiz
    if (q == null) TopicPicker(vm) else ActiveQuiz(vm, q)
}

@Composable
private fun TopicPicker(vm: AppViewModel) {
    val roadmap by vm.roadmap.collectAsState()
    val masteries by vm.mastery.collectAsState()
    val attempts by vm.attempts.collectAsState()
    val busy by vm.busy.collectAsState()
    val topics = roadmap?.allTopics().orEmpty()
    if (topics.isEmpty()) { EmptyState(stringResource(R.string.no_roadmap)); return }
    val byId = masteries.associateBy { it.topicId }
    val due = AdaptiveEngine.dueForReview(masteries).mapNotNull { m -> topics.firstOrNull { it.id == m.topicId } }
    val weak = AdaptiveEngine.weakTopics(masteries).mapNotNull { m -> topics.firstOrNull { it.id == m.topicId } }.filter { it !in due }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
        if (due.isNotEmpty()) item {
            SectionCard(stringResource(R.string.due_review)) { due.forEach { TopicRow(it, byId[it.id], busy == null) { vm.startQuiz(it) } } }
        }
        if (weak.isNotEmpty()) item {
            SectionCard(stringResource(R.string.weak_topics)) { weak.forEach { TopicRow(it, byId[it.id], busy == null) { vm.startQuiz(it) } } }
        }
        item {
            SectionCard(stringResource(R.string.all_topics)) { topics.forEach { TopicRow(it, byId[it.id], busy == null) { vm.startQuiz(it) } } }
        }
        if (attempts.isNotEmpty()) item {
            SectionCard("History") {
                attempts.take(10).forEach { a ->
                    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(a.topicTitle, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Pill("${(a.score * 100).toInt()}%", masteryColor(a.score))
                        Spacer(Modifier.width(6.dp))
                        Text(DateFormat.getDateInstance(DateFormat.SHORT).format(Date(a.at)), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun TopicRow(t: Topic, m: TopicMastery?, enabled: Boolean, onStart: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(t.title + if (t.remedial) " ↺" else "", style = MaterialTheme.typography.bodyMedium)
            val pct = ((m?.mastery ?: 0.0) * 100).toInt()
            Text("${stringResource(R.string.mastery)} $pct% · ${m?.attempts ?: 0} answered", style = MaterialTheme.typography.labelSmall,
                color = m?.takeIf { it.attempts > 0 }?.let { masteryColor(it.mastery) } ?: MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedButton(onClick = onStart, enabled = enabled) { Text(stringResource(R.string.start_quiz)) }
    }
}

@Composable
private fun ActiveQuiz(vm: AppViewModel, s: AppViewModel.QuizState) {
    val busy by vm.busy.collectAsState()
    if (s.questions.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text("Llama is writing questions on \"${s.topic.title}\"… ${s.generated}/3", style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    val res = s.result
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
        item {
            Text(s.topic.title, style = MaterialTheme.typography.headlineSmall)
            Text("Adaptive micro-quiz · difficulty set from your mastery", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        itemsIndexed(s.questions) { qi, q ->
            SectionCard(title = "${qi + 1}. ${q.question}", action = { Pill(q.bloom.name.lowercase(), MaterialTheme.colorScheme.primary) }) {
                q.options.forEachIndexed { oi, opt ->
                    val selected = s.chosen.getOrNull(qi) == oi
                    val border = when {
                        res != null && oi == q.answer -> Leaf
                        res != null && selected -> Rose
                        selected -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.outline
                    }
                    Surface(
                        shape = RoundedCornerShape(12.dp), border = BorderStroke(if (selected || (res != null && oi == q.answer)) 2.dp else 1.dp, border),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable(enabled = res == null) { vm.choose(qi, oi) },
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${'A' + oi}", fontWeight = FontWeight.Bold, color = border)
                            Spacer(Modifier.width(10.dp))
                            Text(opt, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            if (res != null && oi == q.answer) Icon(Icons.Outlined.CheckCircle, null, tint = Leaf, modifier = Modifier.size(18.dp))
                            if (res != null && selected && oi != q.answer) Icon(Icons.Outlined.Cancel, null, tint = Rose, modifier = Modifier.size(18.dp))
                        }
                    }
                }
                if (res != null && q.explanation.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text("Why: ${q.explanation}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            if (res == null) {
                Button(onClick = { vm.submitQuiz() }, enabled = busy == null && s.chosen.none { it == null }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.submit))
                }
            } else {
                val o = res.outcome
                val color = when (o.decision) {
                    AdaptiveEngine.Decision.REMEDIATE -> Rose
                    AdaptiveEngine.Decision.CONTINUE -> Saffron
                    AdaptiveEngine.Decision.ACCELERATE -> Leaf
                }
                SectionCard("Score ${(o.score * 100).toInt()}% · ${stringResource(R.string.mastery)} ${(o.mastery.mastery * 100).toInt()}%") {
                    Pill(o.decision.name.lowercase(), color)
                    Spacer(Modifier.height(6.dp))
                    Text(o.message, style = MaterialTheme.typography.bodyMedium)
                    if (res.diagnosis.isNotBlank()) Text("Diagnosis: ${res.diagnosis}", style = MaterialTheme.typography.bodyMedium)
                    if (res.remedial.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text("Added to your roadmap:", style = MaterialTheme.typography.labelSmall)
                        res.remedial.forEach { Text("↺ ${it.title} (${it.hours}h)", style = MaterialTheme.typography.bodyMedium, color = color) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.closeQuiz() }) { Text("Done") }
                    Button(onClick = { vm.startQuiz(s.topic) }, enabled = busy == null) { Text("New questions") }
                }
            }
        }
    }
}
