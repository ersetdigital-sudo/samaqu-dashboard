# SAMAQU Keyboard — Dashboard Web

Halaman admin untuk mengelola **template chat** dan **kategori** yang dipakai keyboard SAMAQU.

Dibuka di: https://ersetdigital-sudo.github.io/samaqu-dashboard/

## Cara pakai

- Chip di atas = kategori (angka di belakangnya = jumlah template)
- **+ Tambah Kategori**, **Ubah Nama**, **Hapus Kategori** (menghapus kategori ikut menghapus template di dalamnya)
- **Tambah Template** lewat form di bawah daftar; tombol **Ubah** / **Hapus** di tiap kartu

Setiap perubahan langsung ditulis ke Supabase, jadi begitu keyboard di-*Sync* isinya sudah terbaru.

## Data

- Supabase project: `zympqqmrygldagpazvse`
- Tabel: `categories` (id, name, display_order) dan `templates` (id, category_id, content, display_order)
- Skema + policy RLS: `samaqu-keyboard-app/supabase_setup.sql` di repo keyboard

## Catatan keamanan

Halaman ini memakai **anon key** (kunci publik) — sama seperti yang sudah ada di bundle JS website SAMAQU,
jadi tidak menambah paparan baru. Row Level Security membatasi key ini: **hanya** `categories` dan `templates`
yang bisa dibaca dan ditulis. Isinya cuma balasan siap pakai, tidak ada data pelanggan.

Konsekuensinya: siapa pun yang punya link ini bisa mengubah teks template. Kalau nanti butuh login,
pindahkan halaman ini ke panel `/admin` website SAMAQU (Next.js) yang autentikasinya sudah jalan.

## Buka dari dalam app

Di app keyboard: **Dashboard → Atur di Pengaturan → URL Dashboard**, tempel URL di atas untuk membukanya
di dalam aplikasi.
