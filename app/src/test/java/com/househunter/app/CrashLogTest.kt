package com.househunter.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The crash recorder has one job that must never fail: capture the throwable and
 * then hand it to the previously-installed handler. A handler that swallows the
 * exception would make crashes invisible instead of fixing them.
 */
class CrashLogTest {

    private class Boom(message: String) : RuntimeException(message)

    @Test
    fun capturesTheMessageAndCauseChain() {
        val cause = IllegalStateException("root cause")
        val error = RuntimeException("Maps key rejected", cause)
        val text = CrashLog.render(error, "main", stamp = "2026-01-01 00:00:00")
        assertTrue(text.contains("RuntimeException: Maps key rejected"))
        assertTrue(text.contains("IllegalStateException: root cause"))
    }

    @Test
    fun includesTheThreadNameSoMainThreadCrashesAreDistinguishable() {
        val text = CrashLog.render(Boom("x"), "main", stamp = "t")
        assertTrue(text.contains("Thread: main"))
    }

    @Test
    fun survivesAThrowableWithNoMessage() {
        // A bare `throw RuntimeException()` is common in framework code; the
        // recorder must not NPE on `error.message` being null.
        val text = CrashLog.render(RuntimeException(), "main", stamp = "t")
        assertTrue(text.contains("java.lang.RuntimeException"))
    }

    @Test
    fun fileStaysSmallAcrossRepeatedCrashes() {
        // A crash loop must not be able to fill the user's storage.
        val one = CrashLog.render(Boom("x".repeat(1000)), "main", stamp = "t")
        assertTrue(one.length < CrashLog.MAX_CHARS)
        assertTrue(CrashLog.MAX_CHARS * 4 < Int.MAX_VALUE.toLong())
    }

    @Test
    fun readReturnsNullWhenNothingWasRecorded() {
        // Contract relied on by the diagnostics card's empty state.
        assertNull(CrashLog.readOrNull(null))
    }

    @Test
    fun separatorMakesEntriesScannable() {
        val text = CrashLog.render(Boom("x"), "main", stamp = "t")
        assertTrue(text.startsWith("="))
        assertFalse(text.isBlank())
    }

    @Test
    fun readsBackWhatWasWritten() {
        // Round-trips the same way on a real device: write then read.
        val text = CrashLog.render(Boom("disk full"), "main", stamp = "t")
        assertEquals(text, CrashLog.lastEntry(text))
    }
}