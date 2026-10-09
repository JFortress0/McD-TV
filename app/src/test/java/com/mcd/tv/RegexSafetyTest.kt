package com.mcd.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * Every regex literal in the app must also compile on Android's ICU engine (see [IcuRegexLint] for the rules).
 * The JVM cannot run ICU here, so the scan enforces the subset that is safe on both engines, and also
 * compiles each pattern with java.util.regex to catch plain syntax errors.
 */
class RegexSafetyTest {

    @Test
    fun allMainSourceRegexesAreIcuSafe() {
        val root = IcuRegexLint.mainSourceRoot()
        assertNotNull("main sources not found (run from the repo root or the app module)", root)
        val found = IcuRegexLint.scan(root!!)
        // Guard against the extractor silently finding nothing (the app has dozens of literal regexes).
        assertTrue("only ${found.size} regex literals found; the extractor is probably broken", found.size >= 20)

        val problems = ArrayList<String>()
        for (f in found) {
            IcuRegexLint.check(f.pattern).forEach { problems += IcuRegexLint.Problem(f, it).toString() }
            try {
                Pattern.compile(f.pattern)
            } catch (e: PatternSyntaxException) {
                problems += IcuRegexLint.Problem(f, "does not compile on the JVM either: ${e.description}").toString()
            }
        }
        if (problems.isNotEmpty()) fail("Regexes Android (ICU) may reject:\n" + problems.joinToString("\n"))
    }

    // ------------------------------------------------------------------ the checker itself

    private fun ok(p: String) = assertEquals("expected no problems for: $p", emptyList<String>(), IcuRegexLint.check(p))
    private fun bad(p: String) = assertTrue("expected a problem for: $p", IcuRegexLint.check(p).isNotEmpty())

    @Test
    fun flagsTheHistoricalCrash() {
        // Kotlin "\\(\\s*\\)|\\[\\s*]|\\{\\s*}" at runtime: the ']' and '}' are bare. This crashed the app on ICU.
        bad("""\(\s*\)|\[\s*]|\{\s*}""")
        ok("""\(\s*\)|\[\s*\]|\{\s*\}""")
    }

    @Test
    fun acceptsQuantifiersClassesAndEscapes() {
        ok("""\s{2,}""")
        ok("""[a-z0-9]{10,40}""")
        ok("""x{3}y{1,}?""")
        ok("""[\p{L}&][\p{L}&+\- ]{0,15}?""")
        ok("""[|:\])]""") // escaped ']' inside a class, then the real close
        ok("""[\s|:;,./\\\-–—•·*#=_~★☆●◉◄<(\[]+$""")
        ok("""\Q]}\E""") // quoted literal text
        ok("""\x{1F600}\N{BULLET}""")
        ok("""(?<![A-Z0-9])(?:[KW][A-Z]{2,3})""")
        ok("""[a-z&&[^aeiou]]""")
    }

    @Test
    fun flagsBareBracketsAndBraces() {
        bad("a]")
        bad("a}")
        bad("{abc}")
        bad("a{,3}")
        bad("[]a]")
        bad("[^]a]")
        bad("[{}]")
        bad("[abc")
    }

    @Test
    fun extractsKotlinLiteralsLikeTheCompilerDoes() {
        val src = """
            val a = Regex("\\(\\s*\\)|\\[\\s*]")
            val b = Regex(${"\"\"\""}\d+]${"\"\"\""}, RegexOption.IGNORE_CASE)
            val c = "x}".toRegex()
            val d = Regex(
                "one" +
                    "two]",
            )
            val e = Pattern.compile("\\{\\s*}")
            // Regex("]") in a comment is ignored
            /* Regex("}") in a /* nested */ block comment too */
            val q = '"'
            val f = Regex("s0*${'$'}season[ ._-]?e0*${'$'}{episode}x{${'$'}min,${'$'}max}")
            val g = Regex(pattern = "ok\\]")
            val notARegex = "a]b"
        """.trimIndent()
        val found = IcuRegexLint.extract("Sample.kt", src).map { it.pattern }
        assertEquals(
            listOf(
                """\(\s*\)|\[\s*]""",
                """\d+]""",
                "x}",
                "onetwo]",
                """\{\s*}""",
                "s0*0[ ._-]?e0*0x{0,0}",
                """ok\]""",
            ),
            found,
        )
        // Lines are 1-based and point at the literal.
        assertEquals(listOf(1, 2, 3, 5, 8, 12, 13), IcuRegexLint.extract("Sample.kt", src).map { it.line })
        // Templates become "0", so a templated quantifier is still valid.
        assertEquals(emptyList<String>(), IcuRegexLint.check("s0*0[ ._-]?e0*0x{0,0}"))
    }
}
