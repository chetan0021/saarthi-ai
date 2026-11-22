package com.bharatai.learning.core

/**
 * Crash-free JSON layer for small on-device LLM output.
 *
 * A 1B model occasionally wraps JSON in prose or code fences, leaves trailing commas,
 * uses single quotes, or gets truncated when it hits the token budget. Instead of
 * throwing, this parser:
 *   1. extracts the first top-level JSON object/array from arbitrary text,
 *   2. tolerates common syntax slips (trailing commas, single quotes, unquoted keys,
 *      smart quotes, comments, NaN/None/True/False),
 *   3. auto-closes strings/arrays/objects that were cut off mid-stream.
 * Pure Kotlin (no Android deps) so it is unit-tested on the JVM.
 */
sealed class JsonValue {
    data class Obj(val map: LinkedHashMap<String, JsonValue>) : JsonValue()
    data class Arr(val list: List<JsonValue>) : JsonValue()
    data class Str(val value: String) : JsonValue()
    data class Num(val value: Double) : JsonValue()
    data class Bool(val value: Boolean) : JsonValue()
    object Null : JsonValue()

    operator fun get(key: String): JsonValue? = (this as? Obj)?.map?.let { m ->
        m[key] ?: m.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value
    }

    fun str(key: String, default: String = ""): String = when (val v = get(key)) {
        is Str -> v.value.trim()
        is Num -> if (v.value == Math.floor(v.value)) v.value.toLong().toString() else v.value.toString()
        is Bool -> v.value.toString()
        else -> default
    }

    fun num(key: String, default: Double = 0.0): Double = when (val v = get(key)) {
        is Num -> v.value
        is Str -> v.value.trim().trimEnd('%', 'h', 'H').trim().toDoubleOrNull() ?: default
        else -> default
    }

    fun int(key: String, default: Int = 0): Int = num(key, default.toDouble()).toInt()

    fun arr(key: String): List<JsonValue> = when (val v = get(key)) {
        is Arr -> v.list
        null, is Null -> emptyList()
        else -> listOf(v)
    }

    fun strList(key: String): List<String> = arr(key).mapNotNull {
        when (it) {
            is Str -> it.value.trim().takeIf { s -> s.isNotEmpty() }
            is Num -> it.value.toString()
            is Obj -> (it.str("title").ifEmpty { it.str("name") }).takeIf { s -> s.isNotEmpty() }
            else -> null
        }
    }

    fun toJson(): String = buildString { write(this@JsonValue, this) }

    companion object {
        private fun write(v: JsonValue, sb: StringBuilder) {
            when (v) {
                is Obj -> {
                    sb.append('{')
                    var first = true
                    for ((k, value) in v.map) {
                        if (!first) sb.append(',')
                        first = false
                        writeString(k, sb); sb.append(':'); write(value, sb)
                    }
                    sb.append('}')
                }
                is Arr -> {
                    sb.append('[')
                    v.list.forEachIndexed { i, e -> if (i > 0) sb.append(','); write(e, sb) }
                    sb.append(']')
                }
                is Str -> writeString(v.value, sb)
                is Num -> if (v.value == Math.floor(v.value) && !v.value.isInfinite() && Math.abs(v.value) < 1e15)
                    sb.append(v.value.toLong()) else sb.append(v.value)
                is Bool -> sb.append(v.value)
                Null -> sb.append("null")
            }
        }

        private fun writeString(s: String, sb: StringBuilder) {
            sb.append('"')
            for (c in s) when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
            sb.append('"')
        }
    }
}

object LenientJson {

    /** Parse the first JSON object/array found in [raw]; null only if no '{' or '[' exists. */
    fun parse(raw: String): JsonValue? {
        val start = findStart(raw) ?: return null
        val text = normalise(raw.substring(start))
        return try {
            Parser(text).parseValue()
        } catch (e: Exception) {
            null
        }
    }

    /** Parse and require an object at top level (wraps a bare array as {"items": [...]}). */
    fun parseObject(raw: String): JsonValue.Obj? = when (val v = parse(raw)) {
        is JsonValue.Obj -> v
        is JsonValue.Arr -> JsonValue.Obj(linkedMapOf("items" to v))
        else -> null
    }

    private fun findStart(raw: String): Int? {
        val fence = raw.indexOf("```")
        val searchFrom = if (fence >= 0) {
            val nl = raw.indexOf('\n', fence)
            if (nl > 0) nl else fence + 3
        } else 0
        val obj = raw.indexOf('{', searchFrom).takeIf { it >= 0 }
        val arr = raw.indexOf('[', searchFrom).takeIf { it >= 0 }
        val best = listOfNotNull(obj, arr).minOrNull()
        if (best != null) return best
        if (searchFrom > 0) { // fence was misleading, scan the whole string
            return listOfNotNull(raw.indexOf('{').takeIf { it >= 0 }, raw.indexOf('[').takeIf { it >= 0 }).minOrNull()
        }
        return null
    }

    private fun normalise(s: String): String = s
        .replace('“', '"').replace('”', '"')
        .replace('‘', '\'').replace('’', '\'')
        .replace(" ", " ")

