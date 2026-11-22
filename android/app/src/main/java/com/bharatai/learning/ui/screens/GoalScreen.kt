package com.bharatai.learning.ui.screens

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import com.bharatai.learning.R
import com.bharatai.learning.core.DiagnosticQuestion
import com.bharatai.learning.core.GoalType
import com.bharatai.learning.core.Languages
import com.bharatai.learning.core.LearnerProfile
import com.bharatai.learning.ui.AppViewModel
import com.bharatai.learning.ui.components.Pill
import com.bharatai.learning.ui.components.SectionCard
import com.bharatai.learning.ui.theme.Leaf
import com.bharatai.learning.ui.theme.Saffron
import kotlin.math.roundToInt

private val suggestions = mapOf(
    GoalType.CAREER to listOf("VLSI design engineer", "Embedded systems engineer", "Android developer", "Data analyst"),
    GoalType.EXAM to listOf("GATE ECE", "JEE Main Physics", "NEET Biology", "UPSC Prelims GS"),
    GoalType.CERTIFICATION to listOf("AWS Cloud Practitioner", "CCNA", "Google Data Analytics", "Arm Accredited Engineer"),
    GoalType.SKILL to listOf("Python for data science", "Verilog and RTL design", "Public speaking", "Excel for work"),
    GoalType.LANGUAGE to listOf("Spoken Kannada", "Spoken English", "Basic Japanese", "Hindi reading"),
    GoalType.HOBBY to listOf("Guitar basics", "Digital illustration", "Chess openings", "Carnatic music basics"),
    GoalType.LIFELONG to listOf("Personal finance", "Understand AI", "Indian history", "Healthy cooking"),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GoalScreen(vm: AppViewModel) {
    val saved by vm.profile.collectAsState()
    val diag by vm.diagnostic.collectAsState()
    val busy by vm.busy.collectAsState()
    val progress by vm.diagProgress.collectAsState()

    if (diag.isNotEmpty()) { DiagnosticView(diag) { vm.finishDiagnostic(it) }; return }

    var name by rememberSaveable(saved) { mutableStateOf(saved?.name ?: "") }
    var type by rememberSaveable(saved) { mutableStateOf(saved?.goalType ?: GoalType.CAREER) }
    var goal by rememberSaveable(saved) { mutableStateOf(saved?.goal ?: "") }
    var level by rememberSaveable(saved) { mutableFloatStateOf((saved?.level ?: 0).toFloat()) }
    var hours by rememberSaveable(saved) { mutableFloatStateOf((saved?.hoursPerWeek ?: 8).toFloat()) }
    var weeks by rememberSaveable(saved) { mutableFloatStateOf((saved?.weeks ?: 8).toFloat()) }
    var skills by rememberSaveable(saved) { mutableStateOf(saved?.knownSkills?.joinToString(", ") ?: "") }
    var interests by rememberSaveable(saved) { mutableStateOf(saved?.interests?.joinToString(", ") ?: "") }
    var lang by rememberSaveable(saved) { mutableStateOf(saved?.languageCode ?: "en") }
    var langMenu by remember { mutableStateOf(false) }

    fun build() = LearnerProfile(
        name = name.trim(), goalType = type, goal = goal.trim(), weeks = weeks.roundToInt(), hoursPerWeek = hours.roundToInt(),
        level = level.roundToInt(), knownSkills = skills.split(',').map { it.trim() }.filter { it.isNotEmpty() },
        interests = interests.split(',').map { it.trim() }.filter { it.isNotEmpty() }, languageCode = lang,
    )

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(stringResource(R.string.goal_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.goal_subtitle), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

        SectionCard {
            OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.your_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.goal_type), style = MaterialTheme.typography.labelSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                GoalType.entries.forEach { g -> FilterChip(selected = type == g, onClick = { type = g }, label = { Text(g.label) }) }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(goal, { goal = it }, label = { Text(stringResource(R.string.goal_hint)) }, modifier = Modifier.fillMaxWidth(), minLines = 2)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                suggestions[type].orEmpty().forEach { s -> AssistChip(onClick = { goal = s }, label = { Text(s) }) }
            }
        }

        SectionCard {
            val levels = listOf(R.string.level_0, R.string.level_1, R.string.level_2, R.string.level_3).map { stringResource(it) }
            LabeledSlider(stringResource(R.string.level), levels[level.roundToInt()], level, 0f..3f, 2) { level = it }
            LabeledSlider(stringResource(R.string.hours_week), "${hours.roundToInt()} h", hours, 1f..40f, 38) { hours = it }
            LabeledSlider(stringResource(R.string.duration_weeks), "${weeks.roundToInt()}", weeks, 2f..52f, 49) { weeks = it }
        }

        SectionCard {
            OutlinedTextField(skills, { skills = it }, label = { Text(stringResource(R.string.known_skills)) }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(interests, { interests = it }, label = { Text(stringResource(R.string.interests)) }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.language), style = MaterialTheme.typography.labelSmall)
            Box {
                val l = Languages.of(lang)
                OutlinedButton(onClick = { langMenu = true }) {
                    Text("${l.native} · ${l.english}")
                    Icon(Icons.Outlined.ArrowDropDown, null)
                }
                DropdownMenu(expanded = langMenu, onDismissRequest = { langMenu = false }) {
                    Languages.all.forEach { a ->
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("${a.native}  (${a.english})", modifier = Modifier.weight(1f))
                                    Pill(when (a.tier) { 0 -> "native"; 1 -> "good"; else -> "beta" }, if (a.tier == 0) Leaf else Saffron)
                                }
                            },
                            onClick = {
                                lang = a.code; langMenu = false
                                // UI strings follow the learner's language where a translation exists
                                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(a.code))
                            },
                        )
                    }
                }
            }
        }

        val ok = goal.isNotBlank() && busy == null
        Button(onClick = { vm.startDiagnostic(build()) }, enabled = ok, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.take_diagnostic))
        }
        OutlinedButton(onClick = { vm.generateRoadmap(build()) }, enabled = ok, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.skip_generate))
        }
        if (busy == "Diagnostic test") Text("Writing diagnostic questions… $progress/5", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun LabeledSlider(label: String, value: String, v: Float, range: ClosedFloatingPointRange<Float>, steps: Int, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    }
    Slider(value = v, onValueChange = onChange, valueRange = range, steps = steps)
}

@Composable
private fun DiagnosticView(qs: List<DiagnosticQuestion>, onSubmit: (List<Int>) -> Unit) {
    val chosen = remember(qs) { mutableStateListOf<Int>().apply { repeat(qs.size) { add(-1) } } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.diagnostic_title), style = MaterialTheme.typography.headlineSmall)
        Text("Answer honestly – this measures your real starting point for each foundation skill.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        qs.forEachIndexed { i, dq ->
            SectionCard(title = "${i + 1}. ${dq.q.question}", action = { Pill(dq.skill, MaterialTheme.colorScheme.primary) }) {
                dq.q.options.forEachIndexed { o, opt ->
                    Row(Modifier.fillMaxWidth().clickable { chosen[i] = o }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = chosen[i] == o, onClick = { chosen[i] = o })
                        Text(opt, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        Button(onClick = { onSubmit(chosen.toList()) }, enabled = chosen.none { it < 0 }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.submit))
        }
        Spacer(Modifier.height(24.dp))
    }
}
