package com.bharatai.learning.core

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Closed-loop adaptation, all deterministic and on-device:
 *  - Bayesian Knowledge Tracing (BKT) per topic -> mastery probability
 *  - SM-2-lite spaced repetition -> when to review each topic
 *  - decision rule: <70% -> remediation pass (LLM), >=90% with high mastery -> accelerate
 *  - learner feedback (too easy / too hard / need more time) -> pace + difficulty changes
 * The LLM is only asked to *create content*; the decisions are explainable rules a jury can audit.
 */
object AdaptiveEngine {
    // BKT parameters for 4-option MCQs
    private const val GUESS = 0.25
    private const val SLIP = 0.10
    private const val LEARN = 0.15
    const val REMEDIATE_BELOW = 0.70
    const val ACCELERATE_AT = 0.90
    private const val DAY_MS = 24L * 3600 * 1000

    enum class Decision { REMEDIATE, CONTINUE, ACCELERATE }
    enum class Feedback { TOO_EASY, JUST_RIGHT, TOO_HARD, NEED_MORE_TIME }

    data class QuizOutcome(val mastery: TopicMastery, val score: Double, val decision: Decision, val message: String)

    fun bktUpdate(pKnown: Double, correct: Boolean): Double {
        val posterior = if (correct) {
            pKnown * (1 - SLIP) / (pKnown * (1 - SLIP) + (1 - pKnown) * GUESS)
        } else {
            pKnown * SLIP / (pKnown * SLIP + (1 - pKnown) * (1 - GUESS))
        }
        return (posterior + (1 - posterior) * LEARN).coerceIn(0.01, 0.99)
    }

    /** Apply a finished quiz on one topic. [answers] = correctness per question in order. */
    fun applyQuiz(m: TopicMastery, answers: List<Boolean>, now: Long = System.currentTimeMillis()): QuizOutcome {
        var p = m.mastery
        answers.forEach { p = bktUpdate(p, it) }
        val score = if (answers.isEmpty()) 0.0 else answers.count { it }.toDouble() / answers.size

        // SM-2 lite: quality 0..5 from score
        val q = (score * 5).roundToInt()
        val ease = max(1.3, m.ease + (0.1 - (5 - q) * (0.08 + (5 - q) * 0.02)))
        val interval = when {
            q < 3 -> 1
            m.attempts == 0 -> 1
            m.intervalDays <= 1 -> 3
            else -> (m.intervalDays * ease).roundToInt()
        }.coerceIn(1, 60)

        val decision = when {
            score < REMEDIATE_BELOW -> Decision.REMEDIATE
            score >= ACCELERATE_AT && p >= 0.85 -> Decision.ACCELERATE
            else -> Decision.CONTINUE
        }
        val msg = when (decision) {
            Decision.REMEDIATE -> "Below 70% - adding a short remedial module and a review tomorrow."
            Decision.ACCELERATE -> "Excellent! Mastery ${(p * 100).toInt()}% - you can move ahead faster."
            Decision.CONTINUE -> "Good progress. Next review in $interval day(s)."
        }
        return QuizOutcome(
            m.copy(mastery = p, attempts = m.attempts + answers.size, correct = m.correct + answers.count { it },
                intervalDays = interval, ease = ease, nextReviewAt = now + interval * DAY_MS, lastSeenAt = now),
            score, decision, msg,
        )
    }

    /** Diagnostic answers -> per-skill score map (0..1), used for skill-gap analysis. */
    fun diagnosticScores(questions: List<DiagnosticQuestion>, chosen: List<Int>): Map<String, Double> =
        questions.mapIndexed { i, dq -> dq.skill to if (chosen.getOrNull(i) == dq.q.answer) 1.0 else 0.0 }
            .groupBy({ it.first }, { it.second })
            .mapValues { it.value.average() }

    fun dueForReview(all: List<TopicMastery>, now: Long = System.currentTimeMillis()): List<TopicMastery> =
        all.filter { it.attempts > 0 && it.nextReviewAt in 1..now }.sortedBy { it.mastery }

    fun weakTopics(all: List<TopicMastery>): List<TopicMastery> =
        all.filter { it.attempts > 0 && it.mastery < REMEDIATE_BELOW }.sortedBy { it.mastery }

    /** Insert remedial topics (from the LLM remediation pass) right where they are needed. */
    fun insertRemedial(r: Roadmap, afterTopicId: String, remedial: List<Topic>): Roadmap {
        if (remedial.isEmpty()) return r
        val wIdx = r.weeks.indexOfFirst { w -> w.topics.any { it.id == afterTopicId } }.let { if (it < 0) 0 else it }
        val week = r.weeks[wIdx]
        val existing = week.topics.map { it.title.lowercase() }.toSet()
        val fresh = remedial.filter { it.title.lowercase() !in existing }
            .mapIndexed { i, t -> t.copy(id = "w${week.index}r${week.topics.size + i}-" + slug(t.title), remedial = true,
                prerequisites = emptyList()) }
        if (fresh.isEmpty()) return r
        val pos = week.topics.indexOfFirst { it.id == afterTopicId }.let { if (it < 0) week.topics.size else it }
        val topics = week.topics.toMutableList().apply { addAll(pos, fresh) }
        // schedule remedial tasks on the earliest days that still have open work
        val firstOpenDay = week.tasks.filter { !it.done }.minOfOrNull { it.day } ?: 1
        val newTasks = fresh.flatMapIndexed { i, t ->
            val d = (firstOpenDay + i).coerceAtMost(7)
            listOf(DayTask(d, "Remedial: ${t.title}", (t.hours * 40).toInt().coerceIn(20, 90), TaskType.LEARN, t.id),
                DayTask(d, "Practice: ${t.title}", 20, TaskType.PRACTICE, t.id))
        }
        val tasks = if (week.expanded) (week.tasks + newTasks).sortedBy { it.day } else week.tasks
        val weeks = r.weeks.toMutableList().apply { this[wIdx] = week.copy(topics = topics, tasks = tasks) }
        return r.copy(weeks = weeks, version = r.version + 1)
    }

    /** Learner feedback adjusts the profile used for the *next* generation pass. */
    fun applyFeedback(p: LearnerProfile, f: Feedback): LearnerProfile = when (f) {
        Feedback.TOO_EASY -> p.copy(level = (p.level + 1).coerceAtMost(3))
        Feedback.TOO_HARD -> p.copy(level = (p.level - 1).coerceAtLeast(0))
        Feedback.NEED_MORE_TIME -> p.copy(weeks = (p.weeks + 2).coerceAtMost(52))
        Feedback.JUST_RIGHT -> p
    }

    /** Overall progress 0..1 across expanded weeks + mastery of quizzed topics. */
    fun overallProgress(r: Roadmap?, masteries: List<TopicMastery>): Float {
        if (r == null || r.weeks.isEmpty()) return 0f
        val ids = r.allTopics().map { it.id }.toSet()
        val m = masteries.filter { it.topicId in ids }
        val masteryPart = if (ids.isEmpty()) 0.0 else m.sumOf { it.mastery.takeIf { _ -> it.attempts > 0 } ?: 0.0 } / ids.size
        val tasks = r.weeks.flatMap { it.tasks }
        val taskPart = if (tasks.isEmpty()) 0.0 else tasks.count { it.done }.toDouble() / tasks.size
        return ((masteryPart + taskPart) / 2).toFloat().coerceIn(0f, 1f)
    }
}
