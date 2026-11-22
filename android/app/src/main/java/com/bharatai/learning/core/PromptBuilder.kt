package com.bharatai.learning.core

import kotlin.math.ceil

/**
 * Structured prompts for Llama 3.2 Instruct (1B/3B).
 *
 * Design rules for a small on-device model:
 *  - one job per prompt, short system prompt, explicit JSON shape with a filled example;
 *  - English JSON keys always; user-facing string values in the learner's language;
 *  - the assistant turn is pre-seeded with "{" so the first generated token is already JSON;
 *  - output size is bounded (units capped at 8, topics <= 3 per unit) to keep latency low.
 */
/** A fully formatted prompt; [prefill] was appended to the assistant turn and must be re-attached to the output. */
data class Prompt(val text: String, val prefill: String, val maxNewTokens: Int)

object PromptBuilder {

    private const val SYSTEM = "You are Saarthi, an expert teacher and learning-path designer for Indian learners. " +
        "You reason like a good teacher: start from what the learner already knows, teach prerequisites before " +
        "advanced ideas, mix learning with practice, and keep plans realistic for the time available. " +
        "When asked for JSON, reply with ONLY one valid JSON object and nothing else."

    /** Llama 3 chat template. [prefill] is placed at the start of the assistant turn. */
    fun chat(
        system: String, user: String, prefill: String = "", history: List<Pair<String, String>> = emptyList(),
        maxNewTokens: Int = 512,
    ): Prompt = Prompt(buildString {
            append("<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n\n").append(system).append("<|eot_id|>")
            for ((role, text) in history) {
                append("<|start_header_id|>").append(role).append("<|end_header_id|>\n\n").append(text).append("<|eot_id|>")
            }
            append("<|start_header_id|>user<|end_header_id|>\n\n").append(user).append("<|eot_id|>")
            append("<|start_header_id|>assistant<|end_header_id|>\n\n").append(prefill)
        }, prefill, maxNewTokens)

    private fun langRule(code: String, jsonMode: Boolean = true): String {
        val l = Languages.of(code)
        if (l.code == "en") return ""
        return if (jsonMode) "\nWrite every text value in ${l.english} (${l.script} script). Keep JSON keys in English."
        else "\nReply in ${l.english} (${l.script} script). Keep technical terms in English where natural."
    }

    private fun profileBlock(p: LearnerProfile): String = buildString {
        append("Learner goal (${p.goalType.label}): ${p.goal}\n")
        append("Self-rated level: ${p.levelLabel()}\n")
        append("Time: ${p.hoursPerWeek} hours/week for ${p.weeks} weeks\n")
        if (p.knownSkills.isNotEmpty()) append("Already knows: ${p.knownSkills.joinToString(", ")}\n")
        if (p.interests.isNotEmpty()) append("Interests: ${p.interests.joinToString(", ")}\n")
        if (p.diagnosticScore.isNotEmpty()) {
            append("Diagnostic test results (0-100%): ")
            append(p.diagnosticScore.entries.joinToString(", ") { "${it.key} ${(it.value * 100).toInt()}%" })
            append('\n')
        }
    }

    // ---- 1. Goal diagnostic -------------------------------------------------------------
    fun diagnostic(p: LearnerProfile): Prompt = chat(SYSTEM, buildString {
        append(profileBlock(p))
        append("Use only topics relevant to the learner goal above.\n")
        append("\nTask: write 5 short multiple-choice questions that test the 5 most important FOUNDATION skills ")
        append("needed for this goal, one question per skill, matched to the learner's level. ")
        append("Each question has exactly 4 options and one correct answer. \"answer\" is the 0-based index.\n")
        append("JSON format:\n")
        append("{\"questions\":[{\"skill\":\"<skill name>\",\"question\":\"<question text>?\",")
        append("\"options\":[\"<option 1>\",\"<option 2>\",\"<option 3>\",\"<option 4>\"],\"answer\":0,\"explanation\":\"<short explanation>\"}]}")
        append(langRule(p.languageCode))
    }, prefill = "{\"questions\":[", maxNewTokens = 520)

    // ---- 2. Skill-gap analysis + roadmap skeleton ---------------------------------------
    data class Units(val count: Int, val weeksPerUnit: Int)

