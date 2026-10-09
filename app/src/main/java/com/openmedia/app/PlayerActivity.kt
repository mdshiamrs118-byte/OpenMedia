package com.openmedia.app

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlin.math.abs

class PlayerActivity : AppCompatActivity() {

    companion object {
        fun intent(c: Context, v: Video): Intent =
            Intent(c, PlayerActivity::class.java).setData(v.uri)
                .putExtra("title", v.title)
                .putExtra("key", "${v.id}:${v.size}")
                .putExtra("dur", v.durationMs)
    }

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var player: ExoPlayer
    private lateinit var box: FrameLayout
    private lateinit var surface: SurfaceView
    private lateinit var controls: View
    private lateinit var volPanel: View
    private lateinit var seek: SeekBar
    private lateinit var elapsed: TextView
    private lateinit var remaining: TextView
    private lateinit var cueText: TextView
    private lateinit var btnPlay: ImageButton

    private lateinit var videoUri: Uri
    private lateinit var videoTitle: String
    private lateinit var videoKey: String
    private var extraDur = 0L

    private var ttsPlayer: MediaPlayer? = null
    private var ttsReady = false
    private var lastTtsSeek = 0L
    private var cues: List<Cue> = emptyList()

    private var vw = 0
    private var vh = 0
    private var vRatio = 1f
    private var dragging = false
    private var busy = false
    private var gen: TtsGenerator? = null
    private var dialog: AlertDialog? = null
    private var dlgBar: ProgressBar? = null
    private var dlgPct: TextView? = null

    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private val resume by lazy { getSharedPreferences("resume", MODE_PRIVATE) }

