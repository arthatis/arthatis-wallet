package com.arthatis.wallet.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Phonelink
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.arthatis.wallet.AppViewModel
import com.arthatis.wallet.Route
import com.arthatis.wallet.core.Category
import com.arthatis.wallet.core.EntryKind
import com.arthatis.wallet.core.Frequency
import com.arthatis.wallet.core.RatePair
import com.arthatis.wallet.core.formatMinor
import com.arthatis.wallet.core.formatRate
import com.arthatis.wallet.core.parseRateInput
import com.arthatis.wallet.core.supportedCurrencies
import com.arthatis.wallet.core.nextOccurrence
import com.arthatis.wallet.data.HOSTED_URL
import com.arthatis.wallet.data.ServerMode
import com.arthatis.wallet.data.newId
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneOffset

@Composable
private fun NavRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, value: String? = null, onClick: () -> Unit) {
    AppCard(onClick = onClick, padding = 0.dp) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            if (value != null) Hint(value)
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** M07 */
@Composable
fun SettingsScreen(vm: AppViewModel) {
    val ledger by vm.ledger.collectAsState()
    val rates by vm.rates.collectAsState()
    val settings by vm.settings.collectAsState()
    val primary = ledger.profile?.primaryCurrency ?: "IDR"
    var editRate by remember { mutableStateOf<RatePair?>(null) }
    var rateText by remember { mutableStateOf("") }
    var editName by remember { mutableStateOf(false) }
    var nameText by remember { mutableStateOf("") }
    var confirmOut by remember { mutableStateOf(false) }
    val sessions by produceState(0, settings.connected) { value = if (settings.connected) runCatching { vm.vault.webSessions().size }.getOrDefault(0) else 0 }
    Column(Modifier.fillMaxSize()) {
        TopBar(tr("Pengaturan", "Settings"))
        Page {
            AppCard(onClick = { nameText = ledger.profile?.name ?: ""; editName = true }, padding = 16.dp) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(ledger.profile?.name ?: "?", 48.dp)
                    Column(Modifier.weight(1f)) {
                        Text(ledger.profile?.name ?: "—", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                        Hint(if (settings.connected) tr("Terhubung · ${settings.serverUrl?.removePrefix("https://")}", "Connected · ${settings.serverUrl?.removePrefix("https://")}") else tr("Mode offline · data lokal", "Offline mode · local data"))
                    }
                }
            }
            NavRow(Icons.Rounded.AutoAwesome, tr("AI & privasi", "AI & privacy"), "On-device") { vm.go(Route.AiSettings) }
            NavRow(Icons.Rounded.Email, tr("Transaksi dari email", "Transactions from email"), if (vm.prefs.emailEnabled) tr("Aktif", "On") else tr("Nonaktif", "Off")) { vm.go(Route.EmailSync) }
            NavRow(Icons.Rounded.Storage, tr("Data & backup", "Data & backup"), if (settings.lastExportAt > 0) shortDate(com.arthatis.wallet.core.dayOf(settings.lastExportAt)) else null) { vm.go(Route.DataBackup) }
            NavRow(Icons.Rounded.Phonelink, tr("Sesi web", "Web sessions"), if (settings.connected) tr("$sessions aktif", "$sessions active") else tr("Butuh server", "Needs server")) { vm.go(Route.Security) }
            NavRow(Icons.Rounded.Lock, tr("Keamanan & enkripsi", "Security & encryption"), tr("E2EE aktif", "E2EE on")) { vm.go(Route.Security) }
            NavRow(Icons.Rounded.Cloud, "Server", when (settings.serverMode) { ServerMode.None -> tr("Mobile only", "Mobile only"); ServerMode.Hosted -> "wallet.arthatis.com"; ServerMode.Private -> tr("VPS pribadi", "My VPS") }) { vm.go(Route.ServerSettings) }
            NavRow(Icons.Rounded.Repeat, tr("Transaksi berulang", "Recurring"), null) { vm.go(Route.Recurring) }
            NavRow(Icons.Rounded.Savings, tr("Anggaran", "Budgets"), null) { vm.go(Route.Budgets) }
            NavRow(Icons.Rounded.Category, tr("Kategori", "Categories"), null) { vm.go(Route.Categories) }

            AppCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Rounded.DarkMode, null, tint = MaterialTheme.colorScheme.onSurfaceVariant); Text(tr("  Tema", "  Theme"), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface) }
                    ChipRow {
                        AppChip(tr("Terang", "Light"), settings.theme == "light", { vm.setTheme("light") })
                        AppChip(tr("Gelap", "Dark"), settings.theme == "dark", { vm.setTheme("dark") })
                        AppChip(tr("Sistem", "System"), settings.theme == "system", { vm.setTheme("system") })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Rounded.Language, null, tint = MaterialTheme.colorScheme.onSurfaceVariant); Text(tr("  Bahasa", "  Language"), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface) }
                    ChipRow { AppChip("Indonesia", settings.lang == "id", { vm.setLanguage("id") }); AppChip("English", settings.lang == "en", { vm.setLanguage("en") }) }
                }
            }

            AppCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(tr("Mata uang & kurs", "Currency & rates"), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    Hint(tr("Mata uang utama", "Main currency"))
                    ChipRow { supportedCurrencies.forEach { c -> AppChip("${c.code} ${c.symbol}", primary == c.code, { vm.setPrimaryCurrency(c.code) }) } }
                    Hint(tr("Saldo, total, dan input nominal mengikuti mata uang ini.", "Balances, totals and amount input follow this currency."))
                    Hint(tr("Kurs penukaran", "Exchange rates"))
                    listOf("USD", "SGD", "MYR", "EUR").map { RatePair(it, "IDR") }.forEach { pair ->
                        val rate = rates.effective(pair)
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(pair.key, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                            Column(horizontalAlignment = Alignment.End) {
                                AppButton(rate?.let { "Rp${formatRate(it)}" } ?: "—", { rateText = rate?.let { r -> formatRate(r) } ?: ""; editRate = pair }, style = BtnStyle.Text, size = BtnSize.XS)
                                Hint(if (rates.isManual(pair)) tr("Manual", "Manual") else tr("Otomatis · API", "Automatic · API"))
                            }
                        }
                    }
                    Hint(tr("Kurs dipakai untuk total saldo dan konversi antar dompet. Ketuk nilai kurs untuk mengubah manual — kosongkan untuk kembali ke kurs API.", "Rates are used for the total balance and conversions between wallets. Tap a rate to change it — clear it to return to the API rate."))
                    AppButton(tr("Perbarui kurs", "Refresh rates"), { vm.refreshRates() }, style = BtnStyle.Tonal, size = BtnSize.S)
                }
            }

            AppCard {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Ollie(Pose.Wave, 48.dp)
                    Column {
                        Text(tr("Tentang & open source", "About & open source"), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                        Text("Arthatis Wallet v${com.arthatis.wallet.BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                        Hint(tr("Open source (AGPL-3.0) · Dibuat di Jakarta", "Open source (AGPL-3.0) · Made in Jakarta"))
                    }
                }
            }
            if (settings.connected) AppButton(tr("Keluar dari server", "Sign out of server"), { confirmOut = true }, Modifier.fillMaxWidth(), style = BtnStyle.Outlined)
        }
    }
    editRate?.let { pair ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { editRate = null }, title = { Text(tr("Kurs ${pair.key}", "Rate ${pair.key}")) },
            text = { AppTextField(rateText, { rateText = it }, pair.key, keyboard = KeyboardType.Decimal, supporting = tr("Kosongkan untuk kembali ke kurs API.", "Leave empty to use the API rate.")) },
            confirmButton = { androidx.compose.material3.TextButton({ vm.editRate(pair.base, pair.quote, parseRateInput(rateText)); editRate = null }) { Text(tr("Simpan", "Save")) } },
            dismissButton = { androidx.compose.material3.TextButton({ editRate = null }) { Text(tr("Batal", "Cancel")) } },
        )
    }
    if (editName) androidx.compose.material3.AlertDialog(
        onDismissRequest = { editName = false }, title = { Text(tr("Nama", "Name")) },
        text = { AppTextField(nameText, { nameText = it }, tr("Nama", "Name")) },
        confirmButton = { androidx.compose.material3.TextButton({ vm.setName(nameText.trim()); editName = false }) { Text(tr("Simpan", "Save")) } },
        dismissButton = { androidx.compose.material3.TextButton({ editName = false }) { Text(tr("Batal", "Cancel")) } },
    )
    if (confirmOut) ConfirmDialog(tr("Keluar dari server?", "Sign out of the server?"), tr("Data di HP ini tetap ada. Sesi web tidak bisa lagi tersinkron sampai kamu menghubungkan server.", "Data on this phone stays. Web sessions stop syncing until you reconnect."), tr("Keluar", "Sign out"), { confirmOut = false }) { confirmOut = false; vm.disconnectServer() }
}

