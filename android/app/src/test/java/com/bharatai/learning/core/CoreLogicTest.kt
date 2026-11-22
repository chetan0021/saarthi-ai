package com.bharatai.learning.core

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** JVM unit tests for the crash-free JSON layer, schema validation and the adaptive loop. */
class CoreLogicTest {

    @Test fun parsesCleanJson() {
        val j = LenientJson.parse("""{"a":1,"b":[true,null,"x"],"c":{"d":"e"}}""")!!
        assertEquals(1, j.int("a"))
        assertEquals("e", j["c"]!!.str("d"))
        assertEquals(3, j.arr("b").size)
    }

    @Test fun extractsJsonFromProseAndFences() {
        val raw = "Sure! Here is your plan:\n```json\n{\"summary\": \"ok\", \"weeks\": []}\n```\nHope this helps."
        assertEquals("ok", LenientJson.parse(raw)!!.str("summary"))
    }

    @Test fun repairsTrailingCommasSingleQuotesUnquotedKeys() {
        val j = LenientJson.parse("{summary: 'plan', 'weeks': [ {\"index\": 1,}, ], }")!!
        assertEquals("plan", j.str("summary"))
        assertEquals(1, j.arr("weeks").size)
    }

    @Test fun autoClosesTruncatedStream() {
        val truncated = """{"summary":"Learn VLSI","weeks":[{"index":1,"title":"Logic basics","topics":[{"title":"Boolean alg"""
        val r = Roadmap.fromJson(LenientJson.parse(truncated)!!)
        assertEquals(1, r.weeks.size)
        assertEquals("Boolean alg", r.weeks[0].topics[0].title)
    }

    @Test fun neverThrowsOnGarbage() {
        listOf("", "no json here", "{", "[[[[", "{\"a\":\"\\u12", "}{][", "{\"q\": \"5' 4\" tall\"}").forEach {
            LenientJson.parse(it) // must not throw
        }
    }

    @Test fun keepsUnicodeIndicText() {
        val j = LenientJson.parse("""{"title":"बूलियन बीजगणित","t":["தமிழ்","ಕನ್ನಡ"]}""")!!
        assertEquals("बूलियन बीजगणित", j.str("title"))
        assertEquals(listOf("தமிழ்", "ಕನ್ನಡ"), j.strList("t"))
    }

    @Test fun streamWatcherDetectsEndOfObject() {
        val w = JsonStreamWatcher()
        assertTrue(!w.feed("{\"a\":\"}\""))   // brace inside string is ignored
        assertTrue(!w.feed(",\"b\":[1,{\"c\":2}]"))
        assertTrue(w.feed("} trailing chatter"))
    }

    @Test fun quizValidationClampsAndNormalises() {
        val j = LenientJson.parse("""{"questions":[
            {"question":"2+2?","options":["A) 3","B) 4","C) 5","D) 6"],"answer":"B","explanation":"basic"},
            {"question":"bad","options":["only one"],"answer":0},
            {"question":"idx out of range","options":["a","b","c","d"],"answer":9},
            {"question":"text answer","options":["red","green","blue","black"],"answer":"blue"}]}""")
        val qs = QuizQuestion.listFrom(j, "t1", "Arithmetic")
        assertEquals(2, qs.size)
        assertEquals(1, qs[0].answer)
        assertEquals("4", qs[0].options[1])
        assertEquals(2, qs[1].answer)
    }

    @Test fun skillLevelsAreNormalisedAcrossScales() {
        assertEquals(0.6, SkillGap.normLevel(3.0), 1e-9)   // 0-5 scale
        assertEquals(0.7, SkillGap.normLevel(7.0), 1e-9)   // 0-10 scale
        assertEquals(0.45, SkillGap.normLevel(45.0), 1e-9) // percent
    }

    @Test fun bktRaisesOnCorrectLowersOnWrong() {
        val up = AdaptiveEngine.bktUpdate(0.3, true)
        val down = AdaptiveEngine.bktUpdate(0.3, false)
        assertTrue(up > 0.3)
        assertTrue(down < up)
    }

