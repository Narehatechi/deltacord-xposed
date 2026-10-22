package io.github.deltacord.xposed.tweaks.bridge

import io.github.deltacord.bridge.asDelegate
import io.github.deltacord.reloadApp
import io.github.deltacord.xposed.api.registerNativeMethod
import io.github.deltacord.xposed.openFileGuarded
import io.github.deltacord.xposed.tweak
import java.io.File

/**
 * `deltacord.fs.*` + `deltacord.app.*` bridge methods.
 */
val additionalBridgeMethods by tweak {
    registerNativeMethod("deltacord.app.reload") {
        reloadApp()
    }

    withAppContext { ctx ->
        registerNativeMethod("deltacord.fs.getConstants") {
            mapOf(
                "data" to ctx.dataDir.absolutePath,
                "files" to ctx.filesDir.absolutePath,
                "cache" to ctx.cacheDir.absolutePath,
            )
        }

        registerNativeMethod("deltacord.fs.delete") { args ->
            val argv = args.asDelegate()
            val path by argv.string()
            val f = File(path)
            if (f.isDirectory) f.deleteRecursively() else f.delete()
        }

        registerNativeMethod("deltacord.fs.exists") { args ->
            val argv = args.asDelegate()
            val path by argv.string()
            File(path).exists()
        }

        registerNativeMethod("deltacord.fs.read") { args ->
            val argv = args.asDelegate()
            val path by argv.string()
            val file = File(path).also { it.openFileGuarded() }
            file.bufferedReader().use { it.readText() }
        }

        registerNativeMethod("deltacord.fs.write") { args ->
            val argv = args.asDelegate()
            val path by argv.string()
            val contents by argv.string()
            File(path).apply {
                if (isDirectory) throw Error("Path is a directory: $path")
                parentFile?.mkdirs()
                writeText(contents)
            }

            null
        }
    }
}
