package com.arkeosar.groundscan.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.arkeosar.groundscan.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnMagnetometer.setOnClickListener {
            startActivity(
                Intent(this, ScanActivity::class.java)
                    .putExtra(ScanActivity.EXTRA_MODE, ScanActivity.MODE_MAGNETOMETER)
            )
        }

        binding.btnGroundScan.setOnClickListener {
            startActivity(Intent(this, GridScanActivity::class.java))
        }

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.btnFiles.setOnClickListener {
            startActivity(Intent(this, FileExplorerActivity::class.java))
        }
    }
}