/** Server choice outside onboarding. */
@Composable
fun ServerSettingsScreen(vm: AppViewModel) {
    val settings by vm.settings.collectAsState()
    var mode by remember { mutableStateOf(settings.serverMode) }
    var url by remember { mutableStateOf(vm.prefs.privateUrl) }
    var tested by remember { mutableStateOf<Boolean?>(null) }
    var testing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val gate = remember { GateState() }
    val updated = tr("Server diperbarui.", "Server updated.")
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        TopBar("Server", onBack = { vm.back() })
        Page {
            Body(tr("Server dipakai untuk web mirror dan wallet bareng. Isinya hanya ciphertext.", "The server powers the web mirror and shared wallets. It only holds ciphertext."))
            ServerPicker(mode, { mode = it; tested = null }, url, { url = it; tested = null }, tested, { scope.launch { testing = true; tested = vm.testServer(url); testing = false } }, testing)
            if (mode != ServerMode.None) GateFields(vm, gate, mode, url, tested == true)
            Hint(tr("Mengganti server mengunggah ulang datamu (terenkripsi) ke server baru. Wallet bareng tetap di server lamanya.", "Switching servers re-uploads your data (encrypted) to the new one. Shared wallets stay on the old server."))
            AppButton(tr("Terapkan", "Apply"), { scope.launch { busy = true; val ok = vm.connect(mode, url.trim().trimEnd('/'), email = gate.email.trim(), emailCode = gate.code); busy = false; if (ok) { vm.back(); vm.say(updated) } } }, Modifier.fillMaxWidth(), enabled = (mode != ServerMode.Private || tested == true) && (mode == ServerMode.None || gate.ready), loading = busy)
        }
    }
}

