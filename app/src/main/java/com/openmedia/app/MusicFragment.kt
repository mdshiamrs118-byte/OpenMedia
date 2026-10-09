package com.openmedia.app

import android.media.MediaPlayer
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ListView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment

class MusicFragment : Fragment(R.layout.fragment_music), Refreshable {
    private lateinit var list: ListView
    private lateinit var empty: TextView
    private lateinit var playerBar: View
    private lateinit var trackTitle: TextView
    private lateinit var btnPlay: ImageButton
    private lateinit var seek: SeekBar
    private lateinit var time: TextView
    private var adapter: TrackAdapter? = null
    private var mp: MediaPlayer? = null
    private var current: Track? = null
    private var dragging = false

    private val tick = object : Runnable {
        override fun run() {
            val p = mp ?: return
            try {
                if (!dragging) {
                    seek.progress = p.currentPosition
                    time.text = Fmt.time(p.currentPosition.toLong())
                }
            } catch (_: IllegalStateException) {
            }
            Bg.main.postDelayed(this, 300)
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
            val p = mp ?: return@setOnClickListener
            try {
                if (p.isPlaying) p.pause() else p.start()
            } catch (_: IllegalStateException) {
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
                try {
                    mp?.seekTo(seek.progress)
                } catch (_: IllegalStateException) {
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onPause() {
        super.onPause()
        try {
            mp?.pause()
        } catch (_: IllegalStateException) {
        }
        if (::btnPlay.isInitialized) updateIcon()
    }

    override fun onDestroyView() {
        Bg.main.removeCallbacks(tick)
        mp?.release()
        mp = null
        super.onDestroyView()
    }

    override fun refresh() {
        val ctx = context ?: return
        if (!::list.isInitialized) return
        val app = ctx.applicationContext
        Bg.io.execute {
            val tracks = MediaRepo.loadTracks(app)
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
        mp?.release()
        current = t
        trackTitle.text = t.videoTitle
        playerBar.visibility = View.VISIBLE
        val p = MediaPlayer()
        mp = p
        try {
            p.setDataSource(requireContext(), t.uri)
            p.setOnPreparedListener {
                seek.max = it.duration
                it.start()
                updateIcon()
                Bg.main.post(tick)
            }
            p.setOnCompletionListener {
                updateIcon()
                seek.progress = 0
                time.text = Fmt.time(0)
            }
            p.setOnErrorListener { _, _, _ ->
                Toast.makeText(context, "Cannot play this file", Toast.LENGTH_SHORT).show()
                true
            }
            p.prepareAsync()
        } catch (e: Exception) {
            Toast.makeText(context, "Cannot play this file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateIcon() {
        val playing = try {
            mp?.isPlaying == true
        } catch (_: IllegalStateException) {
            false
        }
        btnPlay.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun confirmDelete(t: Track) {
        AlertDialog.Builder(requireContext())
            .setTitle("Delete audio?")
            .setMessage(t.displayName)
            .setPositiveButton("Delete") { _, _ ->
                if (current?.uri == t.uri) {
                    Bg.main.removeCallbacks(tick)
                    mp?.release()
                    mp = null
                    current = null
                    playerBar.visibility = View.GONE
                }
                val app = requireContext().applicationContext
                Bg.io.execute {
                    MediaRepo.deleteTrack(app, t)
                    Bg.main.post { refresh() }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
