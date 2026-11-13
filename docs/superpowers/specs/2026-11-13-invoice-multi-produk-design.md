# Desain: Invoice multi-produk + pindah ke database website

Status: **disetujui pemilik (13 Nov 2026)**. Keputusan teknis yang diambil Claude diberi tanda *(keputusan Claude)*.

## Latar belakang

Invoice builder di keyboard SAMAQU belum cocok dengan cara tim mengirim invoice ke customer:

- Hanya 1 produk per invoice, tanpa kode kain, tanpa WA, alamat, layanan ongkir, atau rekening.
- Panel invoice tingginya dikunci 200dp, sehingga kolom "Nama pembeli" kegeser dan hilang. Kolom Qty/Harga/Ongkir hanya punya hint (`1`, `0`, `0`), tanpa label, dan angka diketik di keyboard huruf.
- Katalog produk yang dibaca keyboard sudah ketinggalan. Keyboard membaca project Supabase `zympqqmrygldagpazvse` (111 produk, 547 varian, sebagian harga lama), sedangkan website memakai `bzoitgivmozhwpuomuhj` (121 produk, 587 varian, harga terbaru).

Format invoice yang dipakai tim sekarang (dikirim manual):

```
Berikut invoice pesanannya ya Ka Muhammad Nasir.

INVOICE PESANAN SAMAQU
Nama: Muhammad Nasir
No. WA: 0852-6041-2200
Alamat: Jl. Tgk. Wahab Dahlawi ..., Kota Lhokseumawe, Aceh 24352

Pesanan:
1 x Thobe Broken White Imalah (kain B-01), size M - Rp374.000
Ongkir J&T ECO (estimasi 3-7 hari): Rp40.500

Total: Rp414.500

Pembayaran via transfer:
Bank BSI 7153280058
a.n. Ardo Babus Abdullah

Kalau sudah transfer, kirim bukti transfernya di sini ya Ka.
```

## Tujuan

1. Keyboard membaca katalog yang sama dengan website (project `bzoit…`).
2. Invoice multi-produk yang keluarannya **persis** format di atas.
3. Kode kain, rekening, dan layanan + estimasi ongkir terisi otomatis dari data yang sudah ada.
4. Form invoice nyaman dipakai di HP: semua kolom berlabel, tidak ada yang kepotong, angka diketik di keypad angka.

## Bukan tujuan

- Menyimpan invoice, riwayat, atau status bayar (keputusan penyimpanan di `NOTES.md` §5 masih ditunda).
- Mengisi invoice otomatis dari order website.
- Login admin di app (panel Pending, notifikasi order, dan edit template dari keyboard).
- Mengubah skema atau data di Supabase mana pun. Semua akses tetap baca-saja kecuali `templates`/`categories` yang sudah ada.

## Anti-tujuan (dianggap gagal walau secara teknis jalan)

- Harga atau stok yang tampil berbeda dari website.
- Rekening salah ketik atau usang karena disalin manual ke app.
- Teks invoice yang menyimpang dari format di atas (spasi pada `Rp374.000`, titik dua, urutan baris).

## Fakta yang sudah diperiksa (read-only, 13 Nov 2026)

| | `zympq…` (dipakai keyboard) | `bzoit…` (website) |
|---|---|---|
| products / variants | 111 / 547 | 121 / 587 |
| templates / categories | 38 / 14 | 38 / 14 |
| anon boleh baca | products, variants, jenis_kain, payment_methods, templates, categories | products, variants, jenis_kain, payment_methods, templates, categories |
| anon boleh tulis templates/categories | ya | **tidak** (hanya admin login) |
| orders | tidak terbaca anon | tertutup (admin/customer login) |

- Kode kain ada di tabel `jenis_kain` (`B-01`, `B-02`, `A-02`, `C.01`, `B02`), dihubungkan lewat `products.jenis_kain_id`. Kolom `products.kain` kosong. Aplikasi sekarang membaca `products.kain`, jadi kode kain tidak pernah muncul.
- Rekening ada di `payment_methods` (`method_type = 'bank'`, `bank_name`, `account_number`, `account_name`, `is_active`) dan bisa dibaca anon.
- Layanan dan estimasi ongkir sudah ada di `JntCostOption` (`courier`, `service`, `etd`, `cost`).

## Pendekatan yang dipertimbangkan

