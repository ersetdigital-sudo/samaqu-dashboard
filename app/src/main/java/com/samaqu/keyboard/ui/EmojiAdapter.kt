package com.samaqu.keyboard.ui

import android.content.res.ColorStateList
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

/**
 * Grid adapter for the emoji panel.
 *
 * Items are plain [TextView]s with `layout_width = match_parent`, so the columns
 * are whatever [RecyclerView]'s GridLayoutManager was asked for. That keeps the
 * panel correct on any screen width without hardcoding a cell size.
 *
 * The emoji themselves live in [EmojiCatalog].
 */
class EmojiAdapter(
    private val onClick: (String) -> Unit
) : ListAdapter<String, EmojiAdapter.VH>(DIFF) {

    inner class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        val cell = (44 * ctx.resources.displayMetrics.density).toInt()
        val tv = TextView(ctx).apply {
            textSize = 24f
            gravity = Gravity.CENTER
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, cell)
            // Soft accent ripple, so a tap is visible without a hard background.
            background = RippleDrawable(ColorStateList.valueOf(TAP_RIPPLE), null, null)
        }
        return VH(tv)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        // Capture the emoji instead of the position: the list is swapped on every
        // category change, and a stale position would insert the wrong emoji.
        val emoji = getItem(position)
        holder.tv.text = emoji
        holder.tv.setOnClickListener { onClick(emoji) }
    }

    companion object {
        /** 18% purple — visible on white, but not a heavy highlight. */
        private const val TAP_RIPPLE = 0x2E7C3AED

        private val DIFF = object : DiffUtil.ItemCallback<String>() {
            override fun areItemsTheSame(a: String, b: String) = a == b
            override fun areContentsTheSame(a: String, b: String) = a == b
        }
    }
}
