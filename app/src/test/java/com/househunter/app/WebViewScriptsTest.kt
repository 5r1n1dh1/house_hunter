package com.househunter.app

import com.househunter.app.data.scrape.WebViewScripts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure string tests for [WebViewScripts].
 *
 * These run on a bare JVM because the helper has no Android imports: `WebView.evaluateJavascript`
 * cannot be exercised off-device, so the only thing worth automating here is that the snippets we
 * hand to it are non-empty, reference the DOM globals they claim to, and are syntactically
 * balanced. A typo here would otherwise only show up as "every site scrapes to null" on a phone.
 */
class WebViewScriptsTest {

    @Test
    fun outerHtmlScriptIsNotEmpty() {
        val script = WebViewScripts.outerHtmlScript()
        assertTrue("outerHtmlScript() must not be blank", script.isNotBlank())
    }

    @Test
    fun outerHtmlScriptTargetsTheRenderedDom() {
        val script = WebViewScripts.outerHtmlScript()
        assertTrue(script.contains("document.documentElement.outerHTML"))
        assertTrue("must read the whole document, not just <body>",
            script.contains("document.documentElement ?"))
    }

    @Test
    fun outerHtmlScriptFallsBackInsteadOfThrowing() {
        val script = WebViewScripts.outerHtmlScript()
        assertTrue("a document without a root element must not throw", script.contains("try"))
        assertTrue(script.contains("catch"))
        assertTrue("failure must yield a falsy, scrapable value", script.contains("return ''"))
    }

    @Test
    fun outerHtmlScriptIsSyntacticallyBalanced() {
        assertBalanced(WebViewScripts.outerHtmlScript())
    }

    @Test
    fun pageReadyScriptPublishesTheReadyFlag() {
        val script = WebViewScripts.pageReadyScript()
        assertTrue("pageReadyScript() must not be blank", script.isNotBlank())
        assertTrue("readiness is published on window so a re-check is free",
            script.contains("window.__hhReady"))
        assertTrue(script.contains("document.readyState"))
        assertTrue(script.contains("'complete'"))
    }

    @Test
    fun pageReadyScriptIsSyntacticallyBalanced() {
        assertBalanced(WebViewScripts.pageReadyScript())
    }

    @Test
    fun bothScriptsAreCompletionValueExpressions() {
        // evaluateJavascript resolves with the script's completion value, so both must be an
        // invoked expression that returns something — not a bare statement.
        listOf(WebViewScripts.outerHtmlScript(), WebViewScripts.pageReadyScript()).forEach { script ->
            assertTrue("must be an IIFE: ${script.lineSequence().first()}",
                script.trimStart().startsWith("(function"))
            assertTrue("must be invoked: ${script.lineSequence().last()}",
                script.trimEnd().endsWith("})();"))
            assertTrue("must return a value", script.contains("return"))
        }
    }

    @Test
    fun scriptsArePureAndDeterministic() {
        // Pure helpers: same input (none) every time, so tests and the hot path agree.
        assertEquals(WebViewScripts.outerHtmlScript(), WebViewScripts.outerHtmlScript())
        assertEquals(WebViewScripts.pageReadyScript(), WebViewScripts.pageReadyScript())
    }

    @Test
    fun scriptsStaySmallEnoughToInlineInAFunctionCall() {
        listOf(WebViewScripts.outerHtmlScript(), WebViewScripts.pageReadyScript()).forEach { script ->
            assertTrue("snippet is needlessly large (${script.length} chars)", script.length < 512)
            assertTrue("script must not contain a stray newline-only body", script.isNotBlank())
        }
    }

    /** Fails the test unless braces, parentheses and brackets are balanced outside literals. */
    private fun assertBalanced(script: String) {
        val code = stripLiterals(script)
        val openers = mapOf('}' to '{', ')' to '(', ']' to '[')
        val stack = ArrayDeque<Char>()
        code.forEach { c ->
            val opener = openers[c]
            if (opener == null) {
                if (c == '{' || c == '(' || c == '[') stack.addLast(c)
            } else {
                val found = stack.removeLastOrNull()
                assertEquals("unbalanced '$c' in:\n$script", opener, found)
            }
        }
        assertTrue("unclosed brackets in:\n$script", stack.isEmpty())
    }

    /** Removes string literals and line comments so their contents cannot skew the counts. */
    private fun stripLiterals(source: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < source.length) {
            val c = source[i]
            when {
                c == '\'' || c == '"' || c == '`' -> {
                    val quote = c
                    i++
                    while (i < source.length && source[i] != quote) {
                        if (source[i] == '\\') i++
                        i++
                    }
                    i++
                }

                c == '/' && i + 1 < source.length && source[i + 1] == '/' -> {
                    while (i < source.length && source[i] != '\n') i++
                }

                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }
}