package io.github.deltacord.xposed.tweaks.plugins.internal

import io.github.deltacord.plugins.API_VERSION
import io.github.deltacord.plugins.PluginManifest
import io.github.deltacord.xposed.api.registerNativeMethod

private val manifest = PluginManifest(
    id = "deltacord.example",
    name = "Example Plugin",
    description = "Example plugin.",
    author = "Deltacord",
    version = API_VERSION,
)

internal val examplePlugin =
    internalPlugin(manifest, setOf(InternalPluginFlags.INTERNAL, InternalPluginFlags.ESSENTIAL)) {
        start {
            log.i("started in ${appInfo.packageName}")
            registerNativeMethod("deltacord.example.test") { args ->
                log.i("deltacord.example.test($args)")
                null
            }

            registerNativeMethod("deltacord.example.test.error") {
                errors.tryEmit(Exception("Test exception!"))
            }
        }
    }