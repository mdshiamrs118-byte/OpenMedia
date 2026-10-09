package com.openmedia.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import java.util.Locale
import kotlin.math.roundToInt

class SettingsFragment : Fragment(R.layout.fragment_settings) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val sp = requireContext().getSharedPreferences("settings", Context.MODE_PRIVATE)
        bind(view.findViewById(R.id.rateBar), view.findViewById(R.id.rateVal), "tts_rate", sp)
        bind(view.findViewById(R.id.pitchBar), view.findViewById(R.id.pitchVal), "tts_pitch", sp)
        view.findViewById<Button>(R.id.btnTtsSettings).setOnClickListener {
            try {
                startActivity(Intent("com.android.settings.TTS_SETTINGS"))
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }
    }

    /** Slider 0..15 maps to 0.5x..2.0x. */
    private fun bind(bar: SeekBar, label: TextView, key: String, sp: android.content.SharedPreferences) {
        bar.max = 15
        bar.progress = ((sp.getFloat(key, 1f) - 0.5f) * 10).roundToInt().coerceIn(0, 15)
        label.text = String.format(Locale.US, "%.1fx", 0.5f + bar.progress / 10f)
        bar.setOnSeekBarChangeListener(object : SimpleSeek() {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val v = 0.5f + progress / 10f
                label.text = String.format(Locale.US, "%.1fx", v)
                if (fromUser) sp.edit().putFloat(key, v).apply()
            }
        })
    }
}
