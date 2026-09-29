package com.samaqu.keyboard.network

/**
 * Default Supabase endpoint used when the user has not saved anything in Settings.
 *
 * Fill these two values in once and the whole app (auto text sync, pending orders,
 * order notifications) starts talking to Supabase without any further setup.
 * They are also editable at runtime from Settings.
 *
 * Both values come from Supabase Dashboard > Project Settings > API:
 *   PROJECT_URL -> "Project URL"        e.g. https://abcdefgh.supabase.co
 *   ANON_KEY    -> "anon public" key    (safe to ship: it is a public key)
 */
object SupabaseConfig {

    /** Supabase project URL, no trailing slash needed (normalised by [RetrofitClient]). */
    const val PROJECT_URL = "https://zympqqmrygldagpazvse.supabase.co"

    /**
     * Supabase "anon public" API key. Row Level Security decides what it may read:
     * `categories`, `templates`, `orders` and `products` are read-only for this key.
     */
    const val ANON_KEY =
        "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Inp5bXBxcW1yeWdsZGFncGF6dnNlIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODQ5ODQzMTYsImV4cCI6MjEwMDU2MDMxNn0.C5vZC_6_6TqTB4cqGtTBaBMahIY6UQ2ZNSLSEk9I09o"
}
