package io.github.deltacord.xposed.tweaks.bridge

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import de.robv.android.xposed.XposedBridge
import io.github.deltacord.bridge.asDelegate
import io.github.deltacord.xposed.api.registerNativeAsyncMethod
import io.github.deltacord.xposed.methodHook
import io.github.deltacord.xposed.tweak
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import kotlin.math.max
import kotlin.math.min

/**
 * `deltacord.image.cropScreen(uri, aspectWidth, aspectHeight, outWidth, outHeight, mime)`: Deltacord's
 * image cropper as a plain Android screen (a full-screen Dialog over Discord): pinch to zoom, drag
 * to move, − / RESET / +, CANCEL and DONE. Resolves with what Discord's cropper returns, or null if
 * cancelled. A native Dialog always closes cleanly, unlike React Native's Modal, which could leave an
 * invisible window over the app after cropping.
 */
private const val MAX_SHOWN = 2048
private const val MAX_ZOOM = 6f

@Volatile
private var topActivity: WeakReference<Activity>? = null

/** The picture on screen with the crop frame over it. */
@SuppressLint("ViewConstructor")
private class CropView(context: Context, private val bitmap: Bitmap, private val aspect: Float) : View(context) {
    private val frame = RectF()
    private var fit = 1f
    var zoom = 1f
        private set
    private var offsetX = 0f
    private var offsetY = 0f
    private val shade = Paint().apply { color = Color.argb(170, 0, 0, 0) }
    private val border = Paint().apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = dp(3f) }
    private val guide = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 255, 255, 255); style = Paint.Style.STROKE; strokeWidth = dp(2f) }
    private val picture = Paint(Paint.FILTER_BITMAP_FLAG)
    private val matrix = Matrix()
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            setZoom(zoom * detector.scaleFactor)
            return true
        }
    })

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val pad = dp(16f)
        val maxW = w - pad * 2
        val maxH = h - pad * 2
        var fw = maxW
        var fh = fw / aspect
        if (fh > maxH) {
            fh = maxH
            fw = fh * aspect
        }
        frame.set((w - fw) / 2, (h - fh) / 2, (w + fw) / 2, (h + fh) / 2)
        // Zoom 1: the picture just covers the frame.
        fit = max(fw / bitmap.width, fh / bitmap.height)
        clamp()
    }

    fun setZoom(z: Float) {
        zoom = z.coerceIn(1f, MAX_ZOOM)
        clamp()
        invalidate()
    }

    fun reset() {
        zoom = 1f
        offsetX = 0f
        offsetY = 0f
        clamp()
        invalidate()
    }

    /** Keeps the picture covering the whole frame. */
    private fun clamp() {
        val scale = fit * zoom
        val maxX = max(0f, (bitmap.width * scale - frame.width()) / 2)
        val maxY = max(0f, (bitmap.height * scale - frame.height()) / 2)
        offsetX = offsetX.coerceIn(-maxX, maxX)
        offsetY = offsetY.coerceIn(-maxY, maxY)
    }

    /** Where the picture's top-left corner is drawn, and its scale. */
    private fun placement(): Triple<Float, Float, Float> {
        val scale = fit * zoom
        val left = frame.centerX() - bitmap.width * scale / 2 + offsetX
        val top = frame.centerY() - bitmap.height * scale / 2 + offsetY
        return Triple(left, top, scale)
    }

    /** The framed area, in the shown picture's pixels. */
    fun cropRect(): RectF {
        val (left, top, scale) = placement()
        return RectF((frame.left - left) / scale, (frame.top - top) / scale, (frame.right - left) / scale, (frame.bottom - top) / scale)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        val (left, top, scale) = placement()
        matrix.reset()
        matrix.postScale(scale, scale)
        matrix.postTranslate(left, top)
        canvas.drawBitmap(bitmap, matrix, picture)
        // Dim everything outside the frame.
        canvas.drawRect(0f, 0f, width.toFloat(), frame.top, shade)
        canvas.drawRect(0f, frame.bottom, width.toFloat(), height.toFloat(), shade)
        canvas.drawRect(0f, frame.top, frame.left, frame.bottom, shade)
        canvas.drawRect(frame.right, frame.top, width.toFloat(), frame.bottom, shade)
        canvas.drawRect(frame, border)
        // Where an avatar's circle will be.
        if (kotlin.math.abs(aspect - 1f) < 0.01f) {
            val path = Path().apply { addOval(RectF(frame).apply { inset(dp(3f), dp(3f)) }, Path.Direction.CW) }
            canvas.drawPath(path, guide)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaler.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                dragging = true
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> dragging = false
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == 1 && !scaler.isInProgress) {
                    if (dragging) {
                        offsetX += event.x - lastX
                        offsetY += event.y - lastY
                        clamp()
                        invalidate()
                    }
                    dragging = true
                    lastX = event.x
                    lastY = event.y
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return true
    }
}

/** A white-bordered button in the game's style. */
private fun button(context: Context, label: String, color: Int = Color.WHITE, onClick: () -> Unit) = TextView(context).apply {
    val d = context.resources.displayMetrics.density
    text = label
    setTextColor(color)
    textSize = 16f
    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    letterSpacing = 0.08f
    gravity = Gravity.CENTER
    minWidth = (64 * d).toInt()
    setPadding((16 * d).toInt(), (12 * d).toInt(), (16 * d).toInt(), (12 * d).toInt())
    background = GradientDrawable().apply { setColor(Color.BLACK); setStroke((3 * d).toInt(), color) }
    setOnClickListener { onClick() }
}

