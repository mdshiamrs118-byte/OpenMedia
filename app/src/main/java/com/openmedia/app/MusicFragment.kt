package com.openmedia.app

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ListView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment

/** Lists generated subtitle-TTS sets and plays them (clips at their timestamps) without any video. */
class MusicFragment : Fragment(R.layout.fragment_music), Refreshable {
    private lateinit var list: ListView
    private lateinit var empty: TextView
    private lateinit var playerBar: View
    private lateinit var trackTitle: TextView
    private lateinit var btnPlay: ImageButton
    private lateinit var seek: SeekBar
    private lateinit var time: TextView
    private var adapter: TrackAdapter? = null
    private val clock = VirtualClock()
    private var cp: ClipPlayer? = null
    private var current: Track? = null
    private var totalMs = 0L
    private var dragging = false

    private val tick = object : Runnable {
        override fun run() {
            if (cp == null || !clock.running) return
            val pos = clock.pos()
            if (pos >= totalMs) {
                finishPlayback()
                return
            }
            if (!dragging) {
                seek.progress = pos.toInt()
                time.text = Fmt.time(pos)
            }
            Bg.main.postDelayed(this, 200)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        list = view.findViewById(R.id.list)
        empty = view.findViewById(R.id.empty)
        playerBar = view.findViewById(R.id.player)
        trackTitle = view.findViewById(R.id.trackTitle)
        btnPlay = view.findViewById(R.id.btnPlay)
        seek = view.findViewById(R.id.seek)
        time = view.findViewById(R.id.time)

        list.setOnItemClickListener { _, _, pos, _ -> adapter?.let { play(it.getItem(pos)) } }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            adapter?.let { confirmDelete(it.getItem(pos)) }
            true
        }
        btnPlay.setOnClickListener {
            val c = cp ?: return@setOnClickListener
            if (clock.running) {
                clock.pause()
                c.pause()
            } else {
                if (clock.pos() >= totalMs) {
                    clock.seek(0)
                    c.seek(0)
                }
                clock.start()
                c.resume()
                Bg.main.removeCallbacks(tick)
                Bg.main.post(tick)
            }
            updateIcon()
        }
        seek.setOnSeekBarChangeListener(object : SimpleSeek() {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) time.text = Fmt.time(progress.toLong())
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                dragging = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                dragging = false
                val p = seek.progress.toLong()
                clock.seek(p)
                cp?.seek(p)
            }
        })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onPause() {
        super.onPause()
        if (clock.running) {
            clock.pause()
            cp?.pause()
        }
        if (::btnPlay.isInitialized) updateIcon()
    }

    override fun onDestroyView() {
        Bg.main.removeCallbacks(tick)
        cp?.release()
        cp = null
        current = null
        clock.pause()
        super.onDestroyView()
    }

    override fun refresh() {
        val ctx = context ?: return
        if (!::list.isInitialized) return
        val app = ctx.applicationContext
        Bg.io.execute {
            val tracks = ClipStore.loadAll(app)
            Bg.main.post {
                if (!isAdded || view == null) return@post
                val a = adapter
                if (a == null) {
                    adapter = TrackAdapter(requireContext(), tracks).also { list.adapter = it }
                } else {
                    a.data = tracks
                    a.notifyDataSetChanged()
                }
                empty.visibility = if (tracks.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun play(t: Track) {
        Bg.main.removeCallbacks(tick)
        cp?.release()
        current = t
        val sp = requireContext().getSharedPreferences("settings", Context.MODE_PRIVATE)
        val planned = Planner.plan(t.set.clips, OverlapMode.from(sp.getString("overlap_mode", "hybrid")))
        totalMs = maxOf(t.durationMs, planned.maxOfOrNull { it.endMs } ?: 0L)
        val c = ClipPlayer(requireContext()) { clock.pos() }
        c.load(planned)
        cp = c
        trackTitle.text = t.set.videoTitle
        playerBar.visibility = View.VISIBLE
        seek.max = totalMs.toInt()
        seek.progress = 0
        clock.seek(0)
        clock.start()
        c.seek(0)
        c.resume()
        updateIcon()
        Bg.main.post(tick)
    }

    private fun finishPlayback() {
        clock.pause()
        clock.seek(0)
        cp?.pause()
        cp?.seek(0)
        seek.progress = 0
        time.text = Fmt.time(0)
        updateIcon()
    }

    private fun updateIcon() {
        btnPlay.setImageResource(if (clock.running) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun confirmDelete(t: Track) {
        AlertDialog.Builder(requireContext())
            .setTitle("Delete audio?")
            .setMessage(t.set.videoTitle + "\n" + t.set.subName)
            .setPositiveButton("Delete") { _, _ ->
                if (current?.set?.dir == t.set.dir) {
                    Bg.main.removeCallbacks(tick)
                    cp?.release()
                    cp = null
                    current = null
                    clock.pause()
                    playerBar.visibility = View.GONE
                }
                Bg.io.execute {
                    ClipStore.delete(t.set)
                    Bg.main.post { refresh() }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
