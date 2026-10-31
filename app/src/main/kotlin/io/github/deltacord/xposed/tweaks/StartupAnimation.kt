package io.github.deltacord.xposed.tweaks

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Instrumentation
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.Toast
import io.github.deltacord.reloadApp
import io.github.deltacord.Logger
import io.github.deltacord.xposed.DeltacordConstants
import de.robv.android.xposed.XposedBridge
import io.github.deltacord.xposed.hook
import io.github.deltacord.xposed.methodHook
import io.github.deltacord.xposed.method
import io.github.deltacord.xposed.tweak
import java.io.File
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.max

/**
 * Deltacord's startup animation, shown instead of Discord's logo while Discord starts.
 *
 * One of fourteen styles (chosen in Deltacord > Extras, or random each launch) draws DELTACORD in
 * Determination Mono along the long side of the screen, in one of a few colour variants (a rare
 * golden one about one launch in fifty). It plays for at least [StartupScene.PLAY_MS] and stays
 * (gently glowing, sparkling) until Discord has drawn its first screen, then fades away.
 *
 * Discord normally holds its logo on screen until it's ready by blocking drawing; while the
 * animation is up that block is lifted (Discord's own check still decides when it's ready), so the
 * animation can be seen. It can't be skipped: taps are absorbed and do nothing.
 *
 * Turned off by creating `files/pyoncord/startup-animation-off` (Deltacord > Extras does this).
 */
val startupAnimation by tweak {
    val dataDir = appInfo.dataDir
    if (File(dataDir, "${DeltacordConstants.FILES_DIR}/startup-animation-off").exists()) {
        log.i("Startup animation is turned off")
        return@tweak
    }

    StartupAnimation.log = log
    StartupAnimation.dataDir = dataDir
    BuiltinFont.dataDir = dataDir
    StartupAnimation.modulePath = modulePath
    StartupAnimation.waitingForDiscord = true

    // Let the window draw while the animation is up, and learn when Discord says it's ready.
    ViewTreeObserver::class.java.method("addOnPreDrawListener", ViewTreeObserver.OnPreDrawListener::class.java).hook {
        before {
            val listener = args[0] as? ViewTreeObserver.OnPreDrawListener ?: return@before
            if (listener is DrawGate) return@before
            if (!StartupAnimation.waitingForDiscord && !StartupAnimation.creatingLauncher) return@before
            args[0] = StartupAnimation.gateFor(listener, fromLauncherCreate = StartupAnimation.creatingLauncher)
        }
    }
    ViewTreeObserver::class.java.method("removeOnPreDrawListener", ViewTreeObserver.OnPreDrawListener::class.java).hook {
        before {
            val listener = args[0] as? ViewTreeObserver.OnPreDrawListener ?: return@before
            StartupAnimation.existingGate(listener)?.let { args[0] = it }
        }
    }

    // Discord can recreate its screen while starting (for example on a theme change). Follow it, so
    // the animation and the stuck report move to the new screen, and a stale "keep the logo"
    // hold on it can't block Discord once it has started.
    XposedBridge.hookAllMethods(Instrumentation::class.java, "callActivityOnCreate", methodHook {
        before {
            val activity = args[0] as? Activity ?: return@before
            if (StartupAnimation.isLauncher(activity)) StartupAnimation.creatingLauncher = true
        }
        after {
            val activity = args[0] as? Activity ?: return@after
            if (!StartupAnimation.isLauncher(activity)) return@after
            StartupAnimation.creatingLauncher = false
            runCatching { StartupAnimation.onLauncherCreated(activity) }
                .onFailure { log.e("Couldn't follow the recreated screen", it) }
        }
    }.build())

    withAppActivity { activity ->
        activity.runOnUiThread {
            try {
                StartupAnimation.show(activity)
            } catch (e: Throwable) {
                log.e("Couldn't show the startup animation", e)
                StartupAnimation.waitingForDiscord = false
            }
        }
    }
}

/** Wraps a pre-draw listener so that, while the animation is up, it can't stop the window drawing. */
private class DrawGate(
    val original: ViewTreeObserver.OnPreDrawListener,
    /** Added while Discord's main screen was being created (where its launch-logo hold lives). */
    val fromLauncherCreate: Boolean,
) : ViewTreeObserver.OnPreDrawListener {
    var blocked = false

    override fun onPreDraw(): Boolean {
        val result = original.onPreDraw()
        if (!result) {
            blocked = true
            // Discord still wants its logo. Draw anyway while the animation covers the screen.
            if (StartupAnimation.waitingForDiscord) return true
            // A recreated screen waiting for a "ready" Discord already sent: don't hold it forever.
            if (fromLauncherCreate && StartupAnimation.discordStarted) return true
        } else if (blocked) {
            blocked = false
            StartupAnimation.onDiscordReady()
        }
        return result
    }
}