    private class Parser(val s: String) {
        var i = 0

        fun parseValue(): JsonValue {
            skipWs()
            if (i >= s.length) return JsonValue.Null
            return when (val c = s[i]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"', '\'' -> JsonValue.Str(parseString(c))
                else -> parseBare()
            }
        }

        fun parseObject(): JsonValue.Obj {
            i++ // {
            val map = LinkedHashMap<String, JsonValue>()
            while (true) {
                skipWs()
                if (i >= s.length) return JsonValue.Obj(map) // truncated -> auto-close
                when (s[i]) {
                    '}' -> { i++; return JsonValue.Obj(map) }
                    ',' -> { i++; continue }
                    ']' -> { i++; return JsonValue.Obj(map) } // mismatched close, recover
                }
                val key = when (s[i]) {
                    '"', '\'' -> parseString(s[i])
                    else -> parseBareKey()
                }
                skipWs()
                if (i < s.length && (s[i] == ':' || s[i] == '=')) i++ else {
                    if (i >= s.length) return JsonValue.Obj(map)
                }
                skipWs()
                if (i >= s.length) return JsonValue.Obj(map) // key with no value: drop it
                if (s[i] == '}' || s[i] == ',') { map[key] = JsonValue.Null; continue }
                map[key] = parseValue()
            }
        }

        fun parseArray(): JsonValue.Arr {
            i++ // [
            val list = ArrayList<JsonValue>()
            while (true) {
                skipWs()
                if (i >= s.length) return JsonValue.Arr(list)
                when (s[i]) {
                    ']' -> { i++; return JsonValue.Arr(list) }
                    ',' -> { i++; continue }
                    '}' -> { i++; return JsonValue.Arr(list) }
                }
                val before = i
                list.add(parseValue())
                if (i == before) i++ // guarantee progress
            }
        }

        fun parseString(quote: Char): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    val n = s[i + 1]
                    when (n) {
                        'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                        'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                        'u' -> {
                            val hex = s.substring(i + 2, minOf(i + 6, s.length))
                            val code = hex.toIntOrNull(16)
                            if (code != null && hex.length == 4) { sb.append(code.toChar()); i += 4 }
                        }
                        else -> sb.append(n)
                    }
                    i += 2
                    continue
                }
                if (c == quote) {
                    // A quote followed by something that cannot continue JSON is likely an
                    // unescaped quote inside text (e.g. 5' 4" or a "word"), keep it.
                    if (looksLikeStringEnd(i + 1)) { i++; return sb.toString() }
                    sb.append(c); i++; continue
                }
                sb.append(c); i++
            }
            return sb.toString() // truncated string -> auto-close
        }

        private fun looksLikeStringEnd(from: Int): Boolean {
            var j = from
            while (j < s.length && (s[j] == ' ' || s[j] == '\t' || s[j] == '\r' || s[j] == '\n')) j++
            return j >= s.length || s[j] in ",:}]"
        }

        fun parseBareKey(): String {
            val start = i
            while (i < s.length && s[i] != ':' && s[i] != '=' && s[i] != '}' && s[i] != ',' && !s[i].isWhitespace()) i++
            return s.substring(start, i).trim()
        }

        fun parseBare(): JsonValue {
            val start = i
            while (i < s.length && s[i] !in ",}]\n") i++
            val tok = s.substring(start, i).trim()
            return when (tok.lowercase()) {
                "true" -> JsonValue.Bool(true)
                "false" -> JsonValue.Bool(false)
                "null", "none", "nan", "undefined", "" -> JsonValue.Null
                else -> tok.toDoubleOrNull()?.let { JsonValue.Num(it) } ?: JsonValue.Str(tok)
            }
        }

        fun skipWs() {
            while (i < s.length) {
                val c = s[i]
                if (c.isWhitespace()) { i++; continue }
                if (c == '/' && i + 1 < s.length && s[i + 1] == '/') {
                    while (i < s.length && s[i] != '\n') i++; continue
                }
                if (c == '/' && i + 1 < s.length && s[i + 1] == '*') {
                    val end = s.indexOf("*/", i + 2)
                    i = if (end < 0) s.length else end + 2; continue
                }
                break
            }
        }
    }
}

/**
 * Watches a token stream and reports when the first top-level JSON value is closed,
 * so generation can be stopped immediately (saves tokens = time + battery).
 */
class JsonStreamWatcher {
    private var depth = 0
    private var started = false
    private var inString = false
    private var escape = false
    var complete = false
        private set

    fun feed(chunk: String): Boolean {
        if (complete) return true
        for (c in chunk) {
            if (!started) {
                if (c == '{' || c == '[') { started = true; depth = 1 }
                continue
            }
            if (inString) {
                if (escape) escape = false
                else if (c == '\\') escape = true
                else if (c == '"') inString = false
                continue
            }
            when (c) {
                '"' -> inString = true
                '{', '[' -> depth++
                '}', ']' -> { depth--; if (depth == 0) { complete = true; return true } }
            }
        }
        return false
    }
}
