package com.openmedia.app

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import org.json.JSONObject
import java.io.File

data class Video(
    val id: Long,
    val uri: Uri,
    val title: String,
    val durationMs: Long,
    val size: Long,
    val dateSec: Long,
    val folderPath: String
)

data class Folder(val path: String, val name: String, val dateSec: Long, val videos: List<Video>)

data class Track(
    val uri: Uri,
    val displayName: String,
    val videoTitle: String,
    val subtitleName: String,
    val dateSec: Long,
    val size: Long,
    val durationMs: Long
)

object MediaRepo {
    const val TTS_DIR = "Subtitle TTS"
    private const val META = "tts_meta"

    fun loadFolders(ctx: Context): List<Folder> {
        val videos = ArrayList<Video>()
        val base = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val proj = arrayOf(
            MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION, MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_MODIFIED, MediaStore.Video.Media.DATA
        )
        ctx.contentResolver.query(base, proj, null, null, "${MediaStore.Video.Media.DATE_MODIFIED} DESC")?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val iName = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val iDur = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val iSize = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val iDate = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
            val iData = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
            while (c.moveToNext()) {
                val data = c.getString(iData) ?: continue
                val id = c.getLong(iId)
                val name = c.getString(iName) ?: File(data).name
                videos += Video(
                    id, ContentUris.withAppendedId(base, id), name.substringBeforeLast('.', name),
                    c.getLong(iDur), c.getLong(iSize), c.getLong(iDate), File(data).parent ?: "/"
                )
            }
        }
        return videos.groupBy { it.folderPath }
            .map { (p, l) ->
                Folder(p, File(p).name.ifEmpty { p }, l.maxOf { it.dateSec }, l.sortedBy { it.title.lowercase() })
            }
            .sortedBy { it.name.lowercase() }
    }

    // ---- TTS audio library -------------------------------------------------

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(META, Context.MODE_PRIVATE)

    fun saveMeta(ctx: Context, fileName: String, videoTitle: String, subName: String, durMs: Long) {
        val j = JSONObject().put("v", videoTitle).put("s", subName).put("d", durMs)
        prefs(ctx).edit().putString(fileName, j.toString()).apply()
    }

    fun findAudio(ctx: Context, fileName: String): Uri? {
        val base = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val sel = "${MediaStore.Audio.Media.DISPLAY_NAME}=? AND ${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?"
        ctx.contentResolver.query(
            base, arrayOf(MediaStore.Audio.Media._ID), sel, arrayOf(fileName, "Music/$TTS_DIR%"), null
        )?.use { c ->
            if (c.moveToFirst()) return ContentUris.withAppendedId(base, c.getLong(0))
        }
        return null
    }

    fun loadTracks(ctx: Context): List<Track> {
        val out = ArrayList<Track>()
        val base = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val p = prefs(ctx)
        val proj = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DATE_ADDED, MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.DURATION
        )
        ctx.contentResolver.query(
            base, proj, "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?", arrayOf("Music/$TTS_DIR%"),
            "${MediaStore.Audio.Media.DATE_ADDED} DESC"
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                val m = p.getString(name, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
                val dur = if (m != null && m.optLong("d") > 0) m.optLong("d") else c.getLong(4)
                out += Track(
                    ContentUris.withAppendedId(base, c.getLong(0)), name,
                    m?.optString("v") ?: name.removePrefix("TTS_").substringBeforeLast('.'),
                    m?.optString("s") ?: "", c.getLong(2), c.getLong(3), dur
                )
            }
        }
        return out
    }

    fun deleteTrack(ctx: Context, t: Track) {
        try {
            ctx.contentResolver.delete(t.uri, null, null)
        } catch (_: Exception) {
        }
        prefs(ctx).edit().remove(t.displayName).apply()
    }
}
