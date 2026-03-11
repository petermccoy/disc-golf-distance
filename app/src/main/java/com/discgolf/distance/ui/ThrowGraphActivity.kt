package com.discgolf.distance.ui

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.discgolf.distance.data.DiscThrow
import com.discgolf.distance.databinding.ActivityThrowGraphBinding

class ThrowGraphActivity : AppCompatActivity() {

    private lateinit var binding: ActivityThrowGraphBinding
    private val viewModel: MainViewModel by viewModels()

    private var allThrows: List<DiscThrow> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityThrowGraphBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Throw Graph"

        setupSessionSpinner()
        observeData()

        binding.tvHint.text = "Pinch to zoom  •  Drag to pan  •  Double-tap to reset"
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    private fun setupSessionSpinner() {
        binding.spinnerSession.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?, v: View?, pos: Int, id: Long
                ) {
                    val selected = binding.spinnerSession.selectedItem as? String ?: return
                    val filtered = if (selected == "All Sessions") allThrows
                                   else allThrows.filter { it.sessionId == selected }
                    binding.graphView.setThrows(filtered)
                    updateStats(filtered)
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
    }

    private fun observeData() {
        viewModel.allThrows.observe(this) { throws ->
            allThrows = throws

            // Rebuild spinner options
            val sessions = listOf("All Sessions") + throws.map { it.sessionId }.distinct()
            val adapter = ArrayAdapter(
                this, android.R.layout.simple_spinner_dropdown_item, sessions
            )
            binding.spinnerSession.adapter = adapter

            // Display all by default
            binding.graphView.setThrows(throws)
            updateStats(throws)
        }
    }

    private fun updateStats(throws: List<DiscThrow>) {
        if (throws.isEmpty()) {
            binding.tvStats.text = "No throws"
            return
        }
        val best = throws.maxOf { it.distanceFeet }
        val avg  = throws.map { it.distanceFeet }.average()
        binding.tvStats.text =
            "${throws.size} throws  |  Best: %.1f ft  |  Avg: %.1f ft".format(best, avg)
    }
}
