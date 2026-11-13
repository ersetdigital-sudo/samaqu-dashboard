package com.samaqu.keyboard.ime

import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.inputmethodservice.InputMethodService
import android.inputmethodservice.Keyboard
import android.inputmethodservice.KeyboardView
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.samaqu.keyboard.R
import com.samaqu.keyboard.data.CachedProduct
import com.samaqu.keyboard.data.CachedVariant
import com.samaqu.keyboard.data.CategoryWithTemplates
import com.samaqu.keyboard.data.Prefs
import com.samaqu.keyboard.data.ProductRepository
import com.samaqu.keyboard.data.ShippingRepository
import com.samaqu.keyboard.data.TemplateRepository
import com.samaqu.keyboard.network.JntCostOption
import com.samaqu.keyboard.network.OrderItem
import com.samaqu.keyboard.network.RetrofitClient
import com.samaqu.keyboard.ui.EmojiAdapter
import com.samaqu.keyboard.ui.EmojiCatalog
import com.samaqu.keyboard.ui.MainActivity
import com.samaqu.keyboard.ui.OrderAdapter
import com.samaqu.keyboard.ui.ProductAdapter
import com.samaqu.keyboard.ui.TemplateAdapter
import com.samaqu.keyboard.ui.VariantAdapter
import com.samaqu.keyboard.util.Backspace
import com.samaqu.keyboard.util.CalculatorEngine
import com.samaqu.keyboard.util.CustomerInfoParser
import com.samaqu.keyboard.util.FieldInput
import com.samaqu.keyboard.util.FieldOrder
import com.samaqu.keyboard.util.InvoiceDraft
import com.samaqu.keyboard.util.InvoiceFormatter
import com.samaqu.keyboard.util.InvoiceItem
import com.samaqu.keyboard.util.InvoiceShipping
import com.samaqu.keyboard.util.MoneyInput
import com.samaqu.keyboard.util.SamaQuText
import com.samaqu.keyboard.util.ShiftState
import com.samaqu.keyboard.util.toInvoiceShipping
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * SAMAQU keyboard.
 *
 * Layout: a toolbar (Invoice / Ongkir / Auto Text / Pending / Dashboard / Sync)
 * above a QWERTY keyboard. Each toolbar action opens a panel that can insert
 * text into the currently focused field of the target app.
 */
class SamaQuIME : InputMethodService(), KeyboardView.OnKeyboardActionListener {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var repo: TemplateRepository
    private lateinit var products: ProductRepository
    private val shipping = ShippingRepository()

    private var keyboardView: KeyboardView? = null
    private var qwerty: Keyboard? = null
    private var symbols: Keyboard? = null

    /** Digits-only keypad shown for number and phone fields. */
    private var numpad: Keyboard? = null
    /**
     * Shift state. Shared by the letter deck and by the IME's own in-panel fields, so both
     * type the character the key on screen shows.
     */
    private val shift = ShiftState()

    /** Key size the current input view was built with, so Settings changes can be applied. */
    private var appliedKeySize: String? = null

    private var rootView: View? = null
    private var templatePanel: View? = null
    private var invoicePanel: View? = null
    private var productPanel: View? = null
    private var ongkirPanel: View? = null
    private var pendingPanel: View? = null
    private var emojiPanel: View? = null
    private var calculatorPanel: View? = null

    private var categoryTabs: LinearLayout? = null
    private var emojiCategories: LinearLayout? = null
    private var templateAdapter: TemplateAdapter? = null
    private var emojiAdapter: EmojiAdapter? = null
    private var orderAdapter: OrderAdapter? = null
    private var productAdapter: ProductAdapter? = null
    private var variantAdapter: VariantAdapter? = null
    private var toolbarItems: List<ToolbarItem> = emptyList()

    /** Whole product catalogue, before the picker's search box and category chip filter it. */
    private var allProducts: List<CachedProduct> = emptyList()

    /** Category chip selected in the picker; null means "Semua". */
    private var productCategory: String? = null

    /** Whole size/colour/stock grid, cached like the catalogue itself. */
    private var allVariants: List<CachedVariant> = emptyList()

    /**
     * Product whose size grid is on screen, or null while the picker shows the catalogue.
     * The picker is therefore a two-step screen driven by this single field.
     */
    private var variantProduct: CachedProduct? = null

    /**
     * Quick Calculator state, kept between openings so a number the CS is half-way through
     * survives an accidental close.
     */
    private var calculatorExpression = ""

    /** Text field of the in-keyboard invoice form that currently receives typed keys. */
    private var focusedField: EditText? = null

    /**
     * The invoice being built: its product lines. The text fields (buyer, phone, address,
     * shipping cost) are read from the views when needed, see [currentInvoice].
     */
    private var invoiceDraft = InvoiceDraft()

    /** Courier option picked in the Ongkir panel; its cost can still be changed by hand. */
    private var shippingChoice: InvoiceShipping? = null

    /** Index of the product line that is open for editing; every other line is a saved, read-only row. */
    private var editingItem: Int? = null

    /** Every invoice field that can take focus, so focusing one can un-highlight the rest. */
    private val invoiceFields = mutableListOf<EditText>()

    /** Marks the per-line price fields, which are rebuilt whenever the list is redrawn. */
    private val itemPriceTag = "invoiceItemPrice"

    /**
     * Selection range the framework last reported for the current field (-1/-1 = nothing
     * reported yet). `getSelectedText()` returns null both for "no selection" and for "read
     * failed", so this is the only way to tell those apart when Backspace runs (rule C in
     * [Backspace]).
     */
    private var trackedSelStart = -1
    private var trackedSelEnd = -1

    private var allCategories: List<CategoryWithTemplates> = emptyList()

    /**
     * Category chip the Auto Text panel is showing.
     *
     * Kept so a background refresh re-selects what is on screen instead of snapping back to
     * the first chip: the list a CS is reading must not move under them because a sync
     * finished.
     */
    private var selectedCategoryIndex = 0

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate() {
        super.onCreate()
        repo = TemplateRepository(this)
        products = ProductRepository(this)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // A new field starts with no reported selection; the framework sends a fresh
        // onUpdateSelection as soon as it knows the real one.
        trackedSelStart = -1
        trackedSelEnd = -1
        // Rebuild when the key size was changed in Settings, so the new size shows
        // up without the user having to switch to another keyboard and back.
        val configured = Prefs(this).keySize
        if (appliedKeySize != null && appliedKeySize != configured) {
            setInputView(onCreateInputView())
        }
        // Shift belongs to the field, not to the IME: carrying ONCE or LOCKED across an app or
        // field switch is how a keyboard ends up typing capitals while nothing on screen looks
        // active. This keyboard has no auto-capitalisation, so OFF is the honest start state.
        resetShift()
    }

