package com.bharatai.learning.data

import android.content.Context
import android.util.Log
import com.bharatai.learning.core.AdaptiveEngine
import com.bharatai.learning.core.DayTask
import com.bharatai.learning.core.DiagnosticQuestion
import com.bharatai.learning.core.FallbackPlanner
import com.bharatai.learning.core.JsonValue
import com.bharatai.learning.core.Languages
import com.bharatai.learning.core.LearnerProfile
import com.bharatai.learning.core.LenientJson
import com.bharatai.learning.core.Prompt
import com.bharatai.learning.core.PromptBuilder
import com.bharatai.learning.core.QuizQuestion
import com.bharatai.learning.core.ResourceCatalog
import com.bharatai.learning.core.Roadmap
import com.bharatai.learning.core.Topic
import com.bharatai.learning.core.TopicMastery
import com.bharatai.learning.llm.GenResult
import com.bharatai.learning.llm.LlamaEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * Orchestrates the closed adaptive loop:
 *   goal intake -> diagnostic -> gap analysis + roadmap -> weekly tasks -> micro-quiz
 *   -> BKT mastery update -> (score < 70%) remediation pass -> roadmap updated -> ...
 * Every LLM call is: structured prompt -> stream -> lenient parse -> schema validation
 * -> one retry -> deterministic fallback. Nothing here can leave the UI without data.
 */
class LearningRepository(private val ctx: Context, val engine: LlamaEngine) {
    private val dao = AppDatabase.get(ctx).dao()
    val catalog: ResourceCatalog by lazy {
        ResourceCatalog(ctx.assets.open("resources.json").bufferedReader().use { it.readText() })
    }

    val profile: Flow<LearnerProfile?> = dao.docFlow(K_PROFILE).map { d -> d?.let { LenientJson.parse(it.json) }?.let { LearnerProfile.fromJson(it) } }
    val roadmap: Flow<Roadmap?> = dao.docFlow(K_ROADMAP).map { d -> d?.let { LenientJson.parse(it.json) }?.let { Roadmap.fromJson(it) } }
    val mastery: Flow<List<TopicMastery>> = dao.masteryFlow().map { l -> l.map { it.toDomain() } }
    val attempts = dao.attemptsFlow()
    val chat = dao.chatFlow()
    val perfRuns = dao.perfFlow()
    fun l10n(code: String): Flow<Map<String, String>> = dao.docFlow("l10n:$code").map { d ->
        (d?.let { LenientJson.parse(it.json) } as? JsonValue.Obj)?.map?.mapValues { (it.value as? JsonValue.Str)?.value ?: "" } ?: emptyMap()
    }

    suspend fun currentProfile(): LearnerProfile? = dao.doc(K_PROFILE)?.let { LenientJson.parse(it.json) }?.let { LearnerProfile.fromJson(it) }
    suspend fun currentRoadmap(): Roadmap? = dao.doc(K_ROADMAP)?.let { LenientJson.parse(it.json) }?.let { Roadmap.fromJson(it) }

    suspend fun saveProfile(p: LearnerProfile) = dao.putDoc(DocEntity(K_PROFILE, p.toJson().toJson()))
    private suspend fun saveRoadmap(r: Roadmap) = dao.putDoc(DocEntity(K_ROADMAP, r.toJson().toJson()))

    private fun budget(p: Prompt, lang: String): Int =
        if (lang == "en") p.maxNewTokens else (p.maxNewTokens * 2.2).toInt().coerceAtMost(1400) // Indic scripts need more tokens

    /** Structured-output reliability counters (shown in the Device tab, quoted in the report). */
    data class JsonStats(val total: Int = 0, val strict: Int = 0, val repaired: Int = 0, val failed: Int = 0)
    val jsonStats = MutableStateFlow(JsonStats())

    private suspend fun gen(p: Prompt, lang: String, temp: Float, onPartial: (String) -> Unit = {}): GenResult {
        val r = engine.generate(p, temperature = temp, json = true, maxNewTokens = budget(p, lang), onPartial = onPartial)
        val strictOk = runCatching { org.json.JSONObject(r.text.trim().substringBeforeLast('}') + "}") }.isSuccess
        jsonStats.update {
            it.copy(total = it.total + 1,
                strict = it.strict + if (strictOk) 1 else 0,
                repaired = it.repaired + if (!strictOk && r.json != null) 1 else 0,
                failed = it.failed + if (r.json == null) 1 else 0)
        }
        return r
    }

