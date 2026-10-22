package io.github.undercord.xposed.tweaks.plugins.internal

import io.github.undercord.plugins.API_VERSION
import io.github.undercord.plugins.PluginManifest
import io.github.undercord.xposed.api.registerNativeMethod

private val manifest = PluginManifest(
    id = "undercord.example",
    name = "Example Plugin",
    description = "Example plugin.",
    author = "Undercord",
    version = API_VERSION,
)

internal val examplePlugin =
    internalPlugin(manifest, setOf(InternalPluginFlags.INTERNAL, InternalPluginFlags.ESSENTIAL)) {
        start {
            log.i("started in ${appInfo.packageName}")
            registerNativeMethod("undercord.example.test") { args ->
                log.i("undercord.example.test($args)")
                null
            }

            registerNativeMethod("undercord.example.test.error") {
                errors.tryEmit(Exception("Test exception!"))
            }
        }
    }