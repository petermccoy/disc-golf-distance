package com.discgolf.distance.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.discgolf.distance.data.Disc
import com.discgolf.distance.data.DiscThrow
import com.discgolf.distance.databinding.ItemThrowBinding
import java.text.SimpleDateFormat
import java.util.*

class ThrowAdapter(
    private val onDelete: (DiscThrow) -> Unit
) : ListAdapter<DiscThrow, ThrowAdapter.ViewHolder>(DIFF) {

    private var discMap: Map<Long, Disc> = emptyMap()

    fun updateDiscs(discs: List<Disc>) {
        discMap = discs.associateBy { it.id }
        notifyItemRangeChanged(0, currentList.size)
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<DiscThrow>() {
            override fun areItemsTheSame(a: DiscThrow, b: DiscThrow) = a.id == b.id
            override fun areContentsTheSame(a: DiscThrow, b: DiscThrow) = a == b
        }
        private val timeFmt = SimpleDateFormat("MM/dd HH:mm:ss", Locale.US)
    }

    inner class ViewHolder(val binding: ItemThrowBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemThrowBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        with(holder.binding) {
            tvThrowId.text      = "#%d".format(item.throwNumber)
            tvSession.text      = item.sessionId
            tvDistance.text     = "%.1f ft".format(item.distanceFeet)
            tvDistanceM.text    = "(%.1f m)".format(item.distanceMeters)
            tvTime.text         = timeFmt.format(Date(item.startTimeMs))
            tvFlightTime.text   = formatDuration(item.flightTimeMs)
            btnDelete.setOnClickListener { onDelete(item) }

            // Disc name
            val disc = item.discId?.let { discMap[it] }
            if (disc != null) {
                tvDiscName.text = disc.name
                tvDiscName.visibility = View.VISIBLE
            } else {
                tvDiscName.visibility = View.GONE
            }

            // Alternating row color for readability
            val bg = if (position % 2 == 0)
                root.context.getColor(com.discgolf.distance.R.color.row_even)
            else
                root.context.getColor(com.discgolf.distance.R.color.row_odd)
            root.setBackgroundColor(bg)
        }
    }

    private fun formatDuration(ms: Long): String {
        if (ms <= 0) return "—"
        val s = ms / 1000
        return if (s < 60) "${s}s" else "%dm %ds".format(s / 60, s % 60)
    }
}