    @Test fun lowScoreTriggersRemediationAndInsertion() {
        val out = AdaptiveEngine.applyQuiz(TopicMastery("w1t0", "FSM"), listOf(true, false, false))
        assertEquals(AdaptiveEngine.Decision.REMEDIATE, out.decision)
        val p = profile()
        val r = FallbackPlanner.roadmap(p).let { rm ->
            rm.copy(weeks = rm.weeks.mapIndexed { i, w -> if (i == 0) w.copy(tasks = FallbackPlanner.weekTasks(p, w)) else w })
        }
        val first = r.weeks[0].topics[0]
        val r2 = AdaptiveEngine.insertRemedial(r, first.id, listOf(Topic("x", "State diagrams basics", "FSM", 1.5, Bloom.UNDERSTAND)))
        assertEquals(r.weeks[0].topics.size + 1, r2.weeks[0].topics.size)
        assertTrue(r2.weeks[0].topics[0].remedial)
        assertTrue(r2.weeks[0].tasks.any { it.title.startsWith("Remedial") })
        assertEquals(r.version + 1, r2.version)
    }

    @Test fun highScoreAccelerates() {
        var m = TopicMastery("t", "T", mastery = 0.8)
        val out = AdaptiveEngine.applyQuiz(m, listOf(true, true, true))
        assertEquals(AdaptiveEngine.Decision.ACCELERATE, out.decision)
        m = out.mastery
        assertTrue(m.nextReviewAt > 0)
    }

    @Test fun fallbackRoadmapIsAlwaysValid() {
        listOf(1, 3, 8, 24, 52).forEach { w ->
            val r = FallbackPlanner.roadmap(profile().copy(weeks = w))
            assertTrue(r.isValid(), "weeks=$w")
            assertTrue(r.weeks.size in 2..8)
        }
    }

    @Test fun roadmapRoundTripsThroughStorage() {
        val r = FallbackPlanner.roadmap(profile())
        val back = Roadmap.fromJson(LenientJson.parse(r.toJson().toJson())!!)
        assertEquals(r.weeks.size, back.weeks.size)
        assertEquals(r.weeks[1].topics[0].id, back.weeks[1].topics[0].id)
        assertEquals("template", back.source)
    }

    @Test fun promptsUseLlama3TemplateAndPrefill() {
        val pr = PromptBuilder.roadmap(profile())
        assertTrue(pr.text.startsWith("<|begin_of_text|>"))
        assertTrue(pr.text.endsWith(pr.prefill))
        assertTrue(pr.text.contains("<|start_header_id|>assistant<|end_header_id|>"))
    }

    @Test fun promptTemplatesDoNotContainExampleLeakage() {
        val p = LearnerProfile("Test", GoalType.SKILL, "Learn Mobile App Development", 8, 8, 1,
            listOf("Kotlin"), listOf("Mobile"), "en")
        val sampleTopic = Topic("t1", "Architecture", "Design Patterns", 2.0, Bloom.UNDERSTAND)
        val sampleWeek = Week(1, "Fundamentals", "Learn core ideas", listOf(sampleTopic), "Build a sample app")

        val prompts = listOf(
            PromptBuilder.diagnostic(p),
            PromptBuilder.roadmap(p),
            PromptBuilder.weekDetail(p, sampleWeek, emptyList()),
            PromptBuilder.quiz(p, sampleTopic, 0.5),
            PromptBuilder.remediation(p, sampleTopic, listOf("sample question")),
            PromptBuilder.weeklyReview(p, sampleWeek, mapOf("Design Patterns" to 0.8), 5, 7),
            PromptBuilder.translate("hi", listOf("Unit 1", "Unit 2")),
            PromptBuilder.mentor(p, "Context", emptyList(), "Hello"),
        )

        val forbidden = listOf("Digital logic", "RTL", "adder")
        prompts.forEach { pr ->
            forbidden.forEach { word ->
                assertTrue(!pr.text.contains(word), "Prompt template leaked forbidden string '$word'")
            }
        }
    }

    @Test fun resourceCatalogMatchesByTags() {
        val cat = ResourceCatalog("""[{"title":"HDLBits","provider":"HDLBits","url":"https://hdlbits.01xz.net","type":"practice","tags":["verilog","digital logic","rtl"]},
            {"title":"Khan Academy Algebra","provider":"Khan Academy","url":"https://www.khanacademy.org","tags":["algebra","math"]}]""")
        val rec = cat.recommend("Verilog basics", "RTL design", "VLSI engineer")
        assertEquals("HDLBits", rec.first().title)
    }

