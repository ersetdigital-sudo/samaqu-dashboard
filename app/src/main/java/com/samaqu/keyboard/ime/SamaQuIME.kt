package com.samaqu.keyboard.ime

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
import android.widget.TextView
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
import com.samaqu.keyboard.util.CalculatorEngine
import com.samaqu.keyboard.util.SamaQuText
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
    private var caps = false

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

    private var allCategories: List<CategoryWithTemplates> = emptyList()

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
        // Rebuild when the key size was changed in Settings, so the new size shows
        // up without the user having to switch to another keyboard and back.
        val configured = Prefs(this).keySize
        if (appliedKeySize != null && appliedKeySize != configured) {
            setInputView(onCreateInputView())
        }
    }

    // ------------------------------------------------------------- input view

    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.keyboard_main, null)
        rootView = view

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

        keyboardView = (view.findViewById<View>(R.id.keyboardView) as KeyboardView).apply {
            keyboard = qwerty
            setOnKeyboardActionListener(this@SamaQuIME)
            // Tactile tick + the pressed key highlight (see SamaQuKeyboardView)
            // are enough feedback; the letter bubble above the key is off.
            isHapticFeedbackEnabled = true
            isPreviewEnabled = false
        }

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

        setupToolbar(view)
        setupInvoicePanel(view)
        setupOngkirPanel(view)
        setupProductPanel(view)
        setupCalculatorPanel(view)
        loadCachedTemplates()
        return view
    }

    private fun setupInvoicePanel(view: View) {
        val fields = listOf(
            view.findViewById<EditText>(R.id.invBuyer),
            view.findViewById<EditText>(R.id.invProduct),
            view.findViewById<EditText>(R.id.invQty),
            view.findViewById<EditText>(R.id.invPrice),
            view.findViewById<EditText>(R.id.invOngkir)
        )
        fields.forEach { field ->
            field.setOnTouchListener { _, event ->
                if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                    focusedField = field
                    fields.forEach { it.setBackgroundResource(R.drawable.field_bg) }
                    field.setBackgroundResource(R.drawable.field_bg_active)
                }
                false
            }
        }

        view.findViewById<TextView>(R.id.invBtnGenerate).setOnClickListener {
            val buyer = view.findViewById<EditText>(R.id.invBuyer).text.toString().trim()
            val product = view.findViewById<EditText>(R.id.invProduct).text.toString().trim()
            val qty = view.findViewById<EditText>(R.id.invQty).text.toString().toIntOrNull() ?: 1
            val price = view.findViewById<EditText>(R.id.invPrice).text.toString().toDoubleOrNull() ?: 0.0
            val ongkir = view.findViewById<EditText>(R.id.invOngkir).text.toString().toDoubleOrNull() ?: 0.0

            commit(SamaQuText.keyboardInvoice(buyer, product, qty, price, ongkir))
            hideAllPanels()
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

    /** Puts a quoted cost into the invoice form and switches to it. */
    private fun useOngkir(option: JntCostOption) {
        val view = rootView ?: return
        view.findViewById<EditText>(R.id.invOngkir).setText(option.cost.toString())
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
     * Fills the invoice from a picked product and, when there is one, its size.
     *
     * The price is only written when the catalogue has one, so a 0 in the table cannot
     * silently blank a figure the CS already typed.
     */
    private fun applyProductToInvoice(product: CachedProduct, variant: CachedVariant?) {
        val view = rootView ?: return
        releaseFocusedField()

        view.findViewById<EditText>(R.id.invProduct)
            .setText(SamaQuText.variantLabel(product, variant))

        val price = SamaQuText.priceOf(product, variant)
        if (price > 0) {
            view.findViewById<EditText>(R.id.invPrice).setText(price.toString())
        }

        showProductListStep()
        showPanel(invoicePanel)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // ---------------------------------------------------------------- panels

    private fun showPanel(panel: View?) {
        allPanels().forEach { it?.visibility = View.GONE }
        panel?.visibility = View.VISIBLE

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
        allPanels().filterNotNull().forEach { panel ->
            val params = panel.layoutParams ?: return@forEach
            if (params.height > maxPx) {
                params.height = maxPx
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

    private fun hideAllPanels() {
        allPanels().forEach { it?.visibility = View.GONE }
        keyboardView?.visibility = View.VISIBLE
        releaseFocusedField()
        setActiveToolbar(null)
        // Leaving the calculator drops any pending long-press marker, so the next Enter tap
        // cannot be swallowed by a press that is already finished with.
        (keyboardView as? SamaQuKeyboardView)?.clearEnterLongPress()
        // The picker always reopens on the catalogue, never on a size grid left behind.
        showProductListStep()
    }

    /** Stops routing key taps into an in-keyboard field and restores its idle border. */
    private fun releaseFocusedField() {
        focusedField?.setBackgroundResource(R.drawable.field_bg)
        focusedField = null
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

    private fun loadCachedTemplates() {
        scope.launch {
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
        selectTemplateCategory(0)
    }

    private fun selectTemplateCategory(index: Int) {
        val adapter = templateAdapter ?: return
        val category = allCategories.getOrNull(index) ?: return
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

    private fun commit(text: String) {
        currentInputConnection?.commitText(text, 1)
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
                SamaQuKeyboardView.CODE_SHIFT -> toggleCaps()
                SamaQuKeyboardView.CODE_ENTER -> focusedField = null
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
            SamaQuKeyboardView.CODE_DELETE -> ic.deleteSurroundingText(1, 0)
            SamaQuKeyboardView.CODE_ENTER -> {
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            }
            SamaQuKeyboardView.CODE_SHIFT -> toggleCaps()
            else -> {
                val code = if (caps && primaryCode in 'a'.code..'z'.code) primaryCode - 32 else primaryCode
                ic.commitText(code.toChar().toString(), 1)
                if (caps) toggleCaps()
            }
        }
    }

    private fun switchKeyboard(kb: Keyboard?) {
        keyboardView?.keyboard = kb
        keyboardView?.invalidateAllKeys()
    }

    private fun toggleCaps() {
        caps = !caps
        qwerty?.setShifted(caps)

        // Every letter has an explicit keyLabel, and setShifted does not touch those, so
        // nothing on screen used to change when shift was tapped. The face colour and the
        // glyph below are what make the caps state visible.
        (keyboardView as? SamaQuKeyboardView)?.shiftActive = caps
        val label = if (caps) SHIFT_LABEL_ON else SHIFT_LABEL_OFF
        listOfNotNull(qwerty, symbols).forEach { kb ->
            kb.keys.firstOrNull { it.codes[0] == SamaQuKeyboardView.CODE_SHIFT }?.label = label
        }

        keyboardView?.invalidateAllKeys()
    }

    private fun typeToField(code: Int) {
        val field = focusedField ?: return
        val text: Editable = field.text ?: return
        val pos = field.selectionStart.coerceAtLeast(0)

        if (code == SamaQuKeyboardView.CODE_DELETE) {
            if (text.isNotEmpty() && pos > 0) text.delete(pos - 1, pos)
            return
        }
        // Control codes (shift, emoji, ...) must never reach the field: code.toChar() on a
        // negative value yields an invisible 0xFFFF that then sits in the input.
        if (code < ' '.code) return

        var c = code
        if (caps && c in 'a'.code..'z'.code) c -= 32
        text.insert(pos, c.toChar().toString())
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
        private const val TOOLBAR_HEIGHT_DP = 54

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
        private const val SHIFT_LABEL_ON = "⇪"
    }
}