    // ---- Tab 1: diagnostic ---------------------------------------------------------------
    suspend fun diagnostic(p: LearnerProfile, onPartial: (Int) -> Unit): List<DiagnosticQuestion> {
        if (!engine.hasModel) return emptyList()
        repeat(2) { attempt ->
            val r = runCatching {
                gen(PromptBuilder.diagnostic(p), p.languageCode, 0.2f + attempt * 0.2f) { txt ->
                    onPartial(LenientJson.parse(txt)?.arr("questions")?.size ?: 0)
                }
            }.getOrNull() ?: return emptyList()
            val qs = r.json?.arr("questions").orEmpty().mapNotNull { j ->
                QuizQuestion.fromJson(j, "diag", j.str("skill"))?.let { DiagnosticQuestion(j.str("skill").ifEmpty { "General" }, it) }
            }
            if (qs.size >= 3) return qs.take(5)
        }
        return emptyList()
    }

    // ---- Tab 2: gap analysis + roadmap ----------------------------------------------------
    suspend fun generateRoadmap(p: LearnerProfile, onPartial: (Roadmap?) -> Unit): Roadmap {
        saveProfile(p)
        val weak = dao.allMastery().filter { it.attempts > 0 && it.mastery < AdaptiveEngine.REMEDIATE_BELOW }.map { it.title }
        var result: Roadmap? = null
        if (engine.hasModel) {
            for (attempt in 0 until 2) {
                val r = runCatching {
                    // Pass 1 (reasoning) always in English: best accuracy for a 1B model.
                    gen(PromptBuilder.roadmap(p.copy(languageCode = "en"), weak), "en", 0.3f + 0.2f * attempt) { txt ->
                        LenientJson.parse(txt)?.let { onPartial(Roadmap.fromJson(it, "llm")) }
                    }
                }.onFailure { Log.w(TAG, "roadmap gen failed", it) }.getOrNull() ?: break
                val rm = r.json?.let { Roadmap.fromJson(it, "llm") }
                if (rm != null && rm.isValid()) { result = rm; break }
            }
        }
        val prev = currentRoadmap()
        val plan = (result ?: FallbackPlanner.roadmap(p)).copy(goal = p.goal, version = (prev?.version ?: 0) + 1)
        saveRoadmap(plan)
        plan.allTopics().forEach { t -> if (dao.mastery(t.id) == null) dao.putMastery(TopicMastery(t.id, t.title, mastery = initialMastery(p, t)).toEntity()) }
        onPartial(plan)
        return plan
    }

    private fun initialMastery(p: LearnerProfile, t: Topic): Double {
        val diag = p.diagnosticScore.entries.firstOrNull { t.skill.contains(it.key, true) || it.key.contains(t.skill, true) }?.value
        return diag?.let { 0.2 + 0.5 * it } ?: (0.15 + 0.1 * p.level)
    }

    /** Dual-pass localisation: pass 2 translates the short display strings of the roadmap. */
    suspend fun localise(p: LearnerProfile, r: Roadmap) {
        if (p.languageCode == "en" || !engine.hasModel) return
        val existing = dao.doc("l10n:${p.languageCode}")?.let { LenientJson.parse(it.json) as? JsonValue.Obj }?.map ?: linkedMapOf()
        val lines = (listOf(r.summary) + r.weeks.flatMap { w -> listOf(w.title, w.objective, w.milestone) + w.topics.map { it.title } } +
            r.gaps.map { it.skill }).filter { it.isNotBlank() && it !in existing }.distinct()
        val map = LinkedHashMap(existing)
        for (chunk in lines.chunked(10)) {
            val res = runCatching { gen(PromptBuilder.translate(p.languageCode, chunk), p.languageCode, 0.1f) }.getOrNull() ?: break
            val out = res.json?.strList("t").orEmpty()
            chunk.forEachIndexed { i, src -> out.getOrNull(i)?.takeIf { it.isNotBlank() }?.let { map[src] = JsonValue.Str(it) } }
            dao.putDoc(DocEntity("l10n:${p.languageCode}", JsonValue.Obj(map).toJson()))
        }
    }

    suspend fun expandWeek(weekIndex: Int, onPartial: (List<DayTask>) -> Unit): Roadmap? {
        val p = currentProfile() ?: return null
        val r = currentRoadmap() ?: return null
        val w = r.weeks.firstOrNull { it.index == weekIndex } ?: return r
        val weak = dao.allMastery().filter { it.attempts > 0 && it.mastery < 0.7 }.map { it.title }.take(3)
        var tasks: List<DayTask> = emptyList()
        if (engine.hasModel) {
            val res = runCatching {
                gen(PromptBuilder.weekDetail(p, w, weak), p.languageCode, 0.3f) { txt ->
                    onPartial(LenientJson.parse(txt)?.arr("tasks").orEmpty().mapNotNull { DayTask.fromJson(it, w.topics) })
                }
            }.getOrNull()
            tasks = res?.json?.arr("tasks").orEmpty().mapNotNull { DayTask.fromJson(it, w.topics) }
        }
        if (tasks.size < 4) tasks = FallbackPlanner.weekTasks(p, w)
        val updated = r.copy(weeks = r.weeks.map { if (it.index == weekIndex) it.copy(tasks = tasks.sortedBy { t -> t.day }) else it })
        saveRoadmap(updated)
        return updated
    }