    @Test fun planChangeParserQuickActionChips() {
        val p = profile().copy(weeks = 8, hoursPerWeek = 6, level = 1)

        val fewerWeeks = PlanChangeParser.parse("Fewer weeks", p)
        assertNotNull(fewerWeeks)
        assertEquals(6, fewerWeeks!!.updatedProfile.weeks)

        val moreWeeks = PlanChangeParser.parse("More weeks", p)
        assertNotNull(moreWeeks)
        assertEquals(10, moreWeeks!!.updatedProfile.weeks)

        val moreHours = PlanChangeParser.parse("More hours/week", p)
        assertNotNull(moreHours)
        assertEquals(8, moreHours!!.updatedProfile.hoursPerWeek)

        val fewerHours = PlanChangeParser.parse("Fewer hours/week", p)
        assertNotNull(fewerHours)
        assertEquals(4, fewerHours!!.updatedProfile.hoursPerWeek)

        val easier = PlanChangeParser.parse("Make it easier", p)
        assertNotNull(easier)
        assertEquals(0, easier!!.updatedProfile.level)

        val harder = PlanChangeParser.parse("Make it harder", p)
        assertNotNull(harder)
        assertEquals(2, harder!!.updatedProfile.level)
    }

    @Test fun planChangeParserTypedRegexes() {
        val p = profile().copy(weeks = 8, hoursPerWeek = 6, level = 1)

        val wMatch = PlanChangeParser.parse("I want 12 weeks to finish this", p)
        assertNotNull(wMatch)
        assertEquals(12, wMatch!!.updatedProfile.weeks)

        val hMatch = PlanChangeParser.parse("can study 15 hours per week", p)
        assertNotNull(hMatch)
        assertEquals(15, hMatch!!.updatedProfile.hoursPerWeek)

        val easierMatch = PlanChangeParser.parse("This is too difficult, make it easier", p)
        assertNotNull(easierMatch)
        assertEquals(0, easierMatch!!.updatedProfile.level)

        val harderMatch = PlanChangeParser.parse("I want harder material", p)
        assertNotNull(harderMatch)
        assertEquals(2, harderMatch!!.updatedProfile.level)

        val combined = PlanChangeParser.parse("Can we do 4 weeks and 10 hours and make it harder?", p)
        assertNotNull(combined)
        assertEquals(4, combined!!.updatedProfile.weeks)
        assertEquals(10, combined.updatedProfile.hoursPerWeek)
        assertEquals(2, combined.updatedProfile.level)
    }

    @Test fun planChangeParserIgnoresNonPlanMessages() {
        val p = profile()
        assertNull(PlanChangeParser.parse("What is digital logic?", p))
        assertNull(PlanChangeParser.parse("Explain week 2 to me", p))
        assertNull(PlanChangeParser.parse("Hello mentor, can you help me study?", p))
        assertNull(PlanChangeParser.parse("How many days should I spend on each unit?", p))
    }

    @Test fun roadmapDiffComputesChanges() {
        val w1 = Week(1, "Fundamentals", "Goal 1", emptyList(), "M1")
        val w2Old = Week(2, "Basic Logic", "Goal 2", emptyList(), "M2")
        val w2New = Week(2, "Advanced Logic", "Goal 2", emptyList(), "M2")
        val w3 = Week(3, "Projects", "Goal 3", emptyList(), "M3")
        val w4 = Week(4, "Capstone", "Goal 4", emptyList(), "M4")

        val oldRoadmap = Roadmap("Goal", "Summary", gaps = emptyList(), weeks = listOf(w1, w2Old, w3), source = "llm", version = 1)
        val newRoadmap = Roadmap("Goal", "Summary", gaps = emptyList(), weeks = listOf(w1, w2New, w4), source = "llm", version = 2)

        val diff = RoadmapDiff.compute(oldRoadmap, newRoadmap)
        assertEquals(1, diff.oldVersion)
        assertEquals(2, diff.newVersion)
        assertEquals(listOf("Unit 4: Capstone"), diff.addedUnits)
        assertEquals(listOf("Unit 3: Projects"), diff.removedUnits)
        assertEquals(listOf("Unit 2: 'Basic Logic' → 'Advanced Logic'"), diff.changedUnits)
        assertFalse(diff.isEmpty)
    }

    private fun profile() = LearnerProfile("Asha", GoalType.CAREER, "Become a VLSI design engineer", 8, 8, 1,
        listOf("C"), listOf("electronics"), "en", mapOf("Digital logic" to 0.0, "Verilog" to 1.0))
}
