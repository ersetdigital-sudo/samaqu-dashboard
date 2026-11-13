package com.samaqu.keyboard.network

/**
 * Default Supabase endpoint used when the user has not saved anything in Settings.
 *
 * Fill these two values in once and the whole app (catalogue and Auto Text sync) starts
 * talking to Supabase without any further setup. They are also editable at runtime from
 * Settings.
 *
 * Both values come from Supabase Dashboard > Project Settings > API:
 *   PROJECT_URL -> "Project URL"        e.g. https://abcdefgh.supabase.co
 *   ANON_KEY    -> "anon public" key    (safe to ship: it is a public key)
 *
 * The default is the store website's own project (`SAMAQU WEBSITE`), so the keyboard sees the
 * same products, prices and stock as the shop.
 */
object SupabaseConfig {

    /** Supabase project URL, no trailing slash needed (normalised by [RetrofitClient]). */
    const val PROJECT_URL = "https://bzoitgivmozhwpuomuhj.supabase.co"

    /**
     * Supabase "anon public" API key (the legacy JWT form, which the app sends as both
     * `apikey` and `Authorization: Bearer`). Row Level Security decides what it may read:
     * `products`, `product_variants`, `jenis_kain`, `payment_methods`, `categories` and
     * `templates` are readable; `orders` and writes to `templates` need an admin login.
     */
    const val ANON_KEY =
        "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImJ6b2l0Z2l2bW96aHdwdW9tdWhqIiwicm9sZSI6ImFub24iLCJpYXQiOjE3OTI3MTI3MTEsImV4cCI6MjEwODI4ODcxMX0.oaeio-kSZPtXhh1w24rFmlQw44DVg9b02wPujY6oAhk"

    /**
     * The previous project this keyboard used to read (a stale copy of the store's data). A
     * phone that saved these in Settings is treated as "not configured" so it picks up the
     * new defaults instead of staying on the old catalogue.
     */
    val LEGACY_URLS: Set<String> = setOf("https://zympqqmrygldagpazvse.supabase.co")

    val LEGACY_ANON_KEYS: Set<String> = setOf(
        "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Inp5bXBxcW1yeWdsZGFncGF6dnNlIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODQ5ODQzMTYsImV4cCI6MjEwMDU2MDMxNn0.C5vZC_6_6TqTB4cqGtTBaBMahIY6UQ2ZNSLSEk9I09o"
    )
}