private fun row(context: Context, vararg views: View) = LinearLayout(context).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER
    val gap = (12 * context.resources.displayMetrics.density).toInt()
    views.forEachIndexed { i, v ->
        addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            if (i > 0) leftMargin = gap
        })
    }
}

/**
 * Shows the cropper and waits: the framed area as (x, y, w, h) in the shown picture's pixels, or
 * null if cancelled.
 */
private suspend fun showCropper(activity: Activity, bitmap: Bitmap, aspect: Float): RectF? {
    val answer = CompletableDeferred<RectF?>()
    // On the main thread (Android's Handler: Kotlin's Main dispatcher may not exist in a module).
    Handler(Looper.getMainLooper()).post { try {
        val d = activity.resources.displayMetrics.density
        val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val crop = CropView(activity, bitmap, aspect)
        val finish = { result: RectF? ->
            if (!answer.isCompleted) answer.complete(result)
            runCatching { dialog.dismiss() }
            Unit
        }

        val title = TextView(activity).apply {
            text = "EDIT IMAGE"
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            letterSpacing = 0.12f
            gravity = Gravity.CENTER
        }
        val hint = TextView(activity).apply {
            text = "Pinch to zoom · drag to move"
            setTextColor(Color.rgb(170, 170, 170))
            textSize = 13f
            gravity = Gravity.CENTER
        }
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            val pad = (16 * d).toInt()
            setPadding(pad, pad * 2, pad, pad * 2)
            addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(crop, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(hint, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = pad })
            addView(row(activity,
                button(activity, "−") { crop.setZoom(crop.zoom / 1.25f) },
                button(activity, "RESET") { crop.reset() },
                button(activity, "+") { crop.setZoom(crop.zoom * 1.25f) },
            ), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = pad })
            addView(row(activity,
                button(activity, "CANCEL") { finish(null) },
                button(activity, "DONE", Color.YELLOW) { finish(crop.cropRect()) },
            ), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        dialog.setContentView(root)
        dialog.setOnCancelListener { finish(null) }
        dialog.setOnDismissListener { if (!answer.isCompleted) answer.complete(null) }
        dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        dialog.show()
    } catch (e: Throwable) {
        answer.completeExceptionally(Error("Couldn't open the cropper: ${e.message}"))
    } }
    return answer.await()
}

val nativeCropper by tweak {
    // The screen that's showing, to put the cropper over.
    XposedBridge.hookAllMethods(Activity::class.java, "onResume", methodHook {
        after { topActivity = WeakReference(thisObject as Activity) }
    }.build())

    withAppContext { ctx ->
        registerNativeAsyncMethod("deltacord.image.cropScreen") { args ->
            val argv = args.asDelegate()
            val uri by argv.string()
            val aspectWidth by argv.double(0.0)
            val aspectHeight by argv.double(0.0)
            val outWidth by argv.int(0)
            val outHeight by argv.int(0)
            val mime by argv.string("image/png")
            val activity = topActivity?.get()?.takeIf { !it.isFinishing } ?: throw Error("Open Discord first")

            // The picture as it's meant to be seen (rotation applied), small enough to show.
            val bytes = withContext(Dispatchers.IO) { readAll(ctx, uri) }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0) throw Error("That isn't a picture Android can open")
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_SHOWN) sample *= 2
            var shown = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: throw Error("Couldn't open that picture")
            val degrees = rotation(bytes)
            if (degrees != 0) {
                val turned = Bitmap.createBitmap(shown, 0, 0, shown.width, shown.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
                if (turned !== shown) shown.recycle()
                shown = turned
            }
            val turnedSides = degrees % 180 != 0
            val fullWidth = if (turnedSides) bounds.outHeight else bounds.outWidth
            val fullHeight = if (turnedSides) bounds.outWidth else bounds.outHeight

            val aspect = if (aspectWidth > 0 && aspectHeight > 0) (aspectWidth / aspectHeight).toFloat()
            else shown.width.toFloat() / shown.height
            val area = try {
                showCropper(activity, shown, aspect)
            } finally {
                // The dialog is gone by now (or never showed); free the picture after it's drawn its last.
                Handler(Looper.getMainLooper()).postDelayed({ runCatching { shown.recycle() } }, 500)
            } ?: return@registerNativeAsyncMethod null

            // From the shown picture's pixels to the full picture's.
            val k = fullWidth.toDouble() / shown.width
            val x = area.left * k
            val y = area.top * k
            val w = area.width() * k
            val h = area.height() * k
            val outW = if (outWidth > 0) outWidth else min(2048.0, w).toInt()
            val outH = if (outHeight > 0) outHeight else (outW * h / w).toInt()
            val result = withContext(Dispatchers.Default) { cropImage(ctx, bytes, x, y, w, h, outW, outH, mime) }
            result + mapOf(
                "cropRect" to mapOf(
                    "x" to x.toInt().toDouble(), "y" to y.toInt().toDouble(),
                    "width" to w.toInt().toDouble(), "height" to h.toInt().toDouble(),
                ),
                "fullWidth" to fullWidth.toDouble(),
                "fullHeight" to fullHeight.toDouble(),
            )
        }
    }
}
