package com.bharatai.learning.core

/*
 * Domain model + schema validation. The same JSON shape is used for (a) what the LLM is
 * asked to emit and (b) what we persist in SQLite, so one validator covers both paths.
 * Every parser here is total: bad/missing fields are clamped or defaulted, never thrown.
 */

enum class GoalType(val label: String) {
    CAREER("Career"), EXAM("Exam preparation"), CERTIFICATION("Professional certification"),
    SKILL("Skill development"), LANGUAGE("Learn a language"), HOBBY("Hobby"), LIFELONG("Lifelong learning");

    companion object {
        fun of(s: String) = entries.firstOrNull { it.name.equals(s, true) || it.label.equals(s, true) } ?: SKILL
    }
}

enum class Bloom { REMEMBER, UNDERSTAND, APPLY, ANALYZE, EVALUATE, CREATE;
    companion object {
        fun of(s: String): Bloom = entries.firstOrNull { s.trim().uppercase().startsWith(it.name.take(4)) } ?: UNDERSTAND
    }
}

enum class TaskType { LEARN, PRACTICE, QUIZ, PROJECT, REVIEW;
    companion object {
        fun of(s: String): TaskType {
            val t = s.lowercase()
            return when {
                "quiz" in t || "test" in t -> QUIZ
                "project" in t || "build" in t -> PROJECT
                "review" in t || "revis" in t -> REVIEW
                "practi" in t || "exercise" in t || "solve" in t || "lab" in t -> PRACTICE
                else -> LEARN
            }
        }
    }
}

data class LearnerProfile(
    val name: String,
    val goalType: GoalType,
    val goal: String,
    val weeks: Int,
    val hoursPerWeek: Int,
    val level: Int,                 // 0 beginner .. 3 advanced (self-rated)
    val knownSkills: List<String>,
    val interests: List<String>,
    val languageCode: String,
    val diagnosticScore: Map<String, Double> = emptyMap(), // skill -> 0..1 from diagnostic quiz
) {
    fun levelLabel() = listOf("Beginner", "Elementary", "Intermediate", "Advanced")[level.coerceIn(0, 3)]

    fun toJson(): JsonValue = obj(
        "name" to s(name), "goalType" to s(goalType.name), "goal" to s(goal), "weeks" to n(weeks),
        "hoursPerWeek" to n(hoursPerWeek), "level" to n(level), "knownSkills" to sl(knownSkills),
        "interests" to sl(interests), "language" to s(languageCode),
        "diagnostic" to JsonValue.Obj(LinkedHashMap(diagnosticScore.mapValues { JsonValue.Num(it.value) })),
    )

    companion object {
        fun fromJson(j: JsonValue) = LearnerProfile(
            name = j.str("name"), goalType = GoalType.of(j.str("goalType")), goal = j.str("goal"),
            weeks = j.int("weeks", 8).coerceIn(1, 52), hoursPerWeek = j.int("hoursPerWeek", 6).coerceIn(1, 80),
            level = j.int("level", 0).coerceIn(0, 3), knownSkills = j.strList("knownSkills"),
            interests = j.strList("interests"), languageCode = j.str("language", "en").ifEmpty { "en" },
            diagnosticScore = (j["diagnostic"] as? JsonValue.Obj)?.map
                ?.mapValues { (it.value as? JsonValue.Num)?.value ?: 0.0 } ?: emptyMap(),
        )
    }
}

data class SkillGap(val skill: String, val current: Double, val required: Double, val priority: Int, val reason: String) {
    val gap get() = (required - current).coerceIn(0.0, 1.0)
    fun toJson() = obj("skill" to s(skill), "current" to num(current), "required" to num(required),
        "priority" to n(priority), "reason" to s(reason))

    companion object {
        fun fromJson(j: JsonValue): SkillGap? {
            val skill = j.str("skill").ifEmpty { j.str("name") }.ifEmpty { return null }
            return SkillGap(skill, normLevel(j.num("current", 0.2)), normLevel(j.num("required", 0.8)),
                j.int("priority", 2).coerceIn(1, 3), j.str("reason"))
        }

        /** The model mixes 0-1, 0-5, 0-10 and 0-100 scales; normalise all to 0..1. */
        fun normLevel(v: Double): Double = when {
            v <= 1.0 -> v
            v <= 5.0 -> v / 5.0
            v <= 10.0 -> v / 10.0
            else -> v / 100.0
        }.coerceIn(0.0, 1.0)
    }
}

