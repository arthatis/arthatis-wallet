# Changelog

Versi mengikuti [SemVer](https://semver.org). Selama `0.0.x` ini adalah pra-rilis: belum di Play Store, belum diaudit, dan APK ditandatangani dengan kunci debug.

## [0.0.4] — pra-rilis

- **Semua mata uang 4 desimal** (USD/SGD/MYR/EUR juga, bukan hanya Rupiah). Tampilan non-IDR minimal 2 digit (`$5.00`, `$1,060.7925`). **Tidak kompatibel dengan data 0.0.3** untuk dompet non-IDR: hapus data HP dan server sebelum memasang.
- **Total saldo = kekayaan bersih**: kas + investasi − utang kartu kredit, dengan rincian Kas · Investasi · Kartu kredit di bawahnya (HP dan web).
- **Angka besar diringkas otomatis** (`Rp4,1k`, `$6.7m`, `Rp7,2b`) hanya bila nominal penuh tidak muat.
- **Laporan keuangan PDF** dengan rentang tanggal (default 30 hari terakhir): ringkasan, per kategori, per dompet, daftar transaksi. HP: Data & backup → Laporan keuangan. Web: Laporan → Ekspor PDF.
- **Transaksi otomatis dari email** bank & e-wallet (IMAP + app password, dibaca di HP, tiap ±15 menit). Hasilnya draf yang dikonfirmasi; email tidak lewat server.
- Chat AI: boleh ngobrol dan konsultasi keuangan umum (tetap seputar catatan, konsultasi, dan analisa riwayat), mengingat percakapan, fakta riwayat 6 bulan dan saldo per dompet. **Transfer antar dompet lewat chat** diperbaiki ("tf 50k dari BCA ke OVO", "kirim 100rb ke GoPay", "top up GoPay 200k"), memakai kurs untuk beda mata uang.
- **Scan struk tidak lagi macet**: kegagalan OCR kini tertangani, setiap mesin AI punya batas waktu (Gemini Nano bisa menunggu tanpa akhir saat modelnya belum siap), foto diperkecil dan diputar sesuai EXIF, ada tahap proses dan tombol Batal.
- UI: ikon Transaksi di navigasi kini solid; palet warna dompet lebih cerah (kuning baru); 2 dompet di Beranda = saldo tertinggi; detail dompet memakai satu FAB (Tambah transaksi + Transfer); hapus dompet pindah ke bawah form Ubah dompet; konten tidak lagi terpotong di atas bilah navigasi.

## [0.0.3] — pra-rilis

- **Rupiah bisa berdesimal** (sampai 4 digit, untuk kripto/valas). **Tidak kompatibel dengan data 0.0.1/0.0.2**: satuan simpan IDR berubah, jadi data lama harus dihapus (HP dan server) sebelum memasang. Tampilan `Rp14.125.000` tidak berubah untuk angka bulat.
- Dompet bisa diubah (nama, ikon, warna, saldo awal); tipe dan mata uang terkunci.
- Tipe dompet "Biasa" dinamai ulang menjadi **Tunai/Cash**.
- Keypad: tombol desimal juga untuk Rupiah (tombol `k` tetap ada).

## [0.0.2] — pra-rilis

- **APK jauh lebih ringan**: R8 (minify + shrink resource) aktif untuk rilis. Library ML Kit membawa ~15 MB native per CPU, jadi rilis dibagi satu APK per CPU (19–27 MB, dulu satu APK 83 MB). Semua CPU didukung:
  - `arm64-v8a`: HP 64-bit (hampir semua HP sejak ~2017), 25 MB.
  - `armeabi-v7a`: HP 32-bit lama, 19 MB.
  - `x86_64` dan `x86`: emulator dan perangkat Intel/ChromeOS, 27 MB.
- Berkas: aset GitHub Release `v0.0.2` (`arthatis-wallet-v0.0.2-<cpu>.apk` + `SHA256SUMS`).

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
- Berkas: aset GitHub Release `v0.0.1`.