private object StartupAnimation {
    @Volatile
    var waitingForDiscord = false

    var log: Logger? = null
    var modulePath: String? = null
    var dataDir = ""
    private val processStart = System.currentTimeMillis()
    private var activityRef: WeakReference<Activity>? = null
    private var dialog: AlertDialog? = null

    /** Deltacord said Discord opened during this launch (so a later logo hold is stale). */
    @Volatile
    var discordStarted = false

    @Volatile
    var creatingLauncher = false

    private var launcherClass: String? = null

    fun isLauncher(activity: Activity) = launcherClass != null && activity.javaClass.name == launcherClass

    /** Discord's main screen was created again: carry the animation (if still up) over to it. */
    fun onLauncherCreated(activity: Activity) {
        val old = activityRef?.get()
        if (old === activity) return
        log?.i("Discord recreated its screen")
        activityRef = WeakReference(activity)
        runCatching { dialog?.dismiss() }
        dialog = null

        val current = view ?: return
        val typeface = current.typeface
        val moved = AnimationView(activity, typeface, startOffsetMs = AnimationView.PLAY_MS, scene = current.scene).apply {
            targetProgress = current.targetProgress
            status = current.status
            translationZ = 10_000f
        }
        (current.parent as? ViewGroup)?.removeView(current)
        activity.window.decorView.post {
            (activity.window?.decorView as? ViewGroup)?.addView(
                moved, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )
        }
        view = moved
    }

    private val gates = WeakHashMap<ViewTreeObserver.OnPreDrawListener, DrawGate>()
    private var view: AnimationView? = null
    private var discordReady = false
    private var sawBlockingListener = false
    private val handler = Handler(Looper.getMainLooper())

    /** After the animation has played, how long to wait for Discord before explaining what's wrong. */
    private const val MAX_EXTRA_MS = 30_000L
    /** After the animation gives up, how much longer Discord gets before the stuck message shows. */
    private const val STUCK_PROMPT_MS = 15_000L

    fun gateFor(listener: ViewTreeObserver.OnPreDrawListener, fromLauncherCreate: Boolean): DrawGate =
        synchronized(gates) { gates.getOrPut(listener) { DrawGate(listener, fromLauncherCreate) } }

    fun existingGate(listener: ViewTreeObserver.OnPreDrawListener): DrawGate? =
        synchronized(gates) { gates[listener] }

    fun onDiscordReady() {
        sawBlockingListener = true
        discordReady = true
        handler.post { maybeFinish() }
    }

