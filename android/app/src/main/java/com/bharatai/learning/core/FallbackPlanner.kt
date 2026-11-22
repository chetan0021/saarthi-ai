package com.bharatai.learning.core

/**
 * Deterministic safety net. If the model is missing, output fails validation twice, or the
 * device is too hot to run inference, the app still produces a sensible, structured plan
 * (marked source = "template" in the UI). The app therefore never crashes or shows nothing.
 */
object FallbackPlanner {

    private val phases = mapOf(
        GoalType.EXAM to listOf("Syllabus map and basics", "Core concepts I", "Core concepts II", "Problem practice",
            "Previous-year questions", "Weak-area revision", "Full mock tests", "Final revision"),
        GoalType.CERTIFICATION to listOf("Exam blueprint and basics", "Domain 1 essentials", "Domain 2 essentials",
            "Hands-on labs", "Domain 3 essentials", "Practice exams", "Gap revision", "Exam readiness"),
        GoalType.LANGUAGE to listOf("Script and sounds", "Everyday words", "Basic sentences", "Listening practice",
            "Conversation patterns", "Reading short texts", "Writing practice", "Real-life use"),
        GoalType.HOBBY to listOf("Tools and setup", "First techniques", "Guided practice", "Small project",
            "Intermediate techniques", "Creative project", "Share and get feedback", "Showcase piece"),
    )
    private val generic = listOf("Foundations", "Core concepts", "Applied practice", "Intermediate topics",
        "Mini project", "Advanced topics", "Capstone project", "Review and next steps")

    fun roadmap(p: LearnerProfile): Roadmap {
        val u = PromptBuilder.unitsFor(p)
        val names = phases[p.goalType] ?: generic
        val skills = (p.diagnosticScore.keys.toList() + p.interests).ifEmpty { listOf(p.goal) }
        val hoursPerUnit = (p.hoursPerWeek * u.weeksPerUnit).toDouble()
        val weeks = (1..u.count).map { i ->
            val name = names[((i - 1) * names.size / u.count).coerceIn(0, names.lastIndex)]
            val skill = skills[(i - 1) % skills.size]
            val topics = listOf(
                Topic("w${i}t0-" + slug(name), "$name: $skill", skill, hoursPerUnit * 0.6,
                    if (i <= u.count / 3) Bloom.UNDERSTAND else Bloom.APPLY),
                Topic("w${i}t1-practice", "Practice set ${i}", skill, hoursPerUnit * 0.4, Bloom.APPLY),
            )
            Week(i, name, "Build $skill skills for: ${p.goal}", topics, "Score 70%+ on the unit quiz")
        }
        val gaps = p.diagnosticScore.entries.sortedBy { it.value }.take(5).mapIndexed { idx, e ->
            SkillGap(e.key, e.value, 0.8, if (idx < 2) 1 else 2, "From your diagnostic test")
        }
        return Roadmap(p.goal, "Template plan (on-device model unavailable). Regenerate with Llama for a personalised plan.",
            gaps, weeks, source = "template")
    }

    fun weekTasks(p: LearnerProfile, w: Week): List<DayTask> {
        val perDay = (p.hoursPerWeek * 60 / 7).coerceIn(15, 240)
        val t = w.topics.ifEmpty { return emptyList() }
        return (1..7).map { d ->
            val topic = t[((d - 1) * t.size / 7).coerceIn(0, t.lastIndex)]
            val type = when (d) { 4 -> TaskType.QUIZ; 6 -> TaskType.REVIEW; 7 -> TaskType.PROJECT
                else -> if (d % 2 == 1) TaskType.LEARN else TaskType.PRACTICE }
            val title = when (type) {
                TaskType.LEARN -> "Study: ${topic.title}"
                TaskType.PRACTICE -> "Practice problems: ${topic.title}"
                TaskType.QUIZ -> "Micro-quiz: ${topic.title}"
                TaskType.REVIEW -> "Review notes and mistakes"
                TaskType.PROJECT -> "Milestone: ${w.milestone}"
            }
            DayTask(d, title, perDay, type, topic.id)
        }
    }
}
