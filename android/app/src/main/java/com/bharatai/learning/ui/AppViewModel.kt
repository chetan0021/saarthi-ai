package com.bharatai.learning.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bharatai.learning.core.AdaptiveEngine
import com.bharatai.learning.core.DayTask
import com.bharatai.learning.core.DiagnosticQuestion
import com.bharatai.learning.core.JsonValue
import com.bharatai.learning.core.LearnerProfile
import com.bharatai.learning.core.PlanChangeParser
import com.bharatai.learning.core.ProposedPlanChange
import com.bharatai.learning.core.QuizQuestion
import com.bharatai.learning.core.Roadmap
import com.bharatai.learning.core.RoadmapDiff
import com.bharatai.learning.core.Topic
import com.bharatai.learning.core.TopicMastery
import com.bharatai.learning.data.LearningRepository
import com.bharatai.learning.hw.NativeBridge
import com.bharatai.learning.llm.LlamaEngine
import com.bharatai.learning.llm.ModelFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val engine = LlamaEngine(app)
    val repo = LearningRepository(app, engine)

    private fun <T> state(f: Flow<T>, init: T): StateFlow<T> = f.stateIn(viewModelScope, SharingStarted.Eagerly, init)

    val profile = state(repo.profile, null)
    val roadmap = state(repo.roadmap, null)
    val mastery = state(repo.mastery, emptyList())
    val attempts = state(repo.attempts, emptyList())
    val chat = state(repo.chat, emptyList())
    val perf = state(repo.perfRuns, emptyList())
    val telemetry = engine.telemetry

    @OptIn(ExperimentalCoroutinesApi::class)
    val l10n: StateFlow<Map<String, String>> = state(repo.profile.flatMapLatest { repo.l10n(it?.languageCode ?: "en") }, emptyMap())

    /** Label of the running on-device task (null = idle). One LLM job at a time. */
    val busy = MutableStateFlow<String?>(null)
    val liveRoadmap = MutableStateFlow<Roadmap?>(null)
    val liveTasks = MutableStateFlow<Pair<Int, List<DayTask>>?>(null)
    val chatStreaming = MutableStateFlow("")
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val selectedTab = MutableStateFlow(0)

    // Diagnostic (tab 1)
    val diagnostic = MutableStateFlow<List<DiagnosticQuestion>>(emptyList())
    val diagProgress = MutableStateFlow(0)
    private var pendingProfile: LearnerProfile? = null

    // Quiz (tab 3)
    data class QuizState(
        val topic: Topic,
        val questions: List<QuizQuestion> = emptyList(),
        val chosen: List<Int?> = emptyList(),
        val result: LearningRepository.QuizResult? = null,
        val generated: Int = 0,
    )
    val quiz = MutableStateFlow<QuizState?>(null)
    val weekReview = MutableStateFlow<Pair<Int, JsonValue>?>(null)

    // Mentor (tab 4) & Plan adjustment
    val pendingPlanChange = MutableStateFlow<ProposedPlanChange?>(null)
    val roadmapDiff = MutableStateFlow<RoadmapDiff?>(null)

    // Device (tab 5)
    val neonBench = MutableStateFlow<DoubleArray?>(null)
    val importProgress = MutableStateFlow<Float?>(null)

    private fun job(label: String, block: suspend () -> Unit) {
        if (busy.value != null) { messages.tryEmit("Please wait – ${busy.value} is running"); return }
        busy.value = label
        viewModelScope.launch {
            try { block() } catch (e: Throwable) { messages.tryEmit(e.message ?: "Something went wrong") }
            finally { busy.value = null }
        }
    }

    fun stopGeneration() = engine.stop()

    // ---- Tab 1 ---------------------------------------------------------------------------
    fun startDiagnostic(p: LearnerProfile) = job("Diagnostic test") {
        pendingProfile = p
        repo.saveProfile(p)
        diagProgress.value = 0
        val qs = repo.diagnostic(p) { diagProgress.value = it }
        if (qs.isEmpty()) {
            messages.tryEmit("Diagnostic unavailable – building plan from your self-assessment")
            buildRoadmap(p)
        } else {
            diagnostic.value = qs
        }
    }

    fun finishDiagnostic(chosen: List<Int>) {
        val p = pendingProfile ?: profile.value ?: return
        val scores = AdaptiveEngine.diagnosticScores(diagnostic.value, chosen)
        diagnostic.value = emptyList()
        generateRoadmap(p.copy(diagnosticScore = scores))
    }

    fun generateRoadmap(p: LearnerProfile) = job("Skill-gap analysis & roadmap") { buildRoadmap(p) }

    private suspend fun buildRoadmap(p: LearnerProfile) {
        liveRoadmap.value = null
        selectedTab.value = 1
        val r = repo.generateRoadmap(p) { liveRoadmap.value = it }
        liveRoadmap.value = null
        if (r.source == "template") messages.tryEmit("Used offline template plan (model unavailable or output invalid)")
        if (p.languageCode != "en") {
            busy.value = "Translating plan"
            repo.localise(p, r)
        }
    }

    fun feedback(f: AdaptiveEngine.Feedback) = job("Adapting your plan") {
        if (f == AdaptiveEngine.Feedback.JUST_RIGHT) { messages.tryEmit("Great – keeping the current pace"); return@job }
        liveRoadmap.value = null
        repo.applyFeedback(f) { liveRoadmap.value = it }
        liveRoadmap.value = null
        messages.tryEmit("Roadmap re-planned from your feedback")
    }

    // ---- Tab 2 ---------------------------------------------------------------------------
    fun expandWeek(index: Int) = job("Planning week $index") {
        liveTasks.value = index to emptyList()
        repo.expandWeek(index) { liveTasks.value = index to it }
        liveTasks.value = null
    }

    fun toggleTask(week: Int, task: Int) = viewModelScope.launch { repo.toggleTask(week, task) }

    fun reviewWeek(index: Int) = job("Weekly review") {
        val r = repo.weeklyReview(index)
        if (r != null) {
            weekReview.value = index to r
        } else {
            messages.tryEmit("Review needs the on-device model")
        }
    }

    // ---- Tab 3 ---------------------------------------------------------------------------
    fun startQuiz(topic: Topic, count: Int = 3) {
        selectedTab.value = 2
        if (busy.value != null) { messages.tryEmit("Please wait – ${busy.value} is running"); return }
        quiz.value = QuizState(topic)
        job("Generating quiz") {
            val qs = repo.generateQuiz(topic, count) { n -> quiz.value = quiz.value?.copy(generated = n) }
            if (qs.isEmpty()) {
                quiz.value = null
                messages.tryEmit("Could not create a quiz (model missing and no saved questions)")
            } else {
                quiz.value = QuizState(topic, qs, List(qs.size) { null })
            }
        }
    }

    fun choose(q: Int, option: Int) {
        val s = quiz.value ?: return
        if (s.result != null) return
        quiz.value = s.copy(chosen = s.chosen.toMutableList().also { it[q] = option })
    }

    fun submitQuiz() {
        val s = quiz.value ?: return
        job("Updating mastery") {
            val res = repo.submitQuiz(s.topic, s.questions, s.chosen.map { it ?: -1 })
            quiz.value = s.copy(result = res)
        }
    }

    fun closeQuiz() { quiz.value = null }

    fun topicById(id: String): Topic? = roadmap.value?.allTopics()?.firstOrNull { it.id == id }

    fun masteryOf(id: String): TopicMastery? = mastery.value.firstOrNull { it.topicId == id }

    // ---- Tab 4 ---------------------------------------------------------------------------
    fun onPlanQuickAction(action: String) {
        val p = profile.value ?: return
        val change = PlanChangeParser.parse(action, p) ?: return
        pendingPlanChange.value = change
    }

    fun cancelPlanChange() {
        pendingPlanChange.value = null
    }

    fun clearRoadmapDiff() {
        roadmapDiff.value = null
    }

    fun applyPlanChange() {
        val change = pendingPlanChange.value ?: return
        pendingPlanChange.value = null
        job("Adapting your plan") {
            val oldRoadmap = repo.currentRoadmap()
            buildRoadmap(change.updatedProfile)
            val newRoadmap = repo.currentRoadmap()
            if (oldRoadmap != null && newRoadmap != null) {
                roadmapDiff.value = RoadmapDiff.compute(oldRoadmap, newRoadmap)
            }
            messages.tryEmit("Plan updated (v${newRoadmap?.version ?: 1})")
        }
    }

    fun send(message: String) {
        if (message.isBlank()) return
        val p = profile.value
        if (p != null) {
            val planChange = PlanChangeParser.parse(message, p)
            if (planChange != null) {
                pendingPlanChange.value = planChange
                return
            }
        }
        val r = roadmap.value
        val week = r?.weeks?.firstOrNull { w -> w.tasks.any { !it.done } || !w.expanded }
        val weak = mastery.value.filter { it.attempts > 0 && it.mastery < 0.7 }.take(3).joinToString { it.title }
        val ctx = buildString {
            if (week != null) append("Week ${week.index} '${week.title}', topics: ${week.topics.joinToString { it.title }}. ")
            if (weak.isNotEmpty()) append("Weak topics: $weak.")
        }
        job("Mentor is thinking") {
            chatStreaming.value = ""
            repo.chat(message.trim(), ctx) { chatStreaming.value = it.substringBefore("<|eot_id|>") }
            chatStreaming.value = ""
        }
    }

    fun clearChat() = viewModelScope.launch { repo.clearChat() }

    // ---- Tab 5 ---------------------------------------------------------------------------
    fun runBenchmark(runs: Int = 3) = job("Benchmark") {
        val rows = repo.benchmark(runs) { busy.value = "Benchmark run $it/$runs" }
        val med = rows.map { it.decodeTps }.sorted().let { it[it.size / 2] }
        messages.tryEmit("Median decode: ${"%.1f".format(med)} tokens/s")
    }

    fun runNeonBench() = job("NEON micro-benchmark") {
        neonBench.value = withContext(Dispatchers.Default) { NativeBridge.neonBench() }
    }

    fun exportCsv() = viewModelScope.launch {
        val csv = repo.perfCsv()
        val f = File(getApplication<Application>().getExternalFilesDir(null), "saarthi_perf.csv")
        withContext(Dispatchers.IO) { f.writeText(csv) }
        messages.tryEmit("Saved ${f.absolutePath}")
    }

    fun importModelFile(uri: Uri) = job("Importing file") {
        importProgress.value = 0f
        val f = withContext(Dispatchers.IO) { ModelFiles.importFile(getApplication<Application>(), uri) { importProgress.value = it } }
        importProgress.value = null
        engine.refreshModel()
        messages.tryEmit("Imported ${f.name}")
    }

    fun rescanModel() {
        val p = engine.refreshModel()
        messages.tryEmit(if (p == null) "No model found" else "Found ${p.pte.name}")
    }

    fun setAggressiveMemory(on: Boolean) { engine.aggressiveMemory = on }

    fun unloadModel() = viewModelScope.launch { engine.unload() }

    fun resetLearning() = viewModelScope.launch { repo.resetLearning(); quiz.value = null }

}
