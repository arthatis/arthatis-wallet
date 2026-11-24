# Changelog

Versi mengikuti [SemVer](https://semver.org). Selama `0.0.x` ini adalah pra-rilis: belum di Play Store, belum diaudit, dan APK ditandatangani dengan kunci debug.

## [0.0.2] — pra-rilis

- **APK jauh lebih ringan**: 83 MB → 25 MB. R8 (minify + shrink resource) aktif untuk rilis, dan APK hanya memuat arm64-v8a (HP 64-bit, hampir semua HP sejak ~2017). HP 32-bit (armeabi-v7a) dan emulator x86 tidak didukung APK rilis; untuk emulator pakai build debug.
- Berkas: `releases/arthatis-wallet-v0.0.2.apk` (+ `.sha256`).

- Input nominal memakai pemisah ribuan sesuai mata uang (IDR `1.234.567`, USD/SGD/MYR `1,234.56`, EUR `1.234,56`) saat mengetik; keypad transaksi punya tombol desimal untuk mata uang berdesimal (sebelumnya angka di belakang koma tidak bisa dimasukkan).
- Mata uang baru: MYR (RM) dan EUR.
- Dompet: jenis Rekening/Tunai/E-Wallet diganti pilihan ikon dan warna; tipe Biasa, Investasi, Kartu kredit (investasi dan utang kartu tampil terpisah dari total kas). Data lama tetap terbaca.
- Layar tambah/ubah transaksi berulang di aplikasi dan web.
- Status Gemini Nano di Pengaturan AI diperiksa dengan uji coba nyata, bukan hanya status unduhan, dan menampilkan detailnya.

## [0.0.1] — pra-rilis

Pertama kali Arthatis Wallet v2 dapat dipasang.

- **Aplikasi Android**: onboarding tanpa akun, multi-dompet dan multi-mata uang (IDR/USD/SGD) dengan transfer dan kurs yang bisa diubah, transaksi dengan keypad, transaksi berulang, anggaran, kategori, scan struk (OCR di HP), Chat AI (Gemini Nano → AI server → API pribadi → parser lokal), ekspor/impor `.arthatis`/CSV/SQLite, Wallet Bareng, tautan sesi web lewat QR, bahasa ID/EN, tema terang/gelap.
- **Server**: sinkron E2EE (XChaCha20-Poly1305, X25519 + ML-KEM-768), vault tanpa akun, email pemulihan opsional (SMTP), Bareng, admin API, aktivasi vault untuk hosted.
- **Web mirror**: login QR, CRUD penuh, laporan, Chat AI, Bareng, halaman Admin.
- Belum ada: iOS, billing, deep link undangan Android. Kriptografi belum diaudit pihak ketiga.
- Berkas: `releases/arthatis-wallet-v0.0.1.apk` (+ `.sha256`).