| | Pendekatan | Hasil |
|---|---|---|
| 1 | Rapikan form 1 produk | Ditolak: pesanan 2 produk tetap butuh 2 invoice. |
| **2** | **Invoice multi-produk di dalam keyboard** | **Dipilih pemilik.** |
| 3 | Nomor 2 + isi dari order website + simpan invoice | Ditunda: bergantung pada keputusan penyimpanan dan login admin. |

## Desain

### 1. Pindah database

- `SupabaseConfig.PROJECT_URL` dan `ANON_KEY` diganti ke project `bzoitgivmozhwpuomuhj` (anon/publishable key publik, boleh ada di APK; tidak boleh ada `service_role`).
- `Prefs` bisa menyimpan URL hasil Settings. Phone yang sudah pernah menyimpan URL lama akan tetap memakainya. Karena itu `Prefs` memperlakukan nilai tersimpan yang sama persis dengan URL/key **lama** sebagai "belum diisi" dan memakai default baru *(keputusan Claude)*. Nilai lain yang sengaja diisi pemilik tidak disentuh.
- Konsekuensi yang diterima pemilik: edit template dari keyboard berhenti (RLS `bzoit` hanya mengizinkan admin login). Auto Text tetap bisa dibaca. Panel Pending sudah kosong di database lama.

### 2. Data produk

- `getProducts()` ikut mengambil `jenis_kain(name)` (PostgREST embed). `CachedProduct.kain` diisi nama kain itu. Kolom Room `kain` sudah ada, jadi tidak perlu migrasi.
- Produk tanpa kain tetap tampil tanpa `(kain …)`.
- Filter `is_published` **tidak** dipakai: perilaku katalog dibiarkan sama seperti sekarang *(keputusan Claude; belum ada bukti bahwa produk tak terbit harus disembunyikan)*.

### 3. Rekening otomatis

- Saat sync, app mengambil `payment_methods` aktif dengan `method_type = bank`, urut `display_order`, dan menyimpannya sebagai teks di `Prefs` supaya invoice tetap jalan offline.
- Blok di invoice: `Bank BSI 7153280058` lalu `a.n. Ardo Babus Abdullah`. Jika ada beberapa rekening aktif, semuanya ditulis berurutan.
- Jika belum pernah sync dan tidak ada rekening tersimpan, bagian "Pembayaran via transfer" dilewati (bukan diisi tebakan).

### 4. Model dan format invoice

Fungsi murni baru di `SamaQuText` (bisa diuji di JVM):

- `InvoiceItem(label, qty, unitPrice)` dengan label dari `variantLabel` versi baru: `Thobe Broken White Imalah (kain B-01), size M`. Nama dan series digabung dengan spasi (bukan `•`); warna "default" dibuang; kain dan ukuran diberi label.
- Harga ditulis `Rp374.000` **tanpa spasi** setelah `Rp`.
- Baris pesanan: `{qty} x {label} - Rp{jumlah}`. Untuk qty > 1, jumlah adalah qty × harga satuan *(keputusan Claude)*.
- Baris ongkir: `Ongkir {kurir} {layanan} (estimasi {etd} hari): Rp{biaya}`. Jika estimasi kosong atau sudah memuat kata "hari", kata itu tidak diulang; jika tidak ada layanan, hanya `Ongkir: Rp{biaya}`.
- `Total` = jumlah semua baris + ongkir.
- Baris `No. WA` dan `Alamat` dilewati bila kosong. Salam pembuka memakai `Ka {nama}`, dan `Kak` bila nama kosong.
- Kalimat penutup bisa diubah di Settings, bawaan: `Kalau sudah transfer, kirim bukti transfernya di sini ya Ka.`

### 5. Tampilan panel invoice

- Panel lebih tinggi dan seluruh isinya bisa digulir; tombol "Generate & Kirim ke Chat" selalu terlihat di bawah.
- Bagian atas: Nama, No. WA, Alamat (masing-masing berlabel).
- Daftar pesanan: tombol "+ Pilih produk" membuka picker yang sudah ada (cari, kategori, ukuran + stok). Tiap produk yang dipilih menjadi satu baris dengan qty (+/−) dan hapus. Harga bisa diubah di baris itu bila perlu.
- Ongkir: kolom berlabel dan tombol pintas ke panel Cek Ongkir; "Pakai" di hasil ongkir mengisi biaya dan nama layanan + estimasi.
- Ringkasan Total terlihat sebelum dikirim.
- Kolom angka (qty, harga, ongkir, WA) memunculkan keypad angka; jika `SamaQuIME` belum punya mode itu, ditambahkan dengan cara yang sama seperti dek simbol yang ada. Pengecekan visual harus dilakukan di HP.