data class Topic(
    val id: String,
    val title: String,
    val skill: String,
    val hours: Double,
    val bloom: Bloom,
    val prerequisites: List<String> = emptyList(),
    val remedial: Boolean = false,
) {
    fun toJson() = obj("id" to s(id), "title" to s(title), "skill" to s(skill), "hours" to num(hours),
        "bloom" to s(bloom.name), "prerequisites" to sl(prerequisites), "remedial" to JsonValue.Bool(remedial))

    companion object {
        fun fromJson(j: JsonValue, weekIdx: Int, idx: Int): Topic? {
            val title = when (j) {
                is JsonValue.Str -> j.value
                else -> j.str("title").ifEmpty { j.str("topic") }.ifEmpty { j.str("name") }
            }.trim()
            if (title.isEmpty()) return null
            val id = j.str("id").ifEmpty { "w${weekIdx}t$idx-" + slug(title) }
            return Topic(id, title, j.str("skill").ifEmpty { title }, j.num("hours", 2.0).coerceIn(0.25, 20.0),
                Bloom.of(j.str("bloom")), j.strList("prerequisites"), (j["remedial"] as? JsonValue.Bool)?.value ?: false)
        }
    }
}

data class DayTask(val day: Int, val title: String, val minutes: Int, val type: TaskType, val topicId: String, val done: Boolean = false) {
    fun toJson() = obj("day" to n(day), "title" to s(title), "minutes" to n(minutes), "type" to s(type.name),
        "topicId" to s(topicId), "done" to JsonValue.Bool(done))

    companion object {
        fun fromJson(j: JsonValue, topics: List<Topic>): DayTask? {
            val title = j.str("title").ifEmpty { j.str("task") }.ifEmpty { return null }
            val topicRef = j.str("topicId").ifEmpty { j.str("topic") }
            val topicId = topics.firstOrNull { it.id == topicRef }?.id
                ?: topics.firstOrNull { topicRef.isNotEmpty() && (it.title.contains(topicRef, true) || topicRef.contains(it.title, true)) }?.id
                ?: topics.firstOrNull { title.contains(it.title, true) }?.id
                ?: topics.firstOrNull()?.id ?: ""
            return DayTask(j.int("day", 1).coerceIn(1, 7), title, j.int("minutes", 45).coerceIn(10, 240),
                TaskType.of(j.str("type")), topicId, (j["done"] as? JsonValue.Bool)?.value ?: false)
        }
    }
}

data class Week(
    val index: Int,
    val title: String,
    val objective: String,
    val topics: List<Topic>,
    val milestone: String,
    val tasks: List<DayTask> = emptyList(),
) {
    val expanded get() = tasks.isNotEmpty()
    val progress get() = if (tasks.isEmpty()) 0f else tasks.count { it.done }.toFloat() / tasks.size

    fun toJson() = obj("index" to n(index), "title" to s(title), "objective" to s(objective),
        "topics" to JsonValue.Arr(topics.map { it.toJson() }), "milestone" to s(milestone),
        "tasks" to JsonValue.Arr(tasks.map { it.toJson() }))

    companion object {
        fun fromJson(j: JsonValue, fallbackIdx: Int): Week? {
            val idx = j.int("index", j.int("week", fallbackIdx)).let { if (it <= 0) fallbackIdx else it }
            val topics = j.arr("topics").mapIndexedNotNull { i, t -> Topic.fromJson(t, idx, i) }.distinctBy { it.title.lowercase() }
            val title = j.str("title").ifEmpty { j.str("theme") }.ifEmpty { topics.firstOrNull()?.title ?: return null }
            val tasks = j.arr("tasks").mapNotNull { DayTask.fromJson(it, topics) }
            return Week(idx, title, j.str("objective").ifEmpty { j.str("goal") }, topics.take(6),
                j.str("milestone").ifEmpty { "Complete the week's quiz with 70%+" }, tasks)
        }
    }
}

