# Invoice multi-produk + pindah ke database website — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Keyboard SAMAQU membaca katalog yang sama dengan website dan membuat invoice multi-produk yang keluarannya identik dengan format invoice yang dipakai tim.

**Architecture:** Format dan hitungan invoice ditaruh di Kotlin murni (`util/InvoiceFormatter.kt`) supaya bisa diuji di JVM. Lapisan data menambah kode kain (`jenis_kain`) dan rekening (`payment_methods`) ke sync yang sudah ada. Panel invoice di `SamaQuIME` dibangun ulang di atas `InvoiceDraft`, memakai picker produk dan panel ongkir yang sudah ada.

**Tech Stack:** Kotlin, Android IME (`KeyboardView`), Room, Retrofit + Gson, Supabase PostgREST, JUnit 4 (JVM), Gradle Kotlin DSL, JDK 17, minSdk 26.

**Spec:** `docs/superpowers/specs/2026-11-13-invoice-multi-produk-design.md`

## Global Constraints

- Semua perintah Gradle menyebut flavor: `testLiteDebugUnitTest`, `assembleLiteDebug`. Set `JAVA_HOME` ke JDK 17 dan `ANDROID_HOME` ke `%LOCALAPPDATA%\Android\Sdk`. Jangan commit `local.properties`.
- Keyboard hanya membaca Supabase. Dilarang menaruh `service_role` atau rahasia server di repo. Satu-satunya tulis yang ada tetap `templates`/`categories`.
- Format uang di invoice: `Rp374.000` (**tanpa spasi** setelah `Rp`, pemisah ribuan titik). Fungsi `SamaQuText.rupiah(Long)` sudah menghasilkan `374.000`.
- Teks yang tampil ke pengguna ditaruh di `res/values/strings.xml`, berbahasa Indonesia.
- Fungsi `SamaQuText.variantLabel`, `productLabel`, `keyboardInvoice`, `fullInvoice` dan test-nya **tidak diubah** (dipakai picker dan tab Invoice di dashboard).
- Jangan menyentuh `web-dashboard/` dan `index.html`. Jangan meng-commit APK.
- Tidak ada perangkat atau emulator di lingkungan ini: tampilan panel dan keypad angka tidak bisa diverifikasi di sini. Task UI berakhir pada build hijau dan daftar cek manual.

## Review Focus

1. Produk tanpa series atau tanpa kain: label tidak boleh punya spasi ganda atau `()` kosong. (Task 2)
2. Qty 0, negatif, atau harga kosong: qty ditahan minimal 1, harga 0 tetap dihitung 0. (Task 1)
3. Nama pembeli kosong: salam jadi `Kak`, baris `Nama:` dilewati. (Task 1)
4. Estimasi ongkir kosong, atau sudah memuat kata `hari`: kata tidak diulang. (Task 1)
5. HP yang sudah menyimpan URL/key lama di Settings: tetap membaca database lama tanpa migrasi. (Task 4)
6. Gagal mengambil `payment_methods` tidak boleh menggagalkan sync katalog atau menghapus rekening tersimpan. (Task 3)

---

### Task 1: Pembuat teks invoice (murni)

**Files:**
- Create: `app/src/main/java/com/samaqu/keyboard/util/InvoiceFormatter.kt`
- Test: `app/src/test/java/com/samaqu/keyboard/util/InvoiceFormatterTest.kt`