/** Not connected / sync failed. Everything still works locally. */
@Composable
fun OfflinePanel(vm: AppViewModel, onLocal: (() -> Unit)? = null) {
    EmptyState(Pose.Worried, tr("Kamu sedang offline", "You're offline"), tr("Tenang — semua tetap bisa dicatat. Data akan tersinkron otomatis begitu internet kembali.", "Don't worry — you can still log everything. Data syncs automatically once the internet is back."),
        action = tr("Coba lagi", "Try again"), onAction = { vm.runSync(); vm.loadGroups() })
    if (onLocal != null) AppButton(tr("Lihat data lokal", "View local data"), onLocal, Modifier.fillMaxWidth(), style = BtnStyle.Text)
}

@Composable
fun RecurringScreen(vm: AppViewModel) {
    val ledger by vm.ledger.collectAsState()
    val items = ledger.recurring.filter { it.deletedAt == null }
    Column(Modifier.fillMaxSize()) {
        TopBar(tr("Transaksi berulang", "Recurring"), onBack = { vm.back() }, actions = { AppIconButton(Icons.Rounded.Add, { vm.go(Route.RecurringForm()) }, description = tr("Tambah", "Add")) })
        Page(center = items.isEmpty()) {
            Body(tr("Dicatat otomatis di HP ini sesuai jadwal. Pengingat H-1 berupa notifikasi lokal, bukan dari server.", "Logged automatically on this phone on schedule. The day-before reminder is a local notification, not from a server."))
            if (items.isEmpty()) EmptyState(Pose.Sleepy, tr("Belum ada yang berulang", "Nothing recurring yet"), tr("Gaji, tagihan, langganan — catat sekali.", "Salary, bills, subscriptions — log once."), action = tr("Tambah", "Add"), onAction = { vm.go(Route.RecurringForm()) })
            items.forEach { r ->
                val cur = ledger.wallet(r.walletId)?.currency ?: "IDR"
                val freq = when (r.frequency) { Frequency.daily -> tr("Harian", "Daily"); Frequency.weekly -> tr("Mingguan", "Weekly"); Frequency.monthly -> tr("Bulanan", "Monthly"); Frequency.yearly -> tr("Tahunan", "Yearly") }
                val next = nextOccurrence(r, LocalDate.now(ZoneOffset.UTC))
                AppCard(onClick = { vm.go(Route.RecurringForm(r.id)) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(r.name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                                Hint("$freq · ${formatMinor(r.amountMinor, cur)}" + (next?.let { " · ${tr("berikutnya", "next")} ${shortDate(it)}" } ?: ""))
                            }
                            AppSwitch(!r.paused, { vm.viewModelScope_launch { vm.repo.saveRecurring(r.copy(paused = !it)) } })
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(tr("Ingatkan H-1", "Remind the day before"), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            AppSwitch(r.reminder, { vm.viewModelScope_launch { vm.repo.saveRecurring(r.copy(reminder = it)) } })
                        }
                        AppButton(tr("Hapus", "Delete"), { vm.viewModelScope_launch { vm.repo.deleteRecurring(r) } }, style = BtnStyle.Text, size = BtnSize.XS)
                    }
                }
            }
        }
    }
}

