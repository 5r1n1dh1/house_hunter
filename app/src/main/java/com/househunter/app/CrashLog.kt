package com.househunter.app

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Last-resort crash recorder.
 *
 * A crash on somebody's phone is invisible to us unless the trace is written
 * somewhere it can be read back, and `adb logcat` is not an option for a normal
 * user. This handler:
 *
 *  1. chains to whatever handler was already installed (so Play Console / the
 *     platform's own reporting still runs - it must never suppress that), and
 *  2. appends the throwable and its cause chain to a small rolling file in the
 *     app's private files dir, which the **Settings > Diagnostics** screen can
 *     show and share.
 *
 * Nothing is ever uploaded. The file stays on the device, which keeps the app's
 * no-telemetry promise intact - the user has to actively share it.
 *
 * Scope is deliberately small: writing the trace must not itself throw, or a
 * disk-full phone would turn a crash into a different crash.
 */
object CrashLog {

    private const val TAG = "HouseHunterCrash"
    private const val FILE_NAME = "crash_log.txt"
    internal const val MAX_CHARS = 32_000

    /** Installed once from [HouseHunterApp.onCreate]. */
    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { append(appContext, thread, error) }
            // Always delegate: swallowing this would hide the crash from every
            // other reporter the platform has installed.
            previous?.uncaughtException(thread, error)
        }
    }

    /** Most recent crash text, oldest first, or null when nothing was recorded. */
    fun read(context: Context): String? = runCatching {
        val file = file(context)
        if (!file.exists()) return@runCatching null
        file.readText().takeLast(MAX_CHARS).takeIf { it.isNotBlank() }
    }.getOrNull()

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    private fun file(context: Context) =
        java.io.File(context.filesDir, FILE_NAME)

    private fun append(context: Context, thread: Thread, error: Throwable) {
        val stamp = runCatching {
            LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        }.getOrDefault("unknown time")

        val entry = render(error, thread.name, stamp)
        val file = file(context)
        // Cap the file so a crash loop cannot fill the user's storage.
        if (file.exists() && file.length() > MAX_CHARS * 4L) file.delete()
        file.appendText(entry)
        Log.e(TAG, "Crash captured: ${error.javaClass.name}: ${error.message}")
    }

    /**
     * Formats one crash entry. Split out from [append] so the formatting rules -
     * header fields, cause chain, size cap - are testable without a device.
     */
    internal fun render(error: Throwable, threadName: String, stamp: String): String {
        val trace = StringWriter().also { writer ->
            runCatching { PrintWriter(writer).use { error.printStackTrace(it) } }
        }.toString()

        return buildString {
            appendLine("=".repeat(72))
            appendLine("$stamp  device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Thread: $threadName")
            appendLine("Exception: ${error.javaClass.name}: ${error.message}")
            appendLine()
            append(trace.take(MAX_CHARS))
        }
    }

    /** Most recent entry in [text], or null when there is nothing recorded. */
    internal fun lastEntry(text: String?): String? =
        text?.takeLast(MAX_CHARS)?.takeIf { it.isNotBlank() }

    /** Test seam: mirrors [read] without needing a real [Context]. */
    internal fun readOrNull(context: Context?): String? =
        if (context == null) null else read(context)
}