**Interfaces:**
- Consumes: `com.samaqu.keyboard.network.PaymentMethod` (dibuat Task 3; di Task ini definisikan di test sebagai `PaymentMethod(...)` hanya setelah Task 3, jadi test `paymentText` ditulis di Task 3).
- Produces:
  - `data class InvoiceItem(val label: String, val qty: Int, val unitPrice: Long)` dengan `val lineTotal: Long`.
  - `data class InvoiceShipping(val courier: String = "", val service: String = "", val etd: String = "", val cost: Long = 0)`.
  - `data class InvoiceDraft(val buyer: String = "", val phone: String = "", val address: String = "", val items: List<InvoiceItem> = emptyList(), val shipping: InvoiceShipping? = null)` dengan `val subtotal: Long`, `val total: Long`, `fun withItem(item: InvoiceItem): InvoiceDraft`, `fun withoutItem(index: Int): InvoiceDraft`, `fun withQty(index: Int, qty: Int): InvoiceDraft` (qty ditahan minimal 1; indeks di luar rentang mengembalikan draft yang sama), `fun withUnitPrice(index: Int, price: Long): InvoiceDraft`.
  - `object InvoiceFormatter { const val DEFAULT_CLOSING: String; fun rupiah(value: Long): String; fun shippingLine(s: InvoiceShipping): String; fun format(draft: InvoiceDraft, paymentText: String, closing: String): String }`.

- [ ] **Step 1: Tulis test yang gagal.** Di `InvoiceFormatterTest`, nama dan assertion:
  - `` `format reproduces the owner's invoice exactly` ``: draft `buyer="Muhammad Nasir"`, `phone="0852-6041-2200"`, `address="Jl. Tgk. Wahab Dahlawi (depan Mushalla Babuttaqwa), Dusun Ujong Tunong, Desa Paya Punteut, Kec. Muara Dua, Kota Lhokseumawe, Aceh 24352"`, satu item `InvoiceItem("Thobe Broken White Imalah (kain B-01), size M", 1, 374_000)`, shipping `InvoiceShipping("J&T", "ECO", "3-7", 40_500)`, `paymentText="Bank BSI 7153280058\na.n. Ardo Babus Abdullah"`, `closing=InvoiceFormatter.DEFAULT_CLOSING`. Hasil harus sama persis dengan blok invoice pada bagian "Latar belakang" spec (baris kosong, `Rp374.000`, `Rp40.500`, `Rp414.500`), tanpa baris kosong di akhir.
  - `` `rupiah has no space after Rp` ``: `rupiah(374000) == "Rp374.000"`, `rupiah(0) == "Rp0"`.
  - `` `quantity above one multiplies the line` ``: item qty 2 harga 374_000 menghasilkan baris `2 x … - Rp748.000` dan `total` 748_000.
  - `` `blank buyer greets Kak and skips the Nama line` ``.
  - `` `blank phone and address are skipped` ``.
  - `` `no shipping skips the ongkir line` `` dan total = subtotal.
  - `` `shippingLine does not repeat hari` ``: etd `"3-7 hari"` → `Ongkir J&T ECO (estimasi 3-7 hari): Rp40.500`; etd kosong → `Ongkir J&T ECO: Rp40.500`; courier dan service kosong → `Ongkir: Rp40.500`.
  - `` `blank payment text skips the transfer section` `` dan `` `blank closing is omitted` ``.
  - `` `withQty clamps to one and ignores a bad index` ``, `` `withoutItem removes by index` ``, `` `withUnitPrice changes only that line` ``.

- [ ] **Step 2: Jalankan dan pastikan gagal.** `./gradlew testLiteDebugUnitTest --tests "*InvoiceFormatterTest"` → gagal kompilasi (kelas belum ada).

- [ ] **Step 3: Implementasikan** `InvoiceFormatter.kt` sesuai Interfaces. `rupiah` = `"Rp" + SamaQuText.rupiah(value)`. `DEFAULT_CLOSING = "Kalau sudah transfer, kirim bukti transfernya di sini ya Ka."`. Urutan bagian `format`: salam, baris kosong, `INVOICE PESANAN SAMAQU`, `Nama:`/`No. WA:`/`Alamat:` (yang kosong dilewati), baris kosong, `Pesanan:`, baris item, baris ongkir bila ada, baris kosong, `Total: …`, lalu — hanya bila `paymentText` tidak kosong — baris kosong + `Pembayaran via transfer:` + teksnya, lalu — hanya bila `closing` tidak kosong — baris kosong + `closing`. Salam: `Berikut invoice pesanannya ya Ka {buyer}.`, atau `Berikut invoice pesanannya ya Kak.` bila nama kosong. Spasi di awal/akhir semua teks masukan dipangkas.

