# NOTES.md — read this before changing anything

This is **not a greenfield project**. The code compiles and its unit tests pass, but a
large part of what matters lives outside the source: a live Supabase project, a live
store website, and a set of decisions that were taken (or deliberately deferred) before
you got here. This file is that context.

---

## 0. Latest work (2026-11-13) — start here

The invoice builder was rebuilt and the app moved to the store's own database. Read these two
files first; they hold the reasoning, the exact invoice format and the device checklist:

- Design: `docs/superpowers/specs/2026-11-13-invoice-multi-produk-design.md`
- Plan that was executed: `docs/superpowers/plans/2026-11-13-invoice-multi-produk.md`

What changed, in short: the app reads `SAMAQU WEBSITE` (`bzoit…`, see §2); the invoice is
multi-product and printed in the team's own format (`InvoiceFormatter`); customer data can be
filled from a pasted message (`CustomerInfoParser`, the "Tempel format" button); numeric fields
get a digits-only keypad (`numpad*.xml`); money fields show thousands dots (`MoneyInput`);
product lines are saved read-only rows with an "Ubah" button; the toolbar hides while a panel is
open.

State right now:

- 171 JVM tests are green. The code was written without a phone attached; the **owner has run
  the APK on their own phone** and an invoice came out in the right shape, but panel layout and
  typing feel were only judged from their screenshots. Work through the "Cek manual di HP" list
  in the design doc before calling anything done.
- Cek Ongkir: the owner reported it "didn't work"; their screenshot showed empty Kecamatan/Kota
  fields and the fixed 1000 g default weight. It now pre-fills the district and city from the
  invoice address (`AreaParser`) and the weight from the products (`InvoiceDraft.totalWeightGram`,
  grams, from `weight` on products and variants). The store endpoint itself was verified with
  curl: it returns ECO and EZ when the district supports ECO, only EZ otherwise. Still worth one
  confirmation on the phone.
- Not built from the owner's reference app (Selly): invoice history, CRM, a separate "RINCIAN"
  breakdown. Those need the storage decision in §5.
- Deferred review minors: panel height may hide Alamat without scrolling; fields take focus on
  touch-down so a scroll starting on a number field flips the deck; picking the same product
  again keeps an edited price; no max length on a price field; Settings Save freezes the current
  default closing sentence.
- `release/SAMAQU-Lite-v1.0.1.apk` was refreshed from this state (same file name, still
  `versionName 1.0.1` / `versionCode 2`, debug build, ~7 MB); it installs over the older one.

---

## 1. What actually works, and what has never been run

| | State |
|---|---|
| Build + 114 JVM unit tests | ✅ green (`./gradlew assembleLiteDebug testLiteDebugUnitTest`) |
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
│  RetrofitClient   │  /rest/v1/...         │ project `SAMAQU WEBSITE`     │
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
- `supabase_setup.sql` (repo root) is the idempotent script for the tables this app owned in
  the **previous** project (see the update below); it is kept for reference.

### Update 2026-11-13: the keyboard now reads the store's own project

Until this date the keyboard read a **separate, older Supabase project**
(`zympqqmrygldagpazvse`: 111 products, 547 variants, prices that had drifted from the shop).
It now defaults to the store website's project, **`SAMAQU WEBSITE`
(`bzoitgivmozhwpuomuhj`)**: 121 products, 587 variants. `Prefs` treats a URL or key saved in
Settings that equals the old project's as "not saved" (`SavedSettings.resolve`), so phones
that stored the old default move over by themselves; a URL typed on purpose is left alone.

What the anon key can do on the new project (read from `pg_policies`, 2026-11-13):

| Tables | anon read | anon write |
|---|---|---|
| `products`, `product_variants`, `jenis_kain`, `payment_methods`, `categories`, `templates` | yes | **no** — admin login only |
| `orders`, `order_items` | **no** policy for anon | no |
| `store_settings` | yes (`Public read store_settings`) | no |

Consequences you should know about:

- **Editing templates from the keyboard stopped working.** Auto Text still reads them.
- **`web-dashboard/index.html` and the root `index.html` still point at the old project** and
  write templates there, so edits made in that dashboard no longer reach the keyboard until
  the dashboard is moved or given an admin login. Not changed here on purpose.
- **Pending panel and the order-notification poller read `orders`**, which the anon key cannot
  read on the new project: both show nothing until the app gets an admin login (§5, option C).
- The fabric code printed on invoices (`B-01`, `B-02`, `A-02`, `C.01`, `B02`) is the `name` of the
  `jenis_kain` row a product points at through `products.jenis_kain_id`. `products.kain` is
  empty. The bank accounts on invoices come from `payment_methods` (`method_type = 'bank'`,
  `is_active`), cached in `Prefs.paymentText` after each catalogue sync.

---

## 3. Live schema — probed, not assumed

Columns below were read from the running project, not guessed. Re-probe before relying
on them; the store's website is developed separately and can change them.
**The row counts and the "anon has full CRUD" claims in this section describe the previous
project; the update at the end of §2 describes the current one.**

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

> Items 2–4 were verified on the previous project. On the current one (§2) `orders` and
> `order_items` have no anon policy and `templates` / `categories` are no longer anon-writable.
> A `Public read store_settings` policy still exists there, so item 1 needs re-checking.

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

The Invoice Builder produces a multi-product invoice as **text** that is committed into the
chat, in the format the sales team already used by hand (see
`docs/superpowers/specs/2026-11-13-invoice-multi-produk-design.md`). Nothing is stored, no
status exists, and the dashboard has no invoice module. Three options were laid out:

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
- **Room is at version 4** with real migrations (`MIGRATION_1_2`, `MIGRATION_2_3`, `MIGRATION_3_4`),
  the first two checked character-for-character against the schema Room generates. v4 adds
  `weight` (grams) to the product and variant caches with `ALTER TABLE ... ADD COLUMN ... NOT NULL
  DEFAULT 0` and `@ColumnInfo(defaultValue = "0")`; **this migration has not run on a device yet**,
  so if the keyboard crashes right after an upgrade, look here first. Adding a table means
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
  `app/build/outputs/apk/lite/debug/app-lite-debug.apk` (~7 MB since 2026-11-13), because that is the
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
