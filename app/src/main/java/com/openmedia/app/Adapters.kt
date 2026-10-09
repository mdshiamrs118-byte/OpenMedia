package com.openmedia.app

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.BaseExpandableListAdapter
import android.widget.ImageView
import android.widget.TextView

private object Thumbs {
    val cache = object : LruCache<Long, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: Long, value: Bitmap): Int = value.byteCount
    }
}

class FolderAdapter(private val ctx: Context, val data: List<Folder>) : BaseExpandableListAdapter() {
    private val inf = LayoutInflater.from(ctx)

    override fun getGroupCount() = data.size
    override fun getChildrenCount(groupPosition: Int) = data[groupPosition].videos.size
    override fun getGroup(groupPosition: Int) = data[groupPosition]
    override fun getChild(groupPosition: Int, childPosition: Int) = data[groupPosition].videos[childPosition]
    override fun getGroupId(groupPosition: Int) = groupPosition.toLong()
    override fun getChildId(groupPosition: Int, childPosition: Int) = data[groupPosition].videos[childPosition].id
    override fun hasStableIds() = false
    override fun isChildSelectable(groupPosition: Int, childPosition: Int) = true

    override fun getGroupView(groupPosition: Int, isExpanded: Boolean, convertView: View?, parent: ViewGroup?): View {
        val v = convertView ?: inf.inflate(R.layout.item_folder, parent, false)
        val f = data[groupPosition]
        val n = f.videos.size
        v.findViewById<TextView>(R.id.name).text = f.name
        v.findViewById<TextView>(R.id.path).text = f.path
        v.findViewById<TextView>(R.id.meta).text =
            "${Fmt.date(f.dateSec)}  •  $n ${if (n == 1) "video" else "videos"}"
        return v
    }

    override fun getChildView(
        groupPosition: Int, childPosition: Int, isLastChild: Boolean, convertView: View?, parent: ViewGroup?
    ): View {
        val v = convertView ?: inf.inflate(R.layout.item_video, parent, false)
        val vid = data[groupPosition].videos[childPosition]
        v.findViewById<TextView>(R.id.title).text = vid.title
        v.findViewById<TextView>(R.id.meta).text = "${Fmt.time(vid.durationMs)}  •  ${Fmt.size(ctx, vid.size)}"
        val thumb = v.findViewById<ImageView>(R.id.thumb)
        thumb.tag = vid.id
        val cached = Thumbs.cache.get(vid.id)
        if (cached != null) {
            thumb.setImageBitmap(cached)
        } else {
            thumb.setImageDrawable(null)
            Bg.io.execute {
                val bmp = try {
                    ctx.contentResolver.loadThumbnail(vid.uri, Size(192, 108), null)
                } catch (_: Exception) {
                    null
                }
                if (bmp != null) {
                    Thumbs.cache.put(vid.id, bmp)
                    Bg.main.post { if (thumb.tag == vid.id) thumb.setImageBitmap(bmp) }
                }
            }
        }
        return v
    }
}

class TrackAdapter(private val ctx: Context, var data: List<Track>) : BaseAdapter() {
    private val inf = LayoutInflater.from(ctx)

    override fun getCount() = data.size
    override fun getItem(position: Int) = data[position]
    override fun getItemId(position: Int) = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val v = convertView ?: inf.inflate(R.layout.item_track, parent, false)
        val t = data[position]
        v.findViewById<TextView>(R.id.title).text = t.set.videoTitle
        v.findViewById<TextView>(R.id.sub).text =
            if (t.set.subName.isEmpty()) "Subtitle: unknown" else "Subtitle: ${t.set.subName}"
        v.findViewById<TextView>(R.id.meta).text =
            "${Fmt.date(t.set.createdSec)}  •  ${Fmt.size(ctx, t.sizeBytes)}  •  ${Fmt.time(t.durationMs)}"
        return v
    }
}
