package com.arkeosar.groundscan.ui

import android.os.Bundle
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import com.arkeosar.groundscan.data.SettingsData
import com.arkeosar.groundscan.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settings: SettingsData

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = SettingsData(this)

        binding.seekArea.progress = settings.areaMeters
        binding.areaValueText.text = "${settings.areaMeters} m"
        binding.switchAutoMode.isChecked = settings.automaticMode
        binding.switchZigzag.isChecked = settings.zigzag

        binding.seekArea.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                val meters = value.coerceAtLeast(1)
                binding.areaValueText.text = "$meters m"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.btnSaveSettings.setOnClickListener {
            settings.areaMeters = binding.seekArea.progress.coerceAtLeast(1)
            settings.automaticMode = binding.switchAutoMode.isChecked
            settings.zigzag = binding.switchZigzag.isChecked
            finish()
        }
    }
}
