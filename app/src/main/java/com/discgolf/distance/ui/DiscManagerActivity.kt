package com.discgolf.distance.ui

import android.view.MenuItem
import android.view.View
import android.os.Bundle
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.discgolf.distance.data.Disc
import com.discgolf.distance.data.DiscColors
import com.discgolf.distance.databinding.ActivityDiscManagerBinding
import com.discgolf.distance.databinding.DialogAddDiscBinding

class DiscManagerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDiscManagerBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var discAdapter: DiscAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDiscManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "My Discs"

        discAdapter = DiscAdapter(
            onEdit   = { disc -> showAddDiscDialog(disc) },
            onDelete = { disc -> confirmDeleteDisc(disc) }
        )
        binding.recyclerDiscs.layoutManager = LinearLayoutManager(this)
        binding.recyclerDiscs.adapter = discAdapter

        viewModel.allDiscs.observe(this) { discs ->
            discAdapter.submitList(discs)
            binding.tvEmpty.visibility = if (discs.isEmpty()) View.VISIBLE else View.GONE
        }

        binding.fabAddDisc.setOnClickListener { showAddDiscDialog() }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    fun showAddDiscDialog(existing: Disc? = null) {
        val db = DialogAddDiscBinding.inflate(layoutInflater)

        // Configure each slider: expand valueTo first, set value, then narrow valueFrom, then stepSize.
        // value must always remain within [valueFrom, valueTo] at every step or Slider throws.
        val speedDefault = existing?.speed ?: 7
        val glideDefault = existing?.glide ?: 5
        val turnDefault  = existing?.turn  ?: -1
        val fadeDefault  = existing?.fade  ?: 1

        db.sliderSpeed.valueTo   = 14f; db.sliderSpeed.value = speedDefault.toFloat()
        db.sliderSpeed.valueFrom = 1f;  db.sliderSpeed.stepSize = 1f

        db.sliderGlide.valueTo   = 7f;  db.sliderGlide.value = glideDefault.toFloat()
        db.sliderGlide.valueFrom = 1f;  db.sliderGlide.stepSize = 1f

        // Turn: valueFrom=-5 is safe first because default valueTo=1 and default value=0 >= -5
        db.sliderTurn.valueFrom  = -5f; db.sliderTurn.value = turnDefault.toFloat()
        db.sliderTurn.valueTo    = 1f;  db.sliderTurn.stepSize = 1f

        db.sliderFade.valueTo    = 5f;  db.sliderFade.value = fadeDefault.toFloat()
        db.sliderFade.stepSize   = 1f

        updateLabel(db.tvSpeedVal, "Speed", speedDefault)
        updateLabel(db.tvGlideVal, "Glide", glideDefault)
        updateTurnLabel(db.tvTurnVal, turnDefault)
        updateLabel(db.tvFadeVal, "Fade", fadeDefault)

        db.sliderSpeed.addOnChangeListener { _, v, _ -> updateLabel(db.tvSpeedVal, "Speed", v.toInt()) }
        db.sliderGlide.addOnChangeListener { _, v, _ -> updateLabel(db.tvGlideVal, "Glide", v.toInt()) }
        db.sliderTurn.addOnChangeListener  { _, v, _ -> updateTurnLabel(db.tvTurnVal, v.toInt()) }
        db.sliderFade.addOnChangeListener  { _, v, _ -> updateLabel(db.tvFadeVal, "Fade", v.toInt()) }

        // Pre-fill text fields when editing
        if (existing != null) {
            db.etName.setText(existing.name)
            db.etManufacturer.setText(existing.manufacturer)
        }

        var selectedColor = existing?.colorArgb ?: DiscColors.defaultColor
        val colorAdapter = ColorSwatchAdapter(selectedColor) { c -> selectedColor = c }
        db.rvColors.layoutManager = GridLayoutManager(this, DiscColors.COLUMNS)
        db.rvColors.adapter = colorAdapter
        db.rvColors.isNestedScrollingEnabled = false

        val title      = if (existing != null) "Edit Disc" else "Add Disc"
        val actionText = if (existing != null) "Save"      else "Add"

        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(db.root)
            .setPositiveButton(actionText, null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = db.etName.text.toString().trim()
                val mfr  = db.etManufacturer.text.toString().trim()
                if (name.isEmpty()) {
                    db.tilName.error = "Name is required"
                    return@setOnClickListener
                }
                db.tilName.error = null
                if (existing != null) {
                    viewModel.updateDisc(
                        existing.copy(
                            name         = name,
                            manufacturer = mfr,
                            speed        = db.sliderSpeed.value.toInt(),
                            glide        = db.sliderGlide.value.toInt(),
                            turn         = db.sliderTurn.value.toInt(),
                            fade         = db.sliderFade.value.toInt(),
                            colorArgb    = selectedColor
                        )
                    )
                } else {
                    viewModel.insertDisc(
                        Disc(
                            name         = name,
                            manufacturer = mfr,
                            speed        = db.sliderSpeed.value.toInt(),
                            glide        = db.sliderGlide.value.toInt(),
                            turn         = db.sliderTurn.value.toInt(),
                            fade         = db.sliderFade.value.toInt(),
                            colorArgb    = selectedColor
                        )
                    )
                }
                dialog.dismiss()
            }
            // Widen so 8-column color grid fits comfortably
            dialog.window?.setLayout(
                (resources.displayMetrics.widthPixels * 0.95).toInt(),
                android.view.WindowManager.LayoutParams.WRAP_CONTENT
            )
        }

        dialog.show()
    }

    private fun updateLabel(tv: TextView, label: String, v: Int) {
        tv.text = "$label: $v"
    }

    private fun updateTurnLabel(tv: TextView, v: Int) {
        tv.text = "Turn: %+d".format(v)
    }

    private fun confirmDeleteDisc(disc: Disc) {
        AlertDialog.Builder(this)
            .setTitle("Remove Disc")
            .setMessage("Remove \"${disc.name}\"? Throws that used it will keep the association in history.")
            .setPositiveButton("Remove") { _, _ -> viewModel.deleteDisc(disc) }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
