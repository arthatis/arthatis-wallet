package com.arthatis.wallet.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import com.arthatis.wallet.AppViewModel
import com.arthatis.wallet.Route
import com.arthatis.wallet.core.EntryKind
import com.arthatis.wallet.core.Frequency
import com.arthatis.wallet.core.Recurring
import com.arthatis.wallet.core.evaluateExpression
import com.arthatis.wallet.data.newId
import androidx.compose.ui.unit.dp
import com.arthatis.wallet.core.toMajor
import com.arthatis.wallet.core.toMinor
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.TextStyle

/** Add or edit a recurring entry. When its day comes the app adds the transaction by itself (see ReminderWorker). */
@Composable
fun RecurringFormScreen(vm: AppViewModel, id: String?) {
    val ledger by vm.ledger.collectAsState()
    val existing = id?.let { want -> ledger.recurring.firstOrNull { it.id == want && it.deletedAt == null } }
    val today = LocalDate.now(ZoneOffset.UTC)
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var walletId by remember { mutableStateOf(existing?.walletId ?: ledger.liveWallets.firstOrNull()?.id) }
    var kind by remember { mutableStateOf(existing?.kind ?: EntryKind.expense) }
    var categoryId by remember { mutableStateOf(existing?.categoryId) }
    var freq by remember { mutableStateOf(existing?.frequency ?: Frequency.monthly) }
    var dayOfMonth by remember { mutableStateOf((existing?.dayOfMonth ?: today.dayOfMonth).coerceIn(1, 28).toString()) }
    var weekday by remember { mutableStateOf(existing?.weekday ?: today.dayOfWeek.value) }
    var month by remember { mutableStateOf(existing?.month ?: today.monthValue) }
    var remind by remember { mutableStateOf(existing?.reminder ?: true) }
    val wallet = ledger.wallet(walletId)
    val currency = wallet?.currency ?: "IDR"
    var amount by remember { mutableStateOf(existing?.let { toMajor(it.amountMinor, ledger.wallet(it.walletId)?.currency ?: "IDR").toPlainString() } ?: "") }
    val minor = evaluateExpression(amount)?.let { runCatching { toMinor(it, currency) }.getOrNull() }
    val cats = ledger.liveCategories.filter { !it.hidden && it.kind == kind }
    val day = dayOfMonth.toIntOrNull()?.takeIf { it in 1..28 }
    val needsDay = freq == Frequency.monthly || freq == Frequency.yearly
    val ready = name.isNotBlank() && wallet != null && categoryId != null && minor != null && minor > 0 && (!needsDay || day != null)

    Column(Modifier.fillMaxSize()) {
        TopBar(if (existing == null) tr("Tambah transaksi berulang", "Add recurring") else tr("Ubah transaksi berulang", "Edit recurring"), onBack = { vm.back() })
        Page {
            AppTextField(name, { name = it }, tr("Nama", "Name"), supporting = tr("Contoh: Gaji, Listrik, Netflix.", "Example: Salary, Electricity, Netflix."))
            ChipRow {
                AppChip(tr("Pengeluaran", "Expense"), kind == EntryKind.expense, { kind = EntryKind.expense; categoryId = null })
                AppChip(tr("Pemasukan", "Income"), kind == EntryKind.income, { kind = EntryKind.income; categoryId = null })
            }
            AmountField(amount, { amount = it }, currency, tr("Nominal", "Amount"))
            FieldLabel(tr("Kategori", "Category"))
            ChipRow { cats.forEach { c -> AppChip(categoryName(c), categoryId == c.id, { categoryId = c.id }, leading = categoryIcon(c.key)) } }
            FieldLabel(tr("Dompet", "Wallet"))
            WalletPicker(wallet, ledger, { walletId = it.id })
            FieldLabel(tr("Diulang", "Repeats"))
            ChipRow { listOf(Frequency.daily to tr("Harian", "Daily"), Frequency.weekly to tr("Mingguan", "Weekly"), Frequency.monthly to tr("Bulanan", "Monthly"), Frequency.yearly to tr("Tahunan", "Yearly")).forEach { (f, l) -> AppChip(l, freq == f, { freq = f }) } }
            if (freq == Frequency.weekly) {
                ChipRow { DayOfWeek.entries.forEach { d -> AppChip(d.getDisplayName(TextStyle.SHORT, locale()), weekday == d.value, { weekday = d.value }) } }
            }
            if (freq == Frequency.yearly) {
                ChipRow { (1..12).forEach { m -> AppChip(java.time.Month.of(m).getDisplayName(TextStyle.SHORT, locale()), month == m, { month = m }) } }
            }
            if (needsDay) AppTextField(dayOfMonth, { dayOfMonth = it.filter(Char::isDigit).take(2) }, tr("Tanggal (1–28)", "Day of month (1–28)"), keyboard = KeyboardType.Number, error = dayOfMonth.isNotEmpty() && day == null, supporting = tr("Maksimal 28 supaya selalu ada di tiap bulan.", "Capped at 28 so it exists every month."))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(tr("Ingatkan H-1", "Remind the day before"), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                    Hint(tr("Notifikasi lokal di HP ini.", "A local notification on this phone."))
                }
                AppSwitch(remind, { remind = it })
            }
            Hint(tr("Saat harinya tiba, transaksi ditambahkan otomatis.", "When the day comes, the transaction is added automatically."))
            AppButton(tr("Simpan", "Save"), {
                val r = (existing ?: Recurring(newId(), name, walletId!!, categoryId!!, kind, minor!!, freq, updatedAt = 0)).copy(
                    name = name.trim(), walletId = walletId!!, categoryId = categoryId!!, kind = kind, amountMinor = minor!!, frequency = freq,
                    dayOfMonth = if (needsDay) day else null, weekday = if (freq == Frequency.weekly) weekday else null,
                    month = if (freq == Frequency.yearly) month else null, reminder = remind,
                )
                vm.viewModelScope_launch { vm.repo.saveRecurring(r) }
                vm.back()
            }, Modifier.fillMaxWidth(), enabled = ready)
        }
    }
}
