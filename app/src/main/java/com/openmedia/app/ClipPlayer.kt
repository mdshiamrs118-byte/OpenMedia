package com.openmedia.app

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** A stopwatch you can seek and pause; used to play a clip set without a video. */
class VirtualClock {
    private var base = 0L
    private var t0 = 0L
    var running = false
        private set

    fun pos(): Long = if (running) base + (SystemClock.elapsedRealtime() - t0) else base
    fun start() {
        if (!running) {
            t0 = SystemClock.elapsedRealtime()
            running = true
        }
    }

    fun pause() {
        base = pos()
        running = false
    }

    fun seek(p: Long) {
        base = p
        t0 = SystemClock.elapsedRealtime()
    }
}

/**
 * Plays individual TTS clips at their planned times, following [clock] (the video position, or a
 * VirtualClock). Upcoming clips are prepared ahead of time, several can play at once, and clips can be
 * time-stretched (pitch preserved) via MediaPlayer playback speed. Main thread only.
 */
class ClipPlayer(private val ctx: Context, private val clock: () -> Long) {
    private class Slot(val p: Planned) {
        var mp: MediaPlayer? = null
        var prepared = false
        var started = false
    }

    private companion object {
        const val LOOKAHEAD = 2500L
        const val MAX_SLOTS = 16
    }

    private val handler = Handler(Looper.getMainLooper())
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private var plan: List<Planned> = emptyList()
    private var maxLen = 0L
    private var next = 0
    private val slots = ArrayList<Slot>()
    private var playing = false
    private var volume = 1f
    private val loop = object : Runnable {
        override fun run() {
            tick()
            if (playing) handler.postDelayed(this, 40L)
        }
    }

    fun load(planned: List<Planned>) {
        dropAll()
        plan = planned.filter { it.play }
        maxLen = plan.maxOfOrNull { it.endMs - it.startMs } ?: 0L
        next = 0
    }

    fun setVolume(v: Float) {
        volume = v
        for (s in slots) try {
            s.mp?.setVolume(v, v)
        } catch (_: Exception) {
        }
    }

    fun resume() {
        if (playing) return
        playing = true
        for (s in slots) if (s.started) try {
            s.mp?.start()
        } catch (_: Exception) {
        }
        handler.removeCallbacks(loop)
        handler.post(loop)
    }

    fun pause() {
        playing = false
        handler.removeCallbacks(loop)
        for (s in slots) if (s.started) try {
            s.mp?.pause()
        } catch (_: Exception) {
        }
    }

    /** Jump to [pos]: drops everything in flight and re-schedules from there. */
    fun seek(pos: Long) {
        dropAll()
        val key = pos - maxLen
        var lo = 0
        var hi = plan.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (plan[mid].startMs < key) lo = mid + 1 else hi = mid
        }
        next = lo
        if (playing) {
            handler.removeCallbacks(loop)
            handler.post(loop)
        }
    }

    fun release() {
        pause()
        dropAll()
        plan = emptyList()
    }

    private fun tick() {
        val pos = clock()
        while (next < plan.size && plan[next].startMs <= pos + LOOKAHEAD && slots.size < MAX_SLOTS) {
            val p = plan[next++]
            if (p.endMs > pos) newSlot(p)
        }
        for (s in ArrayList(slots)) {
            if (s.started || pos < s.p.startMs) continue
            if (pos >= s.p.endMs) discard(s) else if (s.prepared) begin(s, pos)
        }
    }

    private fun newSlot(p: Planned) {
        val s = Slot(p)
        slots += s
        val mp = MediaPlayer()
        s.mp = mp
        try {
            mp.setAudioAttributes(attrs)
            mp.setDataSource(p.clip.file.absolutePath)
            mp.setVolume(volume, volume)
            mp.setOnPreparedListener {
                s.prepared = true
                if (playing && !s.started) {
                    val now = clock()
                    if (now >= p.startMs) begin(s, now)
                }
            }
            mp.setOnCompletionListener { discard(s) }
            mp.setOnErrorListener { _, _, _ ->
                discard(s)
                true
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            discard(s)
        }
    }

    private fun begin(s: Slot, pos: Long) {
        val mp = s.mp ?: return
        if (pos >= s.p.endMs) {
            discard(s)
            return
        }
        try {
            val late = (pos - s.p.startMs).coerceAtLeast(0L)
            if (late > 60) mp.seekTo((late * s.p.speed).toInt())
            mp.setVolume(volume, volume)
            if (s.p.speed != 1f) mp.playbackParams = PlaybackParams().setSpeed(s.p.speed)
            mp.start()
            s.started = true
        } catch (e: Exception) {
            discard(s)
        }
    }

    private fun discard(s: Slot) {
        slots.remove(s)
        try {
            s.mp?.release()
        } catch (_: Exception) {
        }
        s.mp = null
    }

    private fun dropAll() {
        for (s in ArrayList(slots)) discard(s)
        slots.clear()
    }
}
