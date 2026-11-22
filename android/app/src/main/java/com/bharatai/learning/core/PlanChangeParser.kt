package com.bharatai.learning.core

data class ProposedPlanChange(
    val originalProfile: LearnerProfile,
    val updatedProfile: LearnerProfile,
)

data class RoadmapDiff(
    val oldVersion: Int,
    val newVersion: Int,
    val addedUnits: List<String>,
    val removedUnits: List<String>,
    val changedUnits: List<String>,
) {
    val isEmpty: Boolean get() = addedUnits.isEmpty() && removedUnits.isEmpty() && changedUnits.isEmpty()

    companion object {
        fun compute(old: Roadmap, new: Roadmap): RoadmapDiff {
            val oldMap = old.weeks.associateBy { it.index }
            val newMap = new.weeks.associateBy { it.index }

            val maxIndex = maxOf(
                old.weeks.maxOfOrNull { it.index } ?: 0,
                new.weeks.maxOfOrNull { it.index } ?: 0
            )
            val added = mutableListOf<String>()
            val removed = mutableListOf<String>()
            val changed = mutableListOf<String>()

            for (i in 1..maxIndex) {
                val o = oldMap[i]
                val n = newMap[i]
                when {
                    o == null && n != null -> added.add("Unit $i: ${n.title}")
                    o != null && n == null -> removed.add("Unit $i: ${o.title}")
                    o != null && n != null && o.title.trim() != n.title.trim() ->
                        changed.add("Unit $i: '${o.title}' → '${n.title}'")
                }
            }

            return RoadmapDiff(
                oldVersion = old.version,
                newVersion = new.version,
                addedUnits = added,
                removedUnits = removed,
                changedUnits = changed,
            )
        }
    }
}

object PlanChangeParser {
    // Quick-action chips
    const val CHIP_FEWER_WEEKS = "Fewer weeks"
    const val CHIP_MORE_WEEKS = "More weeks"
    const val CHIP_MORE_HOURS = "More hours/week"
    const val CHIP_FEWER_HOURS = "Fewer hours/week"
    const val CHIP_MAKE_EASIER = "Make it easier"
    const val CHIP_MAKE_HARDER = "Make it harder"

    val QUICK_ACTION_CHIPS = listOf(
        CHIP_FEWER_WEEKS,
        CHIP_MORE_WEEKS,
        CHIP_MORE_HOURS,
        CHIP_FEWER_HOURS,
        CHIP_MAKE_EASIER,
        CHIP_MAKE_HARDER,
    )

    // Regexes for typed requests (English digits)
    val WEEKS_REGEX = Regex("""(\d+)\s*weeks?""", RegexOption.IGNORE_CASE)
    val HOURS_REGEX = Regex("""(\d+)\s*hours?(?:/week)?""", RegexOption.IGNORE_CASE)
    val EASIER_REGEX = Regex("""\beasier\b""", RegexOption.IGNORE_CASE)
    val HARDER_REGEX = Regex("""\bharder\b""", RegexOption.IGNORE_CASE)

    // Relative week and hour phrases
    private val FEWER_WEEKS_REGEX = Regex("""\b(?:fewer|less|reduce|decrease)\s+weeks?\b""", RegexOption.IGNORE_CASE)
    private val MORE_WEEKS_REGEX = Regex("""\b(?:more|increase|add|extra)\s+weeks?\b""", RegexOption.IGNORE_CASE)
    private val MORE_HOURS_REGEX = Regex("""\b(?:more|increase|add|extra)\s+hours?(?:/week)?\b""", RegexOption.IGNORE_CASE)
    private val FEWER_HOURS_REGEX = Regex("""\b(?:fewer|less|reduce|decrease)\s+hours?(?:/week)?\b""", RegexOption.IGNORE_CASE)

    fun parse(input: String, current: LearnerProfile): ProposedPlanChange? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null

        var weeks = current.weeks
        var hours = current.hoursPerWeek
        var level = current.level
        var matched = false

        when {
            trimmed.equals(CHIP_FEWER_WEEKS, ignoreCase = true) -> {
                weeks = (weeks - 2).coerceAtLeast(1)
                matched = true
            }
            trimmed.equals(CHIP_MORE_WEEKS, ignoreCase = true) -> {
                weeks = (weeks + 2).coerceAtMost(52)
                matched = true
            }
            trimmed.equals(CHIP_MORE_HOURS, ignoreCase = true) -> {
                hours = (hours + 2).coerceAtMost(80)
                matched = true
            }
            trimmed.equals(CHIP_FEWER_HOURS, ignoreCase = true) -> {
                hours = (hours - 2).coerceAtLeast(1)
                matched = true
            }
            trimmed.equals(CHIP_MAKE_EASIER, ignoreCase = true) -> {
                level = (level - 1).coerceAtLeast(0)
                matched = true
            }
            trimmed.equals(CHIP_MAKE_HARDER, ignoreCase = true) -> {
                level = (level + 1).coerceAtMost(3)
                matched = true
            }
            else -> {
                val weeksMatch = WEEKS_REGEX.find(trimmed)
                if (weeksMatch != null) {
                    val w = weeksMatch.groupValues[1].toIntOrNull()
                    if (w != null) {
                        weeks = w.coerceIn(1, 52)
                        matched = true
                    }
                } else if (FEWER_WEEKS_REGEX.containsMatchIn(trimmed)) {
                    weeks = (weeks - 2).coerceAtLeast(1)
                    matched = true
                } else if (MORE_WEEKS_REGEX.containsMatchIn(trimmed)) {
                    weeks = (weeks + 2).coerceAtMost(52)
                    matched = true
                }

                val hoursMatch = HOURS_REGEX.find(trimmed)
                if (hoursMatch != null) {
                    val h = hoursMatch.groupValues[1].toIntOrNull()
                    if (h != null) {
                        hours = h.coerceIn(1, 80)
                        matched = true
                    }
                } else if (MORE_HOURS_REGEX.containsMatchIn(trimmed)) {
                    hours = (hours + 2).coerceAtMost(80)
                    matched = true
                } else if (FEWER_HOURS_REGEX.containsMatchIn(trimmed)) {
                    hours = (hours - 2).coerceAtLeast(1)
                    matched = true
                }

                if (EASIER_REGEX.containsMatchIn(trimmed)) {
                    level = (level - 1).coerceAtLeast(0)
                    matched = true
                }
                if (HARDER_REGEX.containsMatchIn(trimmed)) {
                    level = (level + 1).coerceAtMost(3)
                    matched = true
                }
            }
        }

        if (!matched) return null

        val updated = current.copy(
            weeks = weeks,
            hoursPerWeek = hours,
            level = level,
        )
        return ProposedPlanChange(current, updated)
    }
}