- [ ] **Step 4: Jalankan test** perintah Step 2 → semua lulus.

- [ ] **Step 5: Commit**
```bash
git add app/src/main/java/com/samaqu/keyboard/util/InvoiceFormatter.kt app/src/test/java/com/samaqu/keyboard/util/InvoiceFormatterTest.kt
git commit -m "feat: add pure invoice formatter and draft model"
```

---

### Task 2: Label baris produk di invoice

**Files:**
- Modify: `app/src/main/java/com/samaqu/keyboard/util/SamaQuText.kt` (tambah satu fungsi, jangan ubah yang ada)
- Test: `app/src/test/java/com/samaqu/keyboard/util/SamaQuTextTest.kt`

**Interfaces:**
- Consumes: `CachedProduct`, `CachedVariant` (sudah ada).
- Produces: `fun SamaQuText.invoiceLineLabel(product: CachedProduct, variant: CachedVariant?): String`.

- [ ] **Step 1: Tulis test yang gagal** (tambahkan ke `SamaQuTextTest`, memakai helper `product(...)`/`variant(...)` yang sudah ada di berkas itu):
  - `` `invoiceLineLabel joins name series kain and size` ``: produk `name="Thobe Broken White"`, `series="Imalah"`, `kain="B-01"`, varian `size="M"` → `"Thobe Broken White Imalah (kain B-01), size M"`.
  - `` `invoiceLineLabel without series or kain has no stray punctuation` ``: nama `"Kandora Navy"`, series dan kain kosong, varian `size="L"` → `"Kandora Navy, size L"`; tanpa varian → `"Kandora Navy"`.
  - `` `invoiceLineLabel keeps a real colour before the size` ``: warna `"Navy"` → `"… (kain B-01), warna Navy, size M"`; warna `"default"` dibuang.
  - `` `invoiceLineLabel ALL SIZE is written as a size` ``: `size="ALL SIZE"` → `…, size ALL SIZE`.

- [ ] **Step 2: Jalankan** `./gradlew testLiteDebugUnitTest --tests "*SamaQuTextTest"` → gagal (fungsi belum ada).

- [ ] **Step 3: Implementasikan** `invoiceLineLabel` di `SamaQuText`: nama + spasi + series (dipangkas), diikuti ` (kain X)` bila kain tidak kosong, lalu `, warna X` bila warna bukan `default`, lalu `, size X` bila ukuran tidak kosong. Tidak boleh ada spasi ganda.

- [ ] **Step 4: Jalankan test** perintah Step 2 → semua lulus, termasuk test lama.

- [ ] **Step 5: Commit**
```bash
git add app/src/main/java/com/samaqu/keyboard/util/SamaQuText.kt app/src/test/java/com/samaqu/keyboard/util/SamaQuTextTest.kt
git commit -m "feat: add invoice line label with kain and size"
```

---

### Task 3: Kode kain dan rekening dari database

**Files:**
- Modify: `app/src/main/java/com/samaqu/keyboard/network/Dtos.kt`, `app/src/main/java/com/samaqu/keyboard/network/ApiService.kt`, `app/src/main/java/com/samaqu/keyboard/data/ProductRepository.kt`, `app/src/main/java/com/samaqu/keyboard/data/Prefs.kt`, `app/src/main/java/com/samaqu/keyboard/util/InvoiceFormatter.kt`
- Test: `app/src/test/java/com/samaqu/keyboard/data/ProductMappingTest.kt` (baru), `app/src/test/java/com/samaqu/keyboard/util/InvoiceFormatterTest.kt`

