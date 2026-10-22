package io.github.deltacord.xposed.tweaks

import io.github.deltacord.xposed.DeltacordConstants
import io.github.deltacord.xposed.ensureDir
import io.github.deltacord.xposed.ensureFile
import io.github.deltacord.xposed.tweak
import io.github.deltacord.xposed.tweaks.base.InjectorScope
import io.github.deltacord.xposed.tweaks.base.registerScriptInjector
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File

/**
 * Waits for script updates and loads the Deltacord bundle. Depends on [io.github.deltacord.xposed.tweaks.base.scriptLoader].
 *
 * 1. Awaits the bundle download from [deltacordUpdater].
 * 2. Runs every file under `files/pyoncord/preloads/`.
 * 3. Loads `cache/deltacord/bundle.js` (downloaded copy) if present.
 * 4. Falls back to the in-APK `assets://deltacord.bundle` asset shipped with this module.
 */
val deltacordScriptLoader by tweak {
    val dataDir = appInfo.dataDir
    val filesDir = File(dataDir, DeltacordConstants.FILES_DIR).apply { ensureDir() }
    val cacheDir = File(dataDir, DeltacordConstants.CACHE_DIR).apply { ensureDir() }
    val preloadsDir = File(filesDir, DeltacordConstants.PRELOADS_DIR).apply { ensureDir() }
    val mainScript = File(cacheDir, DeltacordConstants.MAIN_SCRIPT_FILE).apply { ensureFile() }

    registerScriptInjector { scope: InjectorScope ->
        runDeltacordScripts(scope, preloadsDir, mainScript)
    }
}

private fun runDeltacordScripts(scope: InjectorScope, preloadsDir: File, mainScript: File) {
    val log = scope.tweakLog
    log.i("Running Deltacord custom scripts...")

    runBlocking {
        try {
            withTimeout(DeltacordUpdater.TIMEOUT) { DeltacordUpdater.downloadReady.await() }
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
            log.i("Downloaded bundle missing; falling back to ${DeltacordConstants.FALLBACK_BUNDLE_ASSET}")
            scope.runAsset(DeltacordConstants.FALLBACK_BUNDLE_ASSET)
        }
    } catch (e: Throwable) {
        log.e("Unable to run Deltacord scripts", e)
    }
}

