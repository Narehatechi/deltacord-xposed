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
import android.graphics.Paint
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
import kotlin.math.min

/**
 * Deltacord's startup animation, shown instead of Discord's logo while Discord starts.
 *
 * In the style of deltarune.com: on black, "DEL", then "TA", then "CORD" glow in, one after the
 * other, in white Determination Mono, as one word running along the long side of the screen.
 * Then a little "by Narehatechi" spins in near the bottom edge and wobbles.
 * It stays (gently glowing) until Discord has drawn its first screen, then fades away.
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
        val moved = AnimationView(activity, typeface, startOffsetMs = AnimationView.PLAY_MS).apply {
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
        val animation = AnimationView(activity, typeface)
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
                        // Quietly: step aside for Discord's own screen; the report is saved for
                        // Deltacord > Doctor > Copy report.
                        runCatching {
                            File(dataDir, "${DeltacordConstants.FILES_DIR}/freeze-report.txt").writeText("Startup took too long.\n\n$report")
                        }
                        finish(force = true)
                    }
                    return
                }
                handler.postDelayed(this, 200)
            }
        }
        handler.postDelayed(check, 200)
    }

    /** Turns Deltacord's startup record into the progress bar and the line under it. */
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

    /**
     * Discord hasn't opened long after the animation: explain what's going on instead of sitting on
     * a black screen, with a report to copy and a way into Discord without Deltacord.
     */
    private fun showStuckReport(report: String, check: Runnable) {
        val activity = activityRef?.get() ?: return finish(force = true)
        if (dialog?.isShowing == true) return
        try {
            dialog = AlertDialog.Builder(activity)
                .setTitle("Deltacord is stuck starting")
                .setMessage("Discord hasn't opened. Copy this report and send it so it can be fixed.\n\n$report")
                .setCancelable(false)
                .setPositiveButton("Copy report") { _, _ ->
                    runCatching {
                        val clipboard = activity.getSystemService(ClipboardManager::class.java)
                        clipboard.setPrimaryClip(ClipData.newPlainText("Deltacord report", report))
                        Toast.makeText(activity, "Report copied", Toast.LENGTH_SHORT).show()
                    }
                    // Ask again in a bit, in case it's still stuck.
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
                .setNeutralButton("Keep waiting") { _, _ -> handler.postDelayed(check, 20_000) }
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
 * Draws the animation: black, then DEL / TA / CORD glowing in, then a slow breathing glow.
 * The word runs along the long side of the screen (turned 90 degrees).
 */
@SuppressLint("ViewConstructor")
private class AnimationView(
    context: Context,
    val typeface: Typeface,
    /** Skip ahead (used when moving to a recreated screen, so the word doesn't replay). */
    startOffsetMs: Long = 0,
) : View(context) {
    companion object {
        private val PARTS = arrayOf("DEL", "TA", "CORD")
        private const val WORD = "DELTACORD"

        /** When each part starts appearing, and how long it takes (ms). */
        private val PART_START = longArrayOf(250, 900, 1550)
        private const val PART_FADE = 520L
        /** The whole word pulses brighter once it's complete. */
        private const val PULSE_START = 2100L
        private const val PULSE_LENGTH = 600L
        /** "by Narehatechi" spins in near the bottom edge, then wobbles. */
        private const val CREDIT = "by Narehatechi"
        private const val CREDIT_START = 2150L
        private const val CREDIT_SPIN = 750L
        const val PLAY_MS = 3200L
    }

    private val start = SystemClock.uptimeMillis() - startOffsetMs
    var played = false
        private set

    /** Startup progress, 0..1, and what's happening (set from the startup record). */
    @Volatile
    var targetProgress = 0.03f
    @Volatile
    var status = "Starting…"
    private var shownProgress = 0f

    private val density = resources.displayMetrics.density
    private val core = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.typeface = typeface
        color = Color.WHITE
        textAlign = Paint.Align.LEFT
    }
    private val glow = Paint(core)
    private val halo = Paint(core)
    private val credit = Paint(core).apply { textAlign = Paint.Align.CENTER }

    init {
        setBackgroundColor(Color.BLACK)
        // Absorb taps so they neither skip the animation nor reach Discord underneath.
        isClickable = true
        isFocusable = true
        setOnTouchListener { _, _ -> true }
        // Shadow-based glow on text needs a software layer on older Android versions.
        if (android.os.Build.VERSION.SDK_INT < 28) setLayerType(View.LAYER_TYPE_SOFTWARE, null)
    }

    private fun fade(t: Long, from: Long, length: Long): Float =
        ((t - from).toFloat() / length).coerceIn(0f, 1f).let { it * it * (3 - 2 * it) } // smoothstep

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val t = SystemClock.uptimeMillis() - start
        if (t >= PLAY_MS) played = true

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        // Turned 90 degrees: the word runs along the long side.
        val along = max(w, h)
        val across = min(w, h)
        canvas.save()
        canvas.rotate(90f, w / 2, h / 2)
        canvas.translate((w - along) / 2, (h - across) / 2)

        // Size the word to fill most of the long side, without getting too tall for the short side.
        // Drawn from Determination's letter outlines (DeterminationGlyphs), not a loaded font.
        val sizeForLength = (along * 0.80f) / DeterminationGlyphs.width(WORD, 1f)
        val sizeForHeight = across * 0.42f
        val size = min(sizeForLength, sizeForHeight)

        val wordWidth = DeterminationGlyphs.width(WORD, size)
        val x0 = (along - wordWidth) / 2
        // Capitals are 1152 units tall (of 2048): centre them.
        val baseline = across / 2 + size * (1152f / DeterminationGlyphs.UNITS_PER_EM) / 2

        // A brighter pulse once the word is complete, then a slow breath while Discord finishes starting.
        val pulse = if (t in PULSE_START until PULSE_START + PULSE_LENGTH) {
            val p = (t - PULSE_START).toFloat() / PULSE_LENGTH
            kotlin.math.sin(p * Math.PI).toFloat()
        } else 0f
        val breath = if (t >= PULSE_START + PULSE_LENGTH) {
            ((kotlin.math.sin((t - PULSE_START - PULSE_LENGTH) / 900.0) + 1) / 2).toFloat() * 0.35f
        } else 0f

        var x = x0
        for ((index, part) in PARTS.withIndex()) {
            val appear = fade(t, PART_START[index], PART_FADE)
            if (appear > 0f) {
                // Each part arrives with a strong glow that settles down.
                val settle = 1f - fade(t, PART_START[index] + PART_FADE / 2, PART_FADE * 2)
                val glowAmount = 0.55f + 0.45f * settle + pulse * 0.6f + breath

                halo.alpha = (90 * appear * glowAmount).toInt().coerceIn(0, 255)
                halo.setShadowLayer(size * 0.38f + density * 6, 0f, 0f, Color.WHITE)
                drawWord(canvas, part, x, baseline, size, halo)

                glow.alpha = (170 * appear * glowAmount).toInt().coerceIn(0, 255)
                glow.setShadowLayer(size * 0.12f + density * 2, 0f, 0f, Color.WHITE)
                drawWord(canvas, part, x, baseline, size, glow)

                core.alpha = (255 * appear).toInt()
                drawWord(canvas, part, x, baseline, size, core)
            }
            x += DeterminationGlyphs.width(part, size)
        }

        canvas.restore()

        drawCredit(canvas, t, w, h)

        postInvalidateOnAnimation()
    }

    private fun drawWord(canvas: Canvas, text: String, x: Float, baseline: Float, size: Float, paint: Paint) {
        canvas.save()
        canvas.translate(x, baseline)
        canvas.drawPath(DeterminationGlyphs.path(text, size), paint)
        canvas.restore()
    }

    /** Ease out with a little overshoot, so the spin lands with a bounce. */
    private fun backOut(x: Float): Float {
        val c1 = 1.70158f
        val c3 = c1 + 1
        val p = x - 1
        return 1 + c3 * p * p * p + c1 * p * p
    }

    /** "by Narehatechi": spins in near the bottom (short) edge, then wobbles like a splash text. */
    private fun drawCredit(canvas: Canvas, t: Long, w: Float, h: Float) {
        if (t < CREDIT_START) return
        val spin = ((t - CREDIT_START).toFloat() / CREDIT_SPIN).coerceIn(0f, 1f)
        val eased = backOut(spin)
        val since = (t - CREDIT_START).toDouble()

        val scale = max(0f, eased) * (1f + 0.045f * kotlin.math.sin(since / 170.0).toFloat() * spin)
        val angle = -720f * (1f - eased) + 4f * kotlin.math.sin(since / 320.0).toFloat() * spin
        if (scale <= 0.01f) return

        credit.textSize = min(w, h) * 0.075f
        credit.alpha = (255 * spin.coerceAtMost(1f)).toInt().coerceIn(0, 255)
        credit.setShadowLayer(credit.textSize * 0.35f, 0f, 0f, Color.WHITE)

        val cx = w / 2
        val cy = h * 0.955f
        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(angle)
        canvas.scale(scale, scale)
        // Centred on the x-height-ish middle of the letters.
        drawWord(canvas, CREDIT, -DeterminationGlyphs.width(CREDIT, credit.textSize) / 2, credit.textSize * 0.28f, credit.textSize, credit)
        canvas.restore()
    }
}
