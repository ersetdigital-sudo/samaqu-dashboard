package com.samaqu.keyboard.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.samaqu.keyboard.R
import com.samaqu.keyboard.data.CachedProduct
import com.samaqu.keyboard.util.SamaQuText

/**
 * One tappable row of the invoice's product picker: the product on the left, its price on
 * the right. Tapping hands the whole row back so the caller can fill the invoice.
 */
class ProductAdapter(
    private val onPick: (CachedProduct) -> Unit
) : ListAdapter<CachedProduct, ProductAdapter.VH>(DIFF) {

    /**
     * Short stock note per product id, e.g. `stok 47`.
     *
     * Kept beside the list rather than inside the row: the note only changes when a sync
     * finishes, while the rows themselves change with every search keystroke.
     */
    private var stockNotes: Map<String, String> = emptyMap()

    fun submitStockNotes(notes: Map<String, String>) {
        stockNotes = notes
        notifyDataSetChanged()
    }

    inner class VH(root: View) : RecyclerView.ViewHolder(root) {
        val name: TextView = root.findViewById(R.id.productName)
        val detail: TextView = root.findViewById(R.id.productDetail)
        val price: TextView = root.findViewById(R.id.productPrice)

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
            .inflate(R.layout.item_product, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val product = getItem(position)
        holder.name.text = product.name
        holder.price.text = "Rp " + SamaQuText.rupiah(product.price)

        // Series, cloth, colour and available stock in one muted line, hidden when empty.
        val detail = listOf(product.series, product.kain, product.colors, stockNotes[product.id].orEmpty())
            .filter { it.isNotBlank() }
            .joinToString(" • ")
        holder.detail.text = detail
        holder.detail.visibility = if (detail.isBlank()) View.GONE else View.VISIBLE
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<CachedProduct>() {
            override fun areItemsTheSame(a: CachedProduct, b: CachedProduct) = a.id == b.id
            override fun areContentsTheSame(a: CachedProduct, b: CachedProduct) = a == b
        }
    }
}