@Composable
fun CategoriesScreen(vm: AppViewModel) {
    val ledger by vm.ledger.collectAsState()
    var adding by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(EntryKind.expense) }
    Column(Modifier.fillMaxSize()) {
        TopBar(tr("Kategori", "Categories"), onBack = { vm.back() }, actions = { AppIconButton(Icons.Rounded.Category, { adding = true }, description = tr("Tambah", "Add")) })
        Page {
            Body(tr("Kategori bawaan tidak bisa dihapus, hanya disembunyikan.", "Default categories can't be deleted, only hidden."))
            ledger.liveCategories.forEach { c ->
                ListItem(categoryName(c), supporting = when (c.kind) { EntryKind.income -> tr("Pemasukan", "Income"); EntryKind.expense -> tr("Pengeluaran", "Expense"); EntryKind.transfer -> tr("Transfer", "Transfer") }, leading = categoryIcon(c.key), trailing = {
                    if (c.key != null) AppSwitch(!c.hidden, { vm.viewModelScope_launch { vm.repo.saveCategory(c.copy(hidden = !it)) } })
                    else AppButton(tr("Hapus", "Delete"), { vm.viewModelScope_launch { vm.repo.saveCategory(c.copy(deletedAt = vm.repo.now())) } }, style = BtnStyle.Text, size = BtnSize.XS)
                })
            }
        }
    }
    if (adding) androidx.compose.material3.AlertDialog(
        onDismissRequest = { adding = false }, title = { Text(tr("Kategori baru", "New category")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AppTextField(name, { name = it }, tr("Nama", "Name"))
                ChipRow { AppChip(tr("Pengeluaran", "Expense"), kind == EntryKind.expense, { kind = EntryKind.expense }); AppChip(tr("Pemasukan", "Income"), kind == EntryKind.income, { kind = EntryKind.income }); AppChip(tr("Transfer", "Transfer"), kind == EntryKind.transfer, { kind = EntryKind.transfer }) }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton({ if (name.isNotBlank()) { vm.viewModelScope_launch { vm.repo.saveCategory(Category(newId(), null, name.trim(), kind, "#717970", false, 100 + ledger.categories.size, 0)) }; name = ""; adding = false } }) { Text(tr("Simpan", "Save")) } },
        dismissButton = { androidx.compose.material3.TextButton({ adding = false }) { Text(tr("Batal", "Cancel")) } },
    )
}

