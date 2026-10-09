package com.openmedia.app

import android.content.Context
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One synthesized subtitle line: its own small WAV file plus the cue timing it belongs to. */
data class Clip(val file: File, val cueStart: Long, val cueEnd: Long, val audioMs: Long, val text: String)

class ClipSet(
    val dir: File, val videoTitle: String, val subName: String, val createdSec: Long, val clips: List<Clip>
)

data class Track(val set: ClipSet, val sizeBytes: Long, val durationMs: Long)

/** Clips live in the app's own Music folder: one folder per video+subtitle, plus a manifest.json. */
object ClipStore {
    fun root(ctx: Context): File =
        File(ctx.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: ctx.filesDir, "Subtitle TTS")

    fun dir(ctx: Context, name: String): File = File(root(ctx), name)

    fun load(dir: File): ClipSet? {
        val m = File(dir, "manifest.json")
        if (!m.exists()) return null
        return try {
            val j = JSONObject(m.readText())
            val a = j.getJSONArray("clips")
            val clips = ArrayList<Clip>(a.length())
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val f = File(dir, o.getString("f"))
                if (f.exists()) clips += Clip(f, o.getLong("s"), o.getLong("e"), o.getLong("d"), o.optString("t"))
            }
            if (clips.isEmpty()) null
            else ClipSet(dir, j.optString("v"), j.optString("s"), j.optLong("c"), clips)
        } catch (e: Exception) {
            null
        }
    }

    /** The manifest is written last (atomically), so its presence means the set is complete. */
    fun save(dir: File, videoTitle: String, subName: String, clips: List<Clip>): ClipSet {
        val created = System.currentTimeMillis() / 1000
        val arr = JSONArray()
        for (c in clips) {
            arr.put(
                JSONObject().put("f", c.file.name).put("s", c.cueStart).put("e", c.cueEnd)
                    .put("d", c.audioMs).put("t", c.text)
            )
        }
        val j = JSONObject().put("v", videoTitle).put("s", subName).put("c", created).put("clips", arr)
        val tmp = File(dir, "manifest.tmp")
        tmp.writeText(j.toString())
        tmp.renameTo(File(dir, "manifest.json"))
        return ClipSet(dir, videoTitle, subName, created, clips)
    }

    fun loadAll(ctx: Context): List<Track> =
        (root(ctx).listFiles() ?: emptyArray()).filter { it.isDirectory }.mapNotNull { d ->
            load(d)?.let { s ->
                Track(
                    s, s.clips.sumOf { it.file.length() },
                    s.clips.maxOf { maxOf(it.cueEnd, it.cueStart + it.audioMs) }
                )
            }
        }.sortedByDescending { it.set.createdSec }

    fun delete(set: ClipSet) {
        set.dir.deleteRecursively()
    }
}

enum class OverlapMode {
    OVERLAP, SKIP, HYBRID;

    companion object {
        fun from(s: String?) = when (s) {
            "overlap" -> OVERLAP
            "skip" -> SKIP
            else -> HYBRID
        }
    }
}

/** A clip placed on the timeline: when it starts, how fast it plays, and whether it plays at all. */
class Planned(val clip: Clip, val startMs: Long, val speed: Float, val play: Boolean) {
    val endMs: Long = startMs + (clip.audioMs / speed).toLong()
}

object Planner {
    const val MAX_SPEED = 2.0f
    private const val SLACK = 1500L      // speech may run this long past its cluster's end (if the gap allows)
    private const val LAG = 3000L        // no single line may finish more than this after its own cue end
    private const val DROP_LATE = 1500L  // a line that could only start this long after its cue ended is dropped

    fun plan(clips: List<Clip>, mode: OverlapMode): List<Planned> = when (mode) {
        OverlapMode.OVERLAP -> clips.map { Planned(it, it.cueStart, 1f, true) }
        OverlapMode.SKIP -> {
            var busyUntil = Long.MIN_VALUE
            clips.map { c ->
                if (c.cueStart < busyUntil) Planned(c, c.cueStart, 1f, false)
                else {
                    busyUntil = c.cueStart + c.audioMs
                    Planned(c, c.cueStart, 1f, true)
                }
            }
        }
        OverlapMode.HYBRID -> hybrid(clips)
    }

    /**
     * No overlapping audio. Cues whose time windows overlap form a cluster; the cluster's audio is played
     * back to back and sped up by ONE shared factor, just enough to finish inside the cluster's combined
     * window (e.g. 5-11s and 7-15s => both are fitted into 5-15s), never slowed down.
     */
    private fun hybrid(clips: List<Clip>): List<Planned> {
        val out = ArrayList<Planned>(clips.size)
        var t = 0L // when the previous audio finishes
        var i = 0
        while (i < clips.size) {
            var j = i
            var clusterEnd = clips[i].cueEnd
            while (j + 1 < clips.size && clips[j + 1].cueStart < clusterEnd) {
                j++
                clusterEnd = maxOf(clusterEnd, clips[j].cueEnd)
            }
            val nextStart = if (j + 1 < clips.size) clips[j + 1].cueStart else Long.MAX_VALUE
            val deadline = minOf(clusterEnd + SLACK, nextStart)
            val group = clips.subList(i, j + 1)
            val speed = fitSpeed(group, t, deadline)
            for (c in group) {
                val start = maxOf(t, c.cueStart)
                if (start > c.cueEnd + DROP_LATE) {
                    out += Planned(c, start, speed, false)
                } else {
                    val p = Planned(c, start, speed, true)
                    out += p
                    t = p.endMs
                }
            }
            i = j + 1
        }
        return out
    }

    private fun fits(group: List<Clip>, carry: Long, s: Float, deadline: Long): Boolean {
        var t = carry
        for (c in group) {
            t = maxOf(t, c.cueStart) + (c.audioMs / s).toLong()
            if (t > c.cueEnd + LAG) return false
        }
        return t <= deadline
    }

    private fun fitSpeed(group: List<Clip>, carry: Long, deadline: Long): Float {
        if (fits(group, carry, 1f, deadline)) return 1f
        if (!fits(group, carry, MAX_SPEED, deadline)) return MAX_SPEED
        var lo = 1f
        var hi = MAX_SPEED
        repeat(12) {
            val mid = (lo + hi) / 2
            if (fits(group, carry, mid, deadline)) hi = mid else lo = mid
        }
        return hi
    }
}
