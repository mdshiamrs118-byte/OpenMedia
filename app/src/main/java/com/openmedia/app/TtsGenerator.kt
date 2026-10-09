package com.openmedia.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Turns subtitle cues into one WAV track using Android's built-in TTS.
 * Each cue is synthesized, then written at its timestamp (silence is streamed in
 * between), so memory use stays tiny even for feature-length videos.
 * Call generate() from a background thread.
 */
class TtsGenerator(private val ctx: Context) {
    @Volatile
    var cancelled = false

    @Volatile
    private var latch = CountDownLatch(0)

    @Volatile
    private var failed = false

    private class Wav(val rate: Int, val pcm: ByteArray)

    fun generate(
        cues: List<Cue>, totalMs: Long, outName: String,
        videoTitle: String, subName: String, onProgress: (Int) -> Unit
    ): Uri {
        val sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val initLatch = CountDownLatch(1)
        var ready = false
        val engine = TextToSpeech(ctx.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            initLatch.countDown()
        }
        if (!initLatch.await(15, TimeUnit.SECONDS) || !ready) {
            engine.shutdown()
            throw IllegalStateException("Text-to-speech engine is not available")
        }
        val tmp = File(ctx.cacheDir, "tts_out.wav")
        val cueFile = File(ctx.cacheDir, "tts_cue.wav")
        var durationMs = 0L
        try {
            if (engine.setLanguage(Locale.getDefault()) < 0) engine.setLanguage(Locale.US)
            engine.setSpeechRate(sp.getFloat("tts_rate", 1f))
            engine.setPitch(sp.getFloat("tts_pitch", 1f))
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    latch.countDown()
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    failed = true
                    latch.countDown()
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    failed = true
                    latch.countDown()
                }
            })

            RandomAccessFile(tmp, "rw").use { raf ->
                raf.setLength(0)
                raf.write(ByteArray(44))
                var outRate = 0
                var pos = 0L
                val zeros = ByteArray(65536)
                fun pad(toBytes: Long) {
                    var n = toBytes - pos
                    while (n > 0) {
                        val k = minOf(n, zeros.size.toLong()).toInt()
                        raf.write(zeros, 0, k)
                        n -= k
                        pos += k
                    }
                }

                for ((i, cue) in cues.withIndex()) {
                    if (cancelled) throw CancellationException("Cancelled")
                    onProgress(i * 100 / cues.size)
                    val wav = synth(engine, cue.text, cueFile, "u$i") ?: continue
                    if (outRate == 0) outRate = wav.rate
                    val pcm = if (wav.rate == outRate) wav.pcm else resample(wav.pcm, wav.rate, outRate)
                    pad((cue.startMs * outRate / 1000) * 2)
                    raf.write(pcm)
                    pos += pcm.size
                }
                if (outRate == 0) throw IllegalStateException("No speech could be generated from this subtitle")
                pad((totalMs * outRate / 1000) * 2)
                writeHeader(raf, outRate, pos)
                durationMs = pos / 2 * 1000 / outRate
            }
            onProgress(100)
            return saveToMusic(tmp, outName, videoTitle, subName, durationMs)
        } finally {
            engine.stop()
            engine.shutdown()
            tmp.delete()
            cueFile.delete()
        }
    }

    private fun saveToMusic(src: File, name: String, videoTitle: String, subName: String, durMs: Long): Uri {
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, name)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/x-wav")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/${MediaRepo.TTS_DIR}")
            put(MediaStore.Audio.Media.TITLE, videoTitle)
            put(MediaStore.Audio.Media.ARTIST, subName)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Cannot create audio file in Music folder")
        try {
            val out = resolver.openOutputStream(uri) ?: throw IOException("Cannot open output stream")
            out.use { o -> src.inputStream().use { it.copyTo(o) } }
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        values.clear()
        values.put(MediaStore.Audio.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        MediaRepo.saveMeta(ctx, name, videoTitle, subName, durMs)
        return uri
    }

    private fun synth(engine: TextToSpeech, text: String, f: File, id: String): Wav? {
        f.delete()
        failed = false
        latch = CountDownLatch(1)
        if (engine.synthesizeToFile(text.take(3900), Bundle(), f, id) != TextToSpeech.SUCCESS) return null
        if (!latch.await(60, TimeUnit.SECONDS) || failed) return null
        return readWav(f)
    }

    private fun readWav(f: File): Wav? {
        if (!f.exists() || f.length() < 44) return null
        val b = f.readBytes()
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        var p = 12
        var rate = 0
        var ch = 1
        var bits = 16
        while (p + 8 <= b.size) {
            val id = String(b, p, 4, Charsets.US_ASCII)
            var size = bb.getInt(p + 4)
            if (id == "fmt ") {
                ch = bb.getShort(p + 10).toInt()
                rate = bb.getInt(p + 12)
                bits = bb.getShort(p + 22).toInt()
            } else if (id == "data") {
                val start = p + 8
                if (size < 0 || start + size > b.size) size = b.size - start
                if (rate == 0 || bits != 16) return null
                var pcm = b.copyOfRange(start, start + size)
                if (ch == 2) pcm = downmix(pcm)
                return Wav(rate, pcm.copyOf(pcm.size and -2))
            }
            if (size < 0) return null
            p += 8 + size + (size and 1)
        }
        return null
    }

    private fun downmix(s: ByteArray): ByteArray {
        val n = s.size / 4
        val o = ByteArray(n * 2)
        for (i in 0 until n) {
            val l = (s[4 * i + 1].toInt() shl 8) or (s[4 * i].toInt() and 0xFF)
            val r = (s[4 * i + 3].toInt() shl 8) or (s[4 * i + 2].toInt() and 0xFF)
            val a = (l + r) / 2
            o[2 * i] = a.toByte()
            o[2 * i + 1] = (a shr 8).toByte()
        }
        return o
    }

    private fun resample(src: ByteArray, from: Int, to: Int): ByteArray {
        val n = src.size / 2
        if (n == 0) return src
        val m = (n.toLong() * to / from).toInt()
        val o = ByteArray(m * 2)
        fun s(i: Int) = (src[2 * i + 1].toInt() shl 8) or (src[2 * i].toInt() and 0xFF)
        for (j in 0 until m) {
            val x = j.toDouble() * from / to
            val i = x.toInt().coerceAtMost(n - 1)
            val i2 = (i + 1).coerceAtMost(n - 1)
            val fr = x - i
            val v = (s(i) * (1 - fr) + s(i2) * fr).toInt()
            o[2 * j] = v.toByte()
            o[2 * j + 1] = (v shr 8).toByte()
        }
        return o
    }

    private fun writeHeader(raf: RandomAccessFile, rate: Int, dataLen: Long) {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt((36 + dataLen).toInt())
        h.put("WAVE".toByteArray()).put("fmt ".toByteArray())
        h.putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        h.put("data".toByteArray()).putInt(dataLen.toInt())
        raf.seek(0)
        raf.write(h.array())
    }
}
