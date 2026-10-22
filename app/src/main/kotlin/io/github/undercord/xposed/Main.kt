package io.github.undercord.xposed

import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.undercord.bridge.UndercordBridge
import io.github.undercord.xposed.api.HostScope
import io.github.undercord.xposed.tweaks.*
import io.github.undercord.xposed.tweaks.base.lifecycleSupport
import io.github.undercord.xposed.tweaks.base.scriptLoader
import io.github.undercord.xposed.tweaks.bridge.UndercordBridgeRegistry
import io.github.undercord.xposed.tweaks.bridge.additionalBridgeMethods
import io.github.undercord.xposed.tweaks.bridge.undercordBridgeSupport
import io.github.undercord.xposed.tweaks.legacy.appearance.fonts
import io.github.undercord.xposed.tweaks.legacy.appearance.sysColors
import io.github.undercord.xposed.tweaks.legacy.appearance.themes
import io.github.undercord.xposed.tweaks.legacy.undercordPayloadGlobal
import io.github.undercord.xposed.tweaks.plugins.pluginInstallMethods
import io.github.undercord.xposed.tweaks.plugins.pluginLoader
import io.github.undercord.xposed.tweaks.plugins.pluginMethods
import io.github.undercord.xposed.tweaks.plugins.pluginStates
import io.github.undercord.xposed.tweaks.plugins.repos.pluginRepos

private lateinit var modulePath: String

@Suppress("UNUSED")
class Main : IXposedHookLoadPackage, IXposedHookZygoteInit {
    @Volatile
    private var hooked = false

    private val tweaks: List<TweakSpec> = listOf(
        // Framework
        lifecycleSupport,
        undercordBridgeSupport,
        scriptLoader,
        discordVersionRetriever,

        // Static patches
        fixResources,

        // Persistence
        caches,
        pluginStates,
        pluginRepos,

        // Async updater
        undercordUpdater,
        undercordBundleManifest,

        // Consumers
        recoveryCompanion,
        discordDevSupport,
        additionalBridgeMethods,
        fonts,
        themes,
        sysColors,
        pluginLoader,
        pluginMethods,
        pluginInstallMethods,
        undercordScriptLoader,
        undercordPayloadGlobal,
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
    override val bridge: UndercordBridge get() = UndercordBridgeRegistry
    override val dexFinder: DexFinder get() = DexFinder
    override fun withAppContext(block: (Context) -> Unit) = io.github.undercord.xposed.tweaks.base.withAppContext(block)
    override fun withAppActivity(block: (Activity) -> Unit) =
        io.github.undercord.xposed.tweaks.base.withAppActivity(block)
}