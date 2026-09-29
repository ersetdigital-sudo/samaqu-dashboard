package com.samaqu.keyboard.ui

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.samaqu.keyboard.R
import com.samaqu.keyboard.data.Prefs

class MainActivity : AppCompatActivity() {

    private var currentFragment: Fragment? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNav)

        findViewById<TextView>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        val startId = when (intent?.getStringExtra(EXTRA_TAB)) {
            TAB_DASHBOARD -> R.id.nav_dashboard
            TAB_ONGKIR -> R.id.nav_ongkir
            TAB_PENDING -> R.id.nav_pending
            TAB_AUTOTEXT -> R.id.nav_autotext
            else -> R.id.nav_invoice
        }
        show(startId, bottomNav, resetSelection = false)
        bottomNav.selectedItemId = startId

        bottomNav.setOnItemSelectedListener { item ->
            show(item.itemId, bottomNav, resetSelection = false)
            true
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val wvf = currentFragment as? WebViewFragment
                if (wvf != null && wvf.canGoBack()) {
                    wvf.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val tab = intent.getStringExtra(EXTRA_TAB) ?: return
        val id = when (tab) {
            TAB_DASHBOARD -> R.id.nav_dashboard
            TAB_ONGKIR -> R.id.nav_ongkir
            TAB_PENDING -> R.id.nav_pending
            TAB_AUTOTEXT -> R.id.nav_autotext
            else -> R.id.nav_invoice
        }
        findViewById<BottomNavigationView>(R.id.bottomNav).selectedItemId = id
    }

    private fun show(itemId: Int, bottomNav: BottomNavigationView, resetSelection: Boolean) {
        // Dashboard opens its own screen: it shows a local summary when no web
        // dashboard URL is configured, instead of a page that demands a login.
        if (itemId == R.id.nav_dashboard) {
            startActivity(Intent(this, DashboardActivity::class.java))
            return
        }

        val dashboardUrl = Prefs(this).dashboardUrl.trim()

        val fragment: Fragment = when (itemId) {
            R.id.nav_ongkir -> WebViewFragment.newInstance(URL_ONGKIR)
            R.id.nav_autotext -> AutoTextFragment()
            R.id.nav_pending ->
                if (dashboardUrl.isBlank()) {
                    InfoFragment.newInstance(getString(R.string.pending_needs_backend))
                } else {
                    WebViewFragment.newInstance("$dashboardUrl/orders")
                }
            else -> InvoiceFragment()
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()
        currentFragment = fragment
        if (resetSelection) bottomNav.selectedItemId = itemId
    }

    companion object {
        const val EXTRA_TAB = "tab"
        const val TAB_INVOICE = "invoice"
        const val TAB_ONGKIR = "ongkir"
        const val TAB_AUTOTEXT = "autotext"
        const val TAB_PENDING = "pending"
        const val TAB_DASHBOARD = "dashboard"

        private const val URL_ONGKIR = "https://cekongkir.com"
    }
}