@Composable
fun AiSettingsScreen(vm: AppViewModel) {
    val settings by vm.settings.collectAsState()
    val p = vm.prefs
    var useServer by remember { mutableStateOf(p.aiUseServer) }
    var useCloud by remember { mutableStateOf(p.aiUseCloudKey) }
    var provider by remember { mutableStateOf(p.aiProvider.takeIf { it != "none" } ?: "anthropic") }
    var key by remember { mutableStateOf(p.aiKey ?: "") }
    var model by remember { mutableStateOf(p.aiModel) }
    Column(Modifier.fillMaxSize()) {
        TopBar(tr("AI & privasi", "AI & privacy"), onBack = { vm.back() })
        Page {
            val nano by produceState<com.arthatis.wallet.ai.ChatEngine.NanoStatus?>(null) { value = vm.nanoStatus() }
            AppCard { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(tr("1 · Di perangkat (Gemini Nano)", "1 · On device (Gemini Nano)"), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                Hint(tr("Dicoba lebih dulu bila HP mendukung. Tidak ada data yang keluar dari perangkat.", "Tried first if your phone supports it. Nothing leaves the device."))
                when (nano) {
                    com.arthatis.wallet.ai.ChatEngine.NanoStatus.Ready -> Text(tr("Tersedia di HP ini ✓", "Available on this phone ✓"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    com.arthatis.wallet.ai.ChatEngine.NanoStatus.NotInstalled -> Text(tr("Model Gemini Nano belum bisa dipakai aplikasi ini (belum diunduh sistem, atau AICore belum siap). Diperiksa dengan uji coba nyata. Sampai siap, aplikasi memakai pilihan berikutnya.", "The Gemini Nano model is not usable by this app yet (not downloaded by the system, or AICore not ready). Checked with a real test run. Until then the app uses the next option."), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    com.arthatis.wallet.ai.ChatEngine.NanoStatus.Unsupported -> Text(tr("Tidak tersedia di HP ini. Aplikasi memakai pilihan berikutnya.", "Not available on this phone. The app uses the next option."), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    null -> Hint(tr("Memeriksa…", "Checking…"))
                }
                if (nano == com.arthatis.wallet.ai.ChatEngine.NanoStatus.NotInstalled || nano == com.arthatis.wallet.ai.ChatEngine.NanoStatus.Unsupported) Hint(tr("Detail: ", "Detail: ") + vm.nanoDetail())
            } }
            AppCard { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(tr("2 · AI di servermu", "2 · AI on your server"), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface); Hint(tr("Ollama di VPS-mu. Teks pertanyaan dikirim ke servermu, tidak terenkripsi E2EE.", "Ollama on your VPS. The question text is sent to your server, not E2EE.")) }
                    AppSwitch(useServer, { useServer = it; p.aiUseServer = it }, Modifier) }
                if (useServer && !settings.connected) Text(tr("Hubungkan server dulu.", "Connect a server first."), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            } }
            AppCard { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(tr("3 · API pribadi (kunci milikmu)", "3 · Your own API key"), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface); Hint(tr("Teks pertanyaan dikirim ke penyedia yang kamu pilih. Kunci disimpan terenkripsi di HP ini.", "The question text is sent to the provider you pick. The key is stored encrypted on this phone.")) }
                    AppSwitch(useCloud, { useCloud = it; p.aiUseCloudKey = it; if (it) p.aiProvider = provider }) }
                if (useCloud) {
                    ChipRow { listOf("anthropic" to "Anthropic", "openai" to "OpenAI", "deepseek" to "DeepSeek").forEach { (id, label) -> AppChip(label, provider == id, { provider = id; p.aiProvider = id }) } }
                    AppTextField(key, { key = it; p.aiKey = it.ifBlank { null } }, tr("Kunci API", "API key"), keyboard = KeyboardType.Password)
                    AppTextField(model, { model = it; p.aiModel = it }, tr("Nama model (opsional)", "Model name (optional)"), supporting = tr("Kosong = bawaan penyedia.", "Empty = provider default."))
                }
            } }
            Hint(tr("Hasil AI selalu berupa draf yang kamu konfirmasi. Tidak ada yang tersimpan otomatis.", "AI output is always a draft you confirm. Nothing is saved automatically."))
        }
    }
}

