@file:Suppress("DEPRECATION")

package io.github.deltacord.xposed.tweaks.legacy

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import io.github.deltacord.Logger
import io.github.deltacord.xposed.*
import io.github.deltacord.xposed.tweaks.base.registerScriptInjector
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

@Deprecated(
    "Payloads will be replaced by synchronous Deltacord bridge methods.",
    level = DeprecationLevel.WARNING,
)
object DeltacordPayloadBuilder {
    private val contributors: MutableList<JsonObjectBuilder.() -> Unit> = mutableListOf()

    fun contribute(block: JsonObjectBuilder.() -> Unit) {
        contributors += block
    }

    internal fun build(): String = DeltacordJson.encodeToString(
        buildJsonObject {
            put("loaderName", DeltacordConstants.LOADER_NAME)
            put("loaderVersion", DeltacordConstants.LOADER_VERSION)
            for (c in contributors) c()
        },
    )
}

internal const val FIELD_NAME = "__PYON_LOADER__"

/**
 * Sets the `__PYON_LOADER__` JS global before the bundle runs.
 */
@Deprecated(
    "Payloads will be replaced by synchronous Deltacord bridge methods.",
    level = DeprecationLevel.WARNING,
)
val deltacordPayloadGlobal by tweak {
    val log: Logger = this.log
    val dataDir = appInfo.dataDir

    registerScriptInjector { scope ->
        val json = DeltacordPayloadBuilder.build()

        val preloadsDir = File("$dataDir/${DeltacordConstants.FILES_DIR}", DeltacordConstants.PRELOADS_DIR)
        if (!preloadsDir.isDirectory) {
            preloadsDir.delete()
            preloadsDir.mkdirs()
        }

        File(preloadsDir, "rv_globals_$FIELD_NAME.js").apply {
            writeText("this[${JsonPrimitive(FIELD_NAME)}]=$json")
            try {
                scope.runFile(absolutePath)
            } catch (e: Throwable) {
                log.e("rv_globals_$FIELD_NAME.js preload injection failed", e)
            } finally {
                delete()
            }
        }
    }
}
