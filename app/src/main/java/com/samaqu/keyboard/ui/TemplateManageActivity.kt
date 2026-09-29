package com.samaqu.keyboard.ui

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.samaqu.keyboard.R
import com.samaqu.keyboard.data.CachedTemplate
import com.samaqu.keyboard.data.CategoryWithTemplates
import com.samaqu.keyboard.data.TemplateRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Dashboard > "Kelola Template": the keyboard's own admin screen.
 *
 * Templates are read into the UI from the Room cache, but every mutation goes through
 * [TemplateRepository] to Supabase first and is followed by a re-sync - there is no local
 * write path, because `sync()` replaces the whole cache and would silently discard an edit
 * that only existed on this device.
 */
class TemplateManageActivity : AppCompatActivity() {

    private lateinit var repo: TemplateRepository
    private lateinit var tabs: LinearLayout
    private lateinit var list: RecyclerView
    private lateinit var empty: TextView
    private lateinit var btnAddCategory: MaterialButton
    private lateinit var btnRenameCategory: MaterialButton
    private lateinit var btnDeleteCategory: MaterialButton
    private lateinit var btnAddTemplate: MaterialButton

    private val adapter = TemplateManageAdapter(
        onEdit = { showTemplateDialog(it) },
        onDelete = { confirmDeleteTemplate(it) }
    )

    private var categories: List<CategoryWithTemplates> = emptyList()
    private var selectedCategoryId: Int = NO_CATEGORY

