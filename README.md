# SAMAQU Keyboard

An Android input method for a sales/CS team: canned replies, an invoice builder that
pulls product names and prices from a catalogue, J&T shipping quotes, pending orders,
emoji, and a quick calculator reached by holding **Enter**.

> **Start with [`NOTES.md`](NOTES.md)** if you are about to change anything. It holds the
> context that is not visible from the source: the live backend's real schema, the open
> security findings, the decision blocking the invoice module, the rules behind the panel
> system, and which parts have never been run on a device.

### Install without building

A prebuilt APK of the shipped flavor is committed under [`release/`](release/) —
`SAMAQU-Lite-v1.0.1.apk` (the debug build, ~10 MB — same code as release, see
[NOTES.md](NOTES.md) §8). Install that to try the app; build from source to change it.

| | |
|---|---|
| Package | `com.samaqu.keyboard.lite` (install this flavor) |
| minSdk / targetSdk | 26 / 34 |
| Language | Kotlin, Gradle Kotlin DSL |
| Backend | Supabase (PostgREST) read directly from the app — see *Configuration* |

---

## Build

Requires **JDK 17** and an **Android SDK with platform 34**. The Gradle wrapper is
checked in, so the first build downloads Gradle 8.7 by itself; nothing else to install.

```bash
# macOS / Linux
JAVA_HOME=/path/to/jdk-17 ./gradlew assembleLiteDebug testLiteDebugUnitTest

# Windows (Git Bash)
JAVA_HOME=/c/path/jdk-17 ./gradlew.bat assembleLiteDebug testLiteDebugUnitTest
```

Output: `app/build/outputs/apk/lite/debug/app-lite-debug.apk`

> There is no `local.properties` in the repo — point `sdk.dir` at your Android SDK, or
> set `ANDROID_HOME`. Gradle will recreate the file.

### Flavors

`prod` (release), `dev` (`.dev` suffix), and **`lite`** — the one shipped. Lite strips
`SYSTEM_ALERT_WINDOW`, `FOREGROUND_SERVICE`, `RECEIVE_BOOT_COMPLETED`, the accessibility
service, the overlay and the boot receiver.

`testDebugUnitTest` is **ambiguous** with three flavors; always name one, e.g.
`testLiteDebugUnitTest`.

### Tests

Pure-JVM tests, no emulator:

```bash
./gradlew testLiteDebugUnitTest
# report: app/build/reports/tests/testLiteDebugUnitTest/index.html
```

---

## Where things are

```
app/src/main/java/com/samaqu/keyboard/
├── ime/        SamaQuIME (the IME), SamaQuKeyboardView (custom key faces)
├── network/    Supabase (RetrofitClient/ApiService) and the store API (StoreClient)
├── data/       Room cache, Prefs, repositories
├── ui/         app screens: dashboard, settings, template manager, adapters
├── notify/     order polling notifications
└── util/       SamaQuText and CalculatorEngine — pure Kotlin, unit-tested
```

Panels are owned by `SamaQuIME.showPanel()`. Full-mode panels (emoji, calculator, product
picker) take the letter deck's place; everything else stacks above it with the deck
visible, because its fields are typed with these very keys.

---

## Configuration

Backend credentials live in `network/SupabaseConfig.kt`:

- `PROJECT_URL` — the Supabase project URL. Since 2026-11-13 this is the store website's own
  project, `SAMAQU WEBSITE` (`bzoitgivmozhwpuomuhj`), so the keyboard shows the same products,
  prices and stock as the shop. A phone that saved the previous project's URL in Settings is
  moved to the new default automatically.
- `ANON_KEY` — the **anon** key. It is a public key by design and is also present in the
  shipped APK and in the store's JavaScript bundle; what actually gates access is Row
  Level Security, not this key.

**Never commit a `service_role` key or any server secret to this repository.**

With the anon key the keyboard can **read** `products`, `product_variants`, `jenis_kain`
(fabric codes), `payment_methods` (bank accounts printed on invoices), `categories` and
`templates`. Editing templates and reading `orders` need an admin login, which the app does
not have yet — see [`NOTES.md`](NOTES.md) §2. `supabase_setup.sql` at the repo root belongs to
the previous project and is kept for reference.

---

## Not in this repository

- `local.properties` — machine-specific SDK path (regenerated locally)
- `app/build`, `.gradle` — build output
- `.apk` files — build artifacts
- The JDK / Gradle / Android SDK toolchain — each machine provides its own

---

## Known limits

- Written and unit-tested, **not** exercised on a physical device: the keyboard's runtime
  behaviour (panel switching, long-press Enter, typing into its own fields) still needs a
  pass on a real phone.
- On the current project the keyboard writes nothing: `templates` / `categories` are
  read-only for the anon key, so Auto Text can be read but not edited from the app. The Pending
  panel and order notifications also show nothing until the app gets an admin login.
- The web dashboard in `web-dashboard/` still writes templates to the previous project, so
  its edits no longer reach the keyboard.
- The invoice is built as text and sent to the chat; it is not stored (see `NOTES.md` §5).
