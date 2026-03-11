package com.discgolf.distance.ui

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.discgolf.distance.data.Disc
import com.discgolf.distance.databinding.ItemDiscBinding

class DiscAdapter(
    private val onDelete: (Disc) -> Unit
) : ListAdapter<Disc, DiscAdapter.VH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Disc>() {
            override fun areItemsTheSame(a: Disc, b: Disc) = a.id == b.id
            override fun areContentsTheSame(a: Disc, b: Disc) = a == b
        }
    }

    inner class VH(val binding: ItemDiscBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemDiscBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val disc = getItem(position)
        with(holder.binding) {
            tvDiscName.text = disc.name
            tvDiscMfr.text = disc.manufacturer.ifBlank { "—" }
            tvDiscFlightNumbers.text = disc.flightNumbers

            val d = GradientDrawable()
            d.shape = GradientDrawable.OVAL
            d.setColor(disc.colorArgb)
            viewDiscColor.background = d

            btnDeleteDisc.setOnClickListener { onDelete(disc) }
        }
    }
}