**Interfaces:**
- Consumes: `Product`, `CachedProduct`, `Prefs` (ada).
- Produces:
  - `data class JenisKainRef(val name: String? = null)`; `Product` mendapat `@SerializedName("jenis_kain") val jenisKain: JenisKainRef? = null`.
  - `data class PaymentMethod(@SerializedName("method_type") val methodType: String? = null, @SerializedName("bank_name") val bankName: String? = null, @SerializedName("account_name") val accountName: String? = null, @SerializedName("account_number") val accountNumber: String? = null, @SerializedName("is_active") val isActive: Boolean = true)`.
  - `ApiService.getProducts()` memakai `select=id,name,category,series,kain,price,colors,image,jenis_kain(name)`; `ApiService.getPaymentMethods(): List<PaymentMethod>` untuk `rest/v1/payment_methods?select=method_type,bank_name,account_name,account_number,is_active&method_type=eq.bank&is_active=eq.true&order=display_order.asc`.
  - Top-level `internal fun Product.toCachedProduct(): CachedProduct` di `ProductRepository.kt` (logika `toCached` yang ada dipindah ke sini; kain = `jenisKain?.name` bila tidak kosong, selain itu `kain`, dipangkas).
  - `InvoiceFormatter.paymentText(methods: List<PaymentMethod>): String`.
  - `Prefs.paymentText: String` (default `""`).

- [ ] **Step 1: Tulis test yang gagal.**
  - `ProductMappingTest` `` `kain comes from the jenis_kain embed` ``: parse JSON `{"id":"thobe-broken-white-imalah","name":"Thobe Broken White ","series":"Imalah","kain":null,"price":374000,"colors":["default"],"jenis_kain":{"name":"B-01"}}` dengan `Gson().fromJson(..., Product::class.java)`; `toCachedProduct()` → `kain == "B-01"`, `name == "Thobe Broken White"`, `colors == ""`.
  - `` `kain falls back to the products column and then to empty` ``: tanpa `jenis_kain` dan `kain="A-02"` → `"A-02"`; keduanya kosong → `""`.
  - Di `InvoiceFormatterTest`: `` `paymentText writes bank, number and account name` `` → `"Bank BSI 7153280058\na.n. Ardo Babus Abdullah"`; `` `paymentText joins several banks and skips incomplete ones` `` (metode tanpa nomor rekening dilewati); `` `paymentText is empty when there is no bank` ``.

- [ ] **Step 2: Jalankan** `./gradlew testLiteDebugUnitTest --tests "*ProductMappingTest" --tests "*InvoiceFormatterTest"` → gagal.

- [ ] **Step 3: Implementasikan** DTO, query, `toCachedProduct`, `InvoiceFormatter.paymentText`, dan `Prefs.paymentText`. Ubah `ProductRepository.sync()`: ambil products dan variants seperti sekarang; ambil `getPaymentMethods()` dalam `runCatching` **terpisah** setelah cache katalog berhasil diganti, dan hanya jika berhasil serta menghasilkan teks tidak kosong tulis `prefs.paymentText`. Kegagalan payment methods tidak boleh menggagalkan `Result` sync dan tidak menghapus teks yang sudah tersimpan. Perbarui KDoc di `ApiService` (jumlah baris dan skema sudah berubah).

- [ ] **Step 4: Jalankan test** perintah Step 2 lalu `./gradlew testLiteDebugUnitTest` → semua lulus.

- [ ] **Step 5: Commit**
```bash
git add -A app/src
git commit -m "feat: read kain code and bank accounts from the store database"
```

---

### Task 4: Pindah ke project `bzoit…` dengan migrasi nilai lama

**Files:**
- Modify: `app/src/main/java/com/samaqu/keyboard/network/SupabaseConfig.kt`, `app/src/main/java/com/samaqu/keyboard/data/Prefs.kt`
- Create: `app/src/main/java/com/samaqu/keyboard/util/SavedSettings.kt`
- Test: `app/src/test/java/com/samaqu/keyboard/util/SavedSettingsTest.kt`

