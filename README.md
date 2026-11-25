# Arthatis Wallet

**English** · [Bahasa Indonesia](README.id.md)

A wallet that keeps working without the internet. **Offline-first, end-to-end encrypted, no ads, no telemetry, no account required.** Open source under [AGPL-3.0](LICENSE).

- **The phone is the source of truth.** Every feature lives in the Android app; the web is a mirror (full CRUD, like WhatsApp Web) linked with a QR code.
- **The server only stores ciphertext.** Keys never leave your devices.
- **Self-host it.** One Docker image; or use Arthatis' hosted server (paid).

## What's in this repo

| Folder | Contents |
|---|---|
| `mobile/` | Android app (Jetpack Compose, minSdk 26). |
| `web/` | Web mirror `wallet.arthatis.com` (Astro + TypeScript): QR login, CRUD, reports, AI chat, Admin page. |
| `server/` | Sync server (Hono + SQLite, Node ≥ 24). Serves the API and the web mirror. |

Design files, the E2EE protocol spec, the admin API contract and product decisions are kept by the maintainers in Arthatis' internal monorepo; this repo holds code only. For big changes, open an issue first.

## Requirements and where to run it

Three parts with different needs. Only the **server** decides your hosting.

### Mobile (Android app)

| | Requirements |
|---|---|
| **Using** | Android 8.0+ (API 26). Internet only for sync, daily exchange rates and online AI; all optional, the app works offline. Camera for receipt photos and the web-session QR. Email access (IMAP, optional) for automatic transactions from bank/e-wallet emails. Fingerprint or screen PIN to approve web sessions. Gemini Nano only on supported phones (optional). |
| **Building** | JDK 21, Android SDK (compileSdk 35), the Gradle wrapper in this repo. Node ≥ 24 only for the shared-wallet flow tests. |
| **Hosting** | None. The app runs on the phone; the data lives on the phone. |

### Server (`server/`)

| | Requirements |
|---|---|
| **Runtime** | Node ≥ 24 (uses the built-in `node:sqlite`), or just Docker. |
| **Disk** | **Persistent disk** for the SQLite database (`DATA_DIR`; in Docker the `/data` volume). Small, since it is only ciphertext. Back that volume up. |
| **Process** | **One long-running instance.** SQLite is one file with one writer; the registration limiter lives in memory and vault cleanup uses a timer. Do not run several replicas. |
| **Network** | Serve it over **HTTPS** (release builds refuse HTTP; the web needs a secure context). Put a TLS reverse proxy (Caddy, nginx, Traefik) in front of port `8787`. Outbound: SMTP (optional), Ollama (optional). |
| **Resources** | Small: 1 vCPU and 512 MB RAM are enough for tens to hundreds of users. |
| **Configuration** | `server/.env.example`: `SMTP_*` (recovery email), `REGISTRATION_MODE`, `OLLAMA_URL` (server AI), `CORS_ORIGIN`, and for a paid server `REQUIRE_ACTIVATION`, `PAY_URL`, `ADMIN_API_TOKEN`. |

### Web (`web/`)

| | Requirements |
|---|---|
| **Using** | A modern browser (latest Chrome, Firefox, Safari, Edge) with WebCrypto and IndexedDB, over HTTPS (or `localhost`). There is no account: sign in by scanning a QR code from the app. |
| **Building** | Node ≥ 20 (`npm run build` in `web/`). |
| **Hosting** | **Static files**, no server of its own. The server's Docker image already serves it from the same origin. Or put it on a CDN/static host (Cloudflare Pages, Netlify, S3 + CDN) with the conditions below. |

### Serverless or VPS?

| Component | Is serverless enough? | Notes |
|---|---|---|
| **Server** | **No.** Use a VPS, or a container platform with a **persistent volume** (e.g. Fly.io, Railway, Render + disk). | Needs a long-running process + disk + a single instance. Function platforms (Cloudflare Workers, AWS Lambda) don't provide that. |
| **Web** | **Yes**, if you want it apart from the server. | Set `PUBLIC_API_URL` at build time to the server's address, set `CORS_ORIGIN` on the server (default `*`), and add an SPA fallback to `index.html` for routes like `/invite/<token>`. With one origin (the Docker default) none of this is needed. |
| **Mobile** | Not applicable | Runs on the phone. |

**Simplest setup:** one small VPS running `docker compose up -d` behind Caddy for HTTPS. That serves both the server and the web.

## Self-hosting

```bash
cd server
cp .env.example .env     # fill in SMTP_* if you want recovery email
docker compose up -d     # build context = the arthatis-wallet/ folder
```

One container serves the API (`/v1`) and the web mirror (`/`). In the app, choose **My VPS** during onboarding and enter your server's URL.

- **Admin**: the first vault to register becomes the server admin. **Register your phone right after the server starts.** The admin decides who may register (open, or listed emails only) from the Admin menu on the web.
- **Email** (optional, for account recovery) uses your own SMTP: fill in `SMTP_*` in `.env`. Without it, email features are off and everything else works. Set up SPF/DKIM to stay out of spam; many VPS providers block port 25 (use 587 through a relay).
- **Data** lives in the `/data` volume (SQLite). It is ciphertext; back that volume up.

## Installing the APK (pre-release)

APKs are on the [Releases](https://github.com/arthatis/arthatis-wallet/releases) page (each version has a `vX.Y.Z` tag). Download the APK for your CPU plus `SHA256SUMS`, then check: `sha256sum -c SHA256SUMS --ignore-missing`. During `0.0.x` the APKs are signed with a **debug key**, are not on the Play Store, and are marked pre-release; install them manually (allow "install from this source"). There is one APK per CPU: `arm64-v8a` for almost every phone, `armeabi-v7a` for old 32-bit phones, `x86_64`/`x86` for emulators and Intel devices. Not sure? Run `adb shell getprop ro.product.cpu.abi` or try `arm64-v8a` first. Maintainers build the APKs with `mobile/release.sh` (output in `mobile/dist/`, not committed) and upload them to a GitHub Release. Real release signing uses `mobile/keystore.properties` (not in the repo). Version history: [CHANGELOG.md](CHANGELOG.md).

## Development

```bash
./dev-server.sh    # server on :8787
./dev-web.sh       # web mirror (needs the server running)
./dev-mobile.sh    # install a debug build on a device/emulator
```

Tests:

```bash
(cd server && npm test)
(cd web && npm test && npx astro check)
(cd mobile && ./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug)
```

The shared-wallet flow tests start a real Node server; they are skipped automatically when `server/node_modules` is missing.

## License

The code in this repo is licensed under the **GNU AGPL-3.0** (see [LICENSE](LICENSE)). If you run a modified version as a service for others, your modified code must stay open.

The **Arthatis** name, logo and the **Ollie** mascot belong to Arthatis and are not licensed under the AGPL, unless stated otherwise in writing. Published forks should use their own name and mascot.

## Contributing and security

[CONTRIBUTING.md](CONTRIBUTING.md) · [SECURITY.md](SECURITY.md)
