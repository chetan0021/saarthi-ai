package com.bharatai.learning.core

/**
 * Resource recommendations come from a curated offline catalog (assets/resources.json),
 * not from the LLM, so the app never shows a hallucinated link. Matching is a
 * keyword-overlap score between the topic/skill/goal and each resource's tags.
 */
data class Resource(val title: String, val provider: String, val url: String, val type: String, val tags: List<String>, val free: Boolean)

class ResourceCatalog(json: String) {
    val items: List<Resource> = (LenientJson.parse(json) as? JsonValue.Arr)?.list.orEmpty().mapNotNull {
        val title = it.str("title").ifEmpty { return@mapNotNull null }
        Resource(title, it.str("provider"), it.str("url"), it.str("type", "course"),
            it.strList("tags").map { t -> t.lowercase() }, (it["free"] as? JsonValue.Bool)?.value ?: true)
    }

    private fun tokens(s: String) = s.lowercase().split(Regex("[^\\p{L}\\p{N}+#]+")).filter { it.length > 1 }.toSet()

    fun recommend(topic: String, skill: String, goal: String, limit: Int = 3): List<Resource> {
        val q = tokens(topic) + tokens(skill)
        val g = tokens(goal)
        return items.map { r ->
            val tagTokens = r.tags.flatMap { tokens(it) }.toSet()
            val phraseHit = r.tags.count { t -> topic.lowercase().contains(t) || skill.lowercase().contains(t) }
            val score = 3.0 * phraseHit + 2.0 * (q intersect tagTokens).size + 0.5 * (g intersect tagTokens).size
            r to score
        }.filter { it.second >= 2.0 }.sortedByDescending { it.second }.take(limit).map { it.first }
    }
}