    fun show(activity: Activity) {
        val decor = activity.window?.decorView as? ViewGroup ?: return
        val typeface = modulePath?.let { BuiltinFont.get(it) }
            ?: Typeface.create(Typeface.MONOSPACE, Typeface.BOLD).also {
                log?.w("Startup animation font unavailable: ${BuiltinFont.problem}")
            }

        launcherClass = activity.javaClass.name
        val animation = AnimationView(activity, typeface, scene = pickScene(activity))
        animation.translationZ = 10_000f
        decor.addView(animation, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        view = animation
        activityRef = WeakReference(activity)
        shownAtMs = SystemClock.uptimeMillis()

        // Check regularly once the animation has played; give up waiting after a while.
        val started = SystemClock.uptimeMillis()
        val check = object : Runnable {
            override fun run() {
                if (view == null) return
                runCatching { updateProgress() }
                runCatching { maybeFinish() }
                if (view == null) return
                val elapsed = SystemClock.uptimeMillis() - started
                if (elapsed > AnimationView.PLAY_MS + MAX_EXTRA_MS) {
                    val report = runCatching { StuckReport.build(dataDir, processStart) }.getOrNull()
                    if (report == null) {
                        // Deltacord says Discord is ready; the signal just didn't reach us in time.
                        log?.w("Discord didn't report ready in time; closing the startup animation")
                        finish(force = true)
                    } else {
                        log?.w("Discord is stuck starting:\n$report")
                        StuckReport.flagStuckLaunch(dataDir)
                        runCatching {
                            File(dataDir, "${DeltacordConstants.FILES_DIR}/freeze-report.txt").writeText("Startup took too long.\n\n$report")
                        }
                        // Step aside for Discord's own screen, and if Discord still hasn't opened a
                        // little later, say so with a way out (someone stuck on the logo can't reach
                        // Doctor to copy the report).
                        finish(force = true)
                        handler.postDelayed(stuckCheck(), STUCK_PROMPT_MS)
                    }
                    return
                }
                handler.postDelayed(this, 200)
            }
        }
        handler.postDelayed(check, 200)
    }

    /** The style chosen in Deltacord > Extras (or a random one), in random colours. */
    private fun pickScene(activity: Activity): StartupScene {
        val setting = runCatching {
            File(dataDir, "${DeltacordConstants.FILES_DIR}/startup-animation-style").takeIf { it.exists() }?.readText()?.trim()
        }.getOrNull()
        val random = java.util.Random()
        val style = StartupStyles.pickStyle(setting, random)
        val palette = StartupStyles.pickPalette(random)
        log?.i("Startup animation: $style in ${palette.id}")
        return StartupScene(style, palette, random.nextInt(10_000), activity.resources.displayMetrics.density)
    }

    /** Turns Deltacord's startup record into the status kept for the stuck report. */
    private fun updateProgress() {
        val animation = view ?: return
        val launch = StuckReport.thisLaunch(dataDir, processStart)
        if (launch == null) {
            animation.targetProgress = 0.08f
            animation.status = "Loading Deltacord…"
            return
        }

        if (launch.optBoolean("opened")) discordStarted = true
        val build = launch.optString("version").substringBefore("-")
        val stages = launch.optJSONArray("stages")
        val names = ArrayList<String>()
        if (stages != null) for (i in 0 until stages.length()) names.add(stages.optJSONObject(i)?.optString("name") ?: continue)

        val started = LinkedHashSet<String>()
        var stepsDone = 0
        for (name in names) {
            when {
                name.startsWith("start: ") -> started.add(name.removePrefix("start: "))
                name.startsWith("done: ") -> if (started.remove(name.removePrefix("done: "))) stepsDone++
            }
        }
        val pending = started.lastOrNull()
        val last = names.lastOrNull() ?: ""

        val (progress, text) = when {
            launch.optBoolean("uiReady") -> 1f to "Ready!"
            launch.optBoolean("opened") -> {
                val plugin = started.lastOrNull { it.startsWith("plugin ") }?.removePrefix("plugin ")
                (if (launch.optBoolean("ready")) 0.93f else 0.85f) to
                    (if (plugin != null) "Starting plugin: $plugin" else "Waiting for Discord…")
            }
            last.startsWith("reading Discord") -> 0.18f to "Reading Discord…"
            last.startsWith("loading Deltacord") -> 0.28f to "Loading Deltacord…"
            last.startsWith("failed") -> 0.7f to "Problem starting: ${last.removePrefix("failed: ").take(40)}"
            names.any { it == "setting up" } -> (0.35f + 0.42f * (stepsDone / 13f).coerceAtMost(1f)) to
                (if (pending != null) "Setting up: $pending" else "Setting up…")
            else -> 0.12f to "Starting Deltacord…"
        }
        animation.targetProgress = max(animation.targetProgress, progress)
        animation.status = buildString {
            append(text)
            if (launch.optBoolean("minimal")) append(" (safe start)")
            if (build.isNotEmpty()) append("  ·  ").append(build)
        }
    }

    private var shownAtMs = 0L

    private fun maybeFinish() {
        val animation = view ?: return
        if (!animation.played) return

        // Deltacord running: it says when Discord's real screen is up (connected, or the login
        // screen), so the animation stays over Discord's own loading screen until then.
        val launch = StuckReport.thisLaunch(dataDir, processStart)
        if (launch != null) {
            if (launch.optBoolean("uiReady")) finish(force = false)
            return
        }

        // No word from Deltacord this launch (Discord running without it): go by what Discord draws.
        val sinceShown = SystemClock.uptimeMillis() - shownAtMs
        if (sinceShown > 8_000 && (discordReady || (!sawBlockingListener && hasDiscordContent(animation.rootView)))) {
            finish(force = false)
        }
    }

    /** Checks again whether Discord is still stuck; if so, shows the report with a way out. */
    private fun stuckCheck(): Runnable = object : Runnable {
        override fun run() {
            val report = runCatching { StuckReport.build(dataDir, processStart) }.getOrNull() ?: return
            showStuckReport(report, this)
        }
    }

    /**
     * Discord hasn't opened long after the animation: explain what's going on instead of sitting on
     * a black screen, with a report to copy and a way into Discord without Deltacord.
     */
    private fun showStuckReport(report: String, check: Runnable) {
        val activity = activityRef?.get()?.takeIf { !it.isFinishing && !it.isDestroyed } ?: return finish(force = true)
        if (dialog?.isShowing == true) return
        try {
            dialog = AlertDialog.Builder(activity)
                .setTitle("Deltacord is stuck starting")
                .setMessage("Discord hasn't opened after almost a minute. \"Open without Deltacord\" gets you into Discord this once (Deltacord comes back next time). Copy the report first and send it so it can be fixed.\n\n$report")
                .setCancelable(false)
                .setPositiveButton("Copy report") { _, _ ->
                    runCatching {
                        val clipboard = activity.getSystemService(ClipboardManager::class.java)
                        clipboard.setPrimaryClip(ClipData.newPlainText("Deltacord report", report))
                        Toast.makeText(activity, "Report copied", Toast.LENGTH_SHORT).show()
                    }
                    // Ask again in a bit, in case it's still stuck.
                    dialog = null
                    handler.postDelayed(check, 1500)
                }
                .setNegativeButton("Open without Deltacord") { _, _ ->
                    runCatching {
                        File(dataDir, "${DeltacordConstants.FILES_DIR}/${StuckReport.SKIP_ONCE_FILE}").apply {
                            parentFile?.mkdirs()
                            writeText("1")
                        }
                    }
                    reloadApp()
                }
                .setNeutralButton("Keep waiting") { _, _ -> handler.postDelayed(check, 30_000) }
                .show()
        } catch (e: Throwable) {
            log?.e("Couldn't show the stuck report", e)
            finish(force = true)
        }
    }

    private fun finish(force: Boolean) {
        runCatching { dialog?.dismiss() }
        dialog = null
        val animation = view ?: return
        view = null
        waitingForDiscord = false
        animation.animate()
            .alpha(0f)
            .setDuration(if (force) 200 else 450)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) {
                    (animation.parent as? ViewGroup)?.removeView(animation)
                }
            })
            .start()
    }

    /** Whether Discord's React screen has drawn anything yet. */
    private fun hasDiscordContent(root: View): Boolean {
        val content = root.findViewById<ViewGroup>(android.R.id.content) ?: return false
        return findReactRoot(content)?.let { it.childCount > 0 && it.width > 0 } ?: false
    }

    private fun findReactRoot(group: ViewGroup): ViewGroup? {
        val name = group.javaClass.name
        if (name.contains("ReactRootView") || name.contains("ReactSurfaceView")) return group
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i) as? ViewGroup ?: continue
            findReactRoot(child)?.let { return it }
        }
        return null
    }
}

