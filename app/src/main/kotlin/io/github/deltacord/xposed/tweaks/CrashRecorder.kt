package io.github.deltacord.xposed.tweaks

import android.app.ActivityManager
import android.app.AndroidAppHelper
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import io.github.deltacord.xposed.tweak
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Remembers the last time Discord closed because of an error in Java or Kotlin code (Discord's,
 * a library's or Deltacord's module), in pyoncord/last-native-crash.txt, so Doctor can show it
 * and it can be sent along. Discord's own handling of the crash carries on as before.
 *
 * Crashes deeper down (native code, Android killing a frozen or out-of-memory app) never reach
 * that, so Android's own record of why Discord last closed is also read once Discord starts, into
 * pyoncord/last-exit.txt.
 */
val crashRecorder by tweak {
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
        try {
            val dir = AndroidAppHelper.currentApplication()?.filesDir?.resolve("pyoncord")
            if (dir != null) {
                dir.mkdirs()
                val text = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
                File(dir, "last-native-crash.txt").writeText(
                    "${java.util.Date()} · thread ${thread.name}\n${text.take(12_000)}"
                )
            }
        } catch (_: Throwable) {
        }
        previous?.uncaughtException(thread, error)
    }

    // Android's record of why Discord last closed (Android 11 and newer).
    if (Build.VERSION.SDK_INT >= 30) {
        Thread({
            try {
                var app = AndroidAppHelper.currentApplication()
                var waited = 0
                while (app == null && waited < 30_000) {
                    Thread.sleep(500); waited += 500
                    app = AndroidAppHelper.currentApplication()
                }
                if (app != null) recordLastExit(app)
            } catch (_: Throwable) {
            }
        }, "DeltacordExitReason").apply { isDaemon = true }.start()
    }
}

private fun reasonName(reason: Int) = when (reason) {
    ApplicationExitInfo.REASON_CRASH -> "crash (Java)"
    ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash (native code)"
    ApplicationExitInfo.REASON_ANR -> "frozen (not responding)"
    ApplicationExitInfo.REASON_LOW_MEMORY -> "out of memory"
    ApplicationExitInfo.REASON_SIGNALED -> "killed by a signal"
    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "using too much"
    ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "failed to start"
    ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "something it needs closed"
    ApplicationExitInfo.REASON_EXIT_SELF -> "closed itself"
    ApplicationExitInfo.REASON_USER_REQUESTED -> "closed by you"
    ApplicationExitInfo.REASON_USER_STOPPED -> "stopped by you"
    ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission changed"
    ApplicationExitInfo.REASON_OTHER -> "other"
    else -> "reason $reason"
}

/** Readable text from a crash or freeze trace (native traces are binary, with names inside). */
private fun traceText(info: ApplicationExitInfo): String {
    val bytes = try {
        info.traceInputStream?.use { it.readNBytesCompat(256 * 1024) }
    } catch (_: Throwable) {
        null
    } ?: return ""
    val text = String(bytes, Charsets.ISO_8859_1)
    return if (info.reason == ApplicationExitInfo.REASON_ANR) {
        // The main thread and any audio threads are the interesting parts of a freeze.
        val lines = text.lines()
        val keep = mutableListOf<String>()
        var inThread = false
        for (line in lines) {
            if (line.startsWith("\"")) inThread = line.contains("\"main\"") || line.contains("Audio", true) || line.contains("Jukebox", true) || line.contains("WebRtc", true)
            if (inThread) keep.add(line)
            if (keep.size > 160) break
        }
        keep.joinToString("\n")
    } else {
        // Printable runs that look like frames, libraries or the abort message.
        Regex("[\\x20-\\x7e]{6,}").findAll(text).map { it.value }
            .filter { it.contains(".so") || it.contains("Abort") || it.contains("signal") || it.contains("Java_") || it.contains("::") || it.contains("art::") || it.contains("Fatal") }
            .distinct().take(60).joinToString("\n")
    }
}

private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    while (out.size() < limit) {
        val n = read(buffer)
        if (n < 0) break
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}

private fun recordLastExit(app: Context) {
    if (Build.VERSION.SDK_INT < 30) return
    val am = app.getSystemService(ActivityManager::class.java) ?: return
    // The main process only (Discord's extra processes come and go), newest first.
    val exits = am.getHistoricalProcessExitReasons(app.packageName, 0, 16).filter { it.processName == app.packageName }
    // The most recent time it closed because something went wrong, even if you've reopened and
    // closed Discord normally since; otherwise just the last time it closed.
    val bad = setOf(
        ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_ANR,
        ApplicationExitInfo.REASON_LOW_MEMORY, ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
    )
    val last = exits.firstOrNull { it.reason in bad } ?: exits.firstOrNull() ?: return
    val dir = app.filesDir.resolve("pyoncord").apply { mkdirs() }
    val file = File(dir, "last-exit.txt")
    val stamp = "${last.timestamp}"
    // Only once per exit.
    if (file.exists() && file.readLines().firstOrNull() == stamp) return
    val text = buildString {
        appendLine(stamp)
        appendLine("${java.util.Date(last.timestamp)} · ${reasonName(last.reason)} · ${last.description ?: ""}".trim())
        appendLine("status ${last.status} · importance ${last.importance} · memory ${last.pss / 1024} MB · ${exits.indexOf(last)} normal closes since")
        append(traceText(last).take(16_000))
    }
    file.writeText(text)
}
