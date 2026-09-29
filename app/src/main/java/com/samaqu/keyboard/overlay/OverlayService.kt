package com.samaqu.keyboard.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.samaqu.keyboard.R
import com.samaqu.keyboard.data.CategoryWithTemplates
import com.samaqu.keyboard.data.TemplateRepository
import com.samaqu.keyboard.ime.SamaQuAccessibility
import com.samaqu.keyboard.ui.TemplateAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground service that shows a floating SAMAQU toolbar (and template panel)
 * just above the on-screen keyboard.
 *
 * Unlike the decompiled original, this is actually wired up: SettingsActivity
 * requests the overlay permission and calls [Companion.start].
 */
class OverlayService : Service() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var repo: TemplateRepository
    private var wm: WindowManager? = null

    private var toolbarView: View? = null
    private var panelView: View? = null

    private var keyboardTop = -1
    private var panelOpen = false
    private var allCategories: List<CategoryWithTemplates> = emptyList()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        repo = TemplateRepository(this)
        wm = getSystemService(WindowManager::class.java)

        startForegroundCompat()

        scope.launch {
            allCategories = withContext(Dispatchers.IO) { repo.getTemplates() }
        }
        showToolbar(defaultToolbarY())
    }

    private fun startForegroundCompat() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.channel_fg), NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.notif_fg_title))
            .setContentText(getString(R.string.notif_fg_text))
            .setSmallIcon(R.drawable.ic_menu_send)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    // -------------------------------------------------------------- positioning

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun navigationBarHeight(): Int {
        val resId = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (resId > 0) resources.getDimensionPixelSize(resId) else dp(48)
    }

    private fun defaultToolbarY(): Int =
        resources.displayMetrics.heightPixels - navigationBarHeight() - dp(48)

    fun onKeyboardShown(kbTop: Int) {
        if (kbTop <= 0) return
        keyboardTop = kbTop
        repositionToolbar(kbTop - dp(48))
        if (panelOpen) repositionPanel()
    }

    fun onKeyboardHidden() {
        keyboardTop = -1
        repositionToolbar(defaultToolbarY())
        removePanel()
        panelOpen = false
    }

    // ---------------------------------------------------------------- toolbar

    private fun showToolbar(yPos: Int) {
        val view = LayoutInflater.from(this).inflate(R.layout.overlay_toolbar, null)
        toolbarView = view
        wm?.addView(view, toolbarParams(yPos))
        view.findViewById<LinearLayout>(R.id.btnAutoText).setOnClickListener { togglePanel() }
        view.findViewById<LinearLayout>(R.id.btnSync).setOnClickListener { doSync() }
    }

    private fun repositionToolbar(yPos: Int) {
        toolbarView?.let { wm?.updateViewLayout(it, toolbarParams(yPos)) }
    }

    private fun toolbarParams(yPos: Int) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        dp(48),
        overlayType(),
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 0
        y = yPos
    }

    // ------------------------------------------------------------------ panel

    private fun togglePanel() {
        if (panelOpen) {
            removePanel()
            panelOpen = false
        } else {
            showPanel()
            panelOpen = true
        }
    }

    private fun showPanel() {
        val panel = LayoutInflater.from(this).inflate(R.layout.overlay_panel, null)
        panelView = panel
        wm?.addView(panel, panelParams())

        val adapter = TemplateAdapter { text -> insertText(text) }
        panel.findViewById<RecyclerView>(R.id.templateList).apply {
            layoutManager = LinearLayoutManager(context)
            this.adapter = adapter
        }

        val tabs = panel.findViewById<LinearLayout>(R.id.categoryTabs)
        val syncStatus = panel.findViewById<TextView>(R.id.syncStatus)

        panel.findViewById<TextView>(R.id.btnClose).setOnClickListener {
            removePanel()
            panelOpen = false
        }
        panel.findViewById<TextView>(R.id.btnSync).setOnClickListener {
            syncStatus.setTextColor(0xFF888888.toInt())
            scope.launch {
                val ok = withContext(Dispatchers.IO) { repo.sync().isSuccess }
                allCategories = withContext(Dispatchers.IO) { repo.getTemplates() }
                renderCategories(adapter, tabs)
                syncStatus.setTextColor(if (ok) 0xFF16A34A.toInt() else 0xFFDC2626.toInt())
            }
        }

        renderCategories(adapter, tabs)
    }

    private fun renderCategories(adapter: TemplateAdapter, tabs: LinearLayout?) {
        tabs ?: return
        tabs.removeAllViews()
        if (allCategories.isEmpty()) return
        adapter.submitList(allCategories.first().templates)
        allCategories.forEach { cat ->
            val tv = TextView(this).apply {
                text = cat.category.name
                textSize = 12f
                setPadding(dp(14), 0, dp(14), 0)
                setTextColor(0xFF1E293B.toInt())
                setBackgroundResource(R.drawable.btn_default_small)
                setOnClickListener { adapter.submitList(cat.templates) }
            }
            tabs.addView(tv)
        }
    }

    private fun removePanel() {
        panelView?.let { wm?.removeView(it) }
        panelView = null
    }

    private fun repositionPanel() {
        panelView?.let { wm?.updateViewLayout(it, panelParams()) }
    }

    private fun panelParams(): WindowManager.LayoutParams {
        val toolbarY = if (keyboardTop > 0) keyboardTop - dp(48) else defaultToolbarY()
        val panelH = dp(260)
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            panelH,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = toolbarY - panelH
        }
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    // ------------------------------------------------------------------ actions

    private fun doSync() {
        scope.launch {
            withContext(Dispatchers.IO) { repo.sync() }
            allCategories = withContext(Dispatchers.IO) { repo.getTemplates() }
        }
    }

    private fun insertText(text: String) {
        if (!SamaQuAccessibility.paste(text, this)) {
            getSystemService(ClipboardManager::class.java)
                ?.setPrimaryClip(ClipData.newPlainText("samaqu", text))
            Toast.makeText(this, getString(R.string.toast_copied_paste), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        instance = null
        scope.cancel()
        toolbarView?.let { runCatching { wm?.removeView(it) } }
        removePanel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "samaqu"
        private const val NOTIF_ID = 1

        @Volatile
        private var instance: OverlayService? = null

        fun getInstance(): OverlayService? = instance

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, OverlayService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, OverlayService::class.java))
        }

        fun isRunning(): Boolean = instance != null
    }
}
