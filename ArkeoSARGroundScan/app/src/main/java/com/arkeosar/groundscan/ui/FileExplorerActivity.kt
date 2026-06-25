package com.arkeosar.groundscan.ui

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import com.arkeosar.groundscan.databinding.ActivityFileExplorerBinding
import java.io.File

class FileExplorerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFileExplorerBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFileExplorerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val dir = File(filesDir, "scans")
        val files = dir.listFiles { f -> f.extension == "asgs" }?.sortedByDescending { it.lastModified() } ?: emptyList()

        if (files.isEmpty()) {
            binding.emptyText.visibility = View.VISIBLE
            binding.fileListView.visibility = View.GONE
        } else {
            val names = files.map { it.name }
            binding.fileListView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, names)
            binding.fileListView.setOnItemClickListener { _, _, position, _ ->
                val file = files[position]
                startActivity(
                    android.content.Intent(this, ScanActivity::class.java)
                        .putExtra(ScanActivity.EXTRA_MODE, ScanActivity.MODE_GROUND_SCAN)
                        .putExtra("openFilePath", file.absolutePath)
                )
            }
        }
    }
}
