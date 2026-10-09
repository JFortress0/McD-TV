package com.mcd.tv

import java.io.File

/**
 * Finds regex string literals in Kotlin sources and flags syntax that java.util.regex accepts but Android's
 * ICU regex engine rejects at runtime (PatternSyntaxException -> crash the first time the Regex is built).
 *
 * Why this exists: `Regex("\\(\\s*\\)|\\[\\s*]|\\{\\s*}")` passed every JVM check and crashed the app on the TV,
 * because ICU treats an unescaped ']' or '}' outside a character class / quantifier as an error, while Java
 * quietly reads them as literals. Escaping them ("\\]", "\\}") is valid in both engines, so the rule is simple:
 *
 *   - ']' must be escaped unless it closes a character class '[...]'.
 *   - '}' must be escaped unless it closes a quantifier '{n}', '{n,}', '{n,m}' or a '\p{..}' / '\x{..}' escape.
 *   - '{' must start one of those quantifiers (Java rejects other '{' too, so this only makes the message clearer).
 *   - Inside a class, '{' and '}' must be escaped too (ICU sets give braces a meaning of their own), and a ']' right
 *     after '[' or '[^' is flagged (Java reads it as a literal, other engines as an empty class).
 *
 * This is a best-effort lexer, not a Kotlin parser. It understands:
 *   - "..." strings (Kotlin escapes \\ \" \$ \n \t \r \b \' \uXXXX) and """raw""" strings (no escapes),
 *   - string templates ($name, ${expr}): replaced by "0", which keeps "{$min,$max}" a valid quantifier,
 *   - // and nested /* */ comments and 'c' char literals (so quotes inside them are not strings),
 *   - literals joined with '+' ("a" + "b") as one pattern.
 * A literal counts as a regex when it is the first argument of Regex(...) or Pattern.compile(...)
 * (also as the named argument Regex(pattern = ...)), or when it is followed by .toRegex() / .toPattern().
 * Patterns built from variables cannot be checked statically and are skipped.
 */
object IcuRegexLint {

    /** A regex literal found in a file: [line] is 1-based, [pattern] is the runtime string (after Kotlin escapes). */
    data class Found(val file: String, val line: Int, val pattern: String)

    data class Problem(val found: Found, val message: String) {
        override fun toString() = "${found.file}:${found.line}: $message\n    pattern: ${found.pattern}"
    }

    // ------------------------------------------------------------------ Kotlin lexing

    private class Lit(val start: Int, val end: Int, val value: String)

    /** Every string literal in [src] (outside comments), in order, with decoded values. */
    private fun literals(src: String): List<Lit> {
        val out = ArrayList<Lit>()
        var i = 0
        val n = src.length
        while (i < n) {
            val c = src[i]
            when {
                src.startsWith("//", i) -> { i = src.indexOf('\n', i).let { if (it < 0) n else it } }
                src.startsWith("/*", i) -> i = skipBlockComment(src, i)
                c == '\'' -> i = skipCharLiteral(src, i)
                c == '"' -> {
                    val sb = StringBuilder()
                    val end = readString(src, i, sb)
                    out.add(Lit(i, end, sb.toString()))
                    i = end
                }
                else -> i++
            }
        }
        return out
    }

    private fun skipBlockComment(src: String, from: Int): Int {
        var depth = 0
        var i = from
        while (i < src.length) {
            if (src.startsWith("/*", i)) { depth++; i += 2; continue }
            if (src.startsWith("*/", i)) { depth--; i += 2; if (depth == 0) return i; continue }
            i++
        }
        return src.length
    }

    /** 'a', '\n', 'é', '"'. Falls back to one character when it does not look like a char literal. */
    private fun skipCharLiteral(src: String, from: Int): Int {
        var i = from + 1
        if (i < src.length && src[i] == '\\') i += if (src.startsWith("u", i + 1)) 6 else 2 else i += 1
        return if (i < src.length && src[i] == '\'') i + 1 else from + 1
    }

    /** Reads a string literal starting at [from] (a '"'), appends its value to [sb], returns the index after it. */
    private fun readString(src: String, from: Int, sb: StringBuilder): Int {
        val raw = src.startsWith("\"\"\"", from)
        var i = from + if (raw) 3 else 1
        val n = src.length
        while (i < n) {
            val c = src[i]
            if (raw) {
                if (src.startsWith("\"\"\"", i)) {
                    // """a"""" ends with the last three quotes; extra quotes belong to the content.
                    var j = i + 3
                    while (j < n && src[j] == '"') { sb.append('"'); j++; i++ }
                    return i + 3
                }
            } else {
                if (c == '"') return i + 1
                if (c == '\n') return i // unterminated: stop at the line end
                if (c == '\\' && i + 1 < n) {
                    val e = src[i + 1]
                    when (e) {
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r' -> sb.append('\r')
                        'b' -> sb.append('\b')
                        'u' -> {
                            val hex = src.substring(i + 2, minOf(n, i + 6))
                            sb.append(hex.toIntOrNull(16)?.toChar() ?: '?')
                            i += 6
                            continue
                        }
                        else -> sb.append(e) // \\ \" \$ \'
                    }
                    i += 2
                    continue
                }
            }
            if (c == '$' && i + 1 < n) {
                val d = src[i + 1]
                if (d == '{') {
                    i = skipTemplate(src, i + 1)
                    sb.append('0')
                    continue
                }
                if (d.isLetter() || d == '_') {
                    i++
                    while (i < n && (src[i].isLetterOrDigit() || src[i] == '_')) i++
                    sb.append('0')
                    continue
                }
            }
            sb.append(c)
            i++
        }
        return n
    }

