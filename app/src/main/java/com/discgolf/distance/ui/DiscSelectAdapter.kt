package com.discgolf.distance.ui

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.discgolf.distance.data.Disc
import com.discgolf.distance.databinding.ItemDiscSelectBinding

class DiscSelectAdapter(
    private val onSelect: (Disc) -> Unit
) : ListAdapter<Disc, DiscSelectAdapter.VH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Disc>() {
            override fun areItemsTheSame(a: Disc, b: Disc) = a.id == b.id
            override fun areContentsTheSame(a: Disc, b: Disc) = a == b
        }
    }

    inner class VH(val binding: ItemDiscSelectBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemDiscSelectBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val disc = getItem(position)
        with(holder.binding) {
            tvSelectDiscName.text = disc.name
            val mfr = disc.manufacturer.ifBlank { "—" }
            tvSelectDiscInfo.text = "$mfr  •  ${disc.flightNumbers}"

            val d = GradientDrawable()
            d.shape = GradientDrawable.OVAL
            d.setColor(disc.colorArgb)
            viewSelectDiscColor.background = d

            root.setOnClickListener { onSelect(disc) }
        }
    }
}
