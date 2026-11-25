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
import androidx.compose.foundation.layout.width
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
import com.arthatis.wallet.core.legacyIcon
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
private fun Keypad(currency: String, onKey: (String) -> Unit) {
    val info = com.arthatis.wallet.core.currencyInfo(currency)
    // Every currency gets a decimal key labelled with its own mark; IDR also keeps the 45k shortcut on an extra row.
    val rows = buildList {
        add(listOf("7", "8", "9", "⌫")); add(listOf("4", "5", "6", "+")); add(listOf("1", "2", "3", "−"))
        add(listOf(info.decimal.toString(), "0", "000", "×"))
        if (currency == "IDR") add(listOf("k", "", "", ""))
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { key ->
                    if (key.isEmpty()) { Box(Modifier.weight(1f).height(52.dp)); return@forEach }
                    Box(
                        Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(Radius.medium))
                            .background(if (key in listOf("+", "−", "×", "⌫", "k", info.decimal.toString())) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
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
        val info = com.arthatis.wallet.core.currencyInfo(currency)
        val token = expr.takeLastWhile { it.isDigit() || it == '.' }
        expr = when {
            k == "⌫" -> expr.dropLast(1)
            k == info.decimal.toString() -> if ('.' in token) expr else (if (token.isEmpty()) expr + "0." else "$expr.")
            // Never take more fraction digits than the currency has: they would be rounded away silently.
            k.all { it.isDigit() } && '.' in token && token.substringAfter('.').length + k.length > info.scale -> expr
            else -> expr + k
        }
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
            if (existing == null) AppIconButton(Icons.Rounded.CameraAlt, { vm.go(Route.Receipt) }, description = tr("Foto struk", "Receipt photo"))
            else AppIconButton(Icons.Rounded.Delete, { confirmDelete = true }, description = tr("Hapus", "Delete"))
        })
        Page {
            ChipRow {
                AppChip(tr("Pengeluaran", "Expense"), kind == EntryKind.expense, { kind = EntryKind.expense; categoryId = null })
                AppChip(tr("Pemasukan", "Income"), kind == EntryKind.income, { kind = EntryKind.income; categoryId = null })
                DateChip(date) { date = it }
            }
            val typedPlain = expr.isNotEmpty() && expr.all { it.isDigit() || it == '.' }
            Text(if (typedPlain) com.arthatis.wallet.core.currencyInfo(currency).symbol + com.arthatis.wallet.core.displayAmount(expr, currency).text else minor?.let { formatMinor(it, currency) } ?: com.arthatis.wallet.core.displayAmount(expr, currency).text.ifBlank { formatMinor(0, currency) }, Modifier.fillMaxWidth(), style = MaterialTheme.typography.headlineLarge, color = if (minor == null && expr.isNotBlank()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
            Hint(tr("Nominal", "Amount"), Modifier.fillMaxWidth())
            if (existing == null) AppTextField(quick, { quick = it }, tr("Ketik cepat", "Quick entry"), supporting = tr("Ketik “bakso 25k” — AI akan isi sisanya.", "Type “lunch 25k” — AI fills in the rest."), trailing = { if (quickBusy) androidx.compose.material3.CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) else AppIconButton(Icons.Rounded.AutoAwesome, { applyQuick() }, description = tr("Isi otomatis", "Autofill")) })
            Keypad(currency, ::key)
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

/** M17 (add) and edit. When editing, type and currency stay fixed; name, icon, colour and opening balance can change. */
@Composable
fun AddWalletScreen(vm: AppViewModel, editId: String? = null) {
    val ledger by vm.ledger.collectAsState()
    val existing = editId?.let { ledger.wallet(it) }
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var kind by remember { mutableStateOf(existing?.kind?.let { if (it == WalletKind.investasi || it == WalletKind.kartu) it else WalletKind.biasa } ?: WalletKind.biasa) }
    var icon by remember { mutableStateOf(existing?.let { it.icon ?: legacyIcon(it.kind) } ?: "wallet") }
    var color by remember { mutableStateOf(existing?.color) }
    var currency by remember { mutableStateOf(existing?.currency ?: ledger.profile?.primaryCurrency ?: "IDR") }
    var confirmDelete by remember { mutableStateOf(false) }
    fun plain(minor: Long, cur: String) = com.arthatis.wallet.core.toMajor(minor, cur).stripTrailingZeros().toPlainString()
    // A card's opening value is the bill it already had (a debt, stored negative); it is entered as a positive amount.
    var opening by remember { mutableStateOf(existing?.let { if (it.openingMinor == 0L) "" else plain(if (it.kind == WalletKind.kartu) -it.openingMinor else it.openingMinor, it.currency) } ?: "") }
    var limit by remember { mutableStateOf(existing?.creditLimitMinor?.let { plain(it, existing.currency) } ?: "") }
    var dueDay by remember { mutableStateOf(existing?.dueDay?.toString() ?: "") }
    Column(Modifier.fillMaxSize()) {
        TopBar(if (existing == null) tr("Tambah dompet", "Add wallet") else tr("Ubah dompet", "Edit wallet"), onBack = { vm.back() })
        Page {
            AppTextField(name, { name = it }, tr("Nama dompet", "Wallet name"), supporting = tr("Contoh: Wise, Jenius, Celengan.", "Example: Wise, Jenius, Piggy bank."))
            FieldLabel(tr("Tipe", "Type"))
            ChipRow { listOf(WalletKind.biasa, WalletKind.investasi, WalletKind.kartu).forEach { k -> AppChip(walletKindLabel(k), kind == k, { if (existing == null) { kind = k; if (icon == "wallet" || icon == "trending" || icon == "card") icon = legacyIcon(k) } }, enabled = existing == null) } }
            if (existing != null) Hint(tr("Tipe dan mata uang tidak bisa diubah setelah dompet dibuat.", "Type and currency can't be changed once the wallet is created."))
            if (kind == WalletKind.kartu) Hint(tr("Limit kartu bukan uangmu: saldo kartu 0 bila tidak dipakai dan minus (utang) bila dipakai. Utangnya mengurangi total saldo di Beranda.", "A card's limit isn't your money: the card is 0 when unused and negative (debt) when used. The debt reduces the total balance on Home."))
            if (kind == WalletKind.investasi) Hint(tr("Ikut dihitung di total saldo, dan tampil terpisah di rinciannya.", "Counted in the total balance and shown separately in its breakdown."))
            WalletStyleFields(icon, { icon = it }, color, { color = it })
            FieldLabel(tr("Mata uang", "Currency"))
            ChipRow { supportedCurrencies.forEach { c -> AppChip("${c.code} ${c.symbol}", currency == c.code, { if (existing == null) currency = c.code }, enabled = existing == null) } }
            if (existing == null) Hint(tr("1 dompet hanya 1 mata uang — tidak bisa diubah setelah dompet dibuat.", "1 wallet holds 1 currency — it can't be changed once the wallet is created."))
            if (kind == WalletKind.kartu) {
                AmountField(opening, { opening = it }, currency, tr("Tagihan saat ini (opsional)", "Current bill (optional)"), supporting = tr("Utang kartu yang sudah ada sebelum mulai mencatat. Bukan pengeluaran baru.", "Debt the card already had before you started tracking. Not a new expense."))
                AmountField(limit, { limit = it }, currency, tr("Limit (opsional)", "Limit (optional)"), supporting = tr("Hanya info sisa limit; tidak pernah dihitung sebagai saldo.", "Only for the remaining limit; never counted as a balance."))
                AppTextField(dueDay, { v -> dueDay = v.filter(Char::isDigit).take(2) }, tr("Tanggal jatuh tempo (1–31, opsional)", "Due date (1–31, optional)"), keyboard = KeyboardType.Number,
                    supporting = tr("Pengingat bayar tagihan muncul H-3 dan pada hari jatuh tempo bila masih ada utang.", "A bill reminder appears 3 days before and on the due date while there is debt."),
                    error = dueDay.isNotEmpty() && dueDay.toInt() !in 1..31)
            } else AmountField(opening, { opening = it }, currency, tr("Saldo awal (opsional)", "Opening balance (optional)"), supporting = tr("Uang yang sudah ada sebelum mulai mencatat. Tidak dihitung sebagai pemasukan.", "Money already there before you started tracking. Not counted as income."))
            val dueOk = dueDay.isEmpty() || dueDay.toInt() in 1..31
            AppButton(if (existing == null) tr("Simpan dompet", "Save wallet") else tr("Simpan perubahan", "Save changes"), {
                val entered = evaluateExpression(opening)?.let { runCatching { toMinor(it, currency) }.getOrNull() } ?: 0L
                val card = kind == WalletKind.kartu
                val minor = if (card) -kotlin.math.abs(entered) else entered
                val limitMinor = if (card) evaluateExpression(limit)?.let { runCatching { toMinor(it, currency) }.getOrNull() }?.takeIf { it > 0 } else null
                val due = if (card) dueDay.toIntOrNull()?.takeIf { it in 1..31 } else null
                if (existing == null) vm.saveWallet(name.trim(), kind, currency, minor, icon, color, limitMinor, due)
                else vm.updateWallet(existing.copy(name = name.trim(), icon = icon, color = color, openingMinor = minor, creditLimitMinor = limitMinor, dueDay = due))
                vm.back()
            }, Modifier.fillMaxWidth(), enabled = name.isNotBlank() && dueOk)
            if (existing != null) {
                androidx.compose.foundation.layout.Spacer(Modifier.height(24.dp))
                androidx.compose.material3.OutlinedButton(
                    { confirmDelete = true }, Modifier.fillMaxWidth().height(48.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    androidx.compose.material3.Icon(Icons.Rounded.Delete, null, Modifier.size(18.dp))
                    androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
                    Text(tr("Hapus dompet", "Delete wallet"))
                }
            }
        }
    }
    if (existing != null && confirmDelete) {
        val count = ledger.liveTxs.count { it.walletId == existing.id || it.toWalletId == existing.id }
        ConfirmDialog(
            tr("Hapus ${existing.name}?", "Delete ${existing.name}?"),
            tr("Dompet dan $count transaksinya dihapus dari perangkat ini dan perangkat tersinkron.", "The wallet and its $count transactions are removed from this device and synced devices."),
            tr("Hapus", "Delete"), { confirmDelete = false },
        ) { confirmDelete = false; vm.deleteWallet(existing); vm.back() }
    }
}

/**
 * M18. Across currencies the conversion card lets the user work by rate or, when they only know both amounts, by the
 * amount received. An optional fee (any transfer) is taken from what the recipient gets. A transfer category is kept.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun TransferScreen(vm: AppViewModel, route: Route.Transfer) {
    val ledger by vm.ledger.collectAsState()
    val rates by vm.rates.collectAsState()
    val scope = rememberCoroutineScope()
    var fromId by remember { mutableStateOf(route.fromId ?: ledger.liveWallets.firstOrNull()?.id) }
    var toId by remember { mutableStateOf(ledger.liveWallets.firstOrNull { it.id != fromId }?.id) }
    var amount by remember { mutableStateOf("") }
    var rateText by remember { mutableStateOf("") }
    var byAmount by remember { mutableStateOf(false) }
    var receivedText by remember { mutableStateOf("") }
    var feeText by remember { mutableStateOf("") }
    val transferCats = ledger.liveCategories.filter { !it.hidden && it.kind == EntryKind.transfer }
    var categoryId by remember { mutableStateOf(transferCats.firstOrNull { it.key == "transfer" }?.id ?: transferCats.firstOrNull()?.id) }
    var failed by remember { mutableStateOf(false) }
    val transferSaved = tr("Transfer disimpan", "Transfer saved")
    val from = ledger.wallet(fromId); val to = ledger.wallet(toId)
    fun minorOf(text: String, cur: String?) = cur?.let { c -> evaluateExpression(text)?.let { runCatching { toMinor(it, c) }.getOrNull() } }
    val minor = minorOf(amount, from?.currency)
    val different = from != null && to != null && from.currency != to.currency
    val pair = if (different) pairFor(from!!.currency, to!!.currency) else null
    val apiRate = pair?.let { rates.api(it) }
    val manual = parseRateInput(rateText)
    val receivedInput = if (different && byAmount) minorOf(receivedText, to?.currency)?.takeIf { it > 0 } else null
    val fee = minorOf(feeText, to?.currency)?.takeIf { it > 0 } ?: 0L
    val effective = when {
        receivedInput != null && minor != null && minor > 0 -> com.arthatis.wallet.core.rateFromAmounts(minor, from!!.currency, receivedInput, to!!.currency)
        byAmount && different -> null
        else -> manual ?: pair?.let { rates.effective(it) }
    }
    // Gross = before the fee; what the destination wallet really gets is gross − fee.
    val gross = if (minor != null && from != null && to != null) {
        if (!different) minor else receivedInput ?: effective?.let { r -> com.arthatis.wallet.core.Rates(emptyMap(), mapOf(pair!!.key to r)).convert(minor, from.currency, to.currency) }
    } else null
    val net = gross?.let { it - fee }
    Column(Modifier.fillMaxSize()) {
        TopBar(tr("Transfer antar dompet", "Transfer between wallets"), onBack = { vm.back() })
        Page {
            FieldLabel(tr("Dari", "From"))
            WalletPicker(from, ledger, { fromId = it.id; rateText = ""; receivedText = "" }, exclude = toId)
            FieldLabel(tr("Ke", "To"))
            WalletPicker(to, ledger, { toId = it.id; rateText = ""; receivedText = "" }, exclude = fromId)
            AmountField(amount, { amount = it }, from?.currency ?: "IDR", tr("Nominal dikirim", "Amount sent"), supporting = if (from != null && minor != null) tr("Saldo ${from.name} setelah transfer: ${formatMinor(walletBalance(from, ledger.txs) - minor, from.currency)}.", "${from.name} balance after transfer: ${formatMinor(walletBalance(from, ledger.txs) - minor, from.currency)}.") else null)
            if (different && from != null && to != null && pair != null) {
                AppCard(padding = 20.dp) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(tr("Konversi mata uang", "Currency conversion"), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                        ChipRow {
                            AppChip(tr("Pakai kurs", "By rate"), !byAmount, { byAmount = false })
                            AppChip(tr("Pakai nominal diterima", "By amount received"), byAmount, { byAmount = true })
                        }
                        if (byAmount) {
                            AmountField(receivedText, { receivedText = it }, to.currency, tr("Nominal diterima (sebelum fee)", "Amount received (before fee)"),
                                supporting = tr("Isi kalau kamu tahu nominal sebelum dan sesudah, tanpa tahu kursnya.", "Use this when you know both amounts but not the rate."))
                            Hint(effective?.let { tr("Kurs ${pair.key} dihitung: ${formatRate(it)}", "Derived ${pair.key} rate: ${formatRate(it)}") } ?: tr("Isi nominal dikirim dan diterima.", "Fill in the amount sent and received."))
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(formatMinor(minor ?: 0, from.currency), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                                Text("=", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(gross?.let { formatMinor(it, to.currency) } ?: "—", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                            }
                            AppTextField(rateText, { rateText = it }, tr("Kurs ${pair.key}", "Rate ${pair.key}"), keyboard = KeyboardType.Decimal,
                                supporting = if (apiRate != null) tr("Otomatis dari API (${formatRate(apiRate)}) · ubah jika kurs terasa kurang akurat. Kosongkan untuk kembali ke kurs API.", "Automatic from the API (${formatRate(apiRate)}) · change it if it feels off. Clear it to use the API rate again.") else tr("Tidak ada kurs API. Isi manual, atau pakai nominal diterima.", "No API rate. Enter one, or use the amount received."))
                        }
                    }
                }
            }
            if (from != null && to != null) {
                AmountField(feeText, { feeText = it }, to.currency, tr("Fee (opsional)", "Fee (optional)"),
                    supporting = tr("Dipotong dari yang diterima ${to.name}. Kosongkan bila gratis.", "Taken from what ${to.name} receives. Leave empty if free."),
                    error = net != null && net < 0)
                net?.let { n -> Hint(if (fee > 0) tr("${to.name} menerima ${formatMinor(n, to.currency)} (setelah fee ${formatMinor(fee, to.currency)}).", "${to.name} receives ${formatMinor(n, to.currency)} (after a ${formatMinor(fee, to.currency)} fee).") else tr("${to.name} menerima ${formatMinor(n, to.currency)}.", "${to.name} receives ${formatMinor(n, to.currency)}.")) }
            }
            if (transferCats.isNotEmpty()) {
                FieldLabel(tr("Kategori", "Category"))
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    transferCats.forEach { c -> AppChip(categoryName(c), categoryId == c.id, { categoryId = c.id }, leading = categoryIcon(c.key)) }
                }
            }
            if (failed) Text(tr("Transfer gagal: kurs belum tersedia atau fee melebihi nominal.", "Transfer failed: no rate, or the fee is larger than the amount."), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            AppButton(tr("Transfer ${minor?.let { formatMinor(it, from?.currency ?: "IDR") } ?: ""}".trim(), "Transfer ${minor?.let { formatMinor(it, from?.currency ?: "IDR") } ?: ""}".trim()), {
                scope.launch {
                    val ok = vm.transfer(from!!, to!!, minor!!, if (byAmount) null else manual, "", receivedInput, fee, categoryId)
                    if (ok) { vm.back(); vm.say(transferSaved) } else failed = true
                }
            }, Modifier.fillMaxWidth(), enabled = from != null && to != null && from.id != to.id && minor != null && minor > 0 && net != null && net >= 0 && (!different || effective != null))
        }
    }
}