    // ------------------------------------------------------------- input view

    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.keyboard_main, null)
        rootView = view

        // A rebuilt view (rotation, key-size change) starts from a clean invoice form: nothing
        // tied to the old views may leak into it, such as a focused price field that no longer
        // exists or a courier picked in a form that has just been emptied.
        focusedField = null
        numberDeckForced = false
        invoiceFields.clear()
        shippingChoice = null
        editingItem = null
        invoiceDraft = InvoiceDraft()

        val keySize = Prefs(this).keySize
        appliedKeySize = keySize
        qwerty = Keyboard(
            this,
            when (keySize) {
                Prefs.KEY_SIZE_SMALL -> R.xml.qwerty_small
                Prefs.KEY_SIZE_LARGE -> R.xml.qwerty_large
                else -> R.xml.qwerty
            }
        )
        symbols = Keyboard(
            this,
            when (keySize) {
                Prefs.KEY_SIZE_SMALL -> R.xml.symbols_small
                Prefs.KEY_SIZE_LARGE -> R.xml.symbols_large
                else -> R.xml.symbols
            }
        )
        numpad = Keyboard(
            this,
            when (keySize) {
                Prefs.KEY_SIZE_SMALL -> R.xml.numpad_small
                Prefs.KEY_SIZE_LARGE -> R.xml.numpad_large
                else -> R.xml.numpad
            }
        )

        keyboardView = (view.findViewById<View>(R.id.keyboardView) as KeyboardView).apply {
            keyboard = qwerty
            setOnKeyboardActionListener(this@SamaQuIME)
            // Tactile tick + the pressed key highlight (see SamaQuKeyboardView)
            // are enough feedback; the letter bubble above the key is off.
            isHapticFeedbackEnabled = true
            isPreviewEnabled = false
        }

        // The decks were just rebuilt from the XML, so they carry the lowercase labels again.
        // Writing the shift state back on keeps a view recreated mid-caps-lock consistent with
        // what it would type.
        applyShiftToKeyboard()

        templatePanel = view.findViewById(R.id.templatePanel)
        invoicePanel = view.findViewById(R.id.invoicePanel)
        productPanel = view.findViewById(R.id.productPanel)
        ongkirPanel = view.findViewById(R.id.ongkirPanel)
        pendingPanel = view.findViewById(R.id.pendingPanel)
        emojiPanel = view.findViewById(R.id.emojiPanel)
        calculatorPanel = view.findViewById(R.id.calculatorPanel)
        categoryTabs = view.findViewById(R.id.categoryTabs)
        emojiCategories = view.findViewById(R.id.emojiCategories)

        // Emoji grid. The column count follows the screen width so the same
        // layout works on a 320dp phone and on a 480dp one.
        val emoji = EmojiAdapter { emoji -> commit(emoji) }
        emojiAdapter = emoji
        view.findViewById<RecyclerView>(R.id.emojiList).apply {
            layoutManager = GridLayoutManager(context, emojiColumns())
            adapter = emoji
        }
        renderEmojiCategories()

        // Pending orders list
        orderAdapter = OrderAdapter { order -> sendOrderGreeting(order) }
        view.findViewById<RecyclerView>(R.id.pendingList).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = orderAdapter
        }

        // Template list
        templateAdapter = TemplateAdapter { text ->
            commit(text)
            hideAllPanels()
        }
        view.findViewById<RecyclerView>(R.id.templateList).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = templateAdapter
        }

        // Holding Enter opens the Quick Calculator instead of inserting a newline.
        (keyboardView as? SamaQuKeyboardView)?.onEnterLongPress = { openCalculator() }

        // Product catalogue for the invoice (read-only, cached in Room). Step two of the
        // picker reuses the same RecyclerView, only its adapter is swapped.
        val productPicker = ProductAdapter { product -> pickProduct(product) }
        productAdapter = productPicker
        variantAdapter = VariantAdapter { variant ->
            variantProduct?.let { product -> applyProductToInvoice(product, variant) }
        }
        view.findViewById<RecyclerView>(R.id.productList).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = productPicker
        }

        // Close buttons
        view.findViewById<TextView>(R.id.btnClosePanel).setOnClickListener { hideAllPanels() }
        view.findViewById<TextView>(R.id.btnCloseInvoice).setOnClickListener { hideAllPanels() }
        view.findViewById<TextView>(R.id.btnCloseProduct).setOnClickListener { hideAllPanels() }
        view.findViewById<TextView>(R.id.btnClosePending).setOnClickListener { hideAllPanels() }
        view.findViewById<TextView>(R.id.btnCloseEmoji).setOnClickListener { hideAllPanels() }
        // The letter deck is hidden while the emoji panel is open, so emoji mode
        // needs its own, always visible way back to the letters.
        view.findViewById<TextView>(R.id.btnEmojiBack).setOnClickListener { hideAllPanels() }
        view.findViewById<TextView>(R.id.btnCloseOngkir).setOnClickListener { hideAllPanels() }

        // Toolbar actions
        view.findViewById<LinearLayout>(R.id.btnAutoText).setOnClickListener {
            showPanel(templatePanel)
            // The dashboard writes straight to Supabase, so what the cache holds is stale the
            // moment a template is added there. Pulling on open is what makes a new template
            // appear without the user having to hunt for the Sync button.
            refreshTemplates()
        }
        view.findViewById<LinearLayout>(R.id.btnSync).setOnClickListener { doSync() }
        view.findViewById<LinearLayout>(R.id.btnInvoice).setOnClickListener {
            showPanel(invoicePanel)
        }
        view.findViewById<LinearLayout>(R.id.btnOngkir).setOnClickListener {
            showPanel(ongkirPanel)
        }
        view.findViewById<LinearLayout>(R.id.btnPending).setOnClickListener {
            showPanel(pendingPanel)
            loadPendingOrders()
        }
        view.findViewById<LinearLayout>(R.id.btnDashboard).setOnClickListener {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_DASHBOARD)
                }
            )
        }

        limitPanelHeights()
        // First run happens before layout, when the deck still measures 0 px; re-apply
        // with the real deck height so stacked panels cannot cover the app below.
        view.post { limitPanelHeights() }

        setupToolbar(view)
        setupInvoicePanel(view)
        setupOngkirPanel(view)
        setupProductPanel(view)
        setupCalculatorPanel(view)
        loadCachedTemplates()
        return view
    }

    private fun setupInvoicePanel(view: View) {
        listOf(R.id.invBuyer, R.id.invPhone, R.id.invAddress, R.id.invOngkir).forEach { id ->
            bindInvoiceField(view.findViewById(id))
        }
        bindMoneyFormat(view.findViewById(R.id.invOngkir))
        view.findViewById<EditText>(R.id.invOngkir).doAfterTextChanged {
            // Emptying the field drops a courier picked earlier, so a new amount typed after
            // that cannot be printed under the old service name.
            if (it.isNullOrBlank()) {
                shippingChoice = null
                view.findViewById<TextView>(R.id.invShippingLabel).visibility = View.GONE
            }
            updateInvoiceTotal()
        }

        view.findViewById<TextView>(R.id.btnInvoiceOngkir).setOnClickListener { showPanel(ongkirPanel) }
        view.findViewById<TextView>(R.id.btnPasteFormat).setOnClickListener { pasteCustomerFormat(view) }

        view.findViewById<TextView>(R.id.invBtnGenerate).setOnClickListener {
            val draft = currentInvoice(view)
            if (draft.items.isEmpty()) {
                Toast.makeText(this, R.string.invoice_need_item, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val prefs = Prefs(this)
            val text = InvoiceFormatter.format(draft, prefs.paymentText, prefs.invoiceClosing)
            // Only clear the form once the text really reached the chat: with no input
            // connection nothing is sent, and the whole invoice would be lost.
            if (currentInputConnection?.commitText(text, 1) != true) {
                Toast.makeText(this, R.string.invoice_send_failed, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            resetInvoice(view)
            hideAllPanels()
        }

        renderInvoiceItems()
    }

    /**
     * "Tempel format": reads the copied customer message and fills name, WA and address with
     * whatever [CustomerInfoParser] finds. Fields it finds nothing for are left as they are.
     */
    private fun pasteCustomerFormat(view: View) {
        val clip = getSystemService(ClipboardManager::class.java)?.primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        if (text.isBlank()) {
            Toast.makeText(this, R.string.invoice_paste_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val info = CustomerInfoParser.parse(text)
        if (info.isEmpty) {
            Toast.makeText(this, R.string.invoice_paste_unknown, Toast.LENGTH_LONG).show()
            return
        }
        val filled = mutableListOf<String>()
        fun put(id: Int, value: String, what: String) {
            if (value.isBlank()) return
            view.findViewById<EditText>(id).setText(value)
            filled += what
        }
        put(R.id.invBuyer, info.name, "nama")
        put(R.id.invPhone, info.phone, "WA")
        put(R.id.invAddress, info.address, "alamat")
        Toast.makeText(this, getString(R.string.invoice_paste_done, filled.joinToString(", ")), Toast.LENGTH_SHORT).show()
    }

    /** Makes [field] receive the keyboard's keys when touched, and highlights it alone. */
    private fun bindInvoiceField(field: EditText) {
        invoiceFields.add(field)
        field.setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_DOWN) focusInvoiceField(field)
            false
        }
    }

    /** Sends the keys to [field], highlights it alone and keeps it on screen. */
    private fun focusInvoiceField(field: EditText) {
        focusedField = field
        onFieldFocused(field)
        invoiceFields.forEach { it.setBackgroundResource(R.drawable.field_bg) }
        field.setBackgroundResource(R.drawable.field_bg_active)
        // The panel and the deck leave little room: scroll just enough to show the field typed into.
        field.post { field.requestRectangleOnScreen(android.graphics.Rect(0, 0, field.width, field.height), false) }
    }

    /**
     * Enter inside the invoice form moves on to the next field (buyer, WA, address, shipping).
     * Anywhere else, and after the last field, it simply ends typing as it always did.
     */
    private fun onEnterInField() {
        val view = rootView
        val current = focusedField
        val nextId = if (view != null && current != null) FieldOrder.next(current.id, INVOICE_FIELD_ORDER) else null
        val next = nextId?.let { view?.findViewById<EditText>(it) }
        if (next != null) {
            focusInvoiceField(next)
        } else {
            focusedField = null
            restoreLetterDeck()
        }
    }

    /**
     * The invoice as it stands: product lines from [invoiceDraft], everything else read from
     * the fields. A blank shipping field means "no shipping line"; any number, even 0, writes one.
     */
    private fun currentInvoice(view: View): InvoiceDraft {
        fun text(id: Int) = view.findViewById<EditText>(id).text.toString().trim()
        return invoiceDraft.copy(
            buyer = text(R.id.invBuyer),
            phone = text(R.id.invPhone),
            address = text(R.id.invAddress),
            shipping = InvoiceFormatter.shippingFor(text(R.id.invOngkir), shippingChoice)
        )
    }

    private fun updateInvoiceTotal() {
        val view = rootView ?: return
        view.findViewById<TextView>(R.id.invTotal).text =
            getString(R.string.invoice_total, InvoiceFormatter.rupiah(currentInvoice(view).total))
    }

    /** Back to an empty form once an invoice has been sent. */
    private fun resetInvoice(view: View) {
        invoiceDraft = InvoiceDraft()
        shippingChoice = null
        editingItem = null
        listOf(R.id.invBuyer, R.id.invPhone, R.id.invAddress, R.id.invOngkir).forEach {
            view.findViewById<EditText>(it).setText("")
        }
        view.findViewById<TextView>(R.id.invShippingLabel).visibility = View.GONE
        releaseFocusedField()
        renderInvoiceItems()
    }

    /** Redraws one row per product line (label, quantity buttons, unit price, remove). */
    private fun renderInvoiceItems() {
        val view = rootView ?: return
        if (focusedField?.tag == itemPriceTag) releaseFocusedField()
        invoiceFields.removeAll { it.tag == itemPriceTag }

        // Rebuilding drops the view that held Android focus, and the ScrollView would then jump
        // to the first field; remember where the CS was and go back there afterwards.
        val scroll = view.findViewById<ScrollView>(R.id.invScroll)
        val scrollY = scroll.scrollY
        scroll.post { scroll.scrollTo(0, scrollY) }

        val container = view.findViewById<LinearLayout>(R.id.invItemsContainer)
        container.removeAllViews()
        view.findViewById<View>(R.id.invItemsEmpty).visibility =
            if (invoiceDraft.items.isEmpty()) View.VISIBLE else View.GONE

        invoiceDraft.items.forEachIndexed { index, item ->
            container.addView(
                buildInvoiceItemRow(index, item),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { bottomMargin = dp(8) }
            )
        }
        updateInvoiceTotal()
    }

    /**
     * One product line. A freshly added line (or one the CS taps "Ubah" on) is open for editing:
     * quantity buttons, unit price and "Simpan". Once saved it is a compact read-only row, so
     * the form is not left open under the CS's fingers.
     */
    private fun buildInvoiceItemRow(index: Int, item: InvoiceItem): View =
        if (index == editingItem) buildEditingItemRow(index, item) else buildSavedItemRow(index, item)

    private fun itemSmallButton(label: String, onTap: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 16f
        setTextColor(ContextCompat.getColor(this@SamaQuIME, R.color.brand_purple))
        setTypeface(null, Typeface.BOLD)
        gravity = Gravity.CENTER
        setBackgroundResource(R.drawable.pill_bg)
        isClickable = true
        isFocusable = true
        setOnClickListener { onTap() }
        layoutParams = LinearLayout.LayoutParams(dp(36), dp(32)).apply { marginStart = dp(6) }
    }

    private fun itemTitle(item: InvoiceItem, vararg buttons: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(
            TextView(this@SamaQuIME).apply {
                text = item.label
                textSize = 12f
                setTextColor(ContextCompat.getColor(this@SamaQuIME, R.color.text_primary))
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply { weight = 1f }
        )
        buttons.forEach { addView(it) }
    }

    private fun removeItemButton(index: Int) = itemSmallButton("✕") {
        invoiceDraft = invoiceDraft.withoutItem(index)
        editingItem = null
        renderInvoiceItems()
    }

    private fun itemCard(vararg children: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.card_bg)
        setPadding(dp(12), dp(8), dp(8), dp(8))
        children.forEach { addView(it) }
    }

    /** Read-only line: what, how many at what price, the line total, and "Ubah". */
    private fun buildSavedItemRow(index: Int, item: InvoiceItem): View {
        val primary = ContextCompat.getColor(this, R.color.text_primary)
        val muted = ContextCompat.getColor(this, R.color.text_muted)

        val edit = TextView(this).apply {
            text = getString(R.string.btn_edit_item)
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@SamaQuIME, R.color.brand_purple))
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.pill_bg)
            isClickable = true
            isFocusable = true
            setPadding(dp(14), 0, dp(14), 0)
            setOnClickListener {
                editingItem = index
                renderInvoiceItems()
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)).apply { marginStart = dp(6) }
        }

        val amounts = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, 0)
            addView(
                TextView(this@SamaQuIME).apply {
                    text = "${item.qty} x ${InvoiceFormatter.rupiah(item.unitPrice)}"
                    textSize = 12f
                    setTextColor(muted)
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply { weight = 1f }
            )
            addView(TextView(this@SamaQuIME).apply {
                text = InvoiceFormatter.rupiah(item.lineTotal)
                textSize = 13f
                setTextColor(primary)
                setTypeface(null, Typeface.BOLD)
            })
        }
        return itemCard(itemTitle(item, edit, removeItemButton(index)), amounts)
    }

    /** Open line: quantity buttons, unit price (shown with thousands dots) and "Simpan". */
    private fun buildEditingItemRow(index: Int, item: InvoiceItem): View {
        val primary = ContextCompat.getColor(this, R.color.text_primary)

        val priceField = EditText(this).apply {
            tag = itemPriceTag
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(MoneyInput.format(item.unitPrice.toString()))
            textSize = 13f
            setTextColor(primary)
            setBackgroundResource(R.drawable.field_bg)
            setPadding(dp(10), 0, dp(10), 0)
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            contentDescription = getString(R.string.invoice_item_price)
            // Attached after setText, so the initial value does not count as an edit.
            bindMoneyFormat(this)
            doAfterTextChanged {
                invoiceDraft = invoiceDraft.withUnitPrice(index, MoneyInput.parse(it?.toString().orEmpty()) ?: 0L)
                updateInvoiceTotal()
            }
        }
        bindInvoiceField(priceField)

        val save = TextView(this).apply {
            text = getString(R.string.btn_save_item)
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@SamaQuIME, R.color.surface))
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.btn_blue_bg)
            isClickable = true
            isFocusable = true
            setPadding(dp(14), 0, dp(14), 0)
            setOnClickListener {
                editingItem = null
                releaseFocusedField()
                renderInvoiceItems()
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply { marginStart = dp(6) }
        }

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
            addView(itemSmallButton("−") {
                invoiceDraft = invoiceDraft.withQty(index, item.qty - 1)
                renderInvoiceItems()
            }.apply { (layoutParams as LinearLayout.LayoutParams).marginStart = 0 })
            addView(
                TextView(this@SamaQuIME).apply {
                    text = item.qty.toString()
                    textSize = 13f
                    setTextColor(primary)
                    setTypeface(null, Typeface.BOLD)
                    gravity = Gravity.CENTER
                },
                LinearLayout.LayoutParams(dp(30), ViewGroup.LayoutParams.WRAP_CONTENT)
            )
            addView(itemSmallButton("+") {
                invoiceDraft = invoiceDraft.withQty(index, item.qty + 1)
                renderInvoiceItems()
            })
            addView(View(this@SamaQuIME), LinearLayout.LayoutParams(0, 1).apply { weight = 1f })
            addView(priceField, LinearLayout.LayoutParams(dp(104), dp(36)))
            addView(save)
        }
        return itemCard(itemTitle(item, removeItemButton(index)), controls)
    }

    /**
     * Shows what is typed into [field] as rupiah with thousands dots (`40500` -> `40.500`),
     * keeping the cursor at the end. The dots are only for the eyes: [MoneyInput.parse] and
     * [InvoiceFormatter.shippingFor] read the digits back.
     */
    private fun bindMoneyFormat(field: EditText) {
        var busy = false
        field.doAfterTextChanged { s ->
            if (busy || s == null) return@doAfterTextChanged
            val formatted = MoneyInput.format(s.toString())
            if (formatted == s.toString()) return@doAfterTextChanged
            busy = true
            s.replace(0, s.length, formatted)
            busy = false
            field.setSelection(field.text.length)
        }
    }

    // ----------------------------------------------------------- ongkir panel

    /**
     * Wires the ongkir inputs into the same "keyboard types into its own field"
     * mechanism the invoice panel uses.
     */
    private fun setupOngkirPanel(view: View) {
        val fields = listOf(
            view.findViewById<EditText>(R.id.ongkirDistrict),
            view.findViewById<EditText>(R.id.ongkirCity),
            view.findViewById<EditText>(R.id.ongkirWeight)
        )
        fields.forEach { field ->
            field.setOnTouchListener { _, event ->
                if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                    focusedField = field
                    onFieldFocused(field)
                    fields.forEach { it.setBackgroundResource(R.drawable.field_bg) }
                    field.setBackgroundResource(R.drawable.field_bg_active)
                }
                false
            }
        }

        view.findViewById<TextView>(R.id.btnCheckOngkir).setOnClickListener { checkOngkir() }
    }

    /**
     * Asks the store website for the J&T tariff of the typed destination.
     *
     * The website does the area mapping and holds the J&T credentials, so nothing secret
     * is needed here - which is the whole reason the panel does not simply open
     * cekongkir.com in a WebView any more.
     */
    private fun checkOngkir() {
        val view = rootView ?: return
        val district = view.findViewById<EditText>(R.id.ongkirDistrict).text.toString().trim()
        val city = view.findViewById<EditText>(R.id.ongkirCity).text.toString().trim()
        // The store's endpoint rejects a request with any empty field (400 "city, district,
        // weight wajib"), and a zero weight too, so both are checked before sending.
        val weight = view.findViewById<EditText>(R.id.ongkirWeight).text.toString()
            .toIntOrNull()?.takeIf { it > 0 } ?: DEFAULT_WEIGHT_GRAM
        val status = view.findViewById<TextView>(R.id.ongkirStatus)
        val results = view.findViewById<LinearLayout>(R.id.ongkirResults)

        results.removeAllViews()
        if (district.isBlank() || city.isBlank()) {
            status.text = getString(R.string.ongkir_need_area)
            status.visibility = View.VISIBLE
            return
        }

        // Release the key stream while the request runs, so stray taps do not edit a field.
        focusedField = null
        restoreLetterDeck()
        status.text = getString(R.string.ongkir_loading)
        status.visibility = View.VISIBLE

        scope.launch {
            val result = withContext(Dispatchers.IO) {
                shipping.cost(city, district, weight)
            }
            result.fold(
                onSuccess = { options ->
                    if (options.isEmpty()) {
                        status.text = getString(R.string.ongkir_empty)
                        status.visibility = View.VISIBLE
                    } else {
                        status.visibility = View.GONE
                        options.forEach { addOngkirRow(results, it) }
                    }
                },
                onFailure = { error ->
                    status.text = getString(R.string.ongkir_failed, error.message ?: "")
                    status.visibility = View.VISIBLE
                }
            )
        }
    }

    /** One quoted service: name, price and a "Pakai" action that fills the invoice. */
    private fun addOngkirRow(container: LinearLayout, option: JntCostOption) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.card_bg)
            setPadding(dp(12), dp(8), dp(6), dp(8))
        }

        val etd = option.etd.trim()
        val label = TextView(this).apply {
            text = buildString {
                append(option.courier.trim())
                if (option.service.isNotBlank()) append(' ').append(option.service.trim())
                if (etd.isNotEmpty()) append(" • ").append(etd)
            }
            setTextColor(ContextCompat.getColor(this@SamaQuIME, R.color.text_primary))
            textSize = 12f
        }
        row.addView(
            label,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply { weight = 1f }
        )

        val price = TextView(this).apply {
            text = "Rp " + SamaQuText.rupiah(option.cost)
            setTextColor(ContextCompat.getColor(this@SamaQuIME, R.color.brand_purple))
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
        }
        row.addView(price)

        val use = TextView(this).apply {
            text = getString(R.string.ongkir_use)
            setTextColor(ContextCompat.getColor(this@SamaQuIME, R.color.brand_accent))
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(14), dp(8), dp(12), dp(8))
            isClickable = true
            isFocusable = true
            setOnClickListener { useOngkir(option) }
        }
        row.addView(use)

        container.addView(
            row,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(8) }
        )
    }

    /** Puts a quoted cost, with its courier service and estimate, into the invoice and switches to it. */
    private fun useOngkir(option: JntCostOption) {
        val view = rootView ?: return
        shippingChoice = option.toInvoiceShipping()
        view.findViewById<EditText>(R.id.invOngkir).setText(option.cost.toString())
        view.findViewById<TextView>(R.id.invShippingLabel).apply {
            text = buildString {
                append(option.courier.trim())
                if (option.service.isNotBlank()) append(' ').append(option.service.trim())
                if (option.etd.isNotBlank()) append(" • ").append(option.etd.trim())
            }
            visibility = View.VISIBLE
        }
        showPanel(invoicePanel)
    }

    // ------------------------------------------------------ quick calculator

    /**
     * Wires the Quick Calculator.
     *
     * Every key in the panel carries its token in `android:tag`, so the whole keypad is wired
     * in one walk of the view tree instead of seventeen separate `findViewById` calls.
     */
    private fun setupCalculatorPanel(view: View) {
        val keys = mutableListOf<TextView>()
        collectTaggedKeys(view.findViewById(R.id.calculatorPanel), keys)
        keys.forEach { key ->
            val token = key.tag as String
            key.setOnClickListener { onCalculatorKey(token) }
        }

        view.findViewById<TextView>(R.id.btnCalcBack).setOnClickListener { hideAllPanels() }
        view.findViewById<TextView>(R.id.btnCalcInsert).setOnClickListener {
            insertCalculatorResult()
        }
    }

    /** Collects every descendant that carries a String tag (the calculator's key tokens). */
    private fun collectTaggedKeys(root: View?, out: MutableList<TextView>) {
        if (root == null) return
        if (root is TextView && root.tag is String) out.add(root)
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) collectTaggedKeys(root.getChildAt(index), out)
        }
    }

    /**
     * Opens the calculator. Long press on Enter is the only way in, so it takes the letter
     * deck's place rather than adding another toolbar entry.
     */
    private fun openCalculator() {
        renderCalculator()
        showPanel(calculatorPanel)
    }

    private fun onCalculatorKey(token: String) {
        when (token) {
            TOKEN_CLEAR -> calculatorExpression = ""

            TOKEN_BACKSPACE -> if (calculatorExpression.isNotEmpty()) {
                calculatorExpression = calculatorExpression.dropLast(1)
            }

            TOKEN_EQUALS -> {
                // "=" folds the result back into the expression so calculations can be
                // chained (125000 × 3 = then × 2) without retyping the number.
                calculatorValue()?.let { calculatorExpression = CalculatorEngine.format(it) }
            }

            // The rules for turning a tap into the next expression live in the engine, so
            // they are covered by unit tests instead of hiding inside the IME.
            else -> calculatorExpression = CalculatorEngine.append(calculatorExpression, token)
        }
        renderCalculator()
    }

    /** The current value, or null while the expression is incomplete, invalid or too large. */
    private fun calculatorValue(): Double? =
        (CalculatorEngine.evaluate(calculatorExpression) as? CalculatorEngine.Result.Value)?.value

    private fun renderCalculator() {
        val view = rootView ?: return
        val expression = view.findViewById<TextView>(R.id.calcExpression)
        val result = view.findViewById<TextView>(R.id.calcResult)

        expression.text = calculatorExpression.ifEmpty { getString(R.string.calc_zero) }

        when (val outcome = CalculatorEngine.evaluate(calculatorExpression)) {
            is CalculatorEngine.Result.Value -> {
                result.text = CalculatorEngine.format(outcome.value)
                result.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            }
            // "1250×" is a normal state while typing, so there is nothing to report yet.
            CalculatorEngine.Result.Incomplete,
            CalculatorEngine.Result.Invalid -> result.text = ""

            CalculatorEngine.Result.DivideByZero ->
                showCalculatorMessage(getString(R.string.calc_div_zero))

            CalculatorEngine.Result.TooLarge ->
                showCalculatorMessage(getString(R.string.calc_too_large))
        }
    }

    private fun showCalculatorMessage(message: String) {
        val result = rootView?.findViewById<TextView>(R.id.calcResult) ?: return
        result.text = message
        result.setTextColor(ContextCompat.getColor(this, R.color.danger))
    }

    /**
     * Puts the calculated number into the chat field.
     *
     * `commitText` inserts at the cursor, so whatever the CS already typed stays put, and no
     * message is ever sent. With no editable field targeted the panel says so instead of
     * claiming the number went somewhere.
     */
    private fun insertCalculatorResult() {
        val value = calculatorValue() ?: return

        if (currentInputConnection == null || currentInputEditorInfo?.inputType == InputType.TYPE_NULL) {
            showCalculatorMessage(getString(R.string.calc_no_target))
            return
        }

        commit(CalculatorEngine.format(value))
        hideAllPanels()
    }

    /**
     * True when the Enter that just came in belongs to a long press that already opened the
     * calculator, so the tap must not also insert a newline.
     */
    private fun consumeEnterLongPress(): Boolean {
        val view = keyboardView as? SamaQuKeyboardView ?: return false
        val pressedAt = view.enterLongPressAt
        if (pressedAt == 0L) return false

        view.clearEnterLongPress()
        return SystemClock.uptimeMillis() - pressedAt <= ENTER_LONG_PRESS_TAP_WINDOW_MS
    }

    // ---------------------------------------------------------- product picker

    /**
     * Wires the invoice's product picker.
     *
     * The "Pilih" chip in the invoice row opens it, and its search box is typed into with
     * the keyboard itself through the same [focusedField] mechanism the invoice and ongkir
     * forms use - which is why the letter deck stays visible under this panel.
     */
    private fun setupProductPanel(view: View) {
        val search = view.findViewById<EditText>(R.id.productSearch)
        search.setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                focusedField = search
                onFieldFocused(search)
                search.setBackgroundResource(R.drawable.field_bg_active)
                // The keys are typed with these very keys, so the deck comes back under the
                // panel and the panel gives up the room it was using for the list.
                applyDeckAndPickerLayout()
            }
            false
        }
        search.doAfterTextChanged { applyProductFilter() }

        // Touching the results starts a scroll, at which point the deck is simply in the way:
        // focus is released and the list takes the space back. Scrolling is the trigger rather
        // than a raw press, because resizing the layout under a live tap could make the wrong
        // row register as the pick.
        view.findViewById<RecyclerView>(R.id.productList).addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (focusedField != null) releaseFocusedField()
                }
            }
        )

        view.findViewById<TextView>(R.id.btnProductBack).setOnClickListener {
            showProductListStep()
        }

        // The status line is the empty/error state; tapping it retries the load, which is the
        // "coba lagi" the failure message tells the user about.
        view.findViewById<TextView>(R.id.productStatus).setOnClickListener {
            if (variantProduct == null) loadProducts()
        }

        view.findViewById<TextView>(R.id.btnPickProduct).setOnClickListener {
            // Always starts on the catalogue, even if the last visit stopped on a size grid.
            showProductListStep()
            showPanel(productPanel)
            loadProducts()
        }
    }

    /**
     * Renders the cached catalogue immediately, then refreshes it from Supabase in the
     * background.
     *
     * A failed refresh is only reported when there is nothing cached to show: a CS with a
     * flaky connection should not be nagged about a list that is already on screen.
     */
    private fun loadProducts() {
        val status = rootView?.findViewById<TextView>(R.id.productStatus) ?: return
        scope.launch {
            readCatalogueIntoUi()
            if (allProducts.isEmpty()) {
                status.text = getString(R.string.product_loading)
                status.visibility = View.VISIBLE
            }

            withContext(Dispatchers.IO) { products.sync() }.fold(
                onSuccess = { readCatalogueIntoUi() },
                onFailure = { error ->
                    if (allProducts.isEmpty()) {
                        status.text = getString(R.string.product_failed, error.message ?: "")
                        status.visibility = View.VISIBLE
                    }
                }
            )
        }
    }

    /** Reads the cached catalogue and paints every view that depends on it. */
    private suspend fun readCatalogueIntoUi() {
        allProducts = withContext(Dispatchers.IO) { products.getProducts() }
        allVariants = withContext(Dispatchers.IO) { products.getVariants() }
        productAdapter?.submitStockNotes(stockNotes())
        renderProductCategories()
        applyProductFilter()
    }

    /** `stok 47` / `stok habis` note printed under each catalogue row. */
    private fun stockNotes(): Map<String, String> = allProducts.associate { product ->
        val variants = SamaQuText.variantsOf(allVariants, product.id)
        product.id to when {
            variants.isEmpty() -> ""
            SamaQuText.totalStock(variants) > 0 ->
                getString(R.string.product_stock_total, SamaQuText.totalStock(variants))
            else -> getString(R.string.product_stock_empty)
        }
    }

    /** Chips: "Semua" followed by every category found in the catalogue. */
    private fun renderProductCategories() {
        // A background refresh must not paint over the size grid that is on screen.
        if (variantProduct != null) return
        val bar = rootView?.findViewById<LinearLayout>(R.id.productCategories) ?: return
        val categories = SamaQuText.productCategories(allProducts)

        bar.removeAllViews()
        (listOf<String?>(null) + categories).forEach { category ->
            val chip = TextView(this).apply {
                text = category ?: getString(R.string.product_all)
                textSize = 12f
                gravity = Gravity.CENTER
                setOnClickListener { selectProductCategory(category) }
            }
            chipPadding(chip)
            bar.addView(chip, chipParams())
        }

        val index = productCategory?.let { categories.indexOf(it) + 1 } ?: 0
        styleChips(bar, index.coerceAtLeast(0))
    }

    private fun selectProductCategory(category: String?) {
        // Leaving the search field is part of picking a filter: the list gets the deck's
        // space back as soon as the search stops being the thing on screen.
        releaseFocusedField()
        productCategory = category
        renderProductCategories()
        applyProductFilter()
    }

    /** Applies the search box and the selected category chip to the cached catalogue. */
    private fun applyProductFilter() {
        if (variantProduct != null) return
        val view = rootView ?: return
        val adapter = productAdapter ?: return
        val query = view.findViewById<EditText>(R.id.productSearch)?.text?.toString().orEmpty()

        val matches = SamaQuText.filterProducts(allProducts, query, productCategory)
        adapter.submitList(matches)

        val status = view.findViewById<TextView>(R.id.productStatus)
        when {
            allProducts.isEmpty() -> {
                status.text = getString(R.string.product_empty)
                status.visibility = View.VISIBLE
            }
            matches.isEmpty() -> {
                status.text = getString(R.string.product_no_match)
                status.visibility = View.VISIBLE
            }
            else -> status.visibility = View.GONE
        }
    }

    /**
     * A tapped catalogue row.
     *
     * A product with a size grid opens its sizes first - the store holds five sizes per
     * product and more than two thirds of them are sold out, so picking the size is the CS's
     * decision, not something to guess. Products without a grid go straight to the invoice.
     */
    private fun pickProduct(product: CachedProduct) {
        val variants = SamaQuText.variantsOf(allVariants, product.id)
        if (variants.isEmpty()) {
            applyProductToInvoice(product, null)
            return
        }
        showVariantStep(product, variants)
    }

    /** Swaps the picker to the size grid of [product]. */
    private fun showVariantStep(product: CachedProduct, variants: List<CachedVariant>) {
        val view = rootView ?: return
        variantProduct = product
        variantAdapter?.submitList(variants)
        view.findViewById<RecyclerView>(R.id.productList).adapter = variantAdapter

        // Search and category chips belong to the catalogue; a five-row size grid needs neither.
        releaseFocusedField()
        view.findViewById<EditText>(R.id.productSearch).visibility = View.GONE
        view.findViewById<View>(R.id.productCategoryBar).visibility = View.GONE
        view.findViewById<TextView>(R.id.btnProductBack).visibility = View.VISIBLE
        view.findViewById<TextView>(R.id.productPanelTitle)
            .setText(SamaQuText.productLabel(product))

        val status = view.findViewById<TextView>(R.id.productStatus)
        if (SamaQuText.soldOutCount(variants) == variants.size) {
            status.text = getString(R.string.variant_all_sold_out)
            status.visibility = View.VISIBLE
        } else {
            status.visibility = View.GONE
        }
    }

    /** Puts the picker back on the catalogue. Safe to call when it is already there. */
    private fun showProductListStep() {
        val view = rootView ?: return
        releaseFocusedField()
        variantProduct = null
        view.findViewById<RecyclerView>(R.id.productList).adapter = productAdapter
        view.findViewById<EditText>(R.id.productSearch).visibility = View.VISIBLE
        view.findViewById<View>(R.id.productCategoryBar).visibility = View.VISIBLE
        view.findViewById<TextView>(R.id.btnProductBack).visibility = View.GONE
        view.findViewById<TextView>(R.id.productPanelTitle)
            .setText(getString(R.string.panel_product_title))
        applyProductFilter()
    }

    /**
     * Adds a picked product (and its size, when there is one) as a line of the invoice.
     * Picking the same product and size again raises that line's quantity instead of adding
     * a duplicate row.
     */
    private fun applyProductToInvoice(product: CachedProduct, variant: CachedVariant?) {
        releaseFocusedField()

        val label = SamaQuText.invoiceLineLabel(product, variant)
        val existing = invoiceDraft.items.indexOfFirst { it.label == label }
        invoiceDraft = if (existing >= 0) {
            invoiceDraft.withQty(existing, invoiceDraft.items[existing].qty + 1)
        } else {
            invoiceDraft.withItem(InvoiceItem(label, 1, SamaQuText.priceOf(product, variant)))
        }
        editingItem = if (existing >= 0) existing else invoiceDraft.items.lastIndex
        renderInvoiceItems()

        showProductListStep()
        showPanel(invoicePanel)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // ---------------------------------------------------------------- panels

    private fun showPanel(panel: View?) {
        allPanels().forEach { it?.visibility = View.GONE }
        panel?.visibility = View.VISIBLE
        // An open panel has its own close button, so the toolbar steps aside and gives its
        // row to the panel; closing the panel brings it back (see hideAllPanels).
        setToolbarVisible(panel == null)

        // Key taps go to the selected in-keyboard field. If that field belongs to a panel
        // that just went off screen it has to stop receiving them, or the keyboard would
        // keep typing into something invisible.
        val field = focusedField
        if (field != null && !isInside(panel, field)) releaseFocusedField()

        // Panels that replace the letter deck instead of stacking on top of it. Showing both
        // at once is what made it look like a second keyboard bolted under the first one.
        // Every other panel keeps the deck, because typing into its fields is the point
        // (the ongkir panel especially).
        if (panel === emojiPanel) matchEmojiPanelToKeyboard()
        applyDeckAndPickerLayout()

        // The picker is an extension of the invoice (it is opened from there), so it keeps
        // the Invoice toolbar entry highlighted instead of blanking the whole toolbar.
        setActiveToolbar(if (panel === productPanel) invoicePanel else panel)
    }

    private fun allPanels(): List<View?> = listOf(
        templatePanel,
        invoicePanel,
        productPanel,
        ongkirPanel,
        pendingPanel,
        emojiPanel,
        calculatorPanel
    )

    /**
     * Whether [panel] takes the letter deck's place instead of stacking on top of it.
     *
     * Declared in one place on purpose: emoji, the calculator and the product picker are all
     * full-mode surfaces, and listing them here is what keeps a later panel from being drawn
     * over the QWERTY row by accident. The product picker's one exception - handing the deck
     * back while its search field is typed into - is handled in [deckIsHidden].
     */
    private fun replacesDeck(panel: View?): Boolean =
        panel === emojiPanel || panel === calculatorPanel || panel === productPanel

    /** The panel currently on screen, if any. */
    private fun currentPanel(): View? =
        allPanels().firstOrNull { it != null && it.visibility == View.VISIBLE }

    /**
     * Whether the letter deck should be off screen right now.
     *
     * Full-mode panels hide it, with one exception: the product picker's search box is typed
     * with these very keys, so the deck comes back while a field inside the panel is focused
     * and goes away again the moment that field is released - Enter, a category chip, picking
     * a product, or switching panels.
     */
    private fun deckIsHidden(): Boolean {
        val panel = currentPanel() ?: return false
        if (!replacesDeck(panel)) return false
        return !(panel === productPanel && focusedField != null)
    }

    /**
     * Recomputes the deck's visibility and the product picker's height for what is on screen.
     *
     * Both are derived rather than remembered, so the two picker states - "picker owns the
     * screen" and "deck is back for typing" - can never drift apart from each other.
     */
    private fun applyDeckAndPickerLayout() {
        keyboardView?.visibility = if (deckIsHidden()) View.GONE else View.VISIBLE
        if (currentPanel() === productPanel) applyProductPanelHeight()
    }

    /**
     * Gives the product picker the space the deck is not using.
     *
     * Full height while the deck is hidden; whatever is left over - search box, filter chips
     * and a few rows - once the deck is back for typing. Both cases leave [MIN_VISIBLE_APP_DP]
     * of the app visible, so neither the picker nor the keyboard can cover the column being
     * typed into or run past the navigation bar.
     */
    private fun applyProductPanelHeight() {
        val panel = productPanel ?: return
        val params = panel.layoutParams ?: return

        val deck = keyboardView
        var deckHeight = 0
        if (deck != null && deck.visibility == View.VISIBLE) {
            deckHeight = deck.height
            // Just turned back on, so it has not been laid out yet: recompute once it has.
            if (deckHeight == 0) deck.post { applyProductPanelHeight() }
        }

        val height = (maxPanelHeightPx() - deckHeight).coerceAtLeast(dp(MIN_PANEL_DP))
        if (params.height != height) {
            params.height = height
            panel.layoutParams = params
        }
    }

    /**
     * Caps every panel's height so the IME can never push itself past the top of the display.
     *
     * The window is wrap_content, and the ongkir panel keeps the letter deck underneath it, so
     * on a short phone the two together could otherwise leave no room for the app being typed
     * into. Deliberately conservative: on a normal phone the reserve is larger than any panel,
     * so this changes nothing at all.
     */
    private fun limitPanelHeights() {
        val maxPx = maxPanelHeightPx()
        // A panel stacked over the letter deck also has to leave room for that deck, or
        // toolbar + panel + rows could push the app past MIN_VISIBLE_APP_DP on a short or
        // landscape screen. The deck is 0 px the first time this runs (called before the
        // first layout), so onCreateInputView re-applies the cap once it has a height.
        val deck = keyboardView
        val deckPx = if (deck != null && deck.visibility == View.VISIBLE) deck.height else 0
        allPanels().filterNotNull().forEach { panel ->
            val params = panel.layoutParams ?: return@forEach
            val cap = if (replacesDeck(panel)) maxPx
            else (maxPx - deckPx).coerceAtLeast(dp(MIN_PANEL_DP))
            if (params.height > cap) {
                params.height = cap
                panel.layoutParams = params
            }
        }
    }

    /** Tallest a panel may be on this screen, in pixels. */
    private fun maxPanelHeightPx(): Int {
        val maxDp = (resources.configuration.screenHeightDp - TOOLBAR_HEIGHT_DP - MIN_VISIBLE_APP_DP)
            .coerceAtLeast(MIN_PANEL_DP)
        return (maxDp * resources.displayMetrics.density).toInt()
    }

    private fun setToolbarVisible(visible: Boolean) {
        rootView?.findViewById<View>(R.id.toolbar)?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun hideAllPanels() {
        allPanels().forEach { it?.visibility = View.GONE }
        keyboardView?.visibility = View.VISIBLE
        setToolbarVisible(true)
        releaseFocusedField()
        setActiveToolbar(null)
        // Leaving the calculator drops any pending long-press marker, so the next Enter tap
        // cannot be swallowed by a press that is already finished with.
        (keyboardView as? SamaQuKeyboardView)?.clearEnterLongPress()
        // The picker always reopens on the catalogue, never on a size grid left behind.
        showProductListStep()
    }

    /** True while the digits keypad is showing only because a number field was touched. */
    private var numberDeckForced = false

    /**
     * A number or phone field wants the digits row straight away; any other field goes back
     * to letters. Only the deck changes: the user's own choice of deck in the chat itself is
     * left alone, see [restoreLetterDeck].
     */
    private fun onFieldFocused(field: EditText) {
        val wantsNumbers = FieldInput.wantsNumberDeck(field.inputType)
        if (wantsNumbers) {
            switchKeyboard(numpad)
        } else if (numberDeckForced) {
            switchKeyboard(qwerty)
        }
        numberDeckForced = wantsNumbers
    }

    /** Gives the letters back after a number field was released (no-op otherwise). */
    private fun restoreLetterDeck() {
        if (!numberDeckForced) return
        numberDeckForced = false
        switchKeyboard(qwerty)
    }

    /** Stops routing key taps into an in-keyboard field and restores its idle border. */
    private fun releaseFocusedField() {
        focusedField?.setBackgroundResource(R.drawable.field_bg)
        focusedField = null
        restoreLetterDeck()
        // Releasing a field inside the picker hands the deck back to it, so the list grows
        // again - this is what makes the search stop looking like it ate the results.
        applyDeckAndPickerLayout()
    }

    /** Whether [child] sits anywhere inside [parent]. */
    private fun isInside(parent: View?, child: View): Boolean {
        if (parent == null) return false
        var node: android.view.ViewParent? = child.parent
        while (node != null) {
            if (node === parent) return true
            node = node.parent
        }
        return false
    }

    /**
     * Sizes the emoji panel to exactly the letter deck it replaces.
     *
     * The IME window is wrap_content, so matching the deck height keeps the
     * window the same size across the switch: nothing resizes, nothing flickers,
     * and the field in the app underneath never loses focus.
     */
    private fun matchEmojiPanelToKeyboard() {
        val deck = keyboardView ?: return
        val panel = emojiPanel ?: return
        val deckHeight = deck.height.coerceAtMost(maxPanelHeightPx())
        if (deckHeight <= 0) return
        val params = panel.layoutParams ?: return
        if (params.height != deckHeight) {
            params.height = deckHeight
            panel.layoutParams = params
        }
    }

    // --------------------------------------------------------------- toolbar

    /**
     * Caches the toolbar entries so the entry whose panel is open can be
     * highlighted, and neutralises the action-only entries (Dashboard, Sync).
     */
    private fun setupToolbar(view: View) {
        toolbarItems = listOfNotNull(
            toolbarItem(view, R.id.btnInvoice, R.id.tbIconInvoice, R.id.tbLabelInvoice, invoicePanel),
            toolbarItem(view, R.id.btnOngkir, R.id.tbIconOngkir, R.id.tbLabelOngkir, ongkirPanel),
            toolbarItem(view, R.id.btnAutoText, R.id.tbIconAutoText, R.id.tbLabelAutoText, templatePanel),
            toolbarItem(view, R.id.btnPending, R.id.tbIconPending, R.id.tbLabelPending, pendingPanel)
        )
        // Dashboard opens an activity and Sync refreshes data; neither stays open,
        // so they never take the accent highlight.
        listOf(R.id.tbIconDashboard, R.id.tbIconSync).forEach { id ->
            view.findViewById<ImageView>(id).imageTintList = toolbarTint(active = false)
        }
        setActiveToolbar(null)
    }

    private fun toolbarItem(
        root: View,
        containerId: Int,
        iconId: Int,
        labelId: Int,
        panel: View?
    ): ToolbarItem? {
        val container = root.findViewById<LinearLayout>(containerId) ?: return null
        val icon = root.findViewById<ImageView>(iconId) ?: return null
        val label = root.findViewById<TextView>(labelId) ?: return null
        return ToolbarItem(container, icon, label, panel)
    }

    private fun setActiveToolbar(panel: View?) {
        toolbarItems.forEach { item ->
            val active = item.panel != null && item.panel === panel
            item.icon.imageTintList = toolbarTint(active)
            item.label.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (active) R.color.brand_purple else R.color.text_secondary
                )
            )
            item.label.setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
            // The selected state drives @color/toolbar_item_bg_color, so the
            // entry keeps its ripple background while gaining the accent pill.
            item.container.isSelected = active
        }
    }

    private fun toolbarTint(active: Boolean): ColorStateList = ColorStateList.valueOf(
        ContextCompat.getColor(
            this,
            if (active) R.color.brand_purple else R.color.text_secondary
        )
    )

    // -------------------------------------------------------------- data load

    /** Shows whatever the cache already holds - instant, and works with no connection. */
    private fun loadCachedTemplates() {
        scope.launch {
            allCategories = withContext(Dispatchers.IO) { repo.getTemplates() }
            renderCategories()
        }
    }

    /**
     * Cache first, then the backend.
     *
     * Rendering the cache before the request keeps the list from blanking out while the round
     * trip is in flight, and if the pull fails (offline, RLS) the cached list simply stays on
     * screen rather than being replaced by an error or by an empty panel.
     */
    private fun refreshTemplates() {
        scope.launch {
            allCategories = withContext(Dispatchers.IO) { repo.getTemplates() }
            renderCategories()

            val synced = withContext(Dispatchers.IO) { repo.sync().isSuccess }
            if (!synced) return@launch

            allCategories = withContext(Dispatchers.IO) { repo.getTemplates() }
            renderCategories()
        }
    }

    private fun doSync() {
        val status = rootView?.findViewById<TextView>(R.id.syncStatus)
        status?.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
        scope.launch {
            // Templates (Auto Text) and the product catalogue are refreshed together: the
            // single Sync button on the toolbar is what a CS reaches for when the data on
            // screen looks stale.
            val templatesOk = withContext(Dispatchers.IO) { repo.sync().isSuccess }
            val productsOk = withContext(Dispatchers.IO) { products.sync().isSuccess }

            allCategories = withContext(Dispatchers.IO) { repo.getTemplates() }
            renderCategories()

            readCatalogueIntoUi()

            status?.setTextColor(
                ContextCompat.getColor(
                    this@SamaQuIME,
                    if (templatesOk && productsOk) R.color.success else R.color.danger
                )
            )
        }
    }

    private fun renderCategories() {
        val tabs = categoryTabs ?: return
        if (allCategories.isEmpty()) return

        tabs.removeAllViews()
        allCategories.forEachIndexed { index, cat ->
            val chip = TextView(this).apply {
                text = cat.category.name
                textSize = 12f
                gravity = Gravity.CENTER
                setOnClickListener { selectTemplateCategory(index) }
            }
            chipPadding(chip)
            tabs.addView(chip, chipParams())
        }
        selectTemplateCategory(selectedCategoryIndex.coerceIn(0, allCategories.lastIndex))
    }

    private fun selectTemplateCategory(index: Int) {
        val adapter = templateAdapter ?: return
        val category = allCategories.getOrNull(index) ?: return
        selectedCategoryIndex = index
        adapter.submitList(category.templates)
        styleChips(categoryTabs, index)
    }

    // ------------------------------------------------------------ emoji panel

    private fun renderEmojiCategories() {
        val bar = emojiCategories ?: return
        bar.removeAllViews()
        EmojiCatalog.CATEGORIES.forEachIndexed { index, category ->
            val chip = TextView(this).apply {
                text = getString(category.labelRes)
                textSize = 12f
                gravity = Gravity.CENTER
                setOnClickListener { selectEmojiCategory(index) }
            }
            chipPadding(chip)
            bar.addView(chip, chipParams())
        }
        selectEmojiCategory(0)
    }

    private fun selectEmojiCategory(index: Int) {
        val category = EmojiCatalog.CATEGORIES.getOrNull(index) ?: return
        emojiAdapter?.submitList(category.emojis)
        styleChips(emojiCategories, index)
    }

    /**
     * Emoji columns that fit the screen, so the grid looks the same on a 320dp
     * phone as on a 480dp one instead of stretching or squashing the cells.
     */
    private fun emojiColumns(): Int = (resources.configuration.screenWidthDp / 44).coerceIn(6, 9)

    // ---------------------------------------------------------------- chips

    private fun chipParams(): LinearLayout.LayoutParams {
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params.marginEnd = (6 * resources.displayMetrics.density).toInt()
        return params
    }

    private fun chipPadding(chip: TextView) {
        val horizontal = (14 * resources.displayMetrics.density).toInt()
        val vertical = (7 * resources.displayMetrics.density).toInt()
        chip.setPadding(horizontal, vertical, horizontal, vertical)
    }

    /** Paints [selectedIndex] with the accent and every other chip neutral. */
    private fun styleChips(bar: LinearLayout?, selectedIndex: Int) {
        if (bar == null) return
        for (i in 0 until bar.childCount) {
            val chip = bar.getChildAt(i) as? TextView ?: continue
            val active = i == selectedIndex
            chip.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (active) R.color.surface else R.color.text_secondary
                )
            )
            chip.setBackgroundResource(
                if (active) R.drawable.chip_bg_selected else R.drawable.chip_bg
            )
            chip.setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    private fun loadPendingOrders() {
        val emptyTxt = rootView?.findViewById<TextView>(R.id.pendingEmpty) ?: return
        val prefs = Prefs(this)

        if (prefs.supabaseUrl.isBlank() || prefs.supabaseAnonKey.isBlank()) {
            emptyTxt.text = getString(R.string.msg_jwt_missing)
            emptyTxt.visibility = View.VISIBLE
            return
        }

        RetrofitClient.init(prefs.supabaseUrl, prefs.supabaseAnonKey)
        scope.launch {
            try {
                val pending = withContext(Dispatchers.IO) {
                    SamaQuText.pendingOnly(RetrofitClient.api.getOrders())
                }
                if (pending.isEmpty()) {
                    emptyTxt.text = getString(R.string.msg_no_pending)
                    emptyTxt.visibility = View.VISIBLE
                } else {
                    emptyTxt.visibility = View.GONE
                    orderAdapter?.submitList(pending)
                }
            } catch (e: Exception) {
                emptyTxt.text = getString(R.string.msg_load_failed, e.message ?: "")
                emptyTxt.visibility = View.VISIBLE
            }
        }
    }

    private fun sendOrderGreeting(order: OrderItem) {
        commit(SamaQuText.orderGreeting(order))
        hideAllPanels()
    }

    /** Keeps [trackedSelStart]/[trackedSelEnd] mirroring the target editor's selection. */
    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        newComposingStart: Int,
        newComposingEnd: Int
    ) {
        super.onUpdateSelection(
            oldSelStart, oldSelEnd, newSelStart, newSelEnd, newComposingStart, newComposingEnd
        )
        trackedSelStart = newSelStart
        trackedSelEnd = newSelEnd
    }

    private fun commit(text: String) {
        currentInputConnection?.commitText(text, 1)
    }

    /** The target app's editor seen through the five operations [Backspace] needs. */
    private val backspaceTarget = object : Backspace.Target {
        override fun selectedText(): CharSequence? = currentInputConnection?.getSelectedText(0)

        override fun hasTrackedSelection(): Boolean =
            trackedSelStart >= 0 && trackedSelEnd > trackedSelStart

        override fun textBeforeCursor(length: Int): CharSequence? =
            currentInputConnection?.getTextBeforeCursor(length, 0)

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int) {
            currentInputConnection?.deleteSurroundingText(beforeLength, afterLength)
        }

        override fun commitText(text: CharSequence, newCursorPosition: Int) {
            currentInputConnection?.commitText(text, newCursorPosition)
        }
    }

    // ------------------------------------------------------------ key handling

    override fun onKey(primaryCode: Int, keyCodes: IntArray?) {
        // A long press on Enter already opened the calculator; the release that follows must
        // not also insert a newline. Checked before both branches so it holds everywhere.
        if (primaryCode == SamaQuKeyboardView.CODE_ENTER && consumeEnterLongPress()) return

        // 1) If an in-keyboard field is selected, type into it.
        if (focusedField != null) {
            when (primaryCode) {
                SamaQuKeyboardView.CODE_EMOJI -> showPanel(emojiPanel)
                SamaQuKeyboardView.CODE_QWERTY -> switchKeyboard(qwerty)
                SamaQuKeyboardView.CODE_SYMBOLS -> switchKeyboard(symbols)
                // Shift has to be routed here too. It used to fall through to typeToField,
                // which inserted an invisible char and left caps untouched - so capitals
                // stopped working as soon as any invoice/ongkir field was selected.
                SamaQuKeyboardView.CODE_SHIFT -> toggleShift()
                SamaQuKeyboardView.CODE_ENTER -> onEnterInField()
                else -> typeToField(primaryCode)
            }
            return
        }

        // 2) Otherwise type into the target app.
        val ic: InputConnection = currentInputConnection ?: return
        when (primaryCode) {
            SamaQuKeyboardView.CODE_EMOJI -> showPanel(emojiPanel)
            SamaQuKeyboardView.CODE_QWERTY -> switchKeyboard(qwerty)
            SamaQuKeyboardView.CODE_SYMBOLS -> switchKeyboard(symbols)
            // Selection first, one whole char before the cursor otherwise. The old
            // deleteSurroundingText(1, 0) could not delete a selection at all (the contract
            // scopes it to text outside the selection) and split emoji surrogate pairs.
            SamaQuKeyboardView.CODE_DELETE -> Backspace.delete(backspaceTarget)
            SamaQuKeyboardView.CODE_ENTER -> {
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            }
            SamaQuKeyboardView.CODE_SHIFT -> toggleShift()
            // Every key goes through the shift state; only a letter spends it. The old branch
            // cancelled shift on whatever key came next, so shift-then-space ate the capital.
            else -> ic.commitText(shiftedCode(primaryCode).toChar().toString(), 1)
        }
    }

    private fun switchKeyboard(kb: Keyboard?) {
        keyboardView?.keyboard = kb
        keyboardView?.invalidateAllKeys()
    }

    /** One tap: lowercase -> one capital -> Caps Lock -> lowercase. */
    private fun toggleShift() {
        shift.tap()
        applyShiftToKeyboard()
    }

    /**
     * Pushes the shift state onto the decks.
     *
     * Every letter key carries an explicit `keyLabel` in the XML and `setShifted()` does not
     * rewrite those, so the capitals have to be written onto the keys by hand. Without that
     * the deck kept reading "q" while it typed "Q", which is exactly the desync between the
     * key face, the key label and the committed character that this has to rule out.
     */
    private fun applyShiftToKeyboard() {
        val active = shift.isActive

        (keyboardView as? SamaQuKeyboardView)?.shiftActive = active

        listOfNotNull(qwerty, symbols).forEach { kb ->
            kb.setShifted(active)
            kb.keys.forEach { key ->
                val code = key.codes.firstOrNull() ?: return@forEach
                if (code in 'a'.code..'z'.code) {
                    key.label = (if (active) code - SHIFT_CASE_DELTA else code).toChar().toString()
                }
            }
            // OFF and ONCE share the arrow and differ only by the tint; the filled glyph is
            // what marks the state that outlives the letter it is about to produce.
            kb.keys.firstOrNull { it.codes.firstOrNull() == SamaQuKeyboardView.CODE_SHIFT }
                ?.label = if (shift.isLocked) SHIFT_LABEL_LOCKED else SHIFT_LABEL_OFF
        }

        keyboardView?.invalidateAllKeys()
    }

    /**
     * The code to actually type, once shift has had its say.
     *
     * Repainting only when the state moved keeps the per-keypress path clear of a full
     * `invalidateAllKeys()`, while still guaranteeing the deck always matches what was typed.
     */
    private fun shiftedCode(primaryCode: Int): Int {
        val before = shift.level
        val code = shift.resolve(primaryCode)
        if (shift.level != before) applyShiftToKeyboard()
        return code
    }

    /** A new input field always starts in lowercase. */
    private fun resetShift() {
        if (!shift.isActive) return
        shift.reset()
        applyShiftToKeyboard()
    }

    private fun typeToField(code: Int) {
        val field = focusedField ?: return
        val text: Editable = field.text ?: return
        val pos = field.selectionStart.coerceAtLeast(0)

        if (code == SamaQuKeyboardView.CODE_DELETE) {
            // Same rules as Backspace.delete: a selection in the field is what goes,
            // otherwise one whole character (emoji = both surrogate halves), and nothing
            // at the start of the text. Selection order can be reversed while dragging.
            val lo = minOf(field.selectionStart, field.selectionEnd)
            val hi = maxOf(field.selectionStart, field.selectionEnd)
            if (hi > lo) {
                text.delete(lo, hi)
                return
            }
            val units = Backspace.unitsToDelete(text.subSequence((pos - 2).coerceAtLeast(0), pos))
            if (units > 0) text.delete(pos - units, pos)
            return
        }
        // Control codes (shift, emoji, ...) must never reach the field: code.toChar() on a
        // negative value yields an invisible 0xFFFF that then sits in the input.
        if (code < ' '.code) return

        text.insert(pos, shiftedCode(code).toChar().toString())
    }

    // KeyboardView callbacks we don't need
    override fun onText(text: CharSequence?) = Unit
    override fun swipeLeft() = Unit
    override fun swipeRight() = Unit
    override fun swipeDown() = Unit
    override fun swipeUp() = Unit
    override fun onPress(primaryCode: Int) {
        val view = keyboardView ?: return
        // Tactile tick plus a visible pressed face, so every tap registers instantly.
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        val keys = view as? SamaQuKeyboardView ?: return
        keys.setPressedCode(primaryCode)

        // Holding Enter is what opens the Quick Calculator. The clock starts here - on the
        // framework's own report that Enter went down - so it cannot start for any other key,
        // and a plain tap always ends before the timer does.
        if (primaryCode == SamaQuKeyboardView.CODE_ENTER) keys.startEnterLongPress()
    }

    override fun onRelease(primaryCode: Int) {
        val keys = keyboardView as? SamaQuKeyboardView ?: return
        keys.setPressedCode(SamaQuKeyboardView.NO_CODE)
        // Releasing Enter ends the hold; sliding onto another key reports a release too, so
        // dragging away cancels the calculator without any coordinate maths of our own.
        if (primaryCode == SamaQuKeyboardView.CODE_ENTER) keys.cancelEnterLongPress()
    }

    /** One toolbar entry, kept as a group so its state can be repainted whole. */
    private data class ToolbarItem(
        val container: LinearLayout,
        val icon: ImageView,
        val label: TextView,
        val panel: View?
    )

    companion object {
        /** J&T quotes are per kilogram; 1 kg is the usual starting point for a CS. */
        private const val DEFAULT_WEIGHT_GRAM = 1000

        /** Toolbar height from keyboard_main.xml, reserved when panel heights are capped. */
        /** The order Enter walks through the invoice form. */
        private val INVOICE_FIELD_ORDER = listOf(R.id.invBuyer, R.id.invPhone, R.id.invAddress, R.id.invOngkir)

        private const val TOOLBAR_HEIGHT_DP = 46

        /** Strip of the app, and of its input field, that must stay visible above the IME. */
        private const val MIN_VISIBLE_APP_DP = 160

        /** Never shrink a panel below this, even on a very short screen. */
        private const val MIN_PANEL_DP = 200

        // Quick Calculator key tokens (android:tag in keyboard_layout.xml)
        private const val TOKEN_CLEAR = "C"
        private const val TOKEN_BACKSPACE = "⌫"
        private const val TOKEN_EQUALS = "="

        /** How long after an Enter long press the matching tap is still swallowed. */
        private const val ENTER_LONG_PRESS_TAP_WINDOW_MS = 1_000L

        private const val SHIFT_LABEL_OFF = "⇧"

        /** Caps Lock's filled arrow, so the state that survives a letter is the visible one. */
        private const val SHIFT_LABEL_LOCKED = "⇪"

        /** 'a' - 'A': the offset that turns a lowercase key code into its capital. */
        private const val SHIFT_CASE_DELTA = 32
    }
}
