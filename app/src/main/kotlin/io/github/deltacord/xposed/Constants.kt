package io.github.deltacord.xposed

object DeltacordConstants {
    const val TARGET_PACKAGE = "com.discord"

    // @TODO: Migration to deltacord named dir
    const val FILES_DIR = "files/pyoncord"
    const val CACHE_DIR = "cache/deltacord"
    const val MAIN_SCRIPT_FILE = "bundle.js"
    const val BUNDLE_MANIFEST_FILE = "manifest.json"
    const val PRELOADS_DIR = "preloads"

    const val LOADER_NAME = "DeltacordXposed"
    val LOADER_VERSION
        get() = BuildConfig.VERSION_NAME
    val USER_AGENT
        get() = "DeltacordXposed/$LOADER_VERSION"

    /**
     * Fallback Hermes bundle shipped inside this APK's `assets/` directory.
     * Loaded by [io.github.deltacord.xposed.tweaks.deltacordScriptLoader] when the cached `bundle.js` isn't available.
     */
    const val FALLBACK_BUNDLE_ASSET = "assets://deltacord.bundle"
}

/**
 * Hermes bytecode assets shipped inside the Xposed module APK.
 */
val scriptAssets = emptyList<String>()
