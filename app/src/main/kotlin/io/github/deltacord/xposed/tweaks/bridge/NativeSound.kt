package io.github.deltacord.xposed.tweaks.bridge

import android.media.AudioAttributes
import android.media.SoundPool
import io.github.deltacord.bridge.asDelegate
import io.github.deltacord.xposed.api.registerNativeAsyncMethod
import io.github.deltacord.xposed.api.registerNativeMethod
import io.github.deltacord.xposed.tweak
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * `deltacord.sound.*`: short sounds with almost no delay (Android's SoundPool), for Deltacord's
 * text sounds, blips and save sound. Discord's own sound player is built for notifications and
 * is too slow (or silent) for a sound on every letter.
 *
 * - `deltacord.sound.load(path)` (async) loads a sound file once; returns its id.
 * - `deltacord.sound.play(id, volume, rate)` plays it (rate 0.5-2 also sets the pitch); several can overlap.
 */
val nativeSound by tweak {
    val pool: SoundPool by lazy {
        SoundPool.Builder()
            .setMaxStreams(8)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build()
            .also { p ->
                p.setOnLoadCompleteListener { _, sampleId, status ->
                    if (status == 0) finished.add(sampleId)
                    waiting.remove(sampleId)?.complete(status == 0)
                }
            }
    }

    registerNativeAsyncMethod("deltacord.sound.load") { args ->
        val argv = args.asDelegate()
        val raw by argv.string()
        val path = raw.removePrefix("file://")
        loaded[path]?.let { return@registerNativeAsyncMethod it }
        if (!File(path).isFile) throw Error("Sound file not found: $path")

        val done = CompletableDeferred<Boolean>()
        val id = synchronized(pool) {
            pool.load(path, 1).also { id -> if (id !in finished) waiting[id] = done else done.complete(true) }
        }
        if (id in finished) done.complete(true)
        val ok = withTimeoutOrNull(5_000) { done.await() } ?: false
        if (!ok) throw Error("Couldn't load the sound: $path")
        loaded[path] = id
        id
    }

    registerNativeMethod("deltacord.sound.play") { args ->
        val argv = args.asDelegate()
        val id by argv.int()
        val volume by argv.double(1.0)
        val rate by argv.double(1.0)
        val v = volume.toFloat().coerceIn(0f, 1f)
        // Rate is also the pitch: 0.5 is an octave down, 2 an octave up.
        pool.play(id, v, v, 1, 0, rate.toFloat().coerceIn(0.5f, 2f))
    }
}

private val loaded = ConcurrentHashMap<String, Int>()
private val waiting = ConcurrentHashMap<Int, CompletableDeferred<Boolean>>()
private val finished: MutableSet<Int> = ConcurrentHashMap.newKeySet()
