package com.samaqu.keyboard.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.samaqu.keyboard.R
import com.samaqu.keyboard.data.CachedVariant

/**
 * Second step of the product picker: the size grid of the chosen product.
 *
 * Stock is the point of this screen - more than two thirds of the store's grid is sold out -
 * so an empty size is printed as "habis" in the warning colour and its label is muted. It
 * stays tappable, because this only reports what the store holds: nothing is reserved here
 * and the CS may know something the table does not.
 */
class VariantAdapter(
    private val onPick: (CachedVariant) -> Unit
) : ListAdapter<CachedVariant, VariantAdapter.VH>(DIFF) {

    inner class VH(root: View) : RecyclerView.ViewHolder(root) {
        val size: TextView = root.findViewById(R.id.variantSize)
        val detail: TextView = root.findViewById(R.id.variantDetail)
        val stock: TextView = root.findViewById(R.id.variantStock)

        init {
            root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onPick(currentList[position])
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_variant, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val variant = getItem(position)
        val context = holder.itemView.context
        val available = variant.stock > 0

        holder.size.text = variant.size.ifBlank { "-" }
        holder.size.setTextColor(
            ContextCompat.getColor(
                context,
                if (available) R.color.text_primary else R.color.text_muted
            )
        )

        // The store keeps "default" as a placeholder colour; showing it would be noise.
        val color = variant.color.takeUnless { it.equals("default", ignoreCase = true) }.orEmpty()
        holder.detail.text = color
        holder.detail.visibility = if (color.isBlank()) View.GONE else View.VISIBLE

        holder.stock.text = if (available) {
            context.getString(R.string.variant_stock, variant.stock)
        } else {
            context.getString(R.string.variant_sold_out)
        }
        holder.stock.setTextColor(
            ContextCompat.getColor(
                context,
                if (available) R.color.success else R.color.danger
            )
        )
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<CachedVariant>() {
            override fun areItemsTheSame(a: CachedVariant, b: CachedVariant) = a.id == b.id
            override fun areContentsTheSame(a: CachedVariant, b: CachedVariant) = a == b
        }
    }
}
