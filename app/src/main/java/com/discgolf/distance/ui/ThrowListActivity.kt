package com.discgolf.distance.ui

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.discgolf.distance.data.DiscThrow
import com.discgolf.distance.databinding.ActivityThrowListBinding

enum class SortField { DISTANCE, TIME, SESSION, THROW_NUMBER }
enum class SortDir   { ASC, DESC }

class ThrowListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityThrowListBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var adapter: ThrowAdapter

    private var sortField = SortField.TIME
    private var sortDir   = SortDir.DESC
    private var allThrows: List<DiscThrow> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityThrowListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Throw History"

        setupRecyclerView()
        setupSortSpinners()
        observeData()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    private fun setupRecyclerView() {
        adapter = ThrowAdapter(
            onDelete = { throw_ -> confirmDelete(throw_) }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
    }

    private fun setupSortSpinners() {
        val fields = arrayOf("Distance", "Time", "Session", "Throw #")
        val dirs   = arrayOf("Descending", "Ascending")

        binding.spinnerSortField.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, fields)
        binding.spinnerSortDir.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, dirs)

        // Default: Time DESC
        binding.spinnerSortField.setSelection(1)
        binding.spinnerSortDir.setSelection(0)

        val listener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                sortField = when (binding.spinnerSortField.selectedItemPosition) {
                    0 -> SortField.DISTANCE
                    1 -> SortField.TIME
                    2 -> SortField.SESSION
                    3 -> SortField.THROW_NUMBER
                    else -> SortField.TIME
                }
                sortDir = if (binding.spinnerSortDir.selectedItemPosition == 0) SortDir.DESC else SortDir.ASC
                displaySorted()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        binding.spinnerSortField.onItemSelectedListener = listener
        binding.spinnerSortDir.onItemSelectedListener  = listener
    }

    private fun observeData() {
        viewModel.allThrows.observe(this) { throws ->
            allThrows = throws
            displaySorted()
            binding.tvEmpty.visibility = if (throws.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun displaySorted() {
        val sorted = when (sortField) {
            SortField.DISTANCE     -> allThrows.sortedBy { it.distanceMeters }
            SortField.TIME         -> allThrows.sortedBy { it.startTimeMs }
            SortField.SESSION      -> allThrows.sortedWith(compareBy({ it.sessionId }, { it.throwNumber }))
            SortField.THROW_NUMBER -> allThrows.sortedBy { it.throwNumber }
        }.let { if (sortDir == SortDir.DESC) it.reversed() else it }

        adapter.submitList(sorted)

        // Summary row
        if (allThrows.isNotEmpty()) {
            val best = allThrows.maxOf { it.distanceFeet }
            val avg  = allThrows.map { it.distanceFeet }.average()
            binding.tvSummary.text =
                "Total: ${allThrows.size} throws  |  Best: %.1f ft  |  Avg: %.1f ft".format(best, avg)
            binding.tvSummary.visibility = View.VISIBLE
        } else {
            binding.tvSummary.visibility = View.GONE
        }
    }

    private fun confirmDelete(throw_: DiscThrow) {
        AlertDialog.Builder(this)
            .setTitle("Delete Throw")
            .setMessage("Delete throw #${throw_.throwNumber} (%.1f ft)?".format(throw_.distanceFeet))
            .setPositiveButton("Delete") { _, _ -> viewModel.deleteThrow(throw_) }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
