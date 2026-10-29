package io.github.deltacord.xposed.tweaks.bridge

import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import io.github.deltacord.xposed.DeltacordConstants
import io.github.deltacord.xposed.tweaks.StuckReport
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A report about Deltacord built by the module itself, so it works even when Discord is stuck on
 * its logo or Deltacord's JavaScript is frozen: versions, how far this launch's startup got, what
 * Discord's threads are doing, the last stuck report, the crash guard, the last Doctor report
 * Deltacord saved, and Discord's recent log lines.
 */
internal object ModuleReport {
    private const val MAX_CHARS = 250_000
    private const val LOG_LINES = 400

    private fun section(out: StringBuilder, title: String) {
        out.append("\n\n===== ").append(title).append(" =====\n")
    }

    private fun age(file: File): String {
        val minutes = (System.currentTimeMillis() - file.lastModified()) / 60_000
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "$minutes min ago"
            minutes < 48 * 60 -> "${minutes / 60} h ago"
            else -> "${minutes / (24 * 60)} days ago"
        }
    }

    private fun readFile(out: StringBuilder, file: File, limit: Int = 20_000) {
        if (!file.exists()) {
            out.append("(none)\n")
            return
        }
        out.append("(saved ").append(age(file)).append(")\n")
        out.append(runCatching { file.readText().take(limit) }.getOrElse { "Couldn't read it: ${it.message}" })
    }

    fun build(ctx: Context): String {
        val out = StringBuilder()
        val dataDir = ctx.applicationInfo.dataDir
        val files = File(dataDir, DeltacordConstants.FILES_DIR)
        out.append("Deltacord report (from the module)\n")
        out.append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date()))

        section(out, "Versions")
        val discord = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0) }.getOrNull()
        out.append("Discord: ").append(discord?.versionName ?: "?").append(" (")
            .append(discord?.let { if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong() } ?: "?").append(") · package ").append(ctx.packageName).append('\n')
        out.append("Module: ").append(DeltacordConstants.LOADER_VERSION).append('\n')
        out.append("Android ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(") · ")
            .append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')

        section(out, "This launch")
        // When this process started, as a wall-clock time (the startup record uses those).
        val processStart = System.currentTimeMillis() - (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime())
        out.append("Running for ").append((System.currentTimeMillis() - processStart) / 1000).append("s\n")
        out.append(runCatching { StuckReport.build(dataDir, processStart, evenIfReady = true) }.getOrNull() ?: "(no startup record)")

        section(out, "Last stuck launch")
        readFile(out, File(files, "freeze-report.txt"))

        section(out, "Crash guard")
        readFile(out, File(files, "deltacord-crashguard.json"))

        section(out, "Last Doctor report")
        readFile(out, File(files, "deltacord-doctor-report.txt"), 60_000)

        section(out, "Discord's log (last $LOG_LINES lines)")
        out.append(
            runCatching {
                val proc = ProcessBuilder("logcat", "-d", "-v", "time", "-t", LOG_LINES.toString(), "--pid=${Process.myPid()}")
                    .redirectErrorStream(true)
                    .start()
                val text = proc.inputStream.bufferedReader().readText()
                proc.waitFor()
                // Long lines (big JSON) are cut so the report stays readable.
                text.lineSequence().joinToString("\n") { if (it.length > 400) it.take(400) + "…" else it }
            }.getOrElse { "Couldn't read the log: ${it.message}" }
        )

        return if (out.length > MAX_CHARS) out.substring(0, MAX_CHARS) + "\n…(cut)" else out.toString()
    }
}
