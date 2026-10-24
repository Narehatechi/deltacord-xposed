package io.github.deltacord.xposed.tweaks

import android.os.SystemClock
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import io.github.deltacord.xposed.loadClassOrNull
import io.github.deltacord.xposed.tweak

/**
 * Stops Discord freezing on its loading screen.
 *
 * Discord downloads some of its files through Google Play ("Play Asset Delivery"). Each time the
 * app comes to the front, it retries downloads that haven't finished, on the UI thread. A patched
 * app isn't installed from Google Play, so those downloads can never finish, and the retry spins
 * forever: the UI thread never gets back to drawing, and Discord sits on its loading screen.
 * (Caught by the freeze report: the UI thread was in `PlayAssetDelivery.retryPendingFetches`.)
 *
 * The retry is switched off (it can't succeed here), and as a backstop, if Discord asks for the
 * list of pending downloads over and over in a tight loop, it gets an empty list so the loop ends.
 */
val playDeliveryFix by tweak {
    val outer = classLoader.loadClassOrNull("com.discord.play_delivery.PlayAssetDelivery")
    if (outer == null) {
        log.i("No Play Asset Delivery in this Discord version")
        return@tweak
    }

    var retries = 0
    for (method in outer.declaredMethods.filter { it.name == "retryPendingFetches" }) {
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (retries++ == 0) log.i("Skipped Discord's Play download retry (it can't finish in a patched app)")
                param.result = null
            }
        })
    }

    val state = classLoader.loadClassOrNull("com.discord.play_delivery.PlayAssetDelivery\$FetchState")
    for (method in state?.declaredMethods?.filter { it.name == "snapshotPending" }.orEmpty()) {
        val empty: () -> Any? = when {
            method.returnType.isAssignableFrom(ArrayList::class.java) -> { -> ArrayList<Any?>() }
            method.returnType.isAssignableFrom(LinkedHashSet::class.java) -> { -> LinkedHashSet<Any?>() }
            method.returnType.isAssignableFrom(LinkedHashMap::class.java) -> { -> LinkedHashMap<Any?, Any?>() }
            else -> continue
        }
        val calls = ThreadLocal<LongArray>() // [window start, calls in window]
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val now = SystemClock.uptimeMillis()
                val window = calls.get() ?: LongArray(2).also { calls.set(it) }
                if (now - window[0] > 1000) {
                    window[0] = now
                    window[1] = 0
                }
                if (++window[1] > 50) {
                    if (window[1] == 51L) log.w("Discord's Play download list was read in a tight loop; ending the loop")
                    param.result = empty()
                }
            }
        })
    }
}