    /** True while a write is in flight, so a double tap cannot send the request twice. */
    private var busy: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_manage_templates)

        repo = TemplateRepository(this)

        findViewById<Toolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        tabs = findViewById(R.id.categoryTabs)
        list = findViewById(R.id.manageList)
        empty = findViewById(R.id.manageEmpty)
        btnAddCategory = findViewById(R.id.btnAddCategory)
        btnRenameCategory = findViewById(R.id.btnRenameCategory)
        btnDeleteCategory = findViewById(R.id.btnDeleteCategory)
        btnAddTemplate = findViewById(R.id.btnAddTemplate)

        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        btnAddCategory.setOnClickListener { showCategoryDialog(category = null) }
        btnRenameCategory.setOnClickListener { currentCategory()?.let { showCategoryDialog(it.category) } }
        btnDeleteCategory.setOnClickListener { currentCategory()?.let { confirmDeleteCategory(it) } }
        btnAddTemplate.setOnClickListener { showTemplateDialog(template = null) }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    // ------------------------------------------------------------------ loading

    private fun load() {
        lifecycleScope.launch {
            val cats = withContext(Dispatchers.IO) { repo.getTemplates() }
            categories = cats
            if (cats.none { it.category.id == selectedCategoryId }) {
                selectedCategoryId = cats.firstOrNull()?.category?.id ?: NO_CATEGORY
            }
            render()
        }
    }

    private fun currentCategory(): CategoryWithTemplates? =
        categories.firstOrNull { it.category.id == selectedCategoryId }

    private fun render() {
        renderTabs()
        renderList()
        val hasSelection = currentCategory() != null
        btnRenameCategory.isEnabled = hasSelection && !busy
        btnDeleteCategory.isEnabled = hasSelection && !busy
        btnAddTemplate.isEnabled = hasSelection && !busy
        btnAddCategory.isEnabled = !busy
    }

    private fun renderTabs() {
        tabs.removeAllViews()
        categories.forEach { cat ->
            val selected = cat.category.id == selectedCategoryId
            val chip = TextView(this).apply {
                text = getString(R.string.manage_template_count, cat.templates.size)
                    .let { "${cat.category.name} • $it" }
                textSize = 12f
                setPadding(24.dp(), 8.dp(), 24.dp(), 8.dp())
                setTextColor(if (selected) Color.WHITE else 0xFF334155.toInt())
                setBackgroundResource(
                    if (selected) R.drawable.chip_bg_selected else R.drawable.chip_bg
                )
                setOnClickListener {
                    selectedCategoryId = cat.category.id
                    render()
                }
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = 8.dp() }
            chip.layoutParams = params
            tabs.addView(chip)
        }
    }

    private fun renderList() {
        val selected = currentCategory()
        adapter.submit(selected?.templates.orEmpty())
        when {
            categories.isEmpty() -> {
                empty.text = getString(R.string.manage_empty_categories)
                empty.visibility = View.VISIBLE
                list.visibility = View.GONE
            }
            selected == null || selected.templates.isEmpty() -> {
                empty.text = getString(R.string.manage_empty_templates)
                empty.visibility = View.VISIBLE
                list.visibility = View.GONE
            }
            else -> {
                empty.visibility = View.GONE
                list.visibility = View.VISIBLE
            }
        }
    }

    // ---------------------------------------------------------------- dialogs

    private fun showCategoryDialog(category: com.samaqu.keyboard.data.CachedCategory?) {
        val input = singleLineInput(category?.name)
        MaterialAlertDialogBuilder(this)
            .setTitle(
                if (category == null) R.string.dialog_add_category
                else R.string.dialog_rename_category
            )
            .setView(dialogBody(input))
            .setPositiveButton(R.string.btn_save) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isBlank()) {
                    toast(R.string.toast_name_required)
                } else if (category == null) {
                    startWrite(R.string.toast_saved) {
                        repo.addCategory(name, categories.size + 1)
                    }
                } else {
                    startWrite(R.string.toast_saved) { repo.renameCategory(category.id, name) }
                }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun showTemplateDialog(template: CachedTemplate?) {
        val input = multiLineInput(template?.content)
        MaterialAlertDialogBuilder(this)
            .setTitle(
                if (template == null) R.string.dialog_add_template
                else R.string.dialog_edit_template
            )
            .setView(dialogBody(input))
            .setPositiveButton(R.string.btn_save) { _, _ ->
                val content = input.text.toString().trim()
                if (content.isBlank()) {
                    toast(R.string.toast_name_required)
                } else {
                    val categoryId = selectedCategoryId
                    if (categoryId == NO_CATEGORY) return@setPositiveButton
                    val nextOrder = currentCategory()?.templates?.size?.plus(1) ?: 1
                    if (template == null) {
                        startWrite(R.string.toast_saved) {
                            repo.addTemplate(categoryId, content, nextOrder)
                        }
                    } else {
                        startWrite(R.string.toast_saved) { repo.updateTemplate(template.id, content) }
                    }
                }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun confirmDeleteTemplate(template: CachedTemplate) {
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.confirm_delete_template)
            .setNegativeButton(R.string.btn_cancel, null)
            .setPositiveButton(R.string.btn_delete) { _, _ ->
                startWrite(R.string.toast_deleted) { repo.deleteTemplate(template.id) }
            }
            .show()
    }

    private fun confirmDeleteCategory(category: CategoryWithTemplates) {
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.confirm_delete_category)
            .setNegativeButton(R.string.btn_cancel, null)
            .setPositiveButton(R.string.btn_delete) { _, _ ->
                selectedCategoryId = NO_CATEGORY
                startWrite(R.string.toast_deleted) { repo.deleteCategory(category.category.id) }
            }
            .show()
    }

    private fun singleLineInput(text: String?): EditText =
        EditText(this).apply {
            hint = getString(R.string.hint_category_name)
            setText(text.orEmpty())
            setSelection(this.text.length)
        }

    private fun multiLineInput(text: String?): EditText =
        EditText(this).apply {
            hint = getString(R.string.hint_template_text)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 3
            gravity = android.view.Gravity.TOP
            setText(text.orEmpty())
            setSelection(this.text.length)
        }

    /** Gives the dialog a breathing room around its plain EditText. */
    private fun dialogBody(child: EditText): FrameLayout =
        FrameLayout(this).apply {
            setPadding(48.dp(), 24.dp(), 48.dp(), 0)
            addView(
                child,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

    // ------------------------------------------------------------------ writes

    private fun startWrite(successMessage: Int, block: suspend () -> Result<Unit>) {
        if (busy) return
        busy = true
        setActionsEnabled(false)

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { block() }
            busy = false
            setActionsEnabled(true)
            result.fold(
                onSuccess = { toast(successMessage) },
                onFailure = {
                    toastMessage(getString(R.string.toast_write_failed, it.message ?: "error"))
                }
            )
            // Refresh either way: on failure this restores the untouched server state.
            load()
        }
    }

    private fun setActionsEnabled(enabled: Boolean) {
        btnAddCategory.isEnabled = enabled
        btnAddTemplate.isEnabled = enabled
        btnRenameCategory.isEnabled = enabled && currentCategory() != null
        btnDeleteCategory.isEnabled = enabled && currentCategory() != null
    }

    private fun toast(resId: Int) = Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()

    private fun toastMessage(text: String) =
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    companion object {
        private const val NO_CATEGORY = -1
    }
}

/**
 * Templates of the selected category with Ubah / Hapus actions.
 * Follows the same plain `RecyclerView.Adapter` shape as [OrderAdapter].
 */
private class TemplateManageAdapter(
    private val onEdit: (CachedTemplate) -> Unit,
    private val onDelete: (CachedTemplate) -> Unit
) : RecyclerView.Adapter<TemplateManageAdapter.VH>() {

    private var items: List<CachedTemplate> = emptyList()

    fun submit(list: List<CachedTemplate>) {
        items = list
        notifyDataSetChanged()
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val content: TextView = v.findViewById(R.id.manageTemplateContent)
        val btnEdit: TextView = v.findViewById(R.id.btnEditTemplate)
        val btnDelete: TextView = v.findViewById(R.id.btnDeleteTemplate)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = android.view.LayoutInflater.from(parent.context)
            .inflate(R.layout.item_manage_template, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.content.text = item.content
        holder.btnEdit.setOnClickListener { onEdit(item) }
        holder.btnDelete.setOnClickListener { onDelete(item) }
    }
}
