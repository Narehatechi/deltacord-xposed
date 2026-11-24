package io.github.deltacord.xposed.tweaks

import android.app.AndroidAppHelper
import io.github.deltacord.xposed.tweak
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Remembers the last time Discord closed because of an error in Java or Kotlin code (Discord's,
 * a library's or Deltacord's module), in pyoncord/last-native-crash.txt, so Doctor can show it
 * and it can be sent along. Discord's own handling of the crash carries on as before.
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
}
