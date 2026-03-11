package com.discgolf.distance.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.discgolf.distance.R
import com.discgolf.distance.data.DiscColors

class ColorSwatchAdapter(
    initialColor: Int,
    private val onColorSelected: (Int) -> Unit
) : RecyclerView.Adapter<ColorSwatchAdapter.VH>() {

    private val colors = DiscColors.palette
    private var selectedIndex = colors.indexOf(initialColor).takeIf { it >= 0 } ?: 0

    fun getSelectedColor(): Int = colors[selectedIndex]

    inner class VH(val view: View) : RecyclerView.ViewHolder(view)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_color_swatch, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val color = colors[position]
        val isSelected = position == selectedIndex
        val d = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 6f
            setColor(color)
            if (isSelected) setStroke(5, Color.WHITE)
        }
        holder.view.background = d
        holder.view.setOnClickListener {
            val old = selectedIndex
            selectedIndex = holder.bindingAdapterPosition
            notifyItemChanged(old)
            notifyItemChanged(selectedIndex)
            onColorSelected(color)
        }
    }

    override fun getItemCount() = colors.size
}
