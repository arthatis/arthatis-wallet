# Changelog

Versions follow [SemVer](https://semver.org). During `0.0.x` everything is a pre-release: not on the Play Store, not audited, and the APKs are signed with a debug key.

## [0.0.5] — pre-release

- **Opening balance is never income.** It is shown as its own line: in the report (opening balance → income → expenses → closing balance, plus an opening column per wallet) and on the wallet detail screen. Tests lock this rule in.
- **Credit cards are debt, not assets.** The card form asks for the current bill (stored as debt), an optional limit (information only, shows the remaining limit) and an optional due date. Cards never appear among the top wallets on Home and are listed after cash and investments.
- **Credit-card bill reminder** 3 days before and on the due date, only while the card has debt.
- **Transfers:** convert by rate or by the amount received (the rate is derived when you only know both amounts); optional fee taken from what the recipient gets, on any transfer; transfer categories (create your own, pick one on the Transfer screen). Same on the web.
- **Report:** transactions grouped by category with count and subtotal (PDF on the phone and the web).
- **Receipt photo** (renamed from "receipt scan"): products are listed as items (ticked); tax, service, fees and rounding (add), discounts (subtract), and payment/change (usually not counted) are listed apart as optional lines. Tax and discounts are pre-ticked only when items + them equal the printed total; the total itself is never a line, only a check. Each amount is checked against the receipt text. "Split per item" takes discounts off the largest lines so no entry is negative.
- **Email transactions:** the saved app password shows as filled (masked, with show/hide) and can be edited in place; providers Gmail/Outlook/Yahoo/iCloud lock the server and port, **Custom** unlocks them; watched senders are empty by default (with an example) and nothing is read until at least one sender is listed.
- Amounts in transaction and wallet rows are right-aligned; the free space sits between the text and the amount. Cash wallets no longer repeat the currency code under their name.
- Docs: README and CHANGELOG in English by default; Indonesian README in `README.id.md`.

## [0.0.4] — pre-release

- **Every currency has 4 decimals** (USD/SGD/MYR/EUR too, not only rupiah). Non-IDR amounts show at least 2 digits (`$5.00`, `$1,060.7925`). **Not compatible with 0.0.3 data** for non-IDR wallets: clear the phone and server data before installing.
- **Total balance = net worth**: cash + investments − credit-card debt, with a Cash · Investments · Credit cards breakdown underneath (phone and web).
- **Large amounts shorten automatically** (`Rp4,1k`, `$6.7m`, `Rp7,2b`) only when the full amount doesn't fit.
- **Financial report as PDF** for a date range (default: the last 30 days): summary, by category, by wallet, transaction list. Phone: Data & backup → Financial report. Web: Reports → Export PDF.
- **Automatic transactions from bank & e-wallet emails** (IMAP + app password, read on the phone every ~15 minutes). They become drafts you confirm; email never goes through the server.
- AI chat: small talk and general money advice are fine (staying around recording, consultation and history analysis); it remembers the conversation and knows 6 months of history and each wallet's balance. **Transfers between wallets from chat** fixed ("tf 50k dari BCA ke OVO", "kirim 100rb ke GoPay", "top up GoPay 200k"), using the rate across currencies.
- **Receipt scan no longer hangs**: OCR failures are handled, every AI engine has a time limit (Gemini Nano could wait forever while its model wasn't ready), the photo is downscaled and rotated per EXIF, and there are visible steps and a Cancel button.
- UI: the Transactions nav icon is solid; brighter wallet colour palette (new yellow); the 2 wallets on Home are the highest balances; wallet detail uses one FAB (Add transaction + Transfer); delete wallet moved to the bottom of Edit wallet; content is no longer cut off above the navigation bar.

## [0.0.3] — pre-release

- **Rupiah can have decimals** (up to 4 digits, for crypto/forex). **Not compatible with 0.0.1/0.0.2 data**: the IDR storage unit changed, so old data must be cleared (phone and server) before installing. `Rp14.125.000` looks the same for whole amounts.
- Wallets can be edited (name, icon, colour, opening balance); type and currency are locked.
- The "Biasa" wallet type is renamed **Tunai/Cash**.
- Keypad: a decimal key for rupiah too (the `k` key stays).

## [0.0.2] — pre-release

- **Much smaller APKs**: R8 (minify + resource shrinking) is on for release. ML Kit ships ~15 MB of native code per CPU, so releases are split into one APK per CPU (19–27 MB, previously one 83 MB APK). Every CPU is supported:
  - `arm64-v8a`: 64-bit phones (almost every phone since ~2017), 25 MB.
  - `armeabi-v7a`: old 32-bit phones, 19 MB.
  - `x86_64` and `x86`: emulators and Intel/ChromeOS devices, 27 MB.
- Files: GitHub Release `v0.0.2` assets (`arthatis-wallet-v0.0.2-<cpu>.apk` + `SHA256SUMS`).
- Amount inputs use the currency's thousands separator while typing (IDR `1.234.567`, USD/SGD/MYR `1,234.56`, EUR `1.234,56`); the transaction keypad has a decimal key for currencies with decimals (previously decimals couldn't be entered).
- New currencies: MYR (RM) and EUR.
- Wallets: the Bank/Cash/E-wallet kinds are replaced by an icon and colour choice; types Regular, Investment, Credit card (investments and card debt shown apart from the cash total). Old data still reads.
- Add/edit screens for recurring transactions in the app and on the web.
- The Gemini Nano status in AI settings is checked with a real trial run, not only the download status, and shows its details.

## [0.0.1] — pre-release

The first installable Arthatis Wallet v2.

- **Android app**: account-free onboarding, multiple wallets and currencies (IDR/USD/SGD) with transfers and editable rates, keypad entry, recurring transactions, budgets, categories, receipt scan (on-device OCR), AI chat (Gemini Nano → server AI → your own API key → local parser), `.arthatis`/CSV/SQLite export/import, shared wallets (Bareng), web-session linking by QR, Indonesian/English, light/dark theme.
- **Server**: E2EE sync (XChaCha20-Poly1305, X25519 + ML-KEM-768), account-free vaults, optional recovery email (SMTP), shared wallets, admin API, vault activation for hosting.
- **Web mirror**: QR login, full CRUD, reports, AI chat, shared wallets, Admin page.
- Not yet: iOS, billing, Android invite deep links. The cryptography has not been audited by a third party.
- Files: GitHub Release `v0.0.1` assets.
