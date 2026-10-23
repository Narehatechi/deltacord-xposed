package io.github.deltacord.xposed.tweaks

import android.os.Build
import io.github.deltacord.xposed.DeltacordConstants
import org.json.JSONObject
import java.io.File

/**
 * Builds a report about a launch that never got Discord open, readable without Discord itself:
 * - how far Deltacord's own startup got (from `files/pyoncord/deltacord-startup.json`, which the
 *   JS side writes as it goes): its build, the last steps, and the steps that never finished,
 * - what Discord's JavaScript thread is doing right now (busy, waiting, or still loading).
 *
 * Returns null if Deltacord says Discord did open during this launch (nothing is stuck).
 */
internal object StuckReport {
    /** Created by "Open without Deltacord": the next launch skips the Deltacord bundle once. */
    const val SKIP_ONCE_FILE = "skip-bundle-once"

    fun build(dataDir: String, processStart: Long): String? {
        val out = StringBuilder()
        val record = File(dataDir, "${DeltacordConstants.FILES_DIR}/deltacord-startup.json")

        if (!record.exists()) {
            out.append("Deltacord's code never started (no startup record).\n")
        } else {
            try {
                val json = JSONObject(record.readText())
                val thisLaunch = json.optLong("startedAt") >= processStart - 10_000
                if (thisLaunch && json.optBoolean("opened")) return null

                out.append("Deltacord ").append(json.optString("version", "?"))
                if (json.optBoolean("minimal")) out.append(" (minimal startup)")
                out.append('\n')
                if (!thisLaunch) out.append("This record is from an earlier launch: Deltacord's code didn't start this time.\n")

                val stages = json.optJSONArray("stages")
                val unfinished = LinkedHashSet<String>()
                val all = ArrayList<String>()
                if (stages != null) {
                    for (i in 0 until stages.length()) {
                        val stage = stages.optJSONObject(i) ?: continue
                        val name = stage.optString("name")
                        all.add("$name @${stage.optLong("ms")}ms")
                        when {
                            name.startsWith("start: ") -> unfinished.add(name.removePrefix("start: "))
                            name.startsWith("done: ") -> unfinished.remove(name.removePrefix("done: "))
                        }
                    }
                }
                out.append("Last steps: ").append(all.takeLast(8).joinToString(" > ").ifEmpty { "none" }).append('\n')
                out.append("Never finished: ").append(unfinished.joinToString(", ").ifEmpty { "nothing recorded" }).append('\n')
            } catch (e: Throwable) {
                out.append("Couldn't read the startup record: ${e.message}\n")
            }
        }

        out.append("\nModule ${DeltacordConstants.LOADER_VERSION} · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n")

        // React Native's threads are named mqt_*; the JavaScript one is mqt_js or mqt_v_js.
        try {
            val threads = Thread.getAllStackTraces()
                .filterKeys { it.name.startsWith("mqt_") }
                .toList()
                .sortedBy { (thread, _) -> if (thread.name.contains("js")) 0 else 1 }
            if (threads.isEmpty()) out.append("No React Native threads found.\n")
            for ((thread, stack) in threads) {
                out.append("\n").append(thread.name).append(" (").append(thread.state).append("):\n")
                val frames = if (thread.name.contains("js")) 16 else 5
                for (frame in stack.take(frames)) out.append("  at ").append(frame).append('\n')
            }
        } catch (e: Throwable) {
            out.append("Couldn't read threads: ${e.message}\n")
        }

        return out.toString()
    }
}
