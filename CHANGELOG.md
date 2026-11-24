# Changelog

Versi mengikuti [SemVer](https://semver.org). Selama `0.0.x` ini adalah pra-rilis: belum di Play Store, belum diaudit, dan APK ditandatangani dengan kunci debug.

## [0.0.1] — pra-rilis

Pertama kali Arthatis Wallet v2 dapat dipasang.

- **Aplikasi Android**: onboarding tanpa akun, multi-dompet dan multi-mata uang (IDR/USD/SGD) dengan transfer dan kurs yang bisa diubah, transaksi dengan keypad, transaksi berulang, anggaran, kategori, scan struk (OCR di HP), Chat AI (Gemini Nano → AI server → API pribadi → parser lokal), ekspor/impor `.arthatis`/CSV/SQLite, Wallet Bareng, tautan sesi web lewat QR, bahasa ID/EN, tema terang/gelap.
- **Server**: sinkron E2EE (XChaCha20-Poly1305, X25519 + ML-KEM-768), vault tanpa akun, email pemulihan opsional (SMTP), Bareng, admin API, aktivasi vault untuk hosted.
- **Web mirror**: login QR, CRUD penuh, laporan, Chat AI, Bareng, halaman Admin.
- Belum ada: iOS, billing, deep link undangan Android. Kriptografi belum diaudit pihak ketiga.
- Berkas: `releases/arthatis-wallet-v0.0.1.apk` (+ `.sha256`).