    fun unitsFor(p: LearnerProfile): Units {
        val count = p.weeks.coerceIn(2, 8)
        return Units(count, ceil(p.weeks / count.toDouble()).toInt().coerceAtLeast(1))
    }

    fun roadmap(p: LearnerProfile, remediationNotes: List<String> = emptyList()): Prompt {
        val u = unitsFor(p)
        return chat(SYSTEM, buildString {
            append(profileBlock(p))
            if (remediationNotes.isNotEmpty()) append("Weak areas found in quizzes: ${remediationNotes.joinToString(", ")}\n")
            append("Use only topics relevant to the learner goal above.\n")
            append("\nTask:\n1. Find the learner's skill gaps: compare current level with what the goal needs. ")
            append("Use the diagnostic results if given. List up to 3 gaps, most important first. ")
            append("current and required are 0.0-1.0. priority 1=critical, 3=nice to have.\n")
            append("2. Plan exactly ${u.count} units (each unit = ${u.weeksPerUnit} week(s)). ")
            append("Order units so prerequisites come first and close the critical gaps early. ")
            append("Each unit has 1-2 topics and a concrete milestone the learner can show.\n")
            append("Keep every title under 6 words.\nJSON format:\n")
            append("{\"summary\":\"<one sentence>\",\"gaps\":[{\"skill\":\"<skill name>\",\"current\":0.0,\"required\":0.0,")
            append("\"priority\":1,\"reason\":\"<short reason>\"}],\"weeks\":[{\"index\":1,\"title\":\"<unit title>\",")
            append("\"objective\":\"<unit objective>\",\"topics\":[{\"title\":\"<topic title>\",\"skill\":\"<skill name>\"}],")
            append("\"milestone\":\"<milestone text>\"}]}")
        }, prefill = "{\"summary\":\"", maxNewTokens = 150 + u.count * 50)
    }

    // ---- 3. Week expansion: daily micro-tasks ------------------------------------------
    fun weekDetail(p: LearnerProfile, w: Week, weakTopics: List<String>): Prompt = chat(SYSTEM, buildString {
        append("Learner goal: ${p.goal}. Level: ${p.levelLabel()}. Study time: ${p.hoursPerWeek} hours this week.\n")
        append("This week: ${w.title}. Objective: ${w.objective}\n")
        append("Topics: ").append(w.topics.joinToString("; ") { "${it.id} = ${it.title}" }).append('\n')
        if (weakTopics.isNotEmpty()) append("Needs revision: ${weakTopics.joinToString(", ")}\n")
        append("Use only topics relevant to the learner goal above.\n")
        append("\nTask: make a 7-day plan. 1-2 tasks per day, minutes per day about ${p.hoursPerWeek * 60 / 7}. ")
        append("type is one of learn, practice, quiz, project, review. Start with learn, add practice after each topic, ")
        append("a quiz mid-week, review on day 6 and the milestone project on day 7. topicId must be one of the ids above.\n")
        append("JSON format:\n{\"tasks\":[{\"day\":1,\"title\":\"<task title>\",\"minutes\":40,")
        append("\"type\":\"learn\",\"topicId\":\"${w.topics.firstOrNull()?.id ?: "t1"}\"}]}")
        append(langRule(p.languageCode))
    }, prefill = "{\"tasks\":[", maxNewTokens = 420)

    // ---- 4. Adaptive micro-quiz ----------------------------------------------------------
    fun quiz(p: LearnerProfile, topic: Topic, mastery: Double, count: Int = 3, mistakes: List<String> = emptyList()): Prompt {
        val target = when {
            mastery < 0.4 -> "remember and understand (basic recall, definitions)"
            mastery < 0.75 -> "understand and apply (short problems)"
            else -> "apply and analyze (tricky cases, compare options)"
        }
        return chat(SYSTEM, buildString {
            append("Learner goal: ${p.goal}. Topic: ${topic.title} (skill: ${topic.skill}).\n")
            append("Current mastery: ${(mastery * 100).toInt()}%. Ask at Bloom level: $target.\n")
            append("Use only topics relevant to the learner goal above.\n")
            if (mistakes.isNotEmpty()) append("The learner previously got these wrong, test the same idea differently: ${mistakes.joinToString(" | ")}\n")
            append("Write $count multiple-choice questions with exactly 4 options each. Only one option is correct. ")
            append("\"answer\" is the 0-based index of the correct option. Explanations are one sentence.\n")
            append("JSON format:\n{\"questions\":[{\"question\":\"<question text>?\",\"options\":[\"<option 1>\",\"<option 2>\",\"<option 3>\",\"<option 4>\"],")
            append("\"answer\":0,\"explanation\":\"<short explanation>\",\"bloom\":\"understand\"}]}")
            append(langRule(p.languageCode))
        }, prefill = "{\"questions\":[", maxNewTokens = 120 + count * 90)
    }

