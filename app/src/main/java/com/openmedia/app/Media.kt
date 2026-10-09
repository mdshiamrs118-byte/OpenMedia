package com.openmedia.app

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
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

object MediaRepo {
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
}
