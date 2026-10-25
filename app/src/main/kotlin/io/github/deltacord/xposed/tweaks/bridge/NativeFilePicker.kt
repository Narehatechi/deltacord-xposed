package io.github.deltacord.xposed.tweaks.bridge

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import de.robv.android.xposed.XposedBridge
import io.github.deltacord.bridge.asDelegate
import io.github.deltacord.xposed.api.registerNativeAsyncMethod
import io.github.deltacord.xposed.methodHook
import io.github.deltacord.xposed.tweak
import kotlinx.coroutines.CompletableDeferred
import java.io.File
import java.lang.ref.WeakReference

/**
 * `deltacord.files.pick(mimeType, folder)` (async): opens Android's file picker, copies the
 * chosen file into Discord's files (`files/<folder>/`), and returns `{ path, name, size }`,
 * or null if nothing was picked. Used to import sounds without needing a link.
 */
private const val REQUEST_CODE = 0x0DE17A
private const val MAX_BYTES = 8L * 1024 * 1024

@Volatile
private var currentActivity: WeakReference<Activity>? = null

@Volatile
private var pending: CompletableDeferred<Uri?>? = null

val nativeFilePicker by tweak {
    // The screen that's showing, to open the picker from.
    XposedBridge.hookAllMethods(Activity::class.java, "onResume", methodHook {
        after { currentActivity = WeakReference(thisObject as Activity) }
    }.build())

    // The picker's answer comes back to that screen; take ours before Discord sees it.
    XposedBridge.hookAllMethods(Activity::class.java, "dispatchActivityResult", methodHook {
        before {
            if (args.size < 4 || args[1] != REQUEST_CODE) return@before
            val ok = args[2] == Activity.RESULT_OK
            val data = args[3] as? Intent
            pending?.complete(if (ok) data?.data else null)
            pending = null
            result = null
        }
    }.build())

    registerNativeAsyncMethod("deltacord.files.pick") { args ->
        val argv = args.asDelegate()
        val mimeType by argv.string("*/*")
        val folder by argv.string("pyoncord/deltacord-sounds")
        val activity = currentActivity?.get() ?: throw Error("Open Discord first")

        pending?.complete(null)
        val answer = CompletableDeferred<Uri?>()
        pending = answer
        Handler(Looper.getMainLooper()).post {
            try {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType(mimeType)
                activity.startActivityForResult(intent, REQUEST_CODE)
            } catch (e: Throwable) {
                answer.completeExceptionally(Error("Couldn't open the file picker: ${e.message}"))
            }
        }

        val uri = answer.await() ?: return@registerNativeAsyncMethod null
        val resolver = activity.contentResolver

        var name = "file"
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        if (size > MAX_BYTES) throw Error("That file is too big (over 8 MB)")

        val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(60)
        val target = File(activity.filesDir, "$folder/picked-${System.currentTimeMillis().toString(36)}-$safeName")
        target.parentFile?.mkdirs()
        resolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(16 * 1024)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_BYTES) {
                        output.close()
                        target.delete()
                        throw Error("That file is too big (over 8 MB)")
                    }
                    output.write(buffer, 0, read)
                }
            }
        } ?: throw Error("Couldn't read that file")

        mapOf("path" to target.absolutePath, "name" to name, "size" to target.length().toDouble())
    }
}
