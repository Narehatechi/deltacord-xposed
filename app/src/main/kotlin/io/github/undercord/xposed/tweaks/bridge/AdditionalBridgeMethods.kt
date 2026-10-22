package io.github.undercord.xposed.tweaks.bridge

import io.github.undercord.bridge.asDelegate
import io.github.undercord.reloadApp
import io.github.undercord.xposed.api.registerNativeMethod
import io.github.undercord.xposed.openFileGuarded
import io.github.undercord.xposed.tweak
import java.io.File

/**
 * `undercord.fs.*` + `undercord.app.*` bridge methods.
 */
val additionalBridgeMethods by tweak {
    registerNativeMethod("undercord.app.reload") {
        reloadApp()
    }

    withAppContext { ctx ->
        registerNativeMethod("undercord.fs.getConstants") {
            mapOf(
                "data" to ctx.dataDir.absolutePath,
                "files" to ctx.filesDir.absolutePath,
                "cache" to ctx.cacheDir.absolutePath,
            )
        }

        registerNativeMethod("undercord.fs.delete") { args ->
            val argv = args.asDelegate()
            val path by argv.string()
            val f = File(path)
            if (f.isDirectory) f.deleteRecursively() else f.delete()
        }

        registerNativeMethod("undercord.fs.exists") { args ->
            val argv = args.asDelegate()
            val path by argv.string()
            File(path).exists()
        }

        registerNativeMethod("undercord.fs.read") { args ->
            val argv = args.asDelegate()
            val path by argv.string()
            val file = File(path).also { it.openFileGuarded() }
            file.bufferedReader().use { it.readText() }
        }

        registerNativeMethod("undercord.fs.write") { args ->
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