/**
 * Draws one of the start-up animation styles ([StartupScene]) over Discord while it starts.
 * The word runs along the long side of the screen (turned 90 degrees on a phone).
 */
private class AnimationView(
    context: Context,
    val typeface: Typeface,
    /** Skip ahead (used when moving to a recreated screen, so the animation doesn't replay). */
    startOffsetMs: Long = 0,
    /** Which style and colours this launch plays; a recreated screen carries on with the same. */
    val scene: StartupScene,
) : View(context) {
    companion object {
        const val PLAY_MS = StartupScene.PLAY_MS
    }

    private val start = SystemClock.uptimeMillis() - startOffsetMs
    var played = false
        private set

    /** Startup progress, 0..1, and what's happening (kept for the stuck report, no longer drawn). */
    @Volatile
    var targetProgress = 0.03f
    @Volatile
    var status = "Starting…"

    init {
        setBackgroundColor(Color.BLACK)
        // Absorb taps so they neither skip the animation nor reach Discord underneath.
        isClickable = true
        isFocusable = true
        setOnTouchListener { _, _ -> true }
        // Shadow-based glow needs a software layer on older Android versions.
        if (android.os.Build.VERSION.SDK_INT < 28) setLayerType(View.LAYER_TYPE_SOFTWARE, null)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val t = SystemClock.uptimeMillis() - start
        if (t >= PLAY_MS) played = true
        try {
            scene.draw(canvas, t, width, height)
        } catch (e: Throwable) {
            // A drawing problem must never keep Discord hidden: count the animation as played.
            played = true
            canvas.drawColor(Color.BLACK)
        }
        postInvalidateOnAnimation()
    }
}