    suspend fun toggleTask(weekIndex: Int, taskIndex: Int) {
        val r = currentRoadmap() ?: return
        saveRoadmap(r.copy(weeks = r.weeks.map { w ->
            if (w.index != weekIndex) w else w.copy(tasks = w.tasks.mapIndexed { i, t -> if (i == taskIndex) t.copy(done = !t.done) else t })
        }))
    }

    // ---- Tab 3: adaptive micro-quiz -------------------------------------------------------
    suspend fun generateQuiz(topic: Topic, count: Int, onPartial: (Int) -> Unit): List<QuizQuestion> {
        val p = currentProfile() ?: return emptyList()
        val m = dao.mastery(topic.id)?.toDomain() ?: TopicMastery(topic.id, topic.title)
        val past = dao.attemptsFor(topic.id)
        val mistakes = past.take(2).flatMap { a ->
            val qs = QuizQuestion.listFrom(LenientJson.parse(a.questionsJson), topic.id, topic.title)
            val chosen = a.chosenCsv.split(',').mapNotNull { it.toIntOrNull() }
            qs.filterIndexed { i, q -> chosen.getOrNull(i) != q.answer }.map { it.question }
        }.take(3)
        if (engine.hasModel) {
            repeat(2) { attempt ->
                val res = runCatching {
                    gen(PromptBuilder.quiz(p, topic, m.mastery, count, mistakes), p.languageCode, 0.4f + 0.2f * attempt) { txt ->
                        onPartial(QuizQuestion.listFrom(LenientJson.parse(txt), topic.id, topic.title).size)
                    }
                }.getOrNull()
                val qs = QuizQuestion.listFrom(res?.json, topic.id, topic.title)
                if (qs.size >= minOf(2, count)) return qs.take(count)
            }
        }
        // offline fallback: reuse the stored question bank for this topic
        return past.flatMap { QuizQuestion.listFrom(LenientJson.parse(it.questionsJson), topic.id, topic.title) }
            .distinctBy { it.question }.shuffled().take(count)
    }

    data class QuizResult(val outcome: AdaptiveEngine.QuizOutcome, val remedial: List<Topic>, val diagnosis: String)

    suspend fun submitQuiz(topic: Topic, qs: List<QuizQuestion>, chosen: List<Int>): QuizResult {
        val before = dao.mastery(topic.id)?.toDomain() ?: TopicMastery(topic.id, topic.title)
        val answers = qs.mapIndexed { i, q -> chosen.getOrNull(i) == q.answer }
        val outcome = AdaptiveEngine.applyQuiz(before, answers)
        dao.putMastery(outcome.mastery.toEntity())
        dao.addAttempt(QuizAttemptEntity(topicId = topic.id, topicTitle = topic.title, score = outcome.score,
            decision = outcome.decision.name, questionsJson = JsonValue.Obj(linkedMapOf("questions" to JsonValue.Arr(qs.map { it.toJson() }))).toJson(),
            chosenCsv = chosen.joinToString(",")))

        var remedial = emptyList<Topic>()
        var diagnosis = ""
        if (outcome.decision == AdaptiveEngine.Decision.REMEDIATE) {
            val p = currentProfile()
            if (p != null && engine.hasModel) {
                val wrong = qs.filterIndexed { i, _ -> !answers[i] }.map { it.question }
                val res = runCatching { gen(PromptBuilder.remediation(p, topic, wrong), p.languageCode, 0.3f) }.getOrNull()
                diagnosis = res?.json?.str("diagnosis").orEmpty()
                remedial = res?.json?.arr("topics").orEmpty().mapIndexedNotNull { i, j -> Topic.fromJson(j, 0, i) }.take(2)
            }
            if (remedial.isEmpty()) remedial = listOf(Topic("rem", "Revisit basics of ${topic.title}", topic.skill, 1.5,
                com.bharatai.learning.core.Bloom.UNDERSTAND))
            currentRoadmap()?.let { r ->
                val updated = AdaptiveEngine.insertRemedial(r, topic.id, remedial)
                saveRoadmap(updated)
                updated.allTopics().filter { it.remedial }.forEach { t ->
                    if (dao.mastery(t.id) == null) dao.putMastery(TopicMastery(t.id, t.title, 0.2).toEntity())
                }
            }
        }
        return QuizResult(outcome, remedial, diagnosis)
    }

