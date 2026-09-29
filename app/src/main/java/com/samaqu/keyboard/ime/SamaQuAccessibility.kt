package com.samaqu.keyboard.ime

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.samaqu.keyboard.overlay.OverlayService

/**
 * Used for two things:
 *  1. Report the keyboard's top Y position to [OverlayService] so the floating
 *     toolbar can sit right above the keyboard.
 *  2. Auto-paste text into the focused field (falls back to clipboard).
 */
class SamaQuAccessibility : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val windows = windows ?: return
        val kbWindow = windows.firstOrNull { it.type == AccessibilityWindowInfoTypeInputMethod }
        val overlay = OverlayService.getInstance()

        if (kbWindow != null) {
            val bounds = Rect()
            kbWindow.getBoundsInScreen(bounds)
            overlay?.onKeyboardShown(bounds.top)
        } else {
            overlay?.onKeyboardHidden()
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    companion object {
        private const val AccessibilityWindowInfoTypeInputMethod = 2

        @Volatile
        private var instance: SamaQuAccessibility? = null

        fun getInstance(): SamaQuAccessibility? = instance

        /**
         * Insert [text] into the currently focused editable field.
         * Returns false if there is no focused field / no permission.
         */
        fun paste(text: String, ctx: Context): Boolean {
            val svc = instance ?: return false
            val root: AccessibilityNodeInfo = svc.rootInActiveWindow ?: return false
            val node: AccessibilityNodeInfo =
                root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false

            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text
                )
            }
            if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                return true
            }

            // Fallback: put on clipboard and paste.
            val clip = ctx.getSystemService(ClipboardManager::class.java)
            clip?.setPrimaryClip(ClipData.newPlainText("samaqu", text))
            node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            return true
        }
    }
}
