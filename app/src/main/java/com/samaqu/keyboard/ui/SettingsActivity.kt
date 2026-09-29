package com.samaqu.keyboard.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.RadioGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.samaqu.keyboard.R
import com.samaqu.keyboard.data.Prefs
import com.samaqu.keyboard.notify.OrderPoller
import com.samaqu.keyboard.overlay.OverlayService

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        prefs = Prefs(this)

        val etSupabaseUrl = findViewById<TextInputEditText>(R.id.etSupabaseUrl)
        val etSupabaseAnonKey = findViewById<TextInputEditText>(R.id.etSupabaseAnonKey)
        val etDashboardUrl = findViewById<TextInputEditText>(R.id.etDashboardUrl)
        val rgKeySize = findViewById<RadioGroup>(R.id.rgKeySize)

        etSupabaseUrl.setText(prefs.supabaseUrl)
        etSupabaseAnonKey.setText(prefs.supabaseAnonKey)
        etDashboardUrl.setText(prefs.dashboardUrl)
        rgKeySize.check(
            when (prefs.keySize) {
                Prefs.KEY_SIZE_SMALL -> R.id.rbSmall
                Prefs.KEY_SIZE_LARGE -> R.id.rbLarge
                else -> R.id.rbNormal
            }
        )

        findViewById<MaterialButton>(R.id.btnSave).setOnClickListener {
            prefs.supabaseUrl = etSupabaseUrl.text.toString().trim()
            prefs.supabaseAnonKey = etSupabaseAnonKey.text.toString().trim()
            prefs.dashboardUrl = etDashboardUrl.text.toString().trim()
            prefs.keySize = when (rgKeySize.checkedRadioButtonId) {
                R.id.rbSmall -> Prefs.KEY_SIZE_SMALL
                R.id.rbLarge -> Prefs.KEY_SIZE_LARGE
                else -> Prefs.KEY_SIZE_NORMAL
            }

            if (prefs.supabaseUrl.isNotBlank() && prefs.supabaseAnonKey.isNotBlank()) {
                OrderPoller.schedule(this)
            } else {
                OrderPoller.cancel(this)
            }
            requestNotificationPermission()
            Snackbar.make(findViewById(android.R.id.content), R.string.settings_saved, Snackbar.LENGTH_LONG).show()
        }

        findViewById<MaterialButton>(R.id.btnEnableIME).setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }

        findViewById<MaterialButton>(R.id.btnSelectIME).setOnClickListener {
            val imm = getSystemService(InputMethodManager::class.java)
            imm?.showInputMethodPicker()
        }

        val btnOverlay = findViewById<MaterialButton>(R.id.btnOverlay)
        btnOverlay.setOnClickListener {
            if (OverlayService.isRunning()) {
                OverlayService.stop(this)
                prefs.overlayEnabled = false
                btnOverlay.setText(R.string.btn_overlay_on)
                Snackbar.make(findViewById(android.R.id.content), R.string.overlay_stopped, Snackbar.LENGTH_SHORT).show()
            } else if (!Settings.canDrawOverlays(this)) {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
                Snackbar.make(findViewById(android.R.id.content), R.string.overlay_permission_needed, Snackbar.LENGTH_LONG).show()
            } else {
                OverlayService.start(this)
                prefs.overlayEnabled = true
                btnOverlay.setText(R.string.btn_overlay_off)
                Snackbar.make(findViewById(android.R.id.content), R.string.overlay_started, Snackbar.LENGTH_SHORT).show()
            }
        }

        findViewById<MaterialButton>(R.id.btnDashboard).setOnClickListener {
            startActivity(Intent(this, DashboardActivity::class.java))
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    1001
                )
            }
        }
    }
}