### 6. Pengujian

- Unit test JVM untuk format invoice (kasus contoh pemilik harus identik karakter per karakter), label produk, ongkir tanpa estimasi, qty > 1, nilai kosong, dan migrasi `Prefs` dari URL lama.
- Build `assembleLiteDebug` dan `testLiteDebugUnitTest`.
- **Belum pernah diuji di perangkat.** Tampilan panel dan keypad angka harus dicoba di HP; hasil build tidak membuktikannya.

## Diserahkan ke pembuat

- Cara tepat menambahkan keypad angka di `SamaQuIME`.
- Pecahan tata letak XML.
- Nama-nama kelas dan berkas pendukung.

## Cek manual di HP

Dibuat saat implementasi. Belum ada yang dijalankan di perangkat; centang hanya setelah dicoba di HP.

Sebelum mulai: buka Settings app, pastikan Supabase URL berisi `https://bzoitgivmozhwpuomuhj.supabase.co` (atau kosongkan/tekan Simpan agar memakai bawaan), lalu buka Invoice > + Produk sekali agar katalog tersinkron.

- [ ] Panel Invoice terbuka dan **Nama pembeli, No. WA, Alamat** terlihat berlabel tanpa perlu menggulir ke atas; tombol "Generate & Kirim ke Chat" selalu terlihat di bawah.
- [ ] "+ Produk" membuka picker; memilih produk dan ukuran menambah satu baris di Pesanan, dengan kode kain (misalnya `(kain B-01)`) pada nama.
- [ ] Memilih produk dan ukuran yang sama dua kali menaikkan qty, bukan menambah baris.
- [ ] Tombol − dan + mengubah qty (tidak turun di bawah 1); ✕ menghapus baris; harga satuan bisa diubah dan Total ikut berubah.
- [ ] "Cek ongkir" membuka panel ongkir; "Pakai" mengisi biaya dan menampilkan layanan + estimasi di panel invoice.
- [ ] Total di atas tombol sama dengan hitungan manual (produk × qty + ongkir).
- [ ] "Generate & Kirim ke Chat" menulis teks yang sama dengan format di bagian *Latar belakang* (cek `Rp374.000` tanpa spasi, baris Pembayaran BSI, kalimat penutup).
- [ ] Setelah dikirim, form kosong dan siap dipakai lagi. Tanpa produk, tombol menampilkan "Pilih minimal satu produk".
- [ ] Mengubah "Kalimat penutup invoice" di Settings mengubah baris terakhir invoice; mengosongkannya menghilangkan baris itu.
- [ ] Mengetuk kolom **No. WA**, **Ongkir**, atau **harga satuan** langsung memunculkan dek angka (baris 1–0); mengetuk **Nama pembeli** atau **Alamat** memunculkan huruf.
- [ ] Menekan Enter atau menutup panel setelah mengisi kolom angka mengembalikan dek ke huruf. Memakai dek simbol sendiri di chat biasa tidak ikut berubah.
- [ ] Ubah harga satuan sebuah produk, lalu tekan + atau − : daftar tidak melompat ke Nama pembeli.
- [ ] Pilih ongkir lewat "Pakai", lalu kosongkan kolom Ongkir dan ketik angka baru: label layanan hilang dan invoice hanya menulis `Ongkir: Rp…`.
- [ ] Putar layar atau ganti ukuran tombol saat panel Invoice terbuka: formulir kembali kosong dan tidak ada angka yang salah masuk ke invoice.
- [ ] Tekan Generate saat kolom chat tidak aktif: muncul pesan "Belum terkirim", formulir tidak dikosongkan.
- [ ] Setelah update dari versi lama, buka picker sebelum sync selesai: katalog tidak menampilkan harga lama (kosong atau memuat dulu).
- [ ] Lihat format estimasi asli dari Cek Ongkir (apakah `3-7` atau `3-7 hari`) dan pastikan baris ongkir invoice terbaca wajar.
