package com.samaqu.keyboard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.samaqu.keyboard.data.Prefs
import com.samaqu.keyboard.notify.OrderPoller
import com.samaqu.keyboard.overlay.OverlayService

/**
 * On boot: reschedule order polling, and restart the floating overlay if the
 * user had it enabled (and still holds the overlay permission).
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val prefs = Prefs(ctx)
        if (prefs.supabaseUrl.isNotBlank() && prefs.supabaseAnonKey.isNotBlank()) {
            OrderPoller.schedule(ctx)
        }
        if (prefs.overlayEnabled && Settings.canDrawOverlays(ctx)) {
            runCatching { OverlayService.start(ctx) }
        }
    }
}
