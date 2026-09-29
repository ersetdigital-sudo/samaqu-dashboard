package com.samaqu.keyboard.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.samaqu.keyboard.R
import com.samaqu.keyboard.data.CategoryWithTemplates
import com.samaqu.keyboard.data.TemplateRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AutoTextFragment : Fragment() {

    private var adapter: TemplateAdapter? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_autotext, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val tabs = view.findViewById<LinearLayout>(R.id.categoryTabs)
        val empty = view.findViewById<TextView>(R.id.emptyText)
        val rv = view.findViewById<RecyclerView>(R.id.templateList)

        val adp = TemplateAdapter { text -> copyToClipboard(text) }
        adapter = adp
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adp

        val repo = TemplateRepository(requireContext())
        viewLifecycleOwner.lifecycleScope.launch {
            val cats = withContext(Dispatchers.IO) { repo.getTemplates() }
            empty.visibility = if (cats.isEmpty()) View.VISIBLE else View.GONE
            renderTabs(tabs, adp, cats)
        }
    }

    private fun renderTabs(tabs: LinearLayout, adp: TemplateAdapter, cats: List<CategoryWithTemplates>) {
        tabs.removeAllViews()
        if (cats.isEmpty()) return
        adp.submitList(cats.first().templates)
        cats.forEach { cat ->
            val tv = TextView(requireContext()).apply {
                text = cat.category.name
                textSize = 12f
                setPadding(36, 8, 36, 8)
                setTextColor(0xFF1E3A8A.toInt())
                typeface = Typeface.SERIF
                setBackgroundResource(R.drawable.tab_bg)
                setOnClickListener { adp.submitList(cat.templates) }
            }
            tabs.addView(tv)
        }
    }

    private fun copyToClipboard(text: String) {
        val cm = requireContext().getSystemService(ClipboardManager::class.java)
        cm?.setPrimaryClip(ClipData.newPlainText("template", text))
        Toast.makeText(requireContext(), getString(R.string.copied), Toast.LENGTH_SHORT).show()
    }
}
