package com.openmedia.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.format.DateFormat
import android.text.format.Formatter
import android.widget.SeekBar
import androidx.core.content.ContextCompat
import java.security.MessageDigest
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Tiny shared background executor + main-thread handler. */
object Bg {
    val io = Executors.newFixedThreadPool(3)
    val main = Handler(Looper.getMainLooper())
}

object Fmt {
    fun time(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
        else String.format(Locale.US, "%02d:%02d", m, sec)
    }

    fun date(sec: Long): String = DateFormat.format("dd MMM yyyy", Date(sec * 1000)).toString()
    fun size(ctx: Context, bytes: Long): String = Formatter.formatShortFileSize(ctx, bytes)
}

object Perm {
    fun list(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33)
            arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
        else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

    fun has(ctx: Context): Boolean =
        list().all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }
}

object Util {
    fun displayName(ctx: Context, uri: Uri): String {
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) {
                    val n = it.getString(0)
                    if (!n.isNullOrEmpty()) return n
                }
            }
        } catch (_: Exception) {
        }
        return uri.lastPathSegment ?: "file"
    }

    fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    fun safeName(s: String): String = s.replace(Regex("[^\\p{L}\\p{N} _-]"), "_").trim().take(40)
}

interface Refreshable {
    fun refresh()
}

abstract class SimpleSeek : SeekBar.OnSeekBarChangeListener {
    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {}
    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
}