    private val pickSub = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) startTts(uri)
    }

    private val hideRunnable = Runnable { if (player.isPlaying) setControls(false) }

    private val ticker = object : Runnable {
        override fun run() {
            val pos = player.currentPosition
            if (!dragging) {
                seek.progress = pos.toInt()
                elapsed.text = Fmt.time(pos)
                remaining.text = "-" + Fmt.time(duration() - pos)
            }
            updateCue(pos)
            syncTts(false)
            ui.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val data = intent.data
        if (data == null) {
            finish()
            return
        }
        videoUri = data
        videoTitle = intent.getStringExtra("title") ?: Util.displayName(this, data).substringBeforeLast('.')
        videoKey = intent.getStringExtra("key") ?: data.toString()
        extraDur = intent.getLongExtra("dur", 0L)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_player)
        hideSystemBars()

        box = findViewById(R.id.videoBox)
        surface = findViewById(R.id.surface)
        controls = findViewById(R.id.controls)
        volPanel = findViewById(R.id.volPanel)
        seek = findViewById(R.id.seek)
        elapsed = findViewById(R.id.elapsed)
        remaining = findViewById(R.id.remaining)
        cueText = findViewById(R.id.cueText)
        btnPlay = findViewById(R.id.btnPlay)
        findViewById<TextView>(R.id.title).text = videoTitle

        player = ExoPlayer.Builder(this).build()
        player.setVideoSurfaceView(surface)
        val saved = resume.getLong(videoKey, 0L)
        player.setMediaItem(MediaItem.fromUri(videoUri), if (saved > 5000) saved else 0L)
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                btnPlay.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
                syncTts(true)
                scheduleHide()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) seek.max = duration().toInt().coerceAtLeast(1)
                if (playbackState == Player.STATE_ENDED) {
                    resume.edit().remove(videoKey).apply()
                    setControls(true)
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                vw = videoSize.width
                vh = videoSize.height
                vRatio = if (videoSize.pixelWidthHeightRatio > 0) videoSize.pixelWidthHeightRatio else 1f
                fit()
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int
            ) {
                syncTts(true)
            }

            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(this@PlayerActivity, "Cannot play this video", Toast.LENGTH_LONG).show()
            }
        })
        player.prepare()
        player.playWhenReady = true

        box.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fit() }
        box.setOnClickListener { setControls(true) }
        controls.setOnClickListener { setControls(false) }
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnSub).setOnClickListener {
            if (busy) return@setOnClickListener
            pickSub.launch(arrayOf("*/*"))
        }
        btnPlay.setOnClickListener { if (player.isPlaying) player.pause() else player.play() }
        findViewById<View>(R.id.btnRew).setOnClickListener { player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0)) }
        findViewById<View>(R.id.btnFwd).setOnClickListener {
            player.seekTo((player.currentPosition + 10_000).coerceAtMost(duration()))
        }

        seek.setOnSeekBarChangeListener(object : SimpleSeek() {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    elapsed.text = Fmt.time(progress.toLong())
                    remaining.text = "-" + Fmt.time(duration() - progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                dragging = true
                ui.removeCallbacks(hideRunnable)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                dragging = false
                player.seekTo(seek.progress.toLong())
                scheduleHide()
            }
        })

        // Independent volume sliders: original video audio and generated TTS audio.
        val volVideo = findViewById<SeekBar>(R.id.volVideo)
        val volTts = findViewById<SeekBar>(R.id.volTts)
        volVideo.progress = prefs.getInt("vol_video", 100)
        volTts.progress = prefs.getInt("vol_tts", 100)
        player.volume = volVideo.progress / 100f
        volVideo.setOnSeekBarChangeListener(object : SimpleSeek() {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                player.volume = progress / 100f
                if (fromUser) prefs.edit().putInt("vol_video", progress).apply()
            }
        })
        volTts.setOnSeekBarChangeListener(object : SimpleSeek() {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                ttsPlayer?.setVolume(progress / 100f, progress / 100f)
                if (fromUser) prefs.edit().putInt("vol_tts", progress).apply()
            }
        })

        ui.post(ticker)
        scheduleHide()
    }

    // ---- UI helpers --------------------------------------------------------

    private fun hideSystemBars() {
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.hide(WindowInsetsCompat.Type.systemBars())
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun setControls(show: Boolean) {
        controls.visibility = if (show) View.VISIBLE else View.GONE
        ui.removeCallbacks(hideRunnable)
        if (show) scheduleHide()
    }

    private fun scheduleHide() {
        ui.removeCallbacks(hideRunnable)
        if (controls.visibility == View.VISIBLE && player.isPlaying) ui.postDelayed(hideRunnable, 4000)
    }

    private fun duration(): Long {
        val d = player.duration
        return if (d != C.TIME_UNSET && d > 0) d else extraDur
    }

    private fun fit() {
        val bw = box.width
        val bh = box.height
        if (bw == 0 || bh == 0 || vw == 0 || vh == 0) return
        val ar = vw * vRatio / vh
        var w = bw
        var h = (bw / ar).toInt()
        if (h > bh) {
            h = bh
            w = (bh * ar).toInt()
        }
        val lp = surface.layoutParams as FrameLayout.LayoutParams
        if (lp.width != w || lp.height != h) {
            lp.width = w
            lp.height = h
            lp.gravity = Gravity.CENTER
            surface.layoutParams = lp
        }
    }

    private fun updateCue(pos: Long) {
        if (!ttsReady || cues.isEmpty()) return
        val t = cues.firstOrNull { pos >= it.startMs && pos < it.endMs }?.text ?: ""
        if (cueText.text.toString() != t) {
            cueText.text = t
            cueText.visibility = if (t.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    // ---- Subtitle -> TTS ---------------------------------------------------

    private fun startTts(subUri: Uri) {
        busy = true
        val wasPlaying = player.isPlaying
        player.pause()
        val baseDur = duration()
        val title = videoTitle
        val key = videoKey
        Bg.io.execute {
            try {
                val bytes = contentResolver.openInputStream(subUri)?.use { it.readBytes() }
                    ?: throw IOException("Cannot read subtitle file")
                val subName = Util.displayName(this, subUri)
                val text = String(bytes, Charsets.UTF_8)
                val parsed = SubtitleParser.parse(text, subName)
                if (parsed.isEmpty()) throw IllegalStateException("No subtitle cues found in this file")

                val cacheKey = "$key|$text|${prefs.getFloat("tts_rate", 1f)}|${prefs.getFloat("tts_pitch", 1f)}"
                val fileName = "TTS_${Util.safeName(title)}_${Util.sha1(cacheKey).take(10)}.wav"

                var audio = MediaRepo.findAudio(this, fileName) // cache hit => skip generation
                if (audio == null) {
                    val g = TtsGenerator(this)
                    gen = g
                    ui.post { showProgress() }
                    val total = if (baseDur > 0) baseDur else parsed.last().endMs + 1000
                    audio = g.generate(parsed, total, fileName, title, subName) { p ->
                        ui.post {
                            dlgBar?.progress = p
                            dlgPct?.text = "$p%"
                        }
                    }
                }
                val result = audio
                ui.post {
                    dismissProgress()
                    loadTts(result, parsed)
                    if (wasPlaying) player.play()
                }
            } catch (e: CancellationException) {
                ui.post { dismissProgress() }
            } catch (e: Exception) {
                ui.post {
                    dismissProgress()
                    Toast.makeText(this, e.message ?: "TTS generation failed", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showProgress() {
        val v = layoutInflater.inflate(R.layout.dialog_progress, null)
        dlgBar = v.findViewById(R.id.dlgBar)
        dlgPct = v.findViewById(R.id.dlgPct)
        dialog = AlertDialog.Builder(this)
            .setView(v)
            .setCancelable(false)
            .setNegativeButton("Cancel") { _, _ -> gen?.cancelled = true }
            .create().also { it.show() }
    }

    private fun dismissProgress() {
        dialog?.dismiss()
        dialog = null
        busy = false
    }

    private fun loadTts(uri: Uri, c: List<Cue>) {
        ttsPlayer?.release()
        ttsPlayer = null
        ttsReady = false
        cues = c
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            )
            mp.setDataSource(this, uri)
            mp.setOnPreparedListener {
                ttsReady = true
                val v = findViewById<SeekBar>(R.id.volTts).progress / 100f
                it.setVolume(v, v)
                volPanel.visibility = View.VISIBLE
                syncTts(true)
            }
            mp.setOnErrorListener { _, _, _ ->
                Toast.makeText(this, "Cannot play generated audio", Toast.LENGTH_SHORT).show()
                true
            }
            mp.prepareAsync()
            ttsPlayer = mp
        } catch (e: Exception) {
            mp.release()
            Toast.makeText(this, "Cannot load generated audio", Toast.LENGTH_SHORT).show()
        }
    }

    /** Keeps the TTS track locked to the video position and play state. */
    private fun syncTts(force: Boolean) {
        val mp = ttsPlayer ?: return
        if (!ttsReady) return
        try {
            val pos = player.currentPosition
            val now = System.currentTimeMillis()
            if (player.isPlaying) {
                if (pos >= mp.duration) {
                    if (mp.isPlaying) mp.pause()
                    return
                }
                if ((force || abs(mp.currentPosition - pos) > 300) && now - lastTtsSeek > 800) {
                    mp.seekTo(pos, MediaPlayer.SEEK_CLOSEST)
                    lastTtsSeek = now
                }
                if (!mp.isPlaying) mp.start()
            } else {
                if (mp.isPlaying) mp.pause()
                if (force) mp.seekTo(pos, MediaPlayer.SEEK_CLOSEST)
            }
        } catch (_: IllegalStateException) {
        }
    }

    // ---- Lifecycle ---------------------------------------------------------

    override fun onPause() {
        super.onPause()
        if (!::player.isInitialized) return
        player.pause()
        try {
            ttsPlayer?.pause()
        } catch (_: IllegalStateException) {
        }
        val d = duration()
        val p = player.currentPosition
        resume.edit().putLong(videoKey, if (d > 0 && p > d - 5000) 0L else p).apply()
    }

    override fun onDestroy() {
        gen?.cancelled = true
        ui.removeCallbacksAndMessages(null)
        dialog?.dismiss()
        ttsPlayer?.release()
        ttsPlayer = null
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }
}