**Interfaces:**
- Produces: `object SavedSettings { fun resolve(saved: String?, default: String, legacy: Set<String>): String }`; `SupabaseConfig.LEGACY_URLS: Set<String>` dan `SupabaseConfig.LEGACY_ANON_KEYS: Set<String>`.

- [ ] **Step 1: Tulis test yang gagal** (`SavedSettingsTest`):
  - `` `null saved value uses the default` ``.
  - `` `blank saved value uses the default` ``.
  - `` `saved value equal to a legacy value uses the default` `` (cocokkan juga bila ada `/` di akhir atau spasi di tepi).
  - `` `saved value the owner typed on purpose is kept` ``.

- [ ] **Step 2: Jalankan** `./gradlew testLiteDebugUnitTest --tests "*SavedSettingsTest"` → gagal.

- [ ] **Step 3: Implementasikan** `SavedSettings.resolve` dan pakai di getter `Prefs.supabaseUrl` dan `Prefs.supabaseAnonKey`. Di `SupabaseConfig`: `PROJECT_URL = "https://bzoitgivmozhwpuomuhj.supabase.co"`; pindahkan URL dan key lama ke `LEGACY_URLS` / `LEGACY_ANON_KEYS`. Ambil anon key project `bzoitgivmozhwpuomuhj` lewat tool MCP Supabase `get_publishable_keys`; pilih key `anon` berbentuk JWT bila tersedia (aplikasi mengirimnya sebagai `apikey` **dan** `Authorization: Bearer`). **Verifikasi sebelum commit** dengan `curl` memakai kedua header itu: `GET {url}/rest/v1/products?select=id&limit=1` harus HTTP 200 dan `.../payment_methods?select=bank_name&limit=1` juga 200. Perbarui komentar KDoc `SupabaseConfig` (tabel mana yang read-only).

- [ ] **Step 4: Jalankan test** perintah Step 2 lalu `./gradlew testLiteDebugUnitTest` → lulus.

- [ ] **Step 5: Commit**
```bash
git add -A app/src
git commit -m "feat: default to the website Supabase project, migrate saved legacy values"
```

---

### Task 5: Kalimat penutup di Settings

