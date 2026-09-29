# NOTES.md — read this before changing anything

This is **not a greenfield project**. The code compiles and its unit tests pass, but a
large part of what matters lives outside the source: a live Supabase project, a live
store website, and a set of decisions that were taken (or deliberately deferred) before
you got here. This file is that context.

---

## 1. What actually works, and what has never been run

| | State |
|---|---|
| Build + 41 JVM unit tests | ✅ green (`./gradlew assembleLiteDebug testLiteDebugUnitTest`) |
| QWERTY / shift / emoji / auto text / pending / ongkir / invoice / dashboard | ✅ written, **never run on a phone** |
| Quick Calculator (hold **Enter**) | ✅ written, **never run on a phone** |
| Product picker + size/stock step | ✅ written, **never run on a phone** |
| Product picker hiding the QWERTY deck | ✅ written, **never run on a phone** |
| Supabase reads and writes | ✅ **verified against the live project with curl** |
| J&T shipping quote endpoint | ✅ **verified against the live endpoint with curl** |

**There is no device or emulator in the authoring environment.** `adb devices` is empty.
Nothing above has been exercised by a human tap. If you have a device, install
`release/SAMAQU-Lite-v1.0.1.apk` and run the list in §9 first — that is the highest value
work available, and it is the one gap no amount of reading closes.

---

## 2. Backend: there are two, and no server of your own

```
┌───────────────────┐   anon key (in APK)   ┌──────────────────────────────┐
│  SAMAQU keyboard  │ ────────────────────► │ Supabase PostgREST           │
│  RetrofitClient   │  /rest/v1/...         │ project `samaqu`             │
└───────────────────┘                       └──────────────────────────────┘

┌───────────────────┐   NO key of ours      ┌──────────────────────────────┐
│  SAMAQU keyboard  │ ────────────────────► │ www.samaqu.id  (Next.js,     │
│  StoreClient      │  /api/...             │ Vercel) — holds J&T creds    │
└───────────────────┘                       └──────────────────────────────┘
```

- **Supabase credentials** live in `network/SupabaseConfig.kt` (`PROJECT_URL`, `ANON_KEY`).
  They are overridable at runtime in Settings → `Prefs.supabaseUrl` / `supabaseAnonKey`.
