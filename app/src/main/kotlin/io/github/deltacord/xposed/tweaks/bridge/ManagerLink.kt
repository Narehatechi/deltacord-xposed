package io.github.deltacord.xposed.tweaks.bridge

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import de.robv.android.xposed.XposedBridge
import io.github.deltacord.bridge.asDelegate
import io.github.deltacord.xposed.api.registerNativeAsyncMethod
import io.github.deltacord.xposed.methodHook
import io.github.deltacord.xposed.tweak
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

/**
 * The link with Deltacord Manager (no root needed):
 * - The Manager sends requests as a broadcast to this app, with a reply token (a PendingIntent).
 *   Android says which app made a token, so requests are only taken from the Manager.
 * - The Manager can also open Discord with a request in the launch intent (when it isn't running).
 * - Deltacord (JS) waits for requests with `deltacord.manager.next` and answers with
 *   `deltacord.manager.status(requestId, json)`, sent back through the Manager's token.
 */
private const val MANAGER = "app.deltacord.manager"
private const val ACTION_REQUEST = "app.deltacord.REQUEST"
private const val EXTRA_REQUEST = "deltacord.request"
private const val EXTRA_REPLY = "deltacord.reply"
private const val EXTRA_STATUS = "deltacord.status"
private const val EXTRA_REPORT = "deltacord.report"
/** Opened from the Manager to get a report: startup needs a while to get stuck (or not) first. */
private const val REPORT_AFTER_LAUNCH_MS = 35_000L
/** How long `next` waits before answering null (Deltacord then asks again). */
private const val WAIT_MS = 50_000L

private val queue = ArrayDeque<String>()
private var waiter: CompletableDeferred<String?>? = null
private val lock = Any()
private val replies = ConcurrentHashMap<String, PendingIntent>()
@Volatile
private var lastReply: PendingIntent? = null
@Volatile
private var appContext: WeakReference<Context>? = null

/** Hands a request to Deltacord: to whoever is waiting, or queued for the next ask. */
private fun deliver(request: String) {
    synchronized(lock) {
        val w = waiter
        if (w != null && !w.isCompleted) {
            waiter = null
            w.complete(request)
        } else {
            queue.addLast(request)
            while (queue.size > 16) queue.removeFirst()
        }
    }
}

@Suppress("DEPRECATION")
private fun replyOf(intent: Intent): PendingIntent? =
    if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_REPLY, PendingIntent::class.java)
    else intent.getParcelableExtra(EXTRA_REPLY)

/**
 * A report, built by the module on its own thread and sent straight back: it doesn't need
 * Deltacord's JavaScript, so it works while Discord is stuck.
 */
private fun sendReport(id: String, reply: PendingIntent, delayMs: Long) {
    Thread {
        runCatching {
            if (delayMs > 0) Thread.sleep(delayMs)
            val ctx = appContext?.get() ?: return@Thread
            val report = ModuleReport.build(ctx)
            reply.send(ctx, 0, Intent().putExtra(EXTRA_REPORT, report).putExtra("deltacord.requestId", id))
        }
    }.apply { name = "deltacord-report"; isDaemon = true }.start()
}

/** Takes a request from an intent, only if the Manager made its reply token. */
private fun accept(intent: Intent?, fromLaunch: Boolean = false): Boolean {
    val request = intent?.getStringExtra(EXTRA_REQUEST) ?: return false
    val reply = runCatching { replyOf(intent) }.getOrNull() ?: return false
    if (reply.creatorPackage != MANAGER) return false
    val json = runCatching { JSONObject(request) }.getOrNull()
    if (json?.optString("action") == "report") {
        sendReport(json.optString("id"), reply, if (fromLaunch) REPORT_AFTER_LAUNCH_MS else 0)
        return true
    }
    val id = json?.optString("id").orEmpty()
    if (id.isNotEmpty()) replies[id] = reply
    lastReply = reply
    deliver(request)
    return true
}

val managerLink by tweak {
    // Requests while Discord is running.
    withAppContext { ctx ->
        appContext = WeakReference(ctx)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                accept(intent)
            }
        }
        val filter = IntentFilter(ACTION_REQUEST)
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            else ctx.registerReceiver(receiver, filter)
        }
    }

    // Requests that came with opening Discord (from the Manager's "open Deltacord").
    val fromLaunch = methodHook {
        after {
            val activity = thisObject as? Activity ?: return@after
            val intent = (args.firstOrNull() as? Intent) ?: activity.intent
            if (accept(intent, fromLaunch = true)) {
                // Once only (not again when the screen rotates).
                intent.removeExtra(EXTRA_REQUEST)
                intent.removeExtra(EXTRA_REPLY)
            }
        }
    }.build()
    XposedBridge.hookAllMethods(Activity::class.java, "onCreate", fromLaunch)
    XposedBridge.hookAllMethods(Activity::class.java, "onNewIntent", fromLaunch)

    registerNativeAsyncMethod("deltacord.manager.next") { _ ->
        val mine: CompletableDeferred<String?>
        synchronized(lock) {
            queue.removeFirstOrNull()?.let { return@registerNativeAsyncMethod it }
            // A previous ask (from before Deltacord reloaded) stops waiting.
            waiter?.complete(null)
            mine = CompletableDeferred()
            waiter = mine
        }
        withTimeoutOrNull(WAIT_MS) { mine.await() } ?: synchronized(lock) {
            if (waiter === mine) waiter = null
            if (mine.isCompleted) mine.getCompleted() else null
        }
    }

    registerNativeAsyncMethod("deltacord.manager.status") { args ->
        val argv = args.asDelegate()
        val requestId by argv.string("")
        val status by argv.string()
        val ctx = appContext?.get() ?: throw Error("Discord isn't ready")
        val reply = replies.remove(requestId) ?: lastReply ?: return@registerNativeAsyncMethod false
        reply.send(ctx, 0, Intent().putExtra(EXTRA_STATUS, status))
        true
    }
}