/** Monthly budget per expense category, in the main currency. Bills are fixed costs and are not budgeted. */
@Composable
fun BudgetsScreen(vm: AppViewModel) {
    val ledger by vm.ledger.collectAsState()
    val rates by vm.rates.collectAsState()
    val currency = ledger.profile?.primaryCurrency ?: "IDR"
    val lines = com.arthatis.wallet.core.budgetLines(ledger.budgets, ledger.liveTxs, ledger.liveWallets.associateBy { it.id }, currency, rates, LocalDate.now(ZoneOffset.UTC))?.associateBy { it.categoryId } ?: emptyMap()
    var editing by remember { mutableStateOf<Category?>(null) }
    var text by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        TopBar(tr("Anggaran", "Budgets"), onBack = { vm.back() })
        Page {
            Body(tr("Anggaran bulanan per kategori dalam $currency. Tagihan bersifat tetap dan tidak dianggarkan.", "Monthly budget per category in $currency. Bills are fixed and not budgeted."))
            ledger.liveCategories.filter { it.kind == EntryKind.expense && !it.hidden && it.key != "bills" }.forEach { c ->
                val line = lines[c.id]
                AppCard(onClick = { text = line?.budgetMinor?.let { com.arthatis.wallet.core.toMajor(it, currency).toPlainString() } ?: ""; editing = c }) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(categoryName(c), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                            Hint(if (line != null) "${formatMinor(line.spentMinor, currency)} / ${formatMinor(line.budgetMinor, currency)}" else tr("Belum diatur", "Not set"))
                        }
                        if (line != null) ProgressBar(line.fraction)
                    }
                }
            }
        }
    }
    editing?.let { c ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { editing = null }, title = { Text(categoryName(c)) },
            text = { AmountField(text, { text = it }, currency, tr("Anggaran per bulan ($currency)", "Monthly budget ($currency)"), supporting = tr("Kosongkan untuk menghapus.", "Leave empty to remove.")) },
            confirmButton = { androidx.compose.material3.TextButton({
                val minor = com.arthatis.wallet.core.evaluateExpression(text)?.let { runCatching { com.arthatis.wallet.core.toMinor(it, currency) }.getOrNull() }
                val existing = ledger.budgets.firstOrNull { it.categoryId == c.id }
                vm.viewModelScope_launch {
                    if (minor == null || minor <= 0) existing?.let { vm.repo.saveBudget(it.copy(deletedAt = vm.repo.now())) }
                    else vm.repo.saveBudget((existing ?: com.arthatis.wallet.core.Budget("bud_${c.id}", c.id, minor, 0)).copy(monthlyMinor = minor, deletedAt = null))
                }
                editing = null
            }) { Text(tr("Simpan", "Save")) } },
            dismissButton = { androidx.compose.material3.TextButton({ editing = null }) { Text(tr("Batal", "Cancel")) } },
        )
    }
}
