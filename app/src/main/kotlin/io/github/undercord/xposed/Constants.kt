package io.github.undercord.xposed

object UndercordConstants {
    const val TARGET_PACKAGE = "com.discord"

    // @TODO: Migration to undercord named dir
    const val FILES_DIR = "files/pyoncord"
    const val CACHE_DIR = "cache/undercord"
    const val MAIN_SCRIPT_FILE = "bundle.js"
    const val BUNDLE_MANIFEST_FILE = "manifest.json"
    const val PRELOADS_DIR = "preloads"

    const val LOADER_NAME = "UndercordXposed"
    val LOADER_VERSION
        get() = BuildConfig.VERSION_NAME
    val USER_AGENT
        get() = "UndercordXposed/$LOADER_VERSION"

    /**
     * Fallback Hermes bundle shipped inside this APK's `assets/` directory.
     * Loaded by [io.github.undercord.xposed.tweaks.undercordScriptLoader] when the cached `bundle.js` isn't available.
     */
    const val FALLBACK_BUNDLE_ASSET = "assets://undercord.bundle"
}

/**
 * Hermes bytecode assets shipped inside the Xposed module APK.
 */
val scriptAssets = emptyList<String>()
