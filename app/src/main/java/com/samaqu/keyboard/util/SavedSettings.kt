package com.samaqu.keyboard.util

/** Pure helper (no Android dependencies) for reading values saved in Settings. */
object SavedSettings {

    /**
     * The value to use for a setting: [default] when nothing usable is saved, or when the saved
     * value is one of the [legacy] values the app shipped with earlier. Anything else was typed
     * on purpose and is kept untouched.
     *
     * Legacy values are compared ignoring surrounding spaces and a trailing slash.
     */
    fun resolve(saved: String?, default: String, legacy: Set<String>): String {
        val value = saved?.trim().orEmpty()
        if (value.isEmpty()) return default
        val normalised = value.trimEnd('/')
        return if (legacy.any { it.trim().trimEnd('/') == normalised }) default else saved!!
    }
}
