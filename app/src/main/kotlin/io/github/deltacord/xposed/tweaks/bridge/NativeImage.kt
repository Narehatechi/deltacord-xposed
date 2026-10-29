package io.github.deltacord.xposed.tweaks.bridge

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import io.github.deltacord.bridge.asDelegate
import io.github.deltacord.xposed.api.registerNativeAsyncMethod
import io.github.deltacord.xposed.tweak
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/**
 * Image helpers for Deltacord's own image cropper (avatars and banners):
 * - `deltacord.image.info(uri)` -> { width, height } as the picture is meant to be seen (rotation applied)
 * - `deltacord.image.crop(uri, x, y, w, h, outWidth, outHeight, mime)` crops that area (in those
 *   same pixels), scales it, and returns what Discord's cropper returns:
 *   { path, width, height, mime, size, data (base64) }.
 * `uri` can be a file:// path, a content:// uri or a data: uri.
 */
private const val MAX_DECODE = 3072

val nativeImage by tweak {
    withAppContext { ctx ->
        registerNativeAsyncMethod("deltacord.image.info") { args ->
            val argv = args.asDelegate()
            val uri by argv.string()
            val bytes = readAll(ctx, uri)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0) throw Error("That isn't a picture Android can open")
            val turned = rotation(bytes) % 180 != 0
            mapOf(
                "width" to (if (turned) bounds.outHeight else bounds.outWidth).toDouble(),
                "height" to (if (turned) bounds.outWidth else bounds.outHeight).toDouble(),
                "mime" to (bounds.outMimeType ?: "image/jpeg"),
            )
        }

        registerNativeAsyncMethod("deltacord.image.crop") { args ->
            val argv = args.asDelegate()
            val uri by argv.string()
            val x by argv.double()
            val y by argv.double()
            val w by argv.double()
            val h by argv.double()
            val outWidth by argv.int(0)
            val outHeight by argv.int(0)
            val mime by argv.string("image/png")

            cropImage(ctx, readAll(ctx, uri), x, y, w, h, outWidth, outHeight, mime)
        }
    }
}

/**
 * Crops ([x], [y], [w], [h] in the picture's pixels as it's meant to be seen) and scales a picture,
 * and returns what Discord's cropper returns: { path, width, height, mime, size, data }.
 */
internal fun cropImage(
    ctx: Context, bytes: ByteArray, x: Double, y: Double, w: Double, h: Double,
    outWidth: Int, outHeight: Int, mime: String,
): Map<String, Any> {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0) throw Error("That isn't a picture Android can open")

    // Big photos are decoded smaller (crop coordinates scale with them).
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_DECODE) sample *= 2
    var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: throw Error("Couldn't open that picture")

    val degrees = rotation(bytes)
    if (degrees != 0) {
        val turned = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
        if (turned !== bitmap) bitmap.recycle()
        bitmap = turned
    }

    val left = (x / sample).toInt().coerceIn(0, bitmap.width - 1)
    val top = (y / sample).toInt().coerceIn(0, bitmap.height - 1)
    val width = (w / sample).toInt().coerceIn(1, bitmap.width - left)
    val height = (h / sample).toInt().coerceIn(1, bitmap.height - top)
    var cropped = Bitmap.createBitmap(bitmap, left, top, width, height)
    if (outWidth > 0 && outHeight > 0 && (outWidth != width || outHeight != height)) {
        val scaled = Bitmap.createScaledBitmap(cropped, outWidth, outHeight, true)
        if (scaled !== cropped) cropped.recycle()
        cropped = scaled
    }
    if (cropped !== bitmap) bitmap.recycle()

    val png = mime.contains("png") || mime.contains("webp")
    val out = ByteArrayOutputStream()
    cropped.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 92, out)
    val result = out.toByteArray()
    val file = File(ctx.cacheDir, "deltacord-crop-${System.currentTimeMillis().toString(36)}.${if (png) "png" else "jpg"}")
    file.writeBytes(result)
    val resultWidth = cropped.width
    val resultHeight = cropped.height
    cropped.recycle()

    return mapOf(
        "path" to "file://${file.absolutePath}",
        "width" to resultWidth.toDouble(),
        "height" to resultHeight.toDouble(),
        "mime" to if (png) "image/png" else "image/jpeg",
        "size" to result.size.toDouble(),
        "data" to Base64.encodeToString(result, Base64.NO_WRAP),
    )
}

internal fun readAll(ctx: Context, uri: String): ByteArray {
    if (uri.startsWith("data:")) {
        val comma = uri.indexOf(',')
        if (comma < 0) throw Error("Broken picture data")
        return Base64.decode(uri.substring(comma + 1), Base64.DEFAULT)
    }
    val stream: InputStream = when {
        uri.startsWith("content://") -> ctx.contentResolver.openInputStream(Uri.parse(uri))
        uri.startsWith("file://") -> File(Uri.parse(uri).path ?: uri.removePrefix("file://")).inputStream()
        else -> File(uri).inputStream()
    } ?: throw Error("Couldn't read that picture")
    return stream.use { it.readBytes() }
}

/** How far the photo is turned (from its EXIF data), in degrees. */
internal fun rotation(bytes: ByteArray): Int = try {
    when (ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90
        ExifInterface.ORIENTATION_ROTATE_180 -> 180
        ExifInterface.ORIENTATION_ROTATE_270 -> 270
        else -> 0
    }
} catch (_: Throwable) {
    0
}
