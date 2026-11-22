package com.nexus.cli

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.nexus.cli.databinding.ItemTerminalLineBinding

class TerminalAdapter : RecyclerView.Adapter<TerminalAdapter.LineHolder>() {

    class LineHolder(val binding: ItemTerminalLineBinding) : RecyclerView.ViewHolder(binding.root)

    private val lines = ArrayList<TerminalLine>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LineHolder {
        val binding = ItemTerminalLineBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return LineHolder(binding)
    }

    override fun onBindViewHolder(holder: LineHolder, position: Int) {
        val line = lines[position]
        val colorRes = when (line.type) {
            LineType.INPUT -> R.color.nexus_green
            LineType.ERROR -> R.color.nexus_red
            LineType.SYSTEM -> R.color.nexus_cyan
            LineType.OUTPUT -> R.color.text_primary
        }
        holder.binding.lineText.text = line.text
        holder.binding.lineText.setTextColor(ContextCompat.getColor(holder.itemView.context, colorRes))
    }

    override fun getItemCount(): Int = lines.size

    fun add(newLines: List<TerminalLine>) {
        if (newLines.isEmpty()) return
        val start = lines.size
        lines.addAll(newLines)
        notifyItemRangeInserted(start, newLines.size)
        trim()
    }

    fun clear() {
        val count = lines.size
        if (count == 0) return
        lines.clear()
        notifyItemRangeRemoved(0, count)
    }

    private fun trim() {
        if (lines.size > MAX_LINES) {
            val remove = lines.size - MAX_LINES
            lines.subList(0, remove).clear()
            notifyItemRangeRemoved(0, remove)
        }
    }

    private companion object {
        const val MAX_LINES = 600
    }
}