- **Never commit a `service_role` key or any server secret.** The anon key is public by
  design (it is in the APK and in the store's JavaScript bundle); RLS, not the key, is
  what gates access.
- `supabase_setup.sql` (repo root) is the idempotent script for the tables this app owns.

---

## 3. Live schema — probed, not assumed

Columns below were read from the running project, not guessed. Re-probe before relying
on them; the store's website is developed separately and can change them.

**Owned by this app (anon has full CRUD):**

| Table | Columns |
|---|---|
| `categories` | `id int, name, display_order, created_at` |
| `templates` | `id int, category_id, content, display_order, created_at` |

**Owned by the store website (read-only from here):**

| Table | Columns that matter |
|---|---|
| `products` | `id` (slug, e.g. `thobe-navy-bayati`), `name, category, series, kain, price, colors, image` — **61 rows** |
| `product_variants` | `id, product_id, color, size, stock, price_override, hex, display_order, base_product_id, base_size` — **305 rows, exactly 5 sizes (XS–XL) per product, 223 of them stock 0**. `price_override` is null on every row today |
| `orders` | `id` (uuid), `order_number, customer_name, customer_email, customer_whatsapp, shipping_*, payment_method, subtotal, discount, total, status, awb_no, jnt_order_id, weight, created_at, updated_at` |
| `order_items` | `id, order_id, product_id, product_name, product_image, color, size, quantity, price, customer_price, minimum_price, series, kain` |

Notes that matter:

- `order_items` **already snapshots** product name / series / kain / price at order time.
  That is the anti-drift property an invoice line needs; do not duplicate it.
- `orders.status` values seen: `pending`, `diproses`, `selesai`, `dibatalkan`.
- **There is no `invoices` table**, and **no payment columns** anywhere
  (`paid_at`, `amount_paid`, `updated_by` do not exist).
- Stock lives in `product_variants.stock`, **not** in `products`.
- Tables also present (untouched by this app): `store_settings`, `category_images`,
  `testimonials`, `vouchers`, `customers`, `admins`, `destination_cache`, …

---

## 4. Security findings (pre-existing, still open)

Verified with the anon key. **Nothing here was introduced by this app**, but nothing has
been fixed either:

1. **`store_settings` is readable by anon** and contains
   `rajaongkir_api_key` — a paid third-party API key. **It should be rotated** (it is
   considered permanently leaked) and the read grant narrowed.
   A `GRANT SELECT (col, …)` narrowing statement is ready and was deliberately **not**
   executed — the owner has not approved touching production.
2. **`orders` is readable by anon** — customer names, emails, WhatsApp numbers and
   addresses.
3. **`orders` and `order_items` accept anon INSERT** (that is how the website's checkout
   works), so anyone with the APK can create rows in the store's order system.
4. `categories` / `templates` are anon-writable **on purpose** — they are canned chat
   replies, no customer data. This was an explicit, accepted decision.

The keyboard has **no user identity**: no Supabase Auth, no login. Any design that needs
authorization (see §5) has to add it; a public anon key cannot be treated as permission.

---

## 5. Deferred: where invoices live (decide before building it)

The Invoice Builder currently only produces **text** that is committed into the chat.
Nothing is stored, no status exists, and the dashboard has no invoice module. Three
options were laid out:

| | Option | Cost |
|---|---|---|
| **A** | Build on `orders` + `order_items` (one source of truth with the store) | 🔴 anon may **INSERT but not UPDATE** → a `paid` status is impossible from the keyboard without a new policy; keyboard rows become real store orders |
| **B** | New `invoices` tables owned by this app, anon CRUD | 🔴 second invoice database (against the goal), and customer data becomes readable by anyone with the APK |
| **C** | Backend endpoint on `www.samaqu.id` with `service_role` kept server-side + admin login in the app | 🔴 needs changes to the store repository and auth in the app |

**Status: undecided — the owner was asked and chose to build other things first.**

Consequence: the payment-status migration (`paid_at`, `amount_paid`, an
`invoice_payments` history, `updated_by`) was **written in concept and deliberately not
run**, because its shape depends on A vs B vs C. Do not run it (or invent one) until that
choice is made.

---

## 6. Panel architecture (read before touching `SamaQuIME`)

`showPanel()` is the only thing allowed to decide what is visible.

```kotlin
replacesDeck(panel)  // emoji, calculator, product picker take the letter deck's place
deckIsHidden()       // derived from the current panel + focusedField
```

Rules that exist for reasons:

1. **Only one panel at a time.** Emoji, calculator and the product picker replace the
   QWERTY deck; every other panel stacks above it with the deck visible, because its
   fields are typed with those very keys.
2. **The product picker is the exception that proves the rule.** It is full-mode, but
   while its search field is focused the deck comes **back** (the search is typed with
   these keys) and the panel shrinks to `(available − deckHeight)`. The deck leaves again
   when the field is released: Enter, a category chip, scrolling the results, picking a
   product, or switching panels. Both heights leave ≥160dp of the app visible.
3. **A released field must stop receiving key taps.** `releaseFocusedField()` restores the
   idle border and re-derives the layout; forgetting it means the keyboard types into an
   invisible `EditText`.
4. **`showProductListStep()` runs on `hideAllPanels()`** so the picker never reopens on a
   stale size grid.
5. The calculator and emoji panels must never show the deck — two keyboards stacked is
   the original bug this design replaced.

---

## 7. Invariants — things that must keep working

- **Supabase read paths**: `getTemplates`, `getOrders`, `getProducts`, `getVariants`.
  They are verified live. A wrong `select=` column fails at runtime with PostgREST 42703
  and no compile error — re-probe the schema rather than assuming it.
- **J&T endpoints** on `www.samaqu.id`: `/api/shipping/jnt-cost` and `/api/jnt/track`.
  They are public and hold no key for us; **do not add credentials to the app**, and do
  not change them without the store website's owner.
- **`orders` / `products` stay read-only** from here.
- **Room is at version 3** with real migrations (`MIGRATION_1_2`, `MIGRATION_2_3`), both
  checked character-for-character against the schema Room generates. Adding a table means
  adding a migration: the destructive fallback exists but wipes the Auto Text cache.
- **Tap Enter must stay a newline.** Only the 400ms hold opens the calculator, and the
  release that follows it is swallowed by a timestamp (`ENTER_LONG_PRESS_TAP_WINDOW_MS`),
  never by disabling the tap path.

---

## 8. Toolchain gotchas

- `./gradlew testDebugUnitTest` is **ambiguous** (three flavors) — always
  `testLiteDebugUnitTest`.
- Flavors: `prod`, `dev` (`.dev`), **`lite`** (shipped). Lite strips the accessibility
  service, overlay, boot receiver and their permissions.
- **Install the `lite` flavor** — it is the only one built here
  (`release/SAMAQU-Lite-v1.0.1.apk`).
- `local.properties` is gitignored and points at this machine's SDK; regenerate it.
- Line endings: `.gitattributes` normalises to LF in the repository.
- `release/SAMAQU-Lite-v1.0.1.apk` is committed **on purpose** so a clone has something to
  install without building. It is deliberately the **debug** build, byte-identical to
  `app/build/outputs/apk/lite/debug/app-lite-debug.apk` (~10.6 MB), because that is the
  APK the team actually sideloads — a size mismatch reads as "wrong version" and gets the
  shipped file distrusted. The release build (`.../lite/release/app-lite-release.apk`,
  ~5.9 MB) carries the same code (`isMinifyEnabled = false`) and signs with the same
  debug keystore, so either one installs over the other. To refresh the shipped APK:
  `cp app/build/outputs/apk/lite/debug/app-lite-debug.apk release/SAMAQU-Lite-v1.0.1.apk`
  and commit it deliberately — every refresh is ~11 MB in history forever.
- `sources/`, `poc-samaqu-keyboard/` and `samaqu-site-ref/` are **outside** this repo
  (decompiled reference, toolchain, and a read-only clone of the store site).

---

## 9. First things to test on a device

Run these before adding anything; they are the ones that cannot be checked by reading:

1. Hold **Enter** → calculator opens? Tap Enter still inserts a newline?
2. Calculator → `125000 × 3` → *Masukkan ke Chat* inserts `375000` and sends nothing?
3. Invoice → **Pilih** → search `navy` → three Thobe Navy rows with different prices?
4. Tap one → size grid with **real stock**? Pick `M` → name + price filled?
5. Picker opens with **no QWERTY**; tap the search field → keyboard returns *and* the
   search box plus results stay visible; tap a category chip → keyboard leaves again.
6. Auto Text, Ongkir, Pending, Dashboard, Sync and Emoji all still open.

If something fails, report **which panel, and whether the tapped key highlighted** — the
pressed face and the calculator share the same press callbacks, so that answer splits the
failure in half.
