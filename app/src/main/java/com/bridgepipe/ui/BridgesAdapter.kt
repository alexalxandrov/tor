package com.bridgepipe.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.bridgepipe.R
import com.bridgepipe.data.Bridge
import com.bridgepipe.data.BridgeStatus

class BridgesAdapter : RecyclerView.Adapter<BridgesAdapter.VH>() {

    private val items = ArrayList<Bridge>()

    fun submit(list: List<Bridge>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val transport: TextView = v.findViewById(R.id.bridgeTransport)
        val status: TextView = v.findViewById(R.id.bridgeStatus)
        val line: TextView = v.findViewById(R.id.bridgeLine)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_bridge, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val b = items[position]
        val ctx = holder.itemView.context
        holder.transport.text = b.transport
        holder.line.text = b.line
        when (b.status) {
            BridgeStatus.OK -> {
                holder.status.text = if (b.latencyMs >= 0) "✓ ${b.latencyMs} мс" else "✓"
                holder.status.setTextColor(ContextCompat.getColor(ctx, R.color.statusOk))
            }
            BridgeStatus.TESTING -> {
                holder.status.text = "проверка…"
                holder.status.setTextColor(ContextCompat.getColor(ctx, R.color.statusStarting))
            }
            BridgeStatus.FAIL -> {
                holder.status.text = "✗ мёртв (попыток: ${b.failCount})"
                holder.status.setTextColor(ContextCompat.getColor(ctx, R.color.statusFail))
            }
            else -> {
                holder.status.text = "новый"
                holder.status.setTextColor(ContextCompat.getColor(ctx, R.color.statusOff))
            }
        }
    }
}
