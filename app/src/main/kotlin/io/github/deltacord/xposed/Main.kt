package io.github.deltacord.xposed

import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.deltacord.bridge.DeltacordBridge
import io.github.deltacord.xposed.api.HostScope
import io.github.deltacord.xposed.tweaks.*
import io.github.deltacord.xposed.tweaks.bridge.nativeSound
import io.github.deltacord.xposed.tweaks.bridge.nativeFilePicker
import io.github.deltacord.xposed.tweaks.bridge.nativeImage
import io.github.deltacord.xposed.tweaks.base.lifecycleSupport
import io.github.deltacord.xposed.tweaks.base.scriptLoader
import io.github.deltacord.xposed.tweaks.bridge.DeltacordBridgeRegistry
import io.github.deltacord.xposed.tweaks.bridge.additionalBridgeMethods
import io.github.deltacord.xposed.tweaks.bridge.deltacordBridgeSupport
import io.github.deltacord.xposed.tweaks.legacy.appearance.fonts
import io.github.deltacord.xposed.tweaks.legacy.appearance.sysColors
import io.github.deltacord.xposed.tweaks.legacy.appearance.themes
import io.github.deltacord.xposed.tweaks.legacy.deltacordPayloadGlobal
import io.github.deltacord.xposed.tweaks.plugins.pluginInstallMethods
import io.github.deltacord.xposed.tweaks.plugins.pluginLoader
import io.github.deltacord.xposed.tweaks.plugins.pluginMethods
import io.github.deltacord.xposed.tweaks.plugins.pluginStates
import io.github.deltacord.xposed.tweaks.plugins.repos.pluginRepos

private lateinit var modulePath: String

@Suppress("UNUSED")
class Main : IXposedHookLoadPackage, IXposedHookZygoteInit {
    @Volatile
    private var hooked = false

    private val tweaks: List<TweakSpec> = listOf(
        // Framework
        lifecycleSupport,
        deltacordBridgeSupport,
        scriptLoader,
        discordVersionRetriever,

        // Static patches
        fixResources,

        // Persistence
        caches,
        pluginStates,
        pluginRepos,

        // Async updater
        deltacordUpdater,
        deltacordBundleManifest,

        // Consumers
        recoveryCompanion,
        playDeliveryFix,
        startupAnimation,
        freezeWatchdog,
        discordDevSupport,
        additionalBridgeMethods,
        nativeSound,
        nativeFilePicker,
        nativeImage,
        fonts,
        themes,
        sysColors,
        pluginLoader,
        pluginMethods,
        pluginInstallMethods,
        deltacordScriptLoader,
        deltacordPayloadGlobal,
    )

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        modulePath = startupParam.modulePath
    }

    override fun handleLoadPackage(param: XC_LoadPackage.LoadPackageParam) {
        // Only hook the main process.
        // Discord uses ProcessPhoenix to spawn a ":phoenix" process to restart the app. It will cause concurrency issues.
        if (param.processName != param.packageName) return

        if (hooked) return
        hooked = true

        val ctx = HostScopeImpl(
            modulePath = modulePath,
            appInfo = param.appInfo,
            classLoader = param.classLoader,
        )
        DexFinder.init(param.appInfo, param.classLoader, modulePath)
        for (spec in tweaks) spec.applyTo(ctx)
        DexFinder.release()
    }
}

private class HostScopeImpl(
    override val modulePath: String,
    override val appInfo: ApplicationInfo,
    override val classLoader: ClassLoader,
) : HostScope {
    override val bridge: DeltacordBridge get() = DeltacordBridgeRegistry
    override val dexFinder: DexFinder get() = DexFinder
    override fun withAppContext(block: (Context) -> Unit) = io.github.deltacord.xposed.tweaks.base.withAppContext(block)
    override fun withAppActivity(block: (Activity) -> Unit) =
        io.github.deltacord.xposed.tweaks.base.withAppActivity(block)
}