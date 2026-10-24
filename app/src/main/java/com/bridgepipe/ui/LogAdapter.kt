package com.bridgepipe.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bridgepipe.R

class LogAdapter : RecyclerView.Adapter<LogAdapter.VH>() {

    private val items = ArrayList<String>()

    fun add(line: String) {
        items.add(line)
        if (items.size > 4000) {
            items.removeAt(0)
            notifyDataSetChanged()
        } else {
            notifyItemInserted(items.size - 1)
        }
    }

    fun clear() {
        items.clear()
        notifyDataSetChanged()
    }

    fun dump(limit: Int = 2000): String = items.takeLast(limit).joinToString("\n")

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val text: TextView = v.findViewById(R.id.logText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_log, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.text.text = items[position]
    }
}
