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
import java.util.Calendar

class ThrowGraphActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TARGET_BEARING = "extra_target_bearing"
    }

    private lateinit var binding: ActivityThrowGraphBinding
    private val viewModel: MainViewModel by viewModels()

    private var allThrows: List<DiscThrow> = emptyList()
    private var selectedSessionId: String? = null  // null = all sessions
    private var arcModeEnabled = false

    /** Bearing received from MainActivity (set when user aimed at basket). */
    private var targetBearing: Float? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityThrowGraphBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Throw Graph"

        // Pick up aim direction passed from MainActivity
        if (intent.hasExtra(EXTRA_TARGET_BEARING)) {
            targetBearing = intent.getFloatExtra(EXTRA_TARGET_BEARING, 0f)
            binding.graphView.targetBearing = targetBearing
            // Default to arc mode when a bearing is set
            arcModeEnabled = true
            binding.graphView.arcMode = true
            binding.btnArcToggle.text = "90°"
            binding.btnArcToggle.isEnabled = true
            binding.btnArcToggle.alpha = 1f
        }

        setupSessionSpinner()
        setupDateFilter()
        setupArcToggle()
        observeData()

        binding.tvHint.text = "Pinch to zoom  •  Drag to pan  •  Double-tap to reset"
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    // ── Session spinner ───────────────────────────────────────────────────────

    private fun setupSessionSpinner() {
        binding.spinnerSession.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?, v: View?, pos: Int, id: Long
                ) {
                    val selected = binding.spinnerSession.selectedItem as? String ?: return
                    selectedSessionId = if (selected == "All Sessions") null else selected
                    applyFilters()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
    }

    // ── Date filter chips ─────────────────────────────────────────────────────

    private fun setupDateFilter() {
        binding.chipGroupDate.setOnCheckedStateChangeListener { _, _ -> applyFilters() }
    }

    /** Returns start-of-day/week/month epoch ms, or null for "All time". */
    private fun selectedDateCutoff(): Long? {
        val checkedId = binding.chipGroupDate.checkedChipId
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return when (checkedId) {
            binding.chipToday.id -> cal.timeInMillis
            binding.chipWeek.id  -> {
                cal.set(Calendar.DAY_OF_WEEK, cal.firstDayOfWeek)
                cal.timeInMillis
            }
            binding.chipMonth.id -> {
                cal.set(Calendar.DAY_OF_MONTH, 1)
                cal.timeInMillis
            }
            else -> null  // chipAll or nothing
        }
    }

    // ── Arc toggle ────────────────────────────────────────────────────────────

    private fun setupArcToggle() {
        binding.btnArcToggle.setOnClickListener {
            arcModeEnabled = !arcModeEnabled
            binding.btnArcToggle.text = if (arcModeEnabled) "90°" else "360°"
            binding.graphView.arcMode = arcModeEnabled
        }
    }

    // ── Filters ───────────────────────────────────────────────────────────────

    private fun applyFilters() {
        val cutoff = selectedDateCutoff()

        var filtered = allThrows

        // Date filter
        if (cutoff != null) {
            filtered = filtered.filter { it.startTimeMs >= cutoff }
        }

        // Session filter
        selectedSessionId?.let { sid ->
            filtered = filtered.filter { it.sessionId == sid }
        }

        binding.graphView.setThrows(filtered)
        updateStats(filtered)
    }

    // ── Data observation ──────────────────────────────────────────────────────

    private fun observeData() {
        viewModel.allThrows.observe(this) { throws ->
            allThrows = throws

            val sessions = listOf("All Sessions") + throws.map { it.sessionId }.distinct()
            val adapter = ArrayAdapter(
                this, android.R.layout.simple_spinner_dropdown_item, sessions
            )
            binding.spinnerSession.adapter = adapter

            applyFilters()
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
