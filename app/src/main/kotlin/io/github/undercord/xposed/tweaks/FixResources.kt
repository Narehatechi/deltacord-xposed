package io.github.undercord.xposed.tweaks

import android.content.res.Resources
import io.github.undercord.xposed.UndercordConstants
import io.github.undercord.xposed.hook
import io.github.undercord.xposed.method
import io.github.undercord.xposed.tweak

/**
 * Hooks [Resources.getIdentifier] to rewrite the package name to `com.discord` when the host app was repackaged.
 */
val fixResources by tweak {
    val hostPkg = appInfo.packageName
    if (hostPkg == UndercordConstants.TARGET_PACKAGE) return@tweak

    Resources::class.java.method(
        "getIdentifier",
        String::class.java,
        String::class.java,
        String::class.java,
    ).hook {
        before {
            if (args[2] == hostPkg) args[2] = UndercordConstants.TARGET_PACKAGE
        }
    }
}