data class Roadmap(
    val goal: String,
    val summary: String,
    val gaps: List<SkillGap>,
    val weeks: List<Week>,
    val source: String,          // "llm" | "template"
    val version: Int = 1,
    val generatedAt: Long = System.currentTimeMillis(),
) {
    fun allTopics() = weeks.flatMap { it.topics }
    fun toJson() = obj("goal" to s(goal), "summary" to s(summary), "gaps" to JsonValue.Arr(gaps.map { it.toJson() }),
        "weeks" to JsonValue.Arr(weeks.map { it.toJson() }), "source" to s(source), "version" to n(version),
        "generatedAt" to JsonValue.Num(generatedAt.toDouble()))

    /** Schema validation for model output: at least 2 weeks, each with >=1 topic. */
    fun isValid(): Boolean = weeks.size >= 2 && weeks.all { it.topics.isNotEmpty() }

    companion object {
        fun fromJson(j: JsonValue, source: String = j.str("source", "llm")): Roadmap {
            val weeks = j.arr("weeks").ifEmpty { j.arr("roadmap") }.ifEmpty { j.arr("plan") }
                .mapIndexedNotNull { i, w -> Week.fromJson(w, i + 1) }
                .sortedBy { it.index }
                .mapIndexed { i, w -> if (w.index != i + 1) w.copy(index = i + 1) else w }
            return Roadmap(
                goal = j.str("goal"), summary = j.str("summary").ifEmpty { j.str("analysis") },
                gaps = j.arr("gaps").ifEmpty { j.arr("skill_gaps") }.ifEmpty { j.arr("skillGaps") }
                    .mapNotNull { SkillGap.fromJson(it) }.distinctBy { it.skill.lowercase() }.take(8),
                weeks = weeks, source = source.ifEmpty { "llm" }, version = j.int("version", 1),
                generatedAt = j.num("generatedAt", System.currentTimeMillis().toDouble()).toLong(),
            )
        }
    }
}

data class QuizQuestion(
    val topicId: String,
    val topic: String,
    val question: String,
    val options: List<String>,
    val answer: Int,
    val explanation: String,
    val bloom: Bloom,
) {
    fun toJson() = obj("topicId" to s(topicId), "topic" to s(topic), "question" to s(question),
        "options" to sl(options), "answer" to n(answer), "explanation" to s(explanation), "bloom" to s(bloom.name))

    companion object {
        private val letter = Regex("^\\s*\\(?([A-Da-d])[).:]\\s*")

        fun fromJson(j: JsonValue, topicId: String, topicTitle: String): QuizQuestion? {
            val q = j.str("question").ifEmpty { j.str("q") }.ifEmpty { return null }
            val opts = j.strList("options").ifEmpty { j.strList("choices") }
                .map { it.replace(letter, "").trim() }.filter { it.isNotEmpty() }.distinct()
            if (opts.size < 2) return null
            val options = opts.take(4)
            val ansRaw = j["answer"] ?: j["correct"] ?: j["answer_index"]
            val answer = when (ansRaw) {
                is JsonValue.Num -> ansRaw.value.toInt().let { if (it in 1..options.size && it == options.size) it - 1 else it }
                is JsonValue.Str -> {
                    val a = ansRaw.value.trim()
                    when {
                        a.length == 1 && a[0].uppercaseChar() in 'A'..'D' -> a[0].uppercaseChar() - 'A'
                        a.toIntOrNull() != null -> a.toInt()
                        else -> options.indexOfFirst { it.equals(a.replace(letter, ""), true) }
                    }
                }
                else -> -1
            }
            if (answer !in options.indices) return null
            return QuizQuestion(topicId.ifEmpty { j.str("topicId") }, topicTitle.ifEmpty { j.str("topic") }, q, options,
                answer, j.str("explanation").ifEmpty { j.str("why") }, Bloom.of(j.str("bloom")))
        }

        fun listFrom(j: JsonValue?, topicId: String, topicTitle: String): List<QuizQuestion> =
            (j?.arr("questions")?.ifEmpty { j.arr("items") } ?: emptyList())
                .mapNotNull { fromJson(it, topicId, topicTitle) }
                .distinctBy { it.question.lowercase().take(60) }
    }
}

data class DiagnosticQuestion(val skill: String, val q: QuizQuestion)

data class TopicMastery(
    val topicId: String,
    val title: String,
    val mastery: Double = 0.3,      // P(known), BKT-style
    val attempts: Int = 0,
    val correct: Int = 0,
    val intervalDays: Int = 1,      // spaced-repetition interval
    val ease: Double = 2.5,
    val nextReviewAt: Long = 0L,
    val lastSeenAt: Long = 0L,
)

// ---- small JSON builders -------------------------------------------------------------
internal fun obj(vararg p: Pair<String, JsonValue>) = JsonValue.Obj(linkedMapOf(*p))
internal fun s(v: String) = JsonValue.Str(v)
internal fun n(v: Int) = JsonValue.Num(v.toDouble())
internal fun num(v: Double) = JsonValue.Num(Math.round(v * 100) / 100.0)
internal fun sl(v: List<String>) = JsonValue.Arr(v.map { JsonValue.Str(it) })
fun slug(t: String) = t.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "-").trim('-').take(32)
