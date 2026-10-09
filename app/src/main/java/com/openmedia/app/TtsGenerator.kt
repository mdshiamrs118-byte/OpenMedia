package com.openmedia.app

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerArray

/**
 * Synthesizes every subtitle line to its own small WAV file, in parallel:
 * several TextToSpeech engines run side by side, and each keeps a few requests queued so the engine is
 * never idle between lines. Nothing is merged; ClipPlayer plays the clips at their timestamps.
 * Call generate() from a background thread.
 */
class TtsGenerator(private val ctx: Context) {
    @Volatile
    var cancelled = false

    private companion object {
        const val ENGINES = 3
        const val DEPTH = 4
    }

    fun generate(
        cues: List<Cue>, dir: File, videoTitle: String, subName: String, onProgress: (Int) -> Unit
    ): ClipSet {
        val sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val rate = sp.getFloat("tts_rate", 1f)
        val pitch = sp.getFloat("tts_pitch", 1f)
        dir.deleteRecursively()
        dir.mkdirs()
        val engines = initEngines()
        var ok = false
        try {
            if (engines.isEmpty()) throw IllegalStateException("Text-to-speech engine is not available")
            val n = cues.size
            val next = AtomicInteger(0)
            val finished = AtomicInteger(0)
            val seen = AtomicIntegerArray(n)
            val results = arrayOfNulls<Clip>(n)
            val allDone = CountDownLatch(1)

            fun finish(i: Int, success: Boolean) {
                if (!seen.compareAndSet(i, 0, 1)) return
                val f = File(dir, "c$i.wav")
                val d = if (success) wavDurationMs(f) else 0L
                if (d >= 30) results[i] = Clip(f, cues[i].startMs, cues[i].endMs, d, cues[i].text) else f.delete()
                if (finished.incrementAndGet() == n) allDone.countDown()
            }

            fun submit(engine: TextToSpeech) {
                while (!cancelled) {
                    val i = next.getAndIncrement()
                    if (i >= n) return
                    val r = engine.synthesizeToFile(cues[i].text.take(3900), Bundle(), File(dir, "c$i.wav"), "c$i")
                    if (r == TextToSpeech.SUCCESS) return
                    finish(i, false)
                }
            }

            fun idx(id: String?): Int? = id?.removePrefix("c")?.toIntOrNull()

            for (e in engines) {
                if (e.setLanguage(Locale.getDefault()) < 0) e.setLanguage(Locale.US)
                e.setSpeechRate(rate)
                e.setPitch(pitch)
                e.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        idx(utteranceId)?.let {
                            finish(it, true)
                            submit(e)
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        idx(utteranceId)?.let {
                            finish(it, false)
                            submit(e)
                        }
                    }
                })
            }
            repeat(DEPTH) { for (e in engines) submit(e) }

            var last = -1
            var lastChange = System.currentTimeMillis()
            while (!allDone.await(250, TimeUnit.MILLISECONDS)) {
                if (cancelled) throw CancellationException("Cancelled")
                val f = finished.get()
                if (f != last) {
                    last = f
                    lastChange = System.currentTimeMillis()
                    onProgress(f * 100 / n)
                } else if (System.currentTimeMillis() - lastChange > 120_000) {
                    throw IllegalStateException("Text-to-speech stopped responding")
                }
            }
            if (cancelled) throw CancellationException("Cancelled")
            val clips = results.filterNotNull()
            if (clips.isEmpty()) throw IllegalStateException("No speech could be generated from this subtitle")
            onProgress(100)
            val set = ClipStore.save(dir, videoTitle, subName, clips)
            ok = true
            return set
        } finally {
            for (e in engines) {
                try {
                    e.stop()
                    e.shutdown()
                } catch (_: Exception) {
                }
            }
            if (!ok) dir.deleteRecursively()
        }
    }

    private fun initEngines(): List<TextToSpeech> {
        val latch = CountDownLatch(ENGINES)
        val oks = BooleanArray(ENGINES)
        val all = ArrayList<TextToSpeech>()
        for (k in 0 until ENGINES) {
            all += TextToSpeech(ctx.applicationContext) { st ->
                oks[k] = st == TextToSpeech.SUCCESS
                latch.countDown()
            }
        }
        latch.await(15, TimeUnit.SECONDS)
        val good = ArrayList<TextToSpeech>()
        all.forEachIndexed { k, e -> if (oks[k]) good += e else e.shutdown() }
        return good
    }

    /** Duration of a WAV from its header (the data size field is ignored; file length is used). */
    private fun wavDurationMs(f: File): Long {
        if (!f.exists() || f.length() < 44) return 0L
        val head = ByteArray(minOf(f.length(), 4096L).toInt())
        f.inputStream().use { it.read(head) }
        val bb = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
        var p = 12
        var byteRate = 0
        while (p + 8 <= head.size) {
            val id = String(head, p, 4, Charsets.US_ASCII)
            val size = bb.getInt(p + 4)
            if (id == "fmt " && p + 20 <= head.size) {
                byteRate = bb.getInt(p + 16)
            } else if (id == "data") {
                return if (byteRate > 0) (f.length() - (p + 8)) * 1000 / byteRate else 0L
            }
            if (size < 0) return 0L
            p += 8 + size + (size and 1)
        }
        return 0L
    }
}