    /** Skips "${ ... }" starting at the '{' (nested braces and strings inside are skipped too). */
    private fun skipTemplate(src: String, open: Int): Int {
        var depth = 0
        var i = open
        while (i < src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return i + 1 }
                '"' -> { i = readString(src, i, StringBuilder()); continue }
                '\'' -> { i = skipCharLiteral(src, i); continue }
            }
            i++
        }
        return src.length
    }

    private val CALL_BEFORE = Regex("""(?:\bRegex|\bPattern\s*\.\s*compile)\s*\(\s*(?:pattern\s*=\s*|regex\s*=\s*)?$""")
    private val CALL_AFTER = Regex("""^\s*\.\s*(?:toRegex|toPattern)\s*\(""")
    private val PLUS_BETWEEN = Regex("""^\s*\+\s*$""")

    /** Regex literals in one Kotlin source text. */
    fun extract(fileName: String, src: String): List<Found> {
        val lits = literals(src)
        val out = ArrayList<Found>()
        var k = 0
        while (k < lits.size) {
            // Join "a" + "b" + "c" into one pattern.
            var last = k
            val value = StringBuilder(lits[k].value)
            while (last + 1 < lits.size && PLUS_BETWEEN.matches(src.substring(lits[last].end, lits[last + 1].start))) {
                last++
                value.append(lits[last].value)
            }
            val before = src.substring(maxOf(0, lits[k].start - 80), lits[k].start)
            val after = src.substring(lits[last].end, minOf(src.length, lits[last].end + 40))
            if (CALL_BEFORE.containsMatchIn(before) || CALL_AFTER.containsMatchIn(after)) {
                val line = 1 + src.substring(0, lits[k].start).count { it == '\n' }
                out.add(Found(fileName, line, value.toString()))
            }
            k = last + 1
        }
        return out
    }

    // ------------------------------------------------------------------ pattern check

    private val QUANTIFIER = Regex("""\{\d+(?:,\d*)?\}""")

    /** Problems ICU would have with [p] (empty when it is safe). */
    fun check(p: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        var classDepth = 0
        val n = p.length
        fun afterClassOpen(at: Int): Int {
            var j = at
            if (j < n && p[j] == '^') j++
            if (j < n && p[j] == ']') out.add("']' right after '[' at index $j: escape it as \\]")
            return j
        }
        while (i < n) {
            val c = p[i]
            if (c == '\\') {
                if (i + 1 >= n) { out.add("pattern ends with a lone backslash"); break }
                val d = p[i + 1]
                when {
                    d == 'Q' -> { val e = p.indexOf("\\E", i + 2); i = if (e < 0) n else e + 2 }
                    (d == 'p' || d == 'P' || d == 'x' || d == 'N') && i + 2 < n && p[i + 2] == '{' -> {
                        val close = p.indexOf('}', i + 3)
                        if (close < 0) { out.add("unclosed \\$d{ at index $i"); i = n } else i = close + 1
                    }
                    d == 'k' && i + 2 < n && p[i + 2] == '<' -> {
                        val close = p.indexOf('>', i + 3)
                        i = if (close < 0) n else close + 1
                    }
                    else -> i += 2
                }
                continue
            }
            if (classDepth > 0) {
                when (c) {
                    '[' -> { classDepth++; i = afterClassOpen(i + 1); continue }
                    ']' -> classDepth--
                    '{', '}' -> out.add("unescaped '$c' inside a character class at index $i: escape it as \\$c")
                }
                i++
                continue
            }
            when (c) {
                '[' -> { classDepth = 1; i = afterClassOpen(i + 1); continue }
                '{' -> {
                    val m = QUANTIFIER.matchAt(p, i)
                    if (m != null) { i = m.range.last + 1; continue }
                    out.add("unescaped '{' at index $i that does not start a {n,m} quantifier: escape it as \\{")
                }
                '}' -> out.add("unescaped '}' at index $i (not closing a {n,m} quantifier): escape it as \\} (ICU rejects it)")
                ']' -> out.add("unescaped ']' at index $i (not closing a [...] class): escape it as \\] (ICU rejects it)")
            }
            i++
        }
        if (classDepth > 0) out.add("unclosed character class '['")
        return out
    }

    /** Main Kotlin sources: src/main/java from the module dir (Gradle) or app/src/main/java from the repo root. */
    fun mainSourceRoot(): File? {
        System.getProperty("jarvis.mainSrc")?.let { return File(it).takeIf { f -> f.isDirectory } }
        return listOf(File("src/main/java"), File("app/src/main/java")).firstOrNull { it.isDirectory }
    }

    fun scan(root: File): List<Found> =
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.flatMap { f ->
            extract(f.relativeTo(root).path, f.readText())
        }.toList()
}
