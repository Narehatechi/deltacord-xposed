package io.github.deltacord.xposed.tweaks.bridge

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import android.util.Log
import de.robv.android.xposed.XposedBridge
import io.github.deltacord.bridge.asDelegate
import io.github.deltacord.xposed.api.registerNativeMethod
import io.github.deltacord.xposed.methodHook
import io.github.deltacord.xposed.tweak
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Jukebox: plays a sound or song through your microphone, so everyone in a voice call hears it.
 *
 * Discord's voice engine reads the microphone through Android's AudioRecord (WebRTC's
 * WebRtcAudioRecord). Right after each read, the music is mixed into the samples Discord just
 * got, before they're encoded and sent. Nothing changes when nothing is playing.
 *
 * The song is decoded on its own thread into a short buffer at 48 kHz mono, and taken out of it
 * as fast as Discord reads the mic. So it only moves forward while you're in a call (with your
 * mic open); pressing play outside a call makes it start when you join one.
 *
 * - `deltacord.jukebox.play(path, loop)` starts a file (replacing what's playing)
 * - `deltacord.jukebox.stop()`, `deltacord.jukebox.pause(paused)`
 * - `deltacord.jukebox.set(volume, voice, hear)`: music loudness (0-2), your voice (0-1, 0 is
 *   "music only"), and whether you hear it yourself
 * - `deltacord.jukebox.state()`: what's playing, how far in, and whether Discord's mic is open
 * - `deltacord.jukebox.remove(path)`: deletes an imported file
 */
val micJukebox by tweak {
    // Every way AudioRecord can be read. The music goes in after Discord's read fills the buffer.
    XposedBridge.hookAllMethods(AudioRecord::class.java, "read", methodHook {
        after {
            try {
                val count = (result as? Int) ?: return@after
                if (count <= 0) return@after
                Jukebox.onMicRead(thisObject as AudioRecord, args[0], count)
            } catch (e: Throwable) {
                Jukebox.error(e)
            }
        }
    }.build())

    registerNativeMethod("deltacord.jukebox.play") { args ->
        val argv = args.asDelegate()
        val raw by argv.string()
        val loop by argv.boolean(false)
        val path = raw.removePrefix("file://")
        if (!File(path).isFile) throw Error("That file isn't there any more")
        Jukebox.play(path, loop)
        Jukebox.state()
    }
    registerNativeMethod("deltacord.jukebox.stop") { _ ->
        Jukebox.stop()
        Jukebox.state()
    }
    registerNativeMethod("deltacord.jukebox.pause") { args ->
        val argv = args.asDelegate()
        val paused by argv.boolean(true)
        Jukebox.paused = paused
        Jukebox.state()
    }
    registerNativeMethod("deltacord.jukebox.set") { args ->
        val argv = args.asDelegate()
        val volume by argv.double(1.0)
        val voice by argv.double(1.0)
        val hear by argv.boolean(true)
        Jukebox.volume = volume.toFloat().coerceIn(0f, 2f)
        Jukebox.voice = voice.toFloat().coerceIn(0f, 1f)
        Jukebox.hear = hear
        Jukebox.state()
    }
    registerNativeMethod("deltacord.jukebox.state") { _ -> Jukebox.state() }
    registerNativeMethod("deltacord.jukebox.remove") { args ->
        val argv = args.asDelegate()
        val raw by argv.string()
        val file = File(raw.removePrefix("file://"))
        // Only files Deltacord imported into its own jukebox folder.
        if (file.parentFile?.name != "jukebox" || !file.path.contains("/pyoncord/")) throw Error("Not a jukebox file")
        if (Jukebox.state()["path"] == file.path) Jukebox.stop()
        file.delete()
    }
}

private object Jukebox {
    private const val TAG = "DeltacordJukebox"
    const val RATE = 48_000

    @Volatile var volume = 1f
    @Volatile var voice = 1f
    @Volatile var hear = true
    @Volatile var paused = false

    @Volatile private var track: Track? = null
    @Volatile private var lastError: String? = null

    // What Discord's mic looks like, for the status line.
    @Volatile private var micRate = 0
    @Volatile private var micChannels = 0
    @Volatile private var micFormat = 0
    @Volatile private var lastRead = 0L
    @Volatile private var reads = 0L

    private var monitor: AudioTrack? = null
    private var monitorBuffer = ShortArray(0)
    private var mixBuffer = FloatArray(0)

    fun error(e: Throwable) {
        if (lastError == null) Log.e(TAG, "Couldn't mix into the mic", e)
        lastError = e.message ?: e.javaClass.simpleName
    }

    @Synchronized
    fun play(path: String, loop: Boolean) {
        track?.finish()
        lastError = null
        paused = false
        track = Track(path, loop).also { it.start() }
    }

    @Synchronized
    fun stop() {
        track?.finish()
        track = null
        paused = false
        monitor?.let { runCatching { it.pause(); it.flush() } }
    }

    fun state(): Map<String, Any?> {
        val t = track
        val sinceRead = if (lastRead == 0L) -1.0 else (SystemClock.elapsedRealtime() - lastRead).toDouble()
        return mapOf(
            "path" to t?.path,
            "playing" to (t != null && !t.done),
            "paused" to paused,
            "loop" to (t?.loop ?: false),
            "position" to (t?.played?.toDouble()?.div(RATE) ?: 0.0),
            "duration" to (t?.durationSec ?: 0.0),
            "error" to (t?.error ?: lastError),
            "volume" to volume.toDouble(),
            "voice" to voice.toDouble(),
            "hear" to hear,
            // The mic counts as open if Discord read it in the last second.
            "micOpen" to (sinceRead in 0.0..1000.0),
            "micRate" to micRate,
            "micChannels" to micChannels,
            "micFormat" to micFormat,
            "micReads" to reads.toDouble(),
        )
    }

    /** Called right after Discord reads [count] samples (or bytes) from the mic into [buffer]. */
    fun onMicRead(record: AudioRecord, buffer: Any?, count: Int) {
        lastRead = SystemClock.elapsedRealtime()
        reads++
        val rate = record.sampleRate
        val channels = max(1, record.channelCount)
        val format = record.audioFormat
        micRate = rate; micChannels = channels; micFormat = format

        val t = track ?: return
        if (paused || t.done && t.available() == 0) return

        // How many samples per channel this read holds.
        val frames = when (buffer) {
            is ByteBuffer -> if (format == AudioFormat.ENCODING_PCM_FLOAT) count / 4 / channels else count / 2 / channels
            is ByteArray -> count / 2 / channels
            is ShortArray -> count / channels
            is FloatArray -> count / channels
            else -> return
        }
        if (frames <= 0) return
        if (buffer is ByteArray && format != AudioFormat.ENCODING_PCM_16BIT) return

        // The music for this stretch of time, at the mic's own rate.
        if (mixBuffer.size < frames) mixBuffer = FloatArray(frames)
        t.take(mixBuffer, frames, rate)

        val v = volume
        val keep = voice
        when (buffer) {
            is ByteBuffer -> {
                val b = buffer.duplicate().order(ByteOrder.nativeOrder())
                if (format == AudioFormat.ENCODING_PCM_FLOAT) {
                    for (f in 0 until frames) for (c in 0 until channels) {
                        val i = (f * channels + c) * 4
                        b.putFloat(i, (b.getFloat(i) * keep + mixBuffer[f] * v).coerceIn(-1f, 1f))
                    }
                } else {
                    for (f in 0 until frames) for (c in 0 until channels) {
                        val i = (f * channels + c) * 2
                        b.putShort(i, clamp16(b.getShort(i) * keep + mixBuffer[f] * v * 32767f))
                    }
                }
            }
            is ByteArray -> for (f in 0 until frames) for (c in 0 until channels) {
                val i = (f * channels + c) * 2
                val s = ((buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)).toShort()
                val out = clamp16(s * keep + mixBuffer[f] * v * 32767f)
                buffer[i] = (out.toInt() and 0xFF).toByte()
                buffer[i + 1] = (out.toInt() shr 8).toByte()
            }
            is ShortArray -> for (f in 0 until frames) for (c in 0 until channels) {
                val i = f * channels + c
                buffer[i] = clamp16(buffer[i] * keep + mixBuffer[f] * v * 32767f)
            }
            is FloatArray -> for (f in 0 until frames) for (c in 0 until channels) {
                val i = f * channels + c
                buffer[i] = (buffer[i] * keep + mixBuffer[f] * v).coerceIn(-1f, 1f)
            }
        }

        if (hear) hearIt(frames, rate, v)
    }

    private fun clamp16(x: Float): Short = x.roundToInt().coerceIn(-32768, 32767).toShort()

    /** Lets you hear the music too, quietly, without ever holding up Discord's mic thread. */
    private fun hearIt(frames: Int, rate: Int, v: Float) {
        try {
            val out = monitor?.takeIf { it.sampleRate == rate } ?: run {
                monitor?.release()
                val size = max(AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT), rate / 5 * 2)
                AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                    .setAudioFormat(AudioFormat.Builder()
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build())
                    .setBufferSizeInBytes(size)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                    .also { monitor = it }
            }
            if (out.playState != AudioTrack.PLAYSTATE_PLAYING) out.play()
            if (monitorBuffer.size < frames) monitorBuffer = ShortArray(frames)
            // Quieter than what you send, so it doesn't drown out the call.
            val quiet = min(v, 1f) * 0.6f
            for (f in 0 until frames) monitorBuffer[f] = clamp16(mixBuffer[f] * quiet * 32767f)
            out.write(monitorBuffer, 0, frames, AudioTrack.WRITE_NON_BLOCKING)
        } catch (e: Throwable) {
            hear = false
            Log.e(TAG, "Couldn't play the music to you; turned that off", e)
        }
    }

    /**
     * One song: decoded on its own thread into a small buffer of 48 kHz mono samples. Discord's
     * mic reads take from it; when it's full, decoding waits.
     */
    class Track(val path: String, val loop: Boolean) : Thread("DeltacordJukebox") {
        private val ring = FloatArray(RATE * 2)
        private var readAt = 0
        private var writeAt = 0
        private var filled = 0
        private val lock = Object()
        @Volatile private var stopped = false
        @Volatile var done = false
        @Volatile var error: String? = null
        @Volatile var durationSec = 0.0
        /** Samples (at 48 kHz) taken so far, for the position shown. */
        @Volatile var played = 0L
        /** Where between two 48 kHz samples the last read ended (for other mic rates). */
        private var phase = 0.0
        private var last = 0f

        init {
            isDaemon = true
        }

        fun finish() {
            stopped = true
            synchronized(lock) { lock.notifyAll() }
            interrupt()
        }

        fun available() = synchronized(lock) { filled }

        /** Fills [out] with [frames] samples at [rate], silence where the song has nothing yet. */
        fun take(out: FloatArray, frames: Int, rate: Int) {
            val step = RATE.toDouble() / rate
            synchronized(lock) {
                for (f in 0 until frames) {
                    phase += step
                    while (phase >= 1.0 && filled > 0) {
                        last = ring[readAt]
                        readAt = (readAt + 1) % ring.size
                        filled--
                        played++
                        phase -= 1.0
                    }
                    if (phase >= 1.0) phase = 0.0 // ran dry: wait for more
                    val next = if (filled > 0) ring[readAt] else last
                    out[f] = last + (next - last) * phase.toFloat()
                }
                lock.notifyAll()
            }
        }

        private fun put(samples: FloatArray, count: Int) {
            var i = 0
            synchronized(lock) {
                while (i < count && !stopped) {
                    if (filled == ring.size) {
                        lock.wait(200)
                        continue
                    }
                    ring[writeAt] = samples[i++]
                    writeAt = (writeAt + 1) % ring.size
                    filled++
                }
            }
        }

        override fun run() {
            var extractor: MediaExtractor? = null
            var codec: MediaCodec? = null
            try {
                extractor = MediaExtractor().apply { setDataSource(path) }
                val index = (0 until extractor.trackCount).firstOrNull {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                } ?: throw Error("That file has no sound in it")
                extractor.selectTrack(index)
                val format = extractor.getTrackFormat(index)
                if (format.containsKey(MediaFormat.KEY_DURATION)) durationSec = format.getLong(MediaFormat.KEY_DURATION) / 1_000_000.0
                codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
                codec.configure(format, null, null, 0)
                codec.start()

                var srcRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                var srcChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                var floats = false
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var mono = FloatArray(8192)
                var resampled = FloatArray(8192)
                var pos = 0.0
                var prev = 0f

                while (!stopped) {
                    if (!inputDone) {
                        val i = codec.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val input = codec.getInputBuffer(i)!!
                            val size = extractor.readSampleData(input, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(i, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, 10_000)
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val f = codec.outputFormat
                        srcRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        srcChannels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        floats = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        continue
                    }
                    if (o < 0) continue
                    val out = codec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                    out.position(info.offset)
                    val frames = info.size / (if (floats) 4 else 2) / max(1, srcChannels)
                    if (mono.size < frames) mono = FloatArray(frames)
                    // Down to one channel.
                    for (f in 0 until frames) {
                        var sum = 0f
                        for (c in 0 until srcChannels) sum += if (floats) out.float else out.short / 32768f
                        mono[f] = sum / srcChannels
                    }
                    codec.releaseOutputBuffer(o, false)

                    // To 48 kHz.
                    val step = srcRate.toDouble() / RATE
                    var n = 0
                    val need = (frames / step).toInt() + 2
                    if (resampled.size < need) resampled = FloatArray(need)
                    while (pos < frames) {
                        val k = pos.toInt()
                        val a = if (k == 0) prev else mono[k - 1]
                        val b = mono[min(k, frames - 1)]
                        resampled[n++] = a + (b - a) * (pos - k).toFloat()
                        pos += step
                    }
                    pos -= frames
                    if (frames > 0) prev = mono[frames - 1]
                    put(resampled, n)

                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        if (!loop) break
                        extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                        codec.flush()
                        inputDone = false
                    }
                }
            } catch (_: InterruptedException) {
            } catch (e: Throwable) {
                if (!stopped) {
                    error = e.message ?: "Couldn't play that file"
                    Log.e(TAG, "Couldn't play $path", e)
                }
            } finally {
                runCatching { codec?.stop() }
                runCatching { codec?.release() }
                runCatching { extractor?.release() }
                // Let what's left in the buffer play out, then it's done.
                while (!stopped && available() > 0) runCatching { sleep(100) }
                done = true
            }
        }
    }
}