**Files:**
- Modify: `app/src/main/java/com/samaqu/keyboard/data/Prefs.kt`, `app/src/main/java/com/samaqu/keyboard/ui/SettingsActivity.kt`, `app/src/main/res/layout/activity_settings.xml`, `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `InvoiceFormatter.DEFAULT_CLOSING` (Task 1).
- Produces: `Prefs.invoiceClosing: String` (getter mengembalikan `DEFAULT_CLOSING` bila belum pernah disimpan; nilai kosong yang sengaja disimpan dipertahankan sebagai kosong).

- [ ] **Step 1: Tambah `Prefs.invoiceClosing`** dengan kunci SharedPreferences `invoice_closing`; pembedaan "belum pernah disimpan" vs "disimpan kosong" memakai `sp.contains(...)`.
- [ ] **Step 2: Tambah kolom** `etInvoiceClosing` (label `Kalimat penutup invoice`, satu paragraf, boleh kosong) di `activity_settings.xml` di bawah kolom URL dashboard; muat dan simpan lewat tombol Simpan yang sudah ada di `SettingsActivity`. String di `strings.xml`.
- [ ] **Step 3: Verifikasi** `./gradlew assembleLiteDebug` → BUILD SUCCESSFUL. (Tampilan Settings dicek manual di HP.)
- [ ] **Step 4: Commit**
```bash
git add -A app/src
git commit -m "feat: editable invoice closing sentence in Settings"
```

---

### Task 6: Panel invoice multi-produk

**Files:**
- Modify: `app/src/main/res/layout/keyboard_main.xml` (blok `invoicePanel`), `app/src/main/res/values/strings.xml`, `app/src/main/java/com/samaqu/keyboard/ime/SamaQuIME.kt` (`setupInvoicePanel`, `applyProductToInvoice`, `useOngkir`, pembuat baris item)
- Test: tidak ada unit test baru (logika ada di Task 1); verifikasi = build + daftar cek manual di bawah.

**Interfaces:**
- Consumes: `InvoiceDraft`, `InvoiceItem`, `InvoiceShipping`, `InvoiceFormatter` (Task 1); `SamaQuText.invoiceLineLabel`, `SamaQuText.priceOf` (Task 2 / ada); `Prefs.paymentText`, `Prefs.invoiceClosing` (Task 3 / 5); `JntCostOption` (ada).
- Produces: id view di `invoicePanel`: `invBuyer`, `invPhone`, `invAddress`, `invItemsContainer` (LinearLayout tempat baris item), `btnPickProduct` (ada), `invShippingLabel` (TextView), `invOngkir` (EditText angka), `btnInvoiceOngkir` (pintasan ke panel Cek Ongkir), `invTotal` (TextView), `invBtnGenerate` (ada), `btnCloseInvoice` (ada). Id `invProduct`, `invQty`, `invPrice` dihapus bersama semua pemakaiannya.

- [ ] **Step 1: Ubah tata letak `invoicePanel`.** Tinggi panel tidak lagi dikunci 200dp: pakai tinggi yang lebih besar (mulai 280dp, disesuaikan agar `ScrollView` isi menggulir dan tombol Generate tetap terlihat di bawah di luar `ScrollView`). Setiap kolom diberi label teks tetap di atasnya (`Nama pembeli`, `No. WA`, `Alamat`, `Ongkir (Rp)`). Tambah `invItemsContainer` dan `invTotal`. Teks hint lama `1` / `0` dihapus.
- [ ] **Step 2: Tambah state `private var invoiceDraft = InvoiceDraft()`** di `SamaQuIME`. Fungsi `renderInvoiceItems()` mengosongkan `invItemsContainer` lalu menambahkan satu baris per item (label, tombol `−`, qty, tombol `+`, tombol hapus) dengan gaya seperti `addOngkirRow`; setiap perubahan memanggil `withQty` / `withoutItem` lalu `renderInvoiceItems()` dan `updateInvoiceTotal()`. Menyentuh harga satuan pada baris membuka kolom ketik harga kecil memakai mekanisme `focusedField` yang ada, lalu `withUnitPrice`.
- [ ] **Step 3: Ganti `applyProductToInvoice`** agar menambah item: `invoiceDraft = invoiceDraft.withItem(InvoiceItem(SamaQuText.invoiceLineLabel(product, variant), 1, SamaQuText.priceOf(product, variant)))`, lalu render dan `showPanel(invoicePanel)`. Memilih produk yang sama dan ukuran yang sama dua kali menaikkan qty item itu, bukan menambah baris baru (cocokkan dengan `label`).
- [ ] **Step 4: Ganti `useOngkir(option)`**: simpan `InvoiceShipping(option.courier, option.service, option.etd, option.cost)` ke `invoiceDraft`, isi `invOngkir` dengan `option.cost`, tampilkan `courier service • etd` di `invShippingLabel`. Bila pengguna mengubah angka `invOngkir` manual, `cost` diperbarui dan layanan lama dipertahankan.
- [ ] **Step 5: Ganti handler `invBtnGenerate`**: baca nama, WA, alamat, ongkir ke `invoiceDraft`, lalu `commit(InvoiceFormatter.format(invoiceDraft, Prefs(this).paymentText, Prefs(this).invoiceClosing))`, `hideAllPanels()`, dan **reset** `invoiceDraft` ke `InvoiceDraft()` beserta kolomnya. Bila `items` kosong, tampilkan `Toast` `Pilih minimal satu produk` dan jangan commit. Tombol `btnInvoiceOngkir` membuka panel Cek Ongkir.
- [ ] **Step 6: Perbarui daftar field fokus** di `setupInvoicePanel` ke `invBuyer`, `invPhone`, `invAddress`, `invOngkir`, dan pastikan `releaseFocusedField()`, `showPanel`, dan `setActiveToolbar` yang merujuk id lama tetap benar (cari dengan `grep -n "invProduct\|invQty\|invPrice" -r app/src`; hasilnya harus kosong).
- [ ] **Step 7: Verifikasi** `./gradlew testLiteDebugUnitTest assembleLiteDebug` → semua test lulus dan BUILD SUCCESSFUL.
- [ ] **Step 8: Tulis daftar cek manual** di bagian akhir `docs/superpowers/specs/2026-11-13-invoice-multi-produk-design.md` (judul `Cek manual di HP`): semua kolom terlihat berlabel tanpa menggulir ke atas; tambah 2 produk beda ukuran; ubah qty; hapus item; pakai ongkir dari Cek Ongkir; Generate menghasilkan teks yang sama dengan format; Total sama dengan hitungan manual; setelah kirim, form kosong.
- [ ] **Step 9: Commit**
```bash
git add -A app/src docs
git commit -m "feat: multi-product invoice panel"
```

---

### Task 7: Keypad angka untuk kolom angka

**Files:**
- Modify: `app/src/main/java/com/samaqu/keyboard/ime/SamaQuIME.kt`

**Interfaces:**
- Consumes: `focusedField`, `switchKeyboard(qwerty)`, `switchKeyboard(symbols)` (ada); id `invPhone`, `invOngkir` (Task 6).
- Produces: aturan: saat `focusedField` menjadi `invPhone` atau `invOngkir` (atau kolom harga satuan) dek aktif pindah ke `symbols` (yang memuat baris angka 1–0); saat menjadi kolom teks (`invBuyer`, `invAddress`, kolom cari produk) dek pindah ke `qwerty`; saat `releaseFocusedField()` dek kembali ke `qwerty`.

- [ ] **Step 1:** Tambah fungsi `private fun onFieldFocused(field: EditText)` yang memilih dek menurut aturan di atas, dan panggil dari semua tempat `focusedField = field` yang sekarang ada (invoice, ongkir, picker). Tandai kolom angka dengan `android:inputType="number"` atau `"phone"` di XML dan gunakan `field.inputType` untuk memutuskan, bukan daftar id yang ditulis tangan.
- [ ] **Step 2: Verifikasi** `./gradlew testLiteDebugUnitTest assembleLiteDebug` → lulus dan BUILD SUCCESSFUL. Tambahkan ke daftar `Cek manual di HP` baris: mengetuk kolom Ongkir memunculkan dek angka; mengetuk Nama pembeli memunculkan huruf; setelah panel ditutup dek kembali huruf.
- [ ] **Step 3: Commit**
```bash
git add -A app/src docs
git commit -m "feat: number deck for numeric invoice fields"
```

---

### Task 8: Dokumentasi dan verifikasi akhir

**Files:**
- Modify: `NOTES.md`, `README.md`

- [ ] **Step 1: Perbarui `NOTES.md`**: §2 (project Supabase yang dipakai sekarang `bzoitgivmozhwpuomuhj`), §3 (jumlah baris, `jenis_kain`, `payment_methods`, kolom `is_sellable`/`sku` pada varian), §4 (templates/categories sekarang hanya bisa ditulis admin login; orders tidak bisa dibaca anon), §5 (invoice sekarang berbentuk teks multi-produk; penyimpanan tetap ditunda). Tambahkan catatan jelas: **`web-dashboard/index.html` dan `index.html` masih menulis template ke project lama `zympq…`**, sehingga perubahan template dari dashboard itu tidak lagi sampai ke keyboard sampai dashboard dipindah atau memakai login admin. Perbarui `README.md` (bagian Configuration dan Known limits) sesuai.
- [ ] **Step 2: Verifikasi akhir.** Jalankan `./gradlew testLiteDebugUnitTest assembleLiteDebug` → lulus dan `app/build/outputs/apk/lite/debug/app-lite-debug.apk` ada. Catat jumlah test.
- [ ] **Step 3: Commit**
```bash
git add NOTES.md README.md
git commit -m "docs: record the database switch and invoice changes"
```
