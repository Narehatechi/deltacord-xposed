package io.github.deltacord.xposed.tweaks

import android.app.AlertDialog
import android.util.AtomicFile
import android.widget.Toast
import androidx.core.util.writeBytes
import io.github.deltacord.logger
import io.github.deltacord.reloadApp
import io.github.deltacord.xposed.*
import io.github.deltacord.xposed.tweaks.base.withAppActivity
import io.github.deltacord.xposed.tweaks.plugins.internal.showRecoveryAlert
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
 * The actual loading of the bundle is handled by [deltacordScriptLoader].
 */
object DeltacordUpdater {
    internal val TIMEOUT = 10.seconds
    /** How long a launch repairing a stuck one waits for its fresh download. */
    internal val TIMEOUT_FRESH = 30.seconds
    /**
     * The download itself isn't cut off when the launch stops waiting: on a slow connection it
     * finishes in the background, so the next launch has it.
     */
    private val DOWNLOAD_LIMIT = 120.seconds
    private const val ETAG_PATH = "etag.txt"
    private const val CONFIG_PATH = "loader.json"
    private const val DEFAULT_BUNDLE_URL =
        "https://github.com/Narehatechi/deltacord-bundle/releases/latest/download/deltacord.min.js"

    private val log = logger("deltacordUpdater")
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
     * [deltacordScriptLoader] joins on this before falling through to its fallback bundle.
     */
    val downloadReady: Deferred<Unit> = _downloadReady

    internal fun init(dataDir: String) {
        val cacheDir = File(dataDir, DeltacordConstants.CACHE_DIR).apply { mkdirs() }
        val filesDir = File(dataDir, DeltacordConstants.FILES_DIR).apply { mkdirs() }

        bundle = File(cacheDir, DeltacordConstants.MAIN_SCRIPT_FILE)
        manifest = File(cacheDir, DeltacordConstants.BUNDLE_MANIFEST_FILE)
        etag = File(cacheDir, ETAG_PATH)
        configFile = File(filesDir, CONFIG_PATH)

        // Repairing a stuck launch: start from a clean download.
        if (StartupRecovery.mode(dataDir) == StartupRecovery.Mode.FRESH) {
            log.w("Last launch got stuck: downloading Deltacord again from scratch")
            runCatching { bundle.delete(); etag.delete(); manifest.delete() }
        }

        config = runCatching {
            if (configFile.exists()) DeltacordJson.decodeFromString<LoaderConfig>(configFile.readText())
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
                etag = if (etag.exists() && hasBundle()) etag.readText() else null,
                timeoutMillis = if (userInitiated) null else DOWNLOAD_LIMIT.inWholeMilliseconds,
            )

            when (result) {
                is ETagFetchResult.Fetched -> {
                    // An empty or cut-off answer (a captive portal, a filter) is not Deltacord.
                    if (result.bytes.size < 50_000) error("Download too small (${result.bytes.size} bytes)")
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
            // Without a download, the built-in copy runs, so only say so when asked to retry.
            if (userInitiated) showErrorDialog(e)
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

    /** A downloaded Deltacord is there (not an empty placeholder). */
    fun hasBundle(): Boolean = ::bundle.isInitialized && bundle.length() > 50_000

    fun readBundleManifest(): String? {
        if (manifest.exists()) return manifest.readText()
        return null
    }

    private fun showUpdateDialog() = withAppActivity { activity ->
        activity.runOnUiThread {
            AlertDialog.Builder(activity)
                .setTitle("Deltacord Update Downloaded")
                .setMessage("A reload is required for changes to take effect.")
                .setPositiveButton("Reload") { d, _ -> reloadApp(); d.dismiss() }
                .setNegativeButton("Later") { d, _ -> d.dismiss() }
                .show()
        }
    }

    private fun showSuccessDialog() = withAppActivity { activity ->
        activity.runOnUiThread {
            AlertDialog.Builder(activity)
                .setTitle("Deltacord Update Successful")
                .setMessage("A reload is required for changes to take effect.")
                .setPositiveButton("Reload") { d, _ -> reloadApp(); d.dismiss() }
                .setNegativeButton("Later") { d, _ -> d.dismiss() }
                .show()
        }
    }

    private fun showErrorDialog(e: Throwable) = withAppActivity { activity ->
        activity.runOnUiThread {
            AlertDialog.Builder(activity)
                .setTitle("Deltacord Update Failed")
                .setMessage(
                    """
                    Unable to download the latest version of Deltacord.
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
 * Wires [DeltacordUpdater] into the lifecycle. Loads the loader config once the target [android.content.Context]
 * is available, then kicks off the first download.
 */
val deltacordUpdater by tweak {
    DeltacordUpdater.init(appInfo.dataDir)

    withAppContext { ctx ->
        DeltacordUpdater.downloadScript(userInitiated = false, showDialog = false)
    }
}
