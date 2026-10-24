package io.github.deltacord.xposed.tweaks

import android.content.res.XModuleResources
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.os.Build
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/**
 * Determination Mono, loaded straight from this module's APK.
 *
 * `Typeface.createFromFile` quietly hands back Android's default font when it can't load a file
 * (it only throws if the file is missing), which is how the startup animation ended up in the
 * plain system font. This tries several ways, checks each one really produced the font, and
 * remembers why it failed if none did.
 */
internal object BuiltinFont {
    private const val ASSET = "fonts/DeterminationMono.ttf"

    @Volatile
    private var cached: Typeface? = null

    /** Why the font couldn't be loaded (null if it loaded, or wasn't tried yet). */
    @Volatile
    var problem: String? = null
        private set

    fun get(modulePath: String): Typeface? {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val errors = ArrayList<String>()
            val typeface = attempt("module code", errors) { fromBytes(fromClassLoader()) }
                ?: attempt("module file", errors) { fromBytes(fromModuleFile(modulePath)) }
                ?: attempt("module assets", errors) { fromModuleAssets(modulePath) }
                ?: attempt("installed copy", errors) { installedCopy() }
            cached = typeface
            problem = if (typeface == null) errors.joinToString("; ") else null
            return typeface
        }
    }

    private inline fun attempt(how: String, errors: MutableList<String>, load: () -> Typeface?): Typeface? = try {
        val typeface = load()
        when {
            typeface == null -> { errors.add("$how: nothing loaded"); null }
            typeface == Typeface.DEFAULT -> { errors.add("$how: Android gave the default font"); null }
            else -> typeface
        }
    } catch (e: Throwable) {
        errors.add("$how: ${e.javaClass.simpleName}: ${e.message}")
        null
    }

    /** The font, read through the class loader that loaded this module (works whatever its path). */
    private fun fromClassLoader(): ByteArray {
        val loader = BuiltinFont::class.java.classLoader ?: error("no class loader")
        val stream = loader.getResourceAsStream("assets/$ASSET") ?: error("not found through the class loader")
        return stream.use { it.readBytes() }
    }

    /** The font file's bytes, from wherever they can be read. */
    fun fontBytes(modulePath: String): ByteArray = try {
        fromClassLoader()
    } catch (_: Throwable) {
        fromModuleFile(modulePath)
    }

    private fun fromModuleFile(modulePath: String): ByteArray = ZipFile(modulePath).use { zip ->
        val entry = zip.getEntry("assets/$ASSET") ?: error("font missing from the module")
        zip.getInputStream(entry).use { it.readBytes() }
    }

    /** Builds the font from its bytes in memory. */
    private fun fromBytes(bytes: ByteArray): Typeface? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // Older Android needs a file.
            val file = java.io.File.createTempFile("determination", ".ttf")
            file.writeBytes(bytes)
            return Typeface.createFromFile(file).also { file.delete() }
        }
        val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        buffer.put(bytes).flip()
        val font = Font.Builder(buffer).build()
        return Typeface.CustomFallbackBuilder(FontFamily.Builder(font).build()).build()
    }

    /** The copy Deltacord's font setting keeps for Discord's text (the one Discord's screens use). */
    @Volatile
    var dataDir: String? = null

    private fun installedCopy(): Typeface? {
        val dir = dataDir ?: return null
        val file = java.io.File(dir, "files/pyoncord/downloads/fonts/Determination/ggsans-Normal.ttf")
        if (!file.isFile || file.length() == 0L) return null
        return Typeface.createFromFile(file)
    }

    /** Older Android: through the module's own asset manager. */
    private fun fromModuleAssets(modulePath: String): Typeface? =
        Typeface.createFromAsset(XModuleResources.createInstance(modulePath, null).assets, ASSET)
}
