package io.github.undercord.xposed.tweaks

import android.app.AlertDialog
import android.util.AtomicFile
import android.widget.Toast
import androidx.core.util.writeBytes
import io.github.undercord.logger
import io.github.undercord.reloadApp
import io.github.undercord.xposed.*
import io.github.undercord.xposed.tweaks.base.withAppActivity
import io.github.undercord.xposed.tweaks.plugins.internal.showRecoveryAlert
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import java.io.File
import kotlin.time.Duration.Companion.seconds

@Serializable
data class CustomLoadUrl(
    val enabled: Boolean = false,
    val url: String = "",
    val manifestUrl: String = "",
)

@Serializable
data class LoaderConfig(
    val customLoadUrl: CustomLoadUrl = CustomLoadUrl(),
)

/**
 * Updater for the JS bundle.
 *
 * Handles configuration, downloading, caching, and user-facing retry/recovery dialogs.
 * The actual loading of the bundle is handled by [undercordScriptLoader].
 */
object UndercordUpdater {
    internal val TIMEOUT = 10.seconds
    private val TIMEOUT_CACHED = 5.seconds
    private const val ETAG_PATH = "etag.txt"
    private const val CONFIG_PATH = "loader.json"
    private const val DEFAULT_BUNDLE_URL =
        "https://github.com/Narehatechi/undercord-bundle/releases/latest/download/undercord.min.js"

    private val log = logger("undercordUpdater")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var config = LoaderConfig()
    private lateinit var bundle: File
    private lateinit var manifest: File
    private lateinit var etag: File
    private lateinit var configFile: File

    private val _downloadReady = CompletableDeferred<Unit>()

    /**
     * Completes after the *first* download attempt finishes (success or any terminal failure).
     * [undercordScriptLoader] joins on this before falling through to its fallback bundle.
     */
    val downloadReady: Deferred<Unit> = _downloadReady

    internal fun init(dataDir: String) {
        val cacheDir = File(dataDir, UndercordConstants.CACHE_DIR).apply { mkdirs() }
        val filesDir = File(dataDir, UndercordConstants.FILES_DIR).apply { mkdirs() }

        bundle = File(cacheDir, UndercordConstants.MAIN_SCRIPT_FILE)
        manifest = File(cacheDir, UndercordConstants.BUNDLE_MANIFEST_FILE)
        etag = File(cacheDir, ETAG_PATH)
        configFile = File(filesDir, CONFIG_PATH)

        config = runCatching {
            if (configFile.exists()) UndercordJson.decodeFromString<LoaderConfig>(configFile.readText())
            else LoaderConfig()
        }.getOrDefault(LoaderConfig())
    }

    fun resetLoaderConfig() {
        if (::configFile.isInitialized && configFile.exists()) configFile.delete()
    }

    /**
     * Trigger a download. If [userInitiated] is true (retry from the error dialog), the timeout
     * is disabled and a success dialog is shown on the next available activity.
     */
    fun downloadScript(userInitiated: Boolean = false, showDialog: Boolean = true): Job = scope.launch {
        try {
            val url = config.customLoadUrl.takeIf { it.enabled }?.url ?: DEFAULT_BUNDLE_URL
            log.i("Fetching JS bundle from: $url")

            val result = httpClient.getWithETag(
                url = url,
                etag = if (etag.exists() && bundle.exists()) etag.readText() else null,
                timeoutMillis = if (userInitiated) null
                else if (bundle.exists()) TIMEOUT_CACHED.inWholeMilliseconds else TIMEOUT.inWholeMilliseconds,
            )

            when (result) {
                is ETagFetchResult.Fetched -> {
                    AtomicFile(bundle).writeBytes(result.bytes)
                    result.etag?.let(etag::writeText) ?: etag.delete()

                    log.i("Bundle updated (${result.bytes.size} bytes)")

                    downloadManifest()

                    if (showDialog) {
                        if (userInitiated) showSuccessDialog() else showUpdateDialog()
                    }
                }

                ETagFetchResult.NotModified -> log.i("Server responded with 304, no changes")
            }
        } catch (e: Throwable) {
            log.e("Failed to download script", e)
            showErrorDialog(e)
        } finally {
            _downloadReady.complete(Unit)
        }
    }

    private suspend fun downloadManifest() {
        val manifestUrl = config.customLoadUrl.takeIf { it.enabled }?.manifestUrl
            ?: return

        try {
            val result = httpClient.getWithETag(url = manifestUrl, etag = null, timeoutMillis = null)
            if (result is ETagFetchResult.Fetched) {
                AtomicFile(manifest).writeBytes(result.bytes)
                log.i("Bundle manifest updated (${result.bytes.size} bytes)")
            }
        } catch (e: Throwable) {
            log.w("Failed to download bundle manifest", e)
        }
    }

    fun readBundleManifest(): String? {
        if (manifest.exists()) return manifest.readText()
        return null
    }

    private fun showUpdateDialog() = withAppActivity { activity ->
        activity.runOnUiThread {
            AlertDialog.Builder(activity)
                .setTitle("Undercord Update Downloaded")
                .setMessage("A reload is required for changes to take effect.")
                .setPositiveButton("Reload") { d, _ -> reloadApp(); d.dismiss() }
                .setNegativeButton("Later") { d, _ -> d.dismiss() }
                .show()
        }
    }

    private fun showSuccessDialog() = withAppActivity { activity ->
        activity.runOnUiThread {
            AlertDialog.Builder(activity)
                .setTitle("Undercord Update Successful")
                .setMessage("A reload is required for changes to take effect.")
                .setPositiveButton("Reload") { d, _ -> reloadApp(); d.dismiss() }
                .setNegativeButton("Later") { d, _ -> d.dismiss() }
                .show()
        }
    }

    private fun showErrorDialog(e: Throwable) = withAppActivity { activity ->
        activity.runOnUiThread {
            AlertDialog.Builder(activity)
                .setTitle("Undercord Update Failed")
                .setMessage(
                    """
                    Unable to download the latest version of Undercord.
                    This is usually caused by bad network connection.

                    Error: ${e.message ?: e.stackTraceToString()}
                    """.trimIndent()
                )
                .setNegativeButton("Dismiss") { d, _ -> d.dismiss() }
                .setPositiveButton("Retry Update") { d, _ ->
                    downloadScript(userInitiated = true)
                    Toast.makeText(activity, "Retrying download in background...", Toast.LENGTH_SHORT).show()
                    d.dismiss()
                }
                .setNeutralButton("Recovery") { d, _ -> showRecoveryAlert(activity); d.dismiss() }
                .show()
        }
    }
}

/**
 * Wires [UndercordUpdater] into the lifecycle. Loads the loader config once the target [android.content.Context]
 * is available, then kicks off the first download.
 */
val undercordUpdater by tweak {
    UndercordUpdater.init(appInfo.dataDir)

    withAppContext { ctx ->
        UndercordUpdater.downloadScript(userInitiated = false, showDialog = false)
    }
}
