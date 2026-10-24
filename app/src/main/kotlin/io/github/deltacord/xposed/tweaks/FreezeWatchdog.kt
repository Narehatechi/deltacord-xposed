package io.github.deltacord.xposed.tweaks

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import io.github.deltacord.xposed.DeltacordConstants
import io.github.deltacord.xposed.tweak
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Notices when Discord's UI thread stops responding while starting (so nothing on screen can
 * change, not even Deltacord's own stuck-startup report) and, from a background thread:
 * - writes a report (what the UI thread is doing, Deltacord's startup steps, the JS thread) to
 *   `files/pyoncord/freeze-report.txt`,
 * - copies it to the clipboard and says so in a short message, so it can be pasted and sent.
 * Watches the first two minutes of each launch, and reports once.
 */
val freezeWatchdog by tweak {
    val dataDir = appInfo.dataDir
    val processStart = System.currentTimeMillis()
    val started = SystemClock.uptimeMillis()
    val lastResponse = AtomicLong(SystemClock.uptimeMillis())
    val reported = AtomicBoolean(false)
    val context = AtomicReference<Context?>(null)
    withAppContext { context.set(it) }

    val thread = HandlerThread("deltacord-watchdog").apply { start() }
    val background = Handler(thread.looper)
    val main = Handler(Looper.getMainLooper())

    val tick = object : Runnable {
        private var lastTick = SystemClock.uptimeMillis()

        override fun run() {
            main.post { lastResponse.set(SystemClock.uptimeMillis()) }
            val now = SystemClock.uptimeMillis()
            // This watchdog itself was held up: Android paused the whole app (it does that to apps
            // in the background), so nothing froze. Same if Discord isn't on screen (started in
            // the background for a notification): only an on-screen freeze counts.
            val paused = now - lastTick > 3_000
            lastTick = now
            if (paused || !onScreen()) lastResponse.set(now)
            val frozenFor = now - lastResponse.get()
            if (frozenFor > 8_000 && reported.compareAndSet(false, true)) {
                try {
                    val samples = sampleMainThread()
                    val report = buildString {
                        append("Discord's screen froze while starting (no response for ${frozenFor / 1000}s).\n\n")
                        append(StuckReport.build(dataDir, processStart, evenIfReady = true) ?: "")
                        append("\nWhere the UI thread was, sampled ${SAMPLES} times:\n").append(samples)
                    }
                    log.w("UI thread frozen:\n$report")
                    StuckReport.flagStuckLaunch(dataDir)
                    runCatching {
                        File(dataDir, "${DeltacordConstants.FILES_DIR}/freeze-report.txt").writeText(report)
                    }
                    // Kept quiet: the report is in Deltacord > Doctor > Copy report.
                } catch (e: Throwable) {
                    log.e("Couldn't report the freeze", e)
                }
            }
            if (now - started < 120_000 && !reported.get()) background.postDelayed(this, 1_000)
            else thread.quitSafely()
        }
    }
    background.postDelayed(tick, 3_000)
}

private const val SAMPLES = 12

/** Discord has a screen showing (not started in the background). */
private fun onScreen(): Boolean = try {
    val info = android.app.ActivityManager.RunningAppProcessInfo()
    android.app.ActivityManager.getMyMemoryState(info)
    info.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
} catch (_: Throwable) {
    true
}

/** Samples the UI thread's stack a few times: a loop shows up as the same frames again and again. */
private fun sampleMainThread(): String {
    val main = Looper.getMainLooper().thread
    val counts = LinkedHashMap<String, Int>()
    repeat(SAMPLES) {
        val top = main.stackTrace.take(6).joinToString("\n    < ") { it.toString() }
        counts[top] = (counts[top] ?: 0) + 1
        Thread.sleep(150)
    }
    return counts.entries.sortedByDescending { it.value }.take(4)
        .joinToString("\n") { (frames, n) -> "  ${n}x:\n    $frames" } + "\n"
}
