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
import java.util.Calendar

enum class SortField { DISTANCE, TIME, SESSION, THROW_NUMBER }
enum class SortDir   { ASC, DESC }

class ThrowListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityThrowListBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var adapter: ThrowAdapter

    private var sortField = SortField.TIME
    private var sortDir   = SortDir.DESC
    private var allThrowsRaw: List<DiscThrow> = emptyList()  // full DB set
    private var allThrows: List<DiscThrow> = emptyList()     // after date+session filter
    private var selectedSessionId: String? = null            // null = all sessions

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityThrowListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Throw History"

        setupRecyclerView()
        setupSortSpinners()
        setupFilterControls()
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

    // ── Filter controls ───────────────────────────────────────────────────────

    private fun setupFilterControls() {
        binding.chipGroupDate.setOnCheckedStateChangeListener { _, _ -> applyFilters() }

        binding.spinnerFilterSession.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?, v: View?, pos: Int, id: Long
                ) {
                    val sel = binding.spinnerFilterSession.selectedItem as? String ?: return
                    selectedSessionId = if (sel == "All Sessions") null else sel
                    applyFilters()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
    }

    private fun selectedDateCutoff(): Long? {
        val checkedId = binding.chipGroupDate.checkedChipId
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0);      set(Calendar.MILLISECOND, 0)
        }
        return when (checkedId) {
            binding.chipToday.id -> cal.timeInMillis
            binding.chipWeek.id  -> { cal.set(Calendar.DAY_OF_WEEK, cal.firstDayOfWeek); cal.timeInMillis }
            binding.chipMonth.id -> { cal.set(Calendar.DAY_OF_MONTH, 1); cal.timeInMillis }
            else -> null
        }
    }

    private fun applyFilters() {
        val cutoff = selectedDateCutoff()
        var filtered = allThrowsRaw
        if (cutoff != null) filtered = filtered.filter { it.startTimeMs >= cutoff }
        selectedSessionId?.let { sid -> filtered = filtered.filter { it.sessionId == sid } }
        allThrows = filtered
        displaySorted()
        binding.tvEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    // ── Data observation ──────────────────────────────────────────────────────

    private fun observeData() {
        viewModel.allThrows.observe(this) { throws ->
            allThrowsRaw = throws

            // Rebuild session spinner
            val sessions = listOf("All Sessions") + throws.map { it.sessionId }.distinct()
            val spinnerAdapter = ArrayAdapter(
                this, android.R.layout.simple_spinner_dropdown_item, sessions
            )
            binding.spinnerFilterSession.adapter = spinnerAdapter

            applyFilters()
        }

        viewModel.allDiscs.observe(this) { discs ->
            adapter.updateDiscs(discs)
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

        // Summary row reflects the filtered set
        if (allThrows.isNotEmpty()) {
            val best = allThrows.maxOf { it.distanceFeet }
            val avg  = allThrows.map { it.distanceFeet }.average()
            binding.tvSummary.text =
                "${allThrows.size} throws  |  Best: %.1f ft  |  Avg: %.1f ft".format(best, avg)
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
