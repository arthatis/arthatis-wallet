# Arthatis Wallet

Dompet yang tetap jalan tanpa internet. **Offline-first, E2EE, tanpa iklan, tanpa telemetry, tanpa akun wajib.** Open source di bawah [AGPL-3.0](LICENSE).

- **HP adalah pusat data.** Semua fitur ada di aplikasi Android; web hanya mirror (CRUD penuh, seperti WhatsApp Web) yang tertaut lewat QR.
- **Server hanya menyimpan ciphertext.** Kunci tidak pernah keluar dari perangkat.
- **Boleh host sendiri.** Satu image Docker; atau pakai server hosted milik Arthatis (berbayar).

## Isi repo

| Folder | Isi |
|---|---|
| `mobile/` | Aplikasi Android (Jetpack Compose, minSdk 26). |
| `web/` | Web mirror `wallet.arthatis.com` (Astro + TypeScript): login QR, CRUD, laporan, Chat AI, halaman Admin. |
| `server/` | Server sinkron (Hono + SQLite, Node ≥ 24). Melayani API dan web mirror. |

Dokumen desain, spesifikasi protokol E2EE, kontrak admin API, dan keputusan produk dikelola maintainer di monorepo internal Arthatis; repo ini hanya berisi kode. Untuk perubahan besar, buka issue lebih dulu.

## Kebutuhan dan tempat menjalankan

Tiga bagian dengan kebutuhan berbeda. Yang menentukan hosting hanya **server**.

### Mobile (aplikasi Android)

| | Kebutuhan |
|---|---|
| **Memakai** | Android 8.0+ (API 26). Internet hanya untuk sinkron, kurs harian, dan AI online; semuanya opsional, aplikasi jalan offline. Kamera untuk scan struk dan QR sesi web. Sidik jari atau PIN layar untuk menyetujui sesi web. Gemini Nano hanya di HP yang mendukung (opsional). |
| **Membangun** | JDK 21, Android SDK (compileSdk 35), Gradle wrapper yang sudah ada di repo. Node ≥ 24 hanya untuk tes alur Bareng. |
| **Hosting** | Tidak ada. Aplikasi berjalan di HP; datanya ada di HP. |

### Server (`server/`)

| | Kebutuhan |
|---|---|
| **Runtime** | Node ≥ 24 (memakai `node:sqlite` bawaan), atau cukup Docker. |
| **Disk** | **Disk permanen** untuk database SQLite (`DATA_DIR`, di Docker: volume `/data`). Ukurannya kecil karena hanya ciphertext. Cadangkan volume itu. |
| **Proses** | **Satu instance yang hidup terus.** SQLite adalah satu file dengan satu penulis; pembatas pendaftaran ada di memori dan pembersih vault memakai timer. Jangan dijalankan di beberapa replika. |
| **Jaringan** | Dilayani lewat **HTTPS** (aplikasi rilis menolak HTTP; web memerlukan konteks aman). Pasang reverse proxy dengan TLS (Caddy, nginx, Traefik) di depan port `8787`. Keluar: SMTP (opsional), Ollama (opsional). |
| **Sumber daya** | Kecil: 1 vCPU dan 512 MB RAM cukup untuk puluhan sampai ratusan pengguna. |
| **Konfigurasi** | `server/.env.example`: `SMTP_*` (email pemulihan), `REGISTRATION_MODE`, `OLLAMA_URL` (AI server), `CORS_ORIGIN`, dan untuk server berbayar `REQUIRE_ACTIVATION`, `PAY_URL`, `ADMIN_API_TOKEN`. |

### Web (`web/`)

| | Kebutuhan |
|---|---|
| **Memakai** | Browser modern (Chrome, Firefox, Safari, Edge terbaru) dengan WebCrypto dan IndexedDB, lewat HTTPS (atau `localhost`). Akun tidak ada: masuk dengan memindai QR dari aplikasi. |
| **Membangun** | Node ≥ 20 (`npm run build` di `web/`). |
| **Hosting** | **File statis**, tanpa server sendiri. Image Docker server sudah melayaninya dari origin yang sama. Atau taruh di CDN/serverless statis (Cloudflare Pages, Netlify, S3 + CDN) dengan syarat di bawah. |

