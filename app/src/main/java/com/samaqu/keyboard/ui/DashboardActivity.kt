package com.samaqu.keyboard.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.button.MaterialButton
import com.samaqu.keyboard.R
import com.samaqu.keyboard.data.Prefs
import com.samaqu.keyboard.data.TemplateRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Dashboard.
 *
 * If no dashboard URL is configured this shows a local summary built from the
 * Room cache and SharedPreferences - no login, no network. The URL that used to
 * be hardcoded here was the original SamaQu project's web dashboard and required
 * that project's CS/Admin account, which this app does not have.
 *
 * When a URL is configured, the WebView path reports real page progress,
 * supports pull-to-refresh and offers a retryable error view.
 */
class DashboardActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs

    // Null until the web dashboard is first shown; the offline summary can be opened
    // without one ever being created.
    private var web: WebView? = null
    private lateinit var toolbar: Toolbar
    private var showingSummary: Boolean = false
    // Named loadProgress, not progress: inside a WebView.apply block the name
    // `progress` would resolve to WebView.getProgress() instead of this field.
    private lateinit var loadProgress: ProgressBar
    private lateinit var errorView: View
    private lateinit var errorText: TextView
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var summaryView: View

    private var configuredUrl: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashboard)

        prefs = Prefs(this)
        configuredUrl = prefs.dashboardUrl.trim()

        setupToolbar()

        loadProgress = findViewById(R.id.progress)
        errorView = findViewById(R.id.errorView)
        errorText = findViewById(R.id.errorText)
        swipe = findViewById(R.id.swipeRefresh)
        summaryView = findViewById(R.id.summaryView)

        findViewById<MaterialButton>(R.id.btnOpenSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        findViewById<MaterialButton>(R.id.btnManageTemplates).setOnClickListener {
            startActivity(Intent(this, TemplateManageActivity::class.java))
        }

        showWebDashboard()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web?.canGoBack() == true) {
                    web?.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    /** Local summary: template/category counts plus configuration status. */
    private fun showSummary() {
        showingSummary = true
        toolbar.menu.findItem(R.id.action_summary)?.setTitle(R.string.action_web_dashboard)
        summaryView.visibility = View.VISIBLE
        errorView.visibility = View.GONE
        swipe.visibility = View.GONE

        findViewById<TextView>(R.id.tvBackend).text =
            if (prefs.supabaseUrl.isBlank()) getString(R.string.summary_backend_unset)
            else getString(R.string.summary_backend_set, prefs.supabaseUrl)

        findViewById<TextView>(R.id.tvToken).text =
            if (prefs.supabaseAnonKey.isBlank()) getString(R.string.summary_token_unset)
            else getString(R.string.summary_token_set)

        val sizeLabel = when (prefs.keySize) {
            Prefs.KEY_SIZE_SMALL -> getString(R.string.key_size_small)
            Prefs.KEY_SIZE_LARGE -> getString(R.string.key_size_large)
            else -> getString(R.string.key_size_normal)
        }
        findViewById<TextView>(R.id.tvKeySize).text = getString(R.string.summary_key_size, sizeLabel)

        val version = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull() ?: "-"
        findViewById<TextView>(R.id.tvAppVersion).text =
            getString(R.string.summary_app_version, version)

        refreshCounts()
    }

    /**
     * Brings the web dashboard to the front, creating the WebView on first use.
     *
     * This is the screen Dashboard opens with, which is why the URL has a compiled-in
     * default: nothing has to be pasted anywhere for template management to work.
     */
    private fun showWebDashboard() {
        showingSummary = false
        toolbar.menu.findItem(R.id.action_summary)?.setTitle(R.string.action_summary)
        summaryView.visibility = View.GONE
        errorView.visibility = View.GONE
        swipe.visibility = View.VISIBLE
        if (web == null) setupWebView()
    }

    /** Re-reads the Room cache so the counters move after leaving Kelola Template. */
    private fun refreshCounts() {
        val repo = TemplateRepository(this)
        lifecycleScope.launch {
            val (categories, templates) = withContext(Dispatchers.IO) { repo.counts() }
            findViewById<TextView>(R.id.tvTemplateCount).text = templates.toString()
            findViewById<TextView>(R.id.tvCategoryCount).text = categories.toString()
        }
    }

    /**
     * The Toolbar stays a plain Toolbar (not the support ActionBar) so the back arrow in the
     * layout keeps working, while still hosting the overflow menu that makes the in-app
     * template manager and Settings reachable from either mode.
     */
    private fun setupToolbar() {
        toolbar = findViewById(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.inflateMenu(R.menu.dashboard_menu)
        toolbar.overflowIcon?.setTint(Color.WHITE)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_summary -> {
                    if (showingSummary) showWebDashboard() else showSummary()
                    true
                }
                R.id.action_manage_templates -> {
                    startActivity(Intent(this, TemplateManageActivity::class.java))
                    true
                }
                R.id.action_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    true
                }
                else -> false
            }
        }
    }

    override fun onResume() {
        super.onResume()

        // Settings may have replaced or cleared the URL while this screen was in the back
        // stack; restart so the new mode (web dashboard vs offline summary) takes effect.
        if (prefs.dashboardUrl.trim() != configuredUrl) {
            recreate()
            return
        }

        // The summary has no lifecycle of its own; without this the template count
        // would stay frozen at whatever it was when the screen was first opened.
        if (showingSummary) refreshCounts()
    }

    private fun setupWebView() {
        if (web != null) return
        swipe.setColorSchemeResources(R.color.brand_blue, R.color.brand_accent)
        swipe.setOnRefreshListener {
            errorView.visibility = View.GONE
            web?.reload()
        }

        findViewById<MaterialButton>(R.id.btnRetry).setOnClickListener {
            errorView.visibility = View.GONE
            web?.loadUrl(configuredUrl)
        }

        web = findViewById<WebView>(R.id.webView).apply {
            setBackgroundColor(ContextCompat.getColor(this@DashboardActivity, R.color.bg_light))

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                setSupportZoom(false)
                builtInZoomControls = false
                cacheMode = WebSettings.LOAD_DEFAULT
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                javaScriptCanOpenWindowsAutomatically = false
                mediaPlaybackRequiresUserGesture = true
            }

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    loadProgress.visibility = View.VISIBLE
                    loadProgress.progress = 0
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    loadProgress.visibility = View.GONE
                    swipe.isRefreshing = false
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    // Only a failed main frame should replace the page with the error
                    // view; a missing image or font must not blank the dashboard.
                    if (request?.isForMainFrame == true) {
                        swipe.isRefreshing = false
                        loadProgress.visibility = View.GONE
                        errorView.visibility = View.VISIBLE
                        errorText.text = getString(R.string.dashboard_error_detail)
                    }
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    loadProgress.progress = newProgress
                    if (newProgress >= 100) loadProgress.visibility = View.GONE
                }
            }

            loadUrl(configuredUrl)
        }
    }

    override fun onDestroy() {
        // Detach so the WebView stops loading and cannot leak the activity.
        web?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            view.destroy()
        }
        web = null
        super.onDestroy()
    }
}
