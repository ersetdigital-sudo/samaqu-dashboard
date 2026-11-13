package com.samaqu.keyboard.data

import android.content.Context
import android.content.SharedPreferences
import com.samaqu.keyboard.network.SupabaseConfig
import com.samaqu.keyboard.util.InvoiceFormatter
import com.samaqu.keyboard.util.SavedSettings

/**
 * Thin wrapper around SharedPreferences ("samaqu_prefs").
 */
class Prefs(ctx: Context) {

    private val sp: SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Supabase project URL. Falls back to the value compiled into [SupabaseConfig], which is
     * also what a fresh install uses before anything is saved in Settings. A saved value that
     * is the previous project's URL counts as "not saved", so phones that stored the old
     * default move to the new project; a URL typed on purpose is left alone.
     */
    var supabaseUrl: String
        get() = SavedSettings.resolve(
            sp.getString(KEY_SUPABASE_URL, null), SupabaseConfig.PROJECT_URL, SupabaseConfig.LEGACY_URLS
        )
        set(v) = sp.edit().putString(KEY_SUPABASE_URL, v).apply()

    /** Supabase "anon public" key, with the same legacy handling as [supabaseUrl]. */
    var supabaseAnonKey: String
        get() = SavedSettings.resolve(
            sp.getString(KEY_SUPABASE_ANON_KEY, null), SupabaseConfig.ANON_KEY, SupabaseConfig.LEGACY_ANON_KEYS
        )
        set(v) = sp.edit().putString(KEY_SUPABASE_ANON_KEY, v).apply()

    /**
     * Bank block printed on invoices, e.g. `Bank BSI 7153280058` / `a.n. ...`. Filled by the
     * catalogue sync from the store's `payment_methods`; empty until the first successful sync.
     */
    var paymentText: String
        get() = sp.getString(KEY_PAYMENT_TEXT, "") ?: ""
        set(v) = sp.edit().putString(KEY_PAYMENT_TEXT, v).apply()

    /** The Supabase URL the cached catalogue was last synced from; null until a sync succeeds. */
    var catalogueSource: String?
        get() = sp.getString(KEY_CATALOGUE_SOURCE, null)
        set(v) = sp.edit().putString(KEY_CATALOGUE_SOURCE, v).apply()

    /**
     * Last sentence of an invoice. [InvoiceFormatter.DEFAULT_CLOSING] until the owner saves
     * something; an empty value saved on purpose stays empty (the sentence is then left out).
     */
    var invoiceClosing: String
        get() = if (sp.contains(KEY_INVOICE_CLOSING)) sp.getString(KEY_INVOICE_CLOSING, "").orEmpty()
        else InvoiceFormatter.DEFAULT_CLOSING
        set(v) = sp.edit().putString(KEY_INVOICE_CLOSING, v).apply()

    /** Count of pending orders seen at the last poll (used to detect new ones). */
    var lastPendingCount: Int
        get() = sp.getInt(KEY_LAST_PENDING, 0)
        set(v) = sp.edit().putInt(KEY_LAST_PENDING, v).apply()

    /**
     * Web dashboard opened by DashboardActivity.
     *
     * Defaults to [DEFAULT_DASHBOARD_URL] so the dashboard works on a fresh install with
     * nothing to paste - the Keyboard's own admin page, hosted on GitHub Pages.
     *
     * A blank or whitespace value also falls back to the default instead of switching the
     * screen off: an older build saved "" here by default, and that must not leave an
     * upgraded phone stuck on the offline summary.
     *
     * (The URL hardcoded in the original app pointed at the previous SamaQu project and
     * demanded that project's CS/Admin login, which is why it was dropped.)
     */
    var dashboardUrl: String
        get() = sp.getString(KEY_DASHBOARD_URL, null)?.takeIf { it.isNotBlank() }
            ?: DEFAULT_DASHBOARD_URL
        set(v) = sp.edit().putString(KEY_DASHBOARD_URL, v).apply()

    /** "small", "normal" or "large". */
    var keySize: String
        get() = sp.getString(KEY_KEY_SIZE, KEY_SIZE_NORMAL) ?: KEY_SIZE_NORMAL
        set(v) = sp.edit().putString(KEY_KEY_SIZE, v).apply()

    /** Whether the floating overlay should be started automatically (e.g. after boot). */
    var overlayEnabled: Boolean
        get() = sp.getBoolean(KEY_OVERLAY_ENABLED, false)
        set(v) = sp.edit().putBoolean(KEY_OVERLAY_ENABLED, v).apply()

    companion object {
        private const val PREFS = "samaqu_prefs"
        private const val KEY_SUPABASE_URL = "supabase_url"
        private const val KEY_SUPABASE_ANON_KEY = "supabase_anon_key"
        private const val KEY_LAST_PENDING = "last_pending"
        private const val KEY_PAYMENT_TEXT = "payment_text"
        private const val KEY_INVOICE_CLOSING = "invoice_closing"
        private const val KEY_CATALOGUE_SOURCE = "catalogue_source"
        private const val KEY_KEY_SIZE = "key_size"
        private const val KEY_DASHBOARD_URL = "dashboard_url"
        private const val KEY_OVERLAY_ENABLED = "overlay_enabled"

        /** The keyboard's own web dashboard: manage chat templates and categories. */
        const val DEFAULT_DASHBOARD_URL = "https://ersetdigital-sudo.github.io/samaqu-dashboard/"

        const val KEY_SIZE_SMALL = "small"
        const val KEY_SIZE_NORMAL = "normal"
        const val KEY_SIZE_LARGE = "large"
    }
}
