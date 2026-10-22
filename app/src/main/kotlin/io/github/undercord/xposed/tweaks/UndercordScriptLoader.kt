package io.github.undercord.xposed.tweaks

import io.github.undercord.xposed.UndercordConstants
import io.github.undercord.xposed.ensureDir
import io.github.undercord.xposed.ensureFile
import io.github.undercord.xposed.tweak
import io.github.undercord.xposed.tweaks.base.InjectorScope
import io.github.undercord.xposed.tweaks.base.registerScriptInjector
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File

/**
 * Waits for script updates and loads the Undercord bundle. Depends on [io.github.undercord.xposed.tweaks.base.scriptLoader].
 *
 * 1. Awaits the bundle download from [undercordUpdater].
 * 2. Runs every file under `files/pyoncord/preloads/`.
 * 3. Loads `cache/undercord/bundle.js` (downloaded copy) if present.
 * 4. Falls back to the in-APK `assets://undercord.bundle` asset shipped with this module.
 */
val undercordScriptLoader by tweak {
    val dataDir = appInfo.dataDir
    val filesDir = File(dataDir, UndercordConstants.FILES_DIR).apply { ensureDir() }
    val cacheDir = File(dataDir, UndercordConstants.CACHE_DIR).apply { ensureDir() }
    val preloadsDir = File(filesDir, UndercordConstants.PRELOADS_DIR).apply { ensureDir() }
    val mainScript = File(cacheDir, UndercordConstants.MAIN_SCRIPT_FILE).apply { ensureFile() }

    registerScriptInjector { scope: InjectorScope ->
        runUndercordScripts(scope, preloadsDir, mainScript)
    }
}

private fun runUndercordScripts(scope: InjectorScope, preloadsDir: File, mainScript: File) {
    val log = scope.tweakLog
    log.i("Running Undercord custom scripts...")

    runBlocking {
        try {
            withTimeout(UndercordUpdater.TIMEOUT) { UndercordUpdater.downloadReady.await() }
        } catch (e: Throwable) {
            log.w("Bundle download did not complete", e)
        }
    }

    try {
        preloadsDir.walk().filter { it.isFile }.sorted().forEach { f ->
            log.d("Running preload: ${f.absolutePath}")
            scope.runFile(f.absolutePath)
        }

        if (mainScript.exists()) {
            log.i("Loading downloaded bundle: ${mainScript.absolutePath}")
            scope.runFile(mainScript.absolutePath)
        } else {
            log.i("Downloaded bundle missing; falling back to ${UndercordConstants.FALLBACK_BUNDLE_ASSET}")
            scope.runAsset(UndercordConstants.FALLBACK_BUNDLE_ASSET)
        }
    } catch (e: Throwable) {
        log.e("Unable to run Undercord scripts", e)
    }
}

