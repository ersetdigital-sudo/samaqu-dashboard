package com.samaqu.keyboard.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.samaqu.keyboard.R
import com.samaqu.keyboard.network.OrderItem

class OrderAdapter(
    private val onClick: (OrderItem) -> Unit
) : RecyclerView.Adapter<OrderAdapter.VH>() {

    private var items: List<OrderItem> = emptyList()

    fun submitList(list: List<OrderItem>) {
        items = list
        notifyDataSetChanged()
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val txtBuyer: TextView = v.findViewById(R.id.txtBuyer)
        val txtOrderId: TextView = v.findViewById(R.id.txtOrderId)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_order, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val buyer = item.customerName?.trim().orEmpty().ifBlank { "Pembeli" }
        val number = item.orderNumber?.trim().orEmpty().ifBlank { item.id.take(8) }
        holder.txtBuyer.text = buyer
        holder.txtOrderId.text = "# $number • ${item.createdAt?.take(10) ?: "-"}"
        holder.itemView.setOnClickListener { onClick(item) }
    }
}