    suspend fun weeklyReview(weekIndex: Int): JsonValue? {
        val p = currentProfile() ?: return null
        val r = currentRoadmap() ?: return null
        val w = r.weeks.firstOrNull { it.index == weekIndex } ?: return null
        val ms = dao.allMastery().associateBy { it.topicId }
        val scores = w.topics.mapNotNull { t -> ms[t.id]?.takeIf { it.attempts > 0 }?.let { t.title to it.mastery } }.toMap()
        if (!engine.hasModel) return null
        val res = runCatching { gen(PromptBuilder.weeklyReview(p, w, scores, w.tasks.count { it.done }, w.tasks.size), p.languageCode, 0.5f) }.getOrNull()
        res?.json?.let { dao.putDoc(DocEntity("review:$weekIndex", it.toJson())) }
        return res?.json
    }

    suspend fun applyFeedback(f: AdaptiveEngine.Feedback, onPartial: (Roadmap?) -> Unit): Roadmap? {
        val p = currentProfile() ?: return null
        val np = AdaptiveEngine.applyFeedback(p, f)
        if (np == p) return currentRoadmap()
        return generateRoadmap(np, onPartial)
    }

    // ---- Tab 4: mentor chat --------------------------------------------------------------
    suspend fun chat(message: String, context: String, onPartial: (String) -> Unit): String {
        val p = currentProfile()
        val history = dao.lastChats(6).reversed().map { (if (it.role == "user") "user" else "assistant") to it.text }
        dao.addChat(ChatEntity(role = "user", text = message))
        val reply = if (!engine.hasModel) {
            "The on-device model is not installed yet. Open the Device tab → Model setup."
        } else runCatching {
            val prompt = PromptBuilder.mentor(p, context, history, message)
            val r = engine.generate(prompt, 0.6f, json = false,
                maxNewTokens = if ((p?.languageCode ?: "en") == "en") prompt.maxNewTokens else 600, onPartial = onPartial)
            r.text.substringBefore("<|eot_id|>").trim()
        }.getOrElse { "Sorry, generation failed: ${it.message}" }
        dao.addChat(ChatEntity(role = "assistant", text = reply, tps = engine.telemetry.value.decodeTps))
        return reply
    }

    suspend fun clearChat() = dao.clearChat()

    // ---- Tab 5: benchmark ----------------------------------------------------------------
    suspend fun benchmark(runs: Int, onRun: (Int) -> Unit): List<PerfRunEntity> {
        val prompt = PromptBuilder.chat("You are a helpful teacher.",
            "Explain how a finite state machine works, with one everyday example.", maxNewTokens = 128)
        val out = mutableListOf<PerfRunEntity>()
        // warm-up run (loads weights, warms caches) is not recorded
        engine.generate(prompt, 0.0f, json = false, maxNewTokens = 16)
        repeat(runs) { i ->
            onRun(i + 1)
            val r = engine.generate(prompt, 0.0f, json = false, maxNewTokens = 128)
            val t = engine.telemetry.value
            val row = PerfRunEntity(label = "bench-128", model = t.modelLabel, decodeTps = r.decodeTps, prefillTps = t.prefillTps,
                ttftMs = r.ttftMs, loadMs = t.loadMs, peakRssMb = t.peakMb, genTokens = r.tokens, promptTokens = t.promptTokens,
                thermal = t.thermal)
            dao.addPerf(row)
            out += row
        }
        return out
    }

    suspend fun perfCsv(): String = buildString {
        append("timestamp,label,model,decode_tps,prefill_tps,ttft_ms,load_ms,peak_rss_mb,session_peak_rss_mb,gen_tokens,prompt_tokens,thermal\n")
        val sessionPeak = engine.telemetry.value.sessionPeakRssMb
        dao.allPerf().forEach {
            append("${it.at},${it.label},${it.model},${"%.2f".format(it.decodeTps)},${"%.2f".format(it.prefillTps)},${it.ttftMs},")
            append("${it.loadMs},${"%.1f".format(it.peakRssMb)},${"%.1f".format(sessionPeak)},${it.genTokens},${it.promptTokens},${it.thermal}\n")
        }
    }

    suspend fun resetLearning() {
        dao.deleteDoc(K_ROADMAP); dao.clearMastery(); dao.clearAttempts()
    }

    fun languageName(code: String) = Languages.of(code).native

    companion object {
        private const val TAG = "LearningRepo"
        const val K_PROFILE = "profile"
        const val K_ROADMAP = "roadmap"
    }
}

fun MasteryEntity.toDomain() = TopicMastery(topicId, title, mastery, attempts, correct, intervalDays, ease, nextReviewAt, lastSeenAt)
fun TopicMastery.toEntity() = MasteryEntity(topicId, title, mastery, attempts, correct, intervalDays, ease, nextReviewAt, lastSeenAt)
