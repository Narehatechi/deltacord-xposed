package io.github.deltacord.xposed.tweaks

import io.github.deltacord.xposed.DeltacordConstants
import io.github.deltacord.xposed.ensureDir
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
 * 3. Loads `cache/deltacord/bundle.js` (downloaded copy) if it's there and not empty.
 * 4. Otherwise, or when [StartupRecovery] says so, or if the download won't run, runs the copy
 *    built into this module (`assets://deltacord.bundle`).
 */
val deltacordScriptLoader by tweak {
    val dataDir = appInfo.dataDir
    val filesDir = File(dataDir, DeltacordConstants.FILES_DIR).apply { ensureDir() }
    val cacheDir = File(dataDir, DeltacordConstants.CACHE_DIR).apply { ensureDir() }
    val preloadsDir = File(filesDir, DeltacordConstants.PRELOADS_DIR).apply { ensureDir() }
    val mainScript = File(cacheDir, DeltacordConstants.MAIN_SCRIPT_FILE)
    val mode = StartupRecovery.mode(dataDir)

    registerScriptInjector { scope: InjectorScope ->
        runDeltacordScripts(scope, preloadsDir, mainScript, mode)
    }
}

private fun runDeltacordScripts(scope: InjectorScope, preloadsDir: File, mainScript: File, mode: StartupRecovery.Mode) {
    val log = scope.tweakLog
    log.i("Running Deltacord custom scripts...")

    runBlocking {
        try {
            // Wait longer when this launch is repairing with a fresh download.
            val wait = if (mode == StartupRecovery.Mode.FRESH) DeltacordUpdater.TIMEOUT_FRESH else DeltacordUpdater.TIMEOUT
            if (mode != StartupRecovery.Mode.BUILT_IN) withTimeout(wait) { DeltacordUpdater.downloadReady.await() }
        } catch (e: Throwable) {
            log.w("Bundle download did not complete", e)
        }
    }

    // "Open without Deltacord" from the stuck-startup report: skip the bundle for this one launch.
    val skipOnce = File(preloadsDir.parentFile, StuckReport.SKIP_ONCE_FILE)
    if (skipOnce.exists()) {
        skipOnce.delete()
        log.w("Skipping the Deltacord bundle for this launch, as asked")
        return
    }

    try {
        preloadsDir.walk().filter { it.isFile }.sorted().forEach { f ->
            log.d("Running preload: ${f.absolutePath}")
            scope.runFile(f.absolutePath)
        }
    } catch (e: Throwable) {
        log.e("Unable to run a preload", e)
    }

    val useDownload = mode != StartupRecovery.Mode.BUILT_IN && DeltacordUpdater.hasBundle()
    if (useDownload) {
        try {
            log.i("Loading downloaded bundle: ${mainScript.absolutePath}")
            scope.runFile(mainScript.absolutePath)
            return
        } catch (e: Throwable) {
            log.e("The downloaded bundle wouldn't run; using the built-in copy", e)
        }
    } else {
        log.i("Using the built-in copy of Deltacord (${if (mode == StartupRecovery.Mode.BUILT_IN) "repairing a stuck launch" else "no download yet"})")
    }
    try {
        scope.runAsset(DeltacordConstants.FALLBACK_BUNDLE_ASSET)
    } catch (e: Throwable) {
        log.e("Unable to run the built-in copy of Deltacord", e)
    }
}

