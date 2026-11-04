package io.github.deltacord.xposed.tweaks

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import io.github.deltacord.xposed.DeltacordConstants
import io.github.deltacord.xposed.tweak
import org.json.JSONObject
import java.io.File

/**
 * Repairs a Deltacord that gets stuck starting, on its own, without turning anything off.
 *
 * Every launch records how long Discord stayed on screen without becoming ready. If the last
 * launch got stuck (on screen for [STUCK_AFTER_MS] and never ready), this one tries a repair, and
 * the next stuck launch tries the other one, round and round, until a launch works:
 * - [Mode.FRESH]: throw away the downloaded Deltacord and download it again (a broken or
 *   half-updated download), waiting longer for it.
 * - [Mode.BUILT_IN]: run the copy of Deltacord built into the module (the download is blocked,
 *   or the newest Deltacord doesn't start on this phone), while downloading again for next time.
 * Plugins, themes and settings always stay on. A working launch resets it all.
 */
internal object StartupRecovery {
    enum class Mode { NORMAL, FRESH, BUILT_IN }

    /** On screen this long without Discord becoming ready counts as stuck. */
    private const val STUCK_AFTER_MS = 25_000L
    private const val FILE = "startup-recovery.json"

    @Volatile
    private var decided: Mode? = null
    private var streak = 0
    private var dataDir = ""

    private fun file() = File(dataDir, "${DeltacordConstants.FILES_DIR}/$FILE")

    /** What this launch does; worked out once, from how the last launch went. */
    @Synchronized
    fun mode(dataDir: String): Mode {
        decided?.let { return it }
        this.dataDir = dataDir
        val last = runCatching { JSONObject(file().readText()) }.getOrNull()
        streak = last?.optInt("streak") ?: 0
        if (last != null) {
            val stuck = !last.optBoolean("ready") && last.optLong("waitedMs") >= STUCK_AFTER_MS
            streak = when {
                last.optBoolean("ready") -> 0
                stuck -> streak + 1
                else -> streak // Closed early or started in the background: no verdict.
            }
        }
        val mode = when {
            streak == 0 -> Mode.NORMAL
            streak % 2 == 1 -> Mode.FRESH
            else -> Mode.BUILT_IN
        }
        decided = mode
        save(ready = false, waitedMs = 0)
        return mode
    }

    /** One line for the stuck report. */
    fun describe(): String = when (decided) {
        Mode.FRESH -> "Repair this launch: fresh download (stuck launches in a row: $streak)"
        Mode.BUILT_IN -> "Repair this launch: built-in copy of Deltacord (stuck launches in a row: $streak)"
        else -> "Repair this launch: none needed"
    }

    private fun save(ready: Boolean, waitedMs: Long) {
        runCatching {
            file().apply { parentFile?.mkdirs() }.writeText(
                JSONObject()
                    .put("streak", streak)
                    .put("mode", decided?.name)
                    .put("ready", ready)
                    .put("waitedMs", waitedMs)
                    .put("at", System.currentTimeMillis())
                    .toString()
            )
        }
    }

    /** Follows this launch: how long it's on screen, and whether Discord becomes ready. */
    fun watch(dataDir: String, processStart: Long) {
        mode(dataDir)
        val thread = HandlerThread("deltacord-recovery").apply { start() }
        val handler = Handler(thread.looper)
        val started = SystemClock.uptimeMillis()
        var waited = 0L
        handler.postDelayed(object : Runnable {
            override fun run() {
                val launch = StuckReport.thisLaunch(dataDir, processStart)
                if (launch?.optBoolean("uiReady") == true) {
                    if (streak > 0) streak = 0
                    save(ready = true, waitedMs = waited)
                    thread.quitSafely()
                    return
                }
                if (isOnScreen()) waited += 2_000
                save(ready = false, waitedMs = waited)
                if (SystemClock.uptimeMillis() - started < 180_000) handler.postDelayed(this, 2_000)
                else thread.quitSafely()
            }
        }, 2_000)
    }

    private fun isOnScreen(): Boolean = try {
        val info = android.app.ActivityManager.RunningAppProcessInfo()
        android.app.ActivityManager.getMyMemoryState(info)
        info.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    } catch (_: Throwable) {
        true
    }
}

val startupRecovery by tweak {
    StartupRecovery.watch(appInfo.dataDir, System.currentTimeMillis())
}
