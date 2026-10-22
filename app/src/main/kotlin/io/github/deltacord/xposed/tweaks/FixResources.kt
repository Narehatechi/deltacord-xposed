package io.github.deltacord.xposed.tweaks

import android.content.res.Resources
import io.github.deltacord.xposed.DeltacordConstants
import io.github.deltacord.xposed.hook
import io.github.deltacord.xposed.method
import io.github.deltacord.xposed.tweak

/**
 * Hooks [Resources.getIdentifier] to rewrite the package name to `com.discord` when the host app was repackaged.
 */
val fixResources by tweak {
    val hostPkg = appInfo.packageName
    if (hostPkg == DeltacordConstants.TARGET_PACKAGE) return@tweak

    Resources::class.java.method(
        "getIdentifier",
        String::class.java,
        String::class.java,
        String::class.java,
    ).hook {
        before {
            if (args[2] == hostPkg) args[2] = DeltacordConstants.TARGET_PACKAGE
        }
    }
}
