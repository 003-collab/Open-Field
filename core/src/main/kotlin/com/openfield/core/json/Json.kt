package com.openfield.core.json

/**
 * Tiny dependency-free JSON reader/writer used for jobs, device profiles,
 * coordinate systems, themes and layouts. Values map to:
 * Map<String, Any?>, List<Any?>, String, Long, Double, Boolean, null.
 */
class JsonException(message: String) : Exception(message)

object Json {
    fun parse(text: String): Any? = Parser(text).parseDocument()

    fun parseObject(text: String): Map<String, Any?> = parse(text).asObj()

    fun stringify(value: Any?, pretty: Boolean = true): String {
        val sb = StringBuilder()
        write(sb, value, pretty, 0)
        return sb.toString()
    }

    private fun write(sb: StringBuilder, v: Any?, pretty: Boolean, depth: Int) {
        when (v) {
            null -> sb.append("null")
            is String -> writeString(sb, v)
            is Boolean -> sb.append(v)
            is Int, is Long, is Short, is Byte -> sb.append(v.toString())
            is Float, is Double -> {
                val d = (v as Number).toDouble()
                if (d.isNaN() || d.isInfinite()) sb.append("null") else sb.append(d.toString())
            }
            is Map<*, *> -> {
                if (v.isEmpty()) { sb.append("{}"); return }
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(',')
                    first = false
                    if (pretty) { sb.append('\n'); indent(sb, depth + 1) }
                    writeString(sb, k.toString())
                    sb.append(if (pretty) ": " else ":")
                    write(sb, value, pretty, depth + 1)
                }
                if (pretty) { sb.append('\n'); indent(sb, depth) }
                sb.append('}')
            }
            is Iterable<*> -> {
                val items = v.toList()
                if (items.isEmpty()) { sb.append("[]"); return }
                // short primitive arrays stay on one line
                val simple = items.all { it == null || it is Number || it is Boolean || (it is String && it.length < 24) }
                sb.append('[')
                items.forEachIndexed { i, item ->
                    if (i > 0) sb.append(if (pretty && simple) ", " else ",")
                    if (pretty && !simple) { sb.append('\n'); indent(sb, depth + 1) }
                    write(sb, item, pretty, depth + 1)
                }
                if (pretty && !simple) { sb.append('\n'); indent(sb, depth) }
                sb.append(']')
            }
            is Array<*> -> write(sb, v.toList(), pretty, depth)
            else -> writeString(sb, v.toString())
        }
    }

    private fun indent(sb: StringBuilder, depth: Int) { repeat(depth) { sb.append("  ") } }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    private class Parser(val s: String) {
        var i = 0

        fun parseDocument(): Any? {
            skipWs()
            val v = parseValue()
            skipWs()
            if (i != s.length) fail("Unexpected trailing characters")
            return v
        }

        fun fail(msg: String): Nothing = throw JsonException("$msg at position $i")

        fun skipWs() {
            while (i < s.length) {
                val c = s[i]
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') i++
                else if (c == '/' && i + 1 < s.length && s[i + 1] == '/') { // allow // comments in hand-edited profiles
                    while (i < s.length && s[i] != '\n') i++
                } else break
            }
        }

        fun parseValue(): Any? {
            if (i >= s.length) fail("Unexpected end")
            return when (val c = s[i]) {
                '{' -> parseObj()
                '[' -> parseArr()
                '"' -> parseStr()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) parseNum() else fail("Unexpected character '$c'")
            }
        }

        fun lit(word: String, value: Any?): Any? {
            if (!s.startsWith(word, i)) fail("Invalid literal")
            i += word.length
            return value
        }

        fun parseObj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++ // {
            skipWs()
            if (i < s.length && s[i] == '}') { i++; return m }
            while (true) {
                skipWs()
                if (i >= s.length || s[i] != '"') fail("Expected string key")
                val k = parseStr()
                skipWs()
                if (i >= s.length || s[i] != ':') fail("Expected ':'")
                i++
                skipWs()
                m[k] = parseValue()
                skipWs()
                if (i >= s.length) fail("Unterminated object")
                if (s[i] == ',') { i++; continue }
                if (s[i] == '}') { i++; return m }
                fail("Expected ',' or '}'")
            }
        }

        fun parseArr(): List<Any?> {
            val l = ArrayList<Any?>()
            i++ // [
            skipWs()
            if (i < s.length && s[i] == ']') { i++; return l }
            while (true) {
                skipWs()
                l.add(parseValue())
                skipWs()
                if (i >= s.length) fail("Unterminated array")
                if (s[i] == ',') { i++; continue }
                if (s[i] == ']') { i++; return l }
                fail("Expected ',' or ']'")
            }
        }

        fun parseStr(): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) fail("Unterminated string")
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) fail("Bad escape")
                        when (val e = s[i++]) {
                            '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                            'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000c')
                            'u' -> {
                                if (i + 4 > s.length) fail("Bad unicode escape")
                                sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4
                            }
                            else -> fail("Bad escape '\\$e'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun parseNum(): Any {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            val t = s.substring(start, i)
            return if (t.any { it == '.' || it == 'e' || it == 'E' }) t.toDoubleOrNull() ?: fail("Bad number '$t'")
            else t.toLongOrNull() ?: t.toDoubleOrNull() ?: fail("Bad number '$t'")
        }
    }
}

// ---- Lenient accessors so profile/theme loaders stay short and tolerate hand-edited files ----

@Suppress("UNCHECKED_CAST")
fun Any?.asObj(): Map<String, Any?> = (this as? Map<String, Any?>) ?: emptyMap()
fun Any?.asList(): List<Any?> = (this as? List<Any?>) ?: emptyList()
fun Any?.asStr(def: String = ""): String = (this as? String) ?: def
fun Any?.asStrOrNull(): String? = this as? String
fun Any?.asDouble(def: Double = 0.0): Double = (this as? Number)?.toDouble() ?: def
fun Any?.asDoubleOrNull(): Double? = (this as? Number)?.toDouble()
fun Any?.asInt(def: Int = 0): Int = (this as? Number)?.toInt() ?: def
fun Any?.asLong(def: Long = 0L): Long = (this as? Number)?.toLong() ?: def
fun Any?.asBool(def: Boolean = false): Boolean = (this as? Boolean) ?: def
fun Any?.asStrList(): List<String> = asList().mapNotNull { it as? String }