### Serverless atau VPS?

| Komponen | Serverless cukup? | Catatan |
|---|---|---|
| **Server** | **Tidak.** Pakai VPS, atau platform container dengan **volume permanen** (mis. Fly.io, Railway, Render + disk). | Butuh proses hidup terus + disk + satu instance. Platform fungsi (Cloudflare Workers, AWS Lambda) tidak menyediakannya. |
| **Web** | **Ya**, bila mau dipisah dari server. | Isi `PUBLIC_API_URL` saat build ke alamat server, atur `CORS_ORIGIN` di server (default `*`), dan tambahkan fallback SPA ke `index.html` untuk rute seperti `/invite/<token>`. Dengan satu origin (default Docker) semua itu tidak perlu. |
| **Mobile** | Tidak relevan | Berjalan di HP. |

**Rekomendasi paling sederhana:** satu VPS kecil dengan `docker compose up -d` di belakang Caddy untuk HTTPS. Itu melayani server sekaligus web.

## Self-host

```bash
cd server
cp .env.example .env     # isi SMTP_* bila mau email pemulihan
docker compose up -d     # build context = folder arthatis-wallet/
```

Satu container melayani API (`/v1`) dan web mirror (`/`). Di aplikasi, pilih **VPS pribadi** saat onboarding dan isi URL servermu.

- **Admin**: vault pertama yang mendaftar menjadi admin server. **Daftarkan HP-mu segera** setelah server menyala. Admin mengatur siapa yang boleh mendaftar (terbuka, atau hanya email terdaftar) lewat menu Admin di web.
- **Email** (opsional, untuk pemulihan akun) memakai SMTP milikmu: isi `SMTP_*` di `.env`. Tanpa itu, fitur email nonaktif dan sisanya tetap jalan. Atur SPF/DKIM agar tidak masuk spam; banyak VPS memblokir port 25 (pakai 587 lewat relay).
- **Data** ada di volume `/data` (SQLite). Isinya ciphertext; cadangkan volume itu.

## Pasang APK (pra-rilis)

APK terbaru ada di [`releases/`](releases/) beserta checksum `.sha256` (`sha256sum -c arthatis-wallet-v0.0.2-arm64-v8a.apk.sha256`). Selama `0.0.x`, APK ditandatangani dengan **kunci debug** dan belum ada di Play Store; pasang manual (izinkan "pasang dari sumber ini"). Ada satu APK per CPU: `arm64-v8a` untuk hampir semua HP, `armeabi-v7a` untuk HP 32-bit lama, `x86_64`/`x86` untuk emulator dan perangkat Intel. Tidak yakin? Jalankan `adb shell getprop ro.product.cpu.abi` atau pasang `arm64-v8a` dulu (jika HP menolak, coba `armeabi-v7a`). Membuat semua APK: `mobile/release.sh` (versi dibaca dari `mobile/app/build.gradle.kts`). Tanda tangan rilis sungguhan memakai `mobile/keystore.properties` (tidak masuk repo). Riwayat versi: [CHANGELOG.md](CHANGELOG.md).

## Pengembangan

```bash
./dev-server.sh    # server di :8787
./dev-web.sh       # web mirror (butuh server berjalan)
./dev-mobile.sh    # pasang debug build ke device/emulator
```

Tes:

```bash
(cd server && npm test)
(cd web && npm test && npx astro check)
(cd mobile && ./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug)
```

Tes alur Bareng menjalankan server Node sungguhan; mereka dilewati otomatis bila `server/node_modules` belum ada.

## Lisensi

Kode di repo ini berlisensi **GNU AGPL-3.0** (lihat [LICENSE](LICENSE)). Jika kamu menjalankan versi yang kamu ubah sebagai layanan untuk orang lain, kode ubahanmu harus tetap terbuka.

Nama **Arthatis**, logo, dan maskot **Ollie** milik Arthatis dan tidak dilisensikan lewat AGPL, kecuali dinyatakan lain secara tertulis. Fork yang dipublikasikan sebaiknya memakai nama dan maskot sendiri.

## Berkontribusi dan keamanan

[CONTRIBUTING.md](CONTRIBUTING.md) · [SECURITY.md](SECURITY.md)
