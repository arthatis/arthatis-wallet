package com.arthatis.wallet.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.arthatis.wallet.AppViewModel
import com.arthatis.wallet.Route
import com.arthatis.wallet.core.CategoryRef
import com.arthatis.wallet.core.EntryKind
import com.arthatis.wallet.core.Frequency
import com.arthatis.wallet.core.NamedRef
import com.arthatis.wallet.core.Recurring
import com.arthatis.wallet.core.Tx
import com.arthatis.wallet.core.WalletKind
import com.arthatis.wallet.core.currencyInfo
import com.arthatis.wallet.core.dayOf
import com.arthatis.wallet.core.epochMillis
import com.arthatis.wallet.core.evaluateExpression
import com.arthatis.wallet.core.formatMinor
import com.arthatis.wallet.core.formatRate
import com.arthatis.wallet.core.pairFor
import com.arthatis.wallet.core.parseChat
import com.arthatis.wallet.core.parseRateInput
import com.arthatis.wallet.core.supportedCurrencies
import com.arthatis.wallet.core.toMinor
import com.arthatis.wallet.core.toMajor
import com.arthatis.wallet.core.walletBalance
import com.arthatis.wallet.data.newId
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneOffset

@Composable
private fun Keypad(onKey: (String) -> Unit) {
    val rows = listOf(listOf("7", "8", "9", "⌫"), listOf("4", "5", "6", "+"), listOf("1", "2", "3", "−"), listOf("k", "0", "000", "×"))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { key ->
                    Box(
                        Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(Radius.medium))
                            .background(if (key in listOf("+", "−", "×", "⌫", "k")) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
                            .clickable { onKey(key) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (key == "⌫") androidx.compose.material3.Icon(Icons.AutoMirrored.Rounded.Backspace, tr("Hapus", "Delete"), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                        else Text(key, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
/** M05: amount keypad, category, wallet, date, note — plus quick text, recurring, and receipt scan. Also edits an existing entry. */
@Composable
fun AddTxScreen(vm: AppViewModel, route: Route.AddTx) {
    val ledger by vm.ledger.collectAsState()
    val existing = route.editId?.let { id -> ledger.txs.firstOrNull { it.id == id } }
    val wallets = ledger.liveWallets
    var kind by remember { mutableStateOf(existing?.kind ?: EntryKind.expense) }
    var expr by remember { mutableStateOf(existing?.let { com.arthatis.wallet.core.toMajor(it.amountMinor, ledger.wallet(it.walletId)?.currency ?: "IDR").toPlainString() } ?: "") }
    var categoryId by remember { mutableStateOf(existing?.categoryId) }
    var walletId by remember { mutableStateOf(existing?.walletId ?: route.walletId ?: wallets.firstOrNull()?.id) }
    var note by remember { mutableStateOf(existing?.note ?: "") }
    var date by remember { mutableStateOf(existing?.occurredAt?.let { epochMillis(dayOf(it)) } ?: epochMillis(LocalDate.now(ZoneOffset.UTC))) }
    var quick by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf(false) }
    var freq by remember { mutableStateOf(Frequency.monthly) }
    var remind by remember { mutableStateOf(true) }
    var confirmDelete by remember { mutableStateOf(false) }
    val wallet = ledger.wallet(walletId)
    val currency = wallet?.currency ?: "IDR"
    val value = evaluateExpression(expr.replace('×', '*').replace('−', '-'))
    val minor = value?.let { runCatching { toMinor(it, currency) }.getOrNull() }
    val cats = ledger.liveCategories.filter { !it.hidden && it.kind == kind }
    val fallbackName = categoryName(ledger.category(categoryId))
    val savedMsg = tr("Transaksi disimpan", "Transaction saved")
    val offlineSuffix = tr(" · offline", " · offline")
    val undoLabel = tr("Urungkan", "Undo")

    fun key(k: String) {
        expr = when (k) { "⌫" -> expr.dropLast(1); "k" -> expr + "k"; else -> expr + k }
    }
    var quickBusy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun applyQuick() {
        scope.launch {
            quickBusy = true
            val draft = vm.quickDraft(quick)
            quickBusy = false
            if (draft != null && draft.kind != EntryKind.transfer) {
                kind = draft.kind; categoryId = draft.categoryId; draft.walletId?.let { walletId = it }
                expr = toMajor(draft.amountMinor, ledger.wallet(draft.walletId ?: walletId)?.currency ?: currency).toPlainString(); note = quick
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(if (existing == null) tr("Tambah transaksi", "Add transaction") else tr("Ubah transaksi", "Edit transaction"), onBack = { vm.back() }, actions = {
            if (existing == null) AppIconButton(Icons.Rounded.CameraAlt, { vm.go(Route.Receipt) }, description = tr("Scan struk", "Scan receipt"))
            else AppIconButton(Icons.Rounded.Delete, { confirmDelete = true }, description = tr("Hapus", "Delete"))
        })
        Page {
            ChipRow {
                AppChip(tr("Pengeluaran", "Expense"), kind == EntryKind.expense, { kind = EntryKind.expense; categoryId = null })
                AppChip(tr("Pemasukan", "Income"), kind == EntryKind.income, { kind = EntryKind.income; categoryId = null })
                DateChip(date) { date = it }
            }
            Text(minor?.let { formatMinor(it, currency) } ?: expr.ifBlank { formatMinor(0, currency) }, Modifier.fillMaxWidth(), style = MaterialTheme.typography.headlineLarge, color = if (minor == null && expr.isNotBlank()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
            Hint(tr("Nominal", "Amount"), Modifier.fillMaxWidth())
            if (existing == null) AppTextField(quick, { quick = it }, tr("Ketik cepat", "Quick entry"), supporting = tr("Ketik “bakso 25k” — AI akan isi sisanya.", "Type “lunch 25k” — AI fills in the rest."), trailing = { if (quickBusy) androidx.compose.material3.CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) else AppIconButton(Icons.Rounded.AutoAwesome, { applyQuick() }, description = tr("Isi otomatis", "Autofill")) })
            Keypad(::key)
            FieldLabel(tr("Kategori", "Category"))
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                cats.forEach { c -> AppChip(categoryName(c), categoryId == c.id, { categoryId = c.id }, leading = categoryIcon(c.key)) }
            }
            FieldLabel(tr("Dompet", "Wallet"))
            WalletPicker(wallet, ledger, { walletId = it.id })
            AppTextField(note, { note = it }, tr("Catatan", "Note"), supporting = tr("Catatan (opsional)", "Note (optional)"), singleLine = false)
            if (existing == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(tr("Ulangi otomatis", "Repeat automatically"), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface); Hint(tr("Dicatat sendiri sesuai jadwal; pengingat H-1 hanya di HP ini.", "Logged on schedule; the day-before reminder stays on this phone.")) }
                    AppSwitch(repeat, { repeat = it })
                }
                if (repeat) {
                    ChipRow { listOf(Frequency.daily to tr("Harian", "Daily"), Frequency.weekly to tr("Mingguan", "Weekly"), Frequency.monthly to tr("Bulanan", "Monthly"), Frequency.yearly to tr("Tahunan", "Yearly")).forEach { (f, l) -> AppChip(l, freq == f, { freq = f }) } }
                    Row(verticalAlignment = Alignment.CenterVertically) { Text(tr("Ingatkan H-1", "Remind the day before"), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface); AppSwitch(remind, { remind = it }) }
                }
            }
            val categoryOk = categoryId != null
            AppButton(tr("Simpan transaksi", "Save transaction"), {
                val w = wallet ?: return@AppButton
                val amount = minor ?: return@AppButton
                val saved = (existing ?: Tx(newId(), w.id, null, categoryId, kind, amount, occurredAt = date, updatedAt = 0)).copy(
                    walletId = w.id, categoryId = categoryId, kind = kind, amountMinor = amount, note = note.trim(), occurredAt = date,
                )
                val day = dayOf(date)
                if (existing == null && repeat) {
                    val r = Recurring(newId(), note.trim().ifBlank { fallbackName }, w.id, categoryId!!, kind, amount, freq,
                        dayOfMonth = day.dayOfMonth, weekday = day.dayOfWeek.value, month = day.monthValue, reminder = remind, lastRunDay = epochMillis(LocalDate.now(ZoneOffset.UTC)), updatedAt = 0)
                    vm.viewModelScope_launch { vm.repo.saveRecurring(r) }
                    vm.saveTx(saved.copy(recurringId = r.id))
                } else vm.saveTx(saved)
                vm.back()
                vm.say(savedMsg + if (vm.settings.value.connected) "" else offlineSuffix, undoLabel) { vm.deleteTx(saved) }
            }, Modifier.fillMaxWidth(), enabled = minor != null && minor > 0 && wallet != null && categoryOk)
            if (!categoryOk && minor != null) Hint(tr("Pilih kategori dulu.", "Choose a category first."))
        }
    }
    if (confirmDelete && existing != null) ConfirmDialog(tr("Hapus transaksi ini?", "Delete this transaction?"), tr("Saldo dompet dihitung ulang.", "The wallet balance is recalculated."), tr("Hapus", "Delete"), { confirmDelete = false }) {
        confirmDelete = false; vm.deleteTx(existing); vm.back()
    }
}

/** M17 */
@Composable
fun AddWalletScreen(vm: AppViewModel) {
    val ledger by vm.ledger.collectAsState()
    var name by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(WalletKind.rekening) }
    var currency by remember { mutableStateOf(ledger.profile?.primaryCurrency ?: "IDR") }
    var opening by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        TopBar(tr("Tambah dompet", "Add wallet"), onBack = { vm.back() })
        Page {
            AppTextField(name, { name = it }, tr("Nama dompet", "Wallet name"), supporting = tr("Contoh: Wise, Jenius, Celengan.", "Example: Wise, Jenius, Piggy bank."))
            FieldLabel(tr("Jenis", "Type"))
            ChipRow { WalletKind.entries.forEach { k -> AppChip(walletKindLabel(k), kind == k, { kind = k }) } }
            FieldLabel(tr("Mata uang", "Currency"))
            ChipRow { supportedCurrencies.forEach { c -> AppChip("${c.code} ${c.symbol}", currency == c.code, { currency = c.code }) } }
            Hint(tr("1 dompet hanya 1 mata uang — tidak bisa diubah setelah dompet dibuat.", "1 wallet holds 1 currency — it can't be changed once the wallet is created."))
            AppTextField(opening, { opening = it }, tr("Saldo awal (opsional)", "Opening balance (optional)"), keyboard = KeyboardType.Decimal, supporting = tr("Dalam mata uang dompet yang dipilih.", "In the wallet's currency."))
            AppButton(tr("Simpan dompet", "Save wallet"), {
                val minor = evaluateExpression(opening)?.let { runCatching { toMinor(it, currency) }.getOrNull() } ?: 0L
                vm.saveWallet(name.trim(), kind, currency, minor); vm.back()
            }, Modifier.fillMaxWidth(), enabled = name.isNotBlank())
        }
    }
}

/** M18: transfers across currencies always show the conversion card with an editable rate. */
@Composable
fun TransferScreen(vm: AppViewModel, route: Route.Transfer) {
    val ledger by vm.ledger.collectAsState()
    val rates by vm.rates.collectAsState()
    val scope = rememberCoroutineScope()
    var fromId by remember { mutableStateOf(route.fromId ?: ledger.liveWallets.firstOrNull()?.id) }
    var toId by remember { mutableStateOf(ledger.liveWallets.firstOrNull { it.id != fromId }?.id) }
    var amount by remember { mutableStateOf("") }
    var rateText by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    val transferSaved = tr("Transfer disimpan", "Transfer saved")
    val from = ledger.wallet(fromId); val to = ledger.wallet(toId)
    val minor = evaluateExpression(amount)?.let { v -> from?.let { runCatching { toMinor(v, it.currency) }.getOrNull() } }
    val different = from != null && to != null && from.currency != to.currency
    val pair = if (different) pairFor(from!!.currency, to!!.currency) else null
    val apiRate = pair?.let { rates.api(it) }
    val manual = parseRateInput(rateText)
    val effective = manual ?: pair?.let { rates.effective(it) }
    val received = if (minor != null && from != null && to != null) {
        if (!different) minor else effective?.let { r -> com.arthatis.wallet.core.Rates(emptyMap(), mapOf(pair!!.key to r)).convert(minor, from.currency, to.currency) }
    } else null
    Column(Modifier.fillMaxSize()) {
        TopBar(tr("Transfer antar dompet", "Transfer between wallets"), onBack = { vm.back() })
        Page {
            FieldLabel(tr("Dari", "From"))
            WalletPicker(from, ledger, { fromId = it.id; rateText = "" }, exclude = toId)
            FieldLabel(tr("Ke", "To"))
            WalletPicker(to, ledger, { toId = it.id; rateText = "" }, exclude = fromId)
            AppTextField(amount, { amount = it }, tr("Nominal", "Amount"), keyboard = KeyboardType.Decimal, supporting = if (from != null && minor != null) tr("Saldo ${from.name} setelah transfer: ${formatMinor(walletBalance(from, ledger.txs) - minor, from.currency)}.", "${from.name} balance after transfer: ${formatMinor(walletBalance(from, ledger.txs) - minor, from.currency)}.") else null)
            if (different && from != null && to != null && pair != null) {
                AppCard(padding = 20.dp) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(tr("Konversi mata uang", "Currency conversion"), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                        Hint(tr("Mata uang dompet tujuan berbeda — nominal dikonversi otomatis.", "The destination wallet uses a different currency — the amount is converted automatically."))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(formatMinor(minor ?: 0, from.currency), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                            Text("=", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(received?.let { formatMinor(it, to.currency) } ?: "—", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                        }
                        Hint(received?.let { tr("${to.name} terima ${formatMinor(it, to.currency)}.", "${to.name} receives ${formatMinor(it, to.currency)}.") } ?: tr("Kurs belum tersedia — isi kurs manual.", "No rate available — enter one manually."))
                        AppTextField(rateText, { rateText = it }, tr("Kurs ${pair.key}", "Rate ${pair.key}"), keyboard = KeyboardType.Decimal,
                            supporting = if (apiRate != null) tr("Otomatis dari API (${formatRate(apiRate)}) · ubah jika kurs terasa kurang akurat. Kosongkan untuk kembali ke kurs API.", "Automatic from the API (${formatRate(apiRate)}) · change it if it feels off. Clear it to use the API rate again.") else tr("Tidak ada kurs API. Isi manual.", "No API rate. Enter one manually."))
                    }
                }
            }
            if (failed) Text(tr("Transfer gagal: kurs belum tersedia.", "Transfer failed: no rate available."), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            AppButton(tr("Transfer ${minor?.let { formatMinor(it, from?.currency ?: "IDR") } ?: ""}".trim(), "Transfer ${minor?.let { formatMinor(it, from?.currency ?: "IDR") } ?: ""}".trim()), {
                scope.launch {
                    val ok = vm.transfer(from!!, to!!, minor!!, manual, "")
                    if (ok) { vm.back(); vm.say(transferSaved) } else failed = true
                }
            }, Modifier.fillMaxWidth(), enabled = from != null && to != null && from.id != to.id && minor != null && minor > 0 && (!different || effective != null))
        }
    }
}
