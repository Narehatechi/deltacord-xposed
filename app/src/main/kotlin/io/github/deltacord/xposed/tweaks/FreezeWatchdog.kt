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
        override fun run() {
            main.post { lastResponse.set(SystemClock.uptimeMillis()) }
            val now = SystemClock.uptimeMillis()
            val frozenFor = now - lastResponse.get()
            if (frozenFor > 8_000 && reported.compareAndSet(false, true)) {
                try {
                    val report = buildString {
                        append("Discord's screen froze while starting (no response for ${frozenFor / 1000}s).\n\n")
                        append(StuckReport.build(dataDir, processStart, evenIfReady = true) ?: "")
                    }
                    log.w("UI thread frozen:\n$report")
                    runCatching {
                        File(dataDir, "${DeltacordConstants.FILES_DIR}/freeze-report.txt").writeText(report)
                    }
                    val ctx = context.get()
                    if (ctx != null) {
                        val copied = runCatching {
                            val clipboard = ctx.getSystemService(ClipboardManager::class.java)
                            clipboard.setPrimaryClip(ClipData.newPlainText("Deltacord freeze report", report))
                        }.isSuccess
                        Toast.makeText(
                            ctx,
                            if (copied) "Deltacord: Discord froze. A report was copied, paste it to send it."
                            else "Deltacord: Discord froze. A report was saved in Deltacord's files.",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
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