    // ---- 5. Remediation (closed adaptive loop) -----------------------------------------
    fun remediation(p: LearnerProfile, topic: Topic, wrongQuestions: List<String>): Prompt = chat(SYSTEM, buildString {
        append("Learner goal: ${p.goal}. Level: ${p.levelLabel()}.\n")
        append("The learner scored below 70% on \"${topic.title}\".\n")
        append("Use only topics relevant to the learner goal above.\n")
        if (wrongQuestions.isNotEmpty()) append("Questions they got wrong: ${wrongQuestions.joinToString(" | ")}\n")
        append("Task: find the missing prerequisite ideas and give 1-2 short remedial topics that fix them, ")
        append("each 1-2 hours, easier than the original topic.\n")
        append("JSON format:\n{\"diagnosis\":\"<one sentence>\",\"topics\":[{\"title\":\"<topic title>\",\"skill\":\"<skill name>\",\"hours\":1.5,")
        append("\"bloom\":\"understand\"}]}")
        append(langRule(p.languageCode))
    }, prefill = "{\"diagnosis\":\"", maxNewTokens = 200)

    // ---- 6. Weekly milestone review ----------------------------------------------------
    fun weeklyReview(p: LearnerProfile, w: Week, scores: Map<String, Double>, tasksDone: Int, tasksTotal: Int): Prompt =
        chat(SYSTEM, buildString {
            append("Learner goal: ${p.goal}.\nWeek ${w.index}: ${w.title}. Milestone: ${w.milestone}\n")
            append("Tasks done: $tasksDone of $tasksTotal. Quiz mastery: ")
            append(scores.entries.joinToString(", ") { "${it.key} ${(it.value * 100).toInt()}%" }.ifEmpty { "no quizzes yet" })
            append("\nUse only topics relevant to the learner goal above.\n")
            append("Task: write a short, encouraging weekly review.\nJSON format:\n")
            append("{\"summary\":\"<two sentences>\",\"strengths\":[\"<strength>\"],\"focus\":[\"<focus area>\"],\"next_step\":\"<one action>\"}")
            append(langRule(p.languageCode))
        }, prefill = "{\"summary\":\"", maxNewTokens = 220)

    // ---- 7. Dual-pass localisation (pass 2) --------------------------------------------
    fun translate(code: String, lines: List<String>): Prompt {
        val l = Languages.of(code)
        return chat("You translate short study-plan titles for Indian learners. Reply with ONLY JSON.", buildString {
            append("Translate each line to ${l.english} (${l.script} script). Keep technical terms (like Python, SQL, GATE) in English. ")
            append("Return the same number of lines in the same order.\n")
            lines.forEachIndexed { i, s -> append(i + 1).append(". ").append(s).append('\n') }
            append("JSON format: {\"t\":[\"line 1\",\"line 2\"]}")
        }, prefill = "{\"t\":[", maxNewTokens = 60 + lines.size * 30)
    }

    // ---- 8. Conversational mentor (plain text) -----------------------------------------
    fun mentor(p: LearnerProfile?, context: String, history: List<Pair<String, String>>, message: String): Prompt {
        val sys = buildString {
            append("You are Saarthi, a friendly and patient tutor. Explain step by step with a simple example, ")
            append("check understanding with one short question at the end, and keep answers under 150 words.")
            if (p != null) append("\nLearner goal: ${p.goal}. Level: ${p.levelLabel()}.")
            if (context.isNotBlank()) append("\nCurrent study context: $context")
            append(langRule(p?.languageCode ?: "en", jsonMode = false))
        }
        return chat(sys, message, history = history.takeLast(4), maxNewTokens = 300)
    }
}
