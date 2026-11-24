package com.arthatis.wallet.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.arthatis.wallet.AppViewModel
import com.arthatis.wallet.Route
import com.arthatis.wallet.bareng.GroupSummary
import com.arthatis.wallet.core.EntryKind
import com.arthatis.wallet.core.Sign
import com.arthatis.wallet.core.Tx
import com.arthatis.wallet.core.Wallet
import com.arthatis.wallet.core.dayOf
import com.arthatis.wallet.core.formatMinor
import com.arthatis.wallet.core.monthBounds
import com.arthatis.wallet.core.periodFlow
import com.arthatis.wallet.core.totalBalance
import com.arthatis.wallet.core.walletBalance
import com.arthatis.wallet.data.Ledger
import com.arthatis.wallet.data.ServerMode
import java.time.LocalDate
import java.time.ZoneOffset

@Composable
fun walletMeta(w: Wallet) = if (w.kind == com.arthatis.wallet.core.WalletKind.investasi || w.kind == com.arthatis.wallet.core.WalletKind.kartu) "${walletKindLabel(w.kind)} · ${w.currency}" else w.currency

@Composable
fun lastUsed(w: Wallet, ledger: Ledger): String? {
    val last = ledger.liveTxs.firstOrNull { it.walletId == w.id || it.toWalletId == w.id } ?: return null
    val day = dayOf(last.occurredAt)
    return if (day == LocalDate.now(ZoneOffset.UTC)) tr("Terakhir dipakai hari ini", "Last used today") else "${tr("Terakhir dipakai", "Last used")} ${shortDate(day)}"
}

@Composable
fun TxList(txs: List<Tx>, ledger: Ledger, vm: AppViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        txs.forEach { tx ->
            val cat = ledger.category(tx.categoryId)
            TxRow(txTitle(tx, ledger), txSupporting(tx, ledger), txAmount(tx, ledger), tx.kind == EntryKind.income, categoryIcon(cat?.key ?: if (tx.kind == EntryKind.transfer) "transfer" else null)) { vm.go(Route.AddTx(editId = tx.id)) }
        }
    }
}

/** Shown when the server refuses this vault: hosted vault awaiting activation (ID + QR + pay link), or suspended. Local data is untouched. */
@Composable
fun AccessCard(vm: AppViewModel) {
    val blocked by vm.blocked.collectAsState()
    val status by vm.vaultStatus.collectAsState()
    val reason = blocked ?: return
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val scheme = MaterialTheme.colorScheme
    val copied = tr("ID vault disalin.", "Vault ID copied.")
    if (reason == "suspended") {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.medium)).background(scheme.errorContainer).padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(tr("Akses ditangguhkan", "Access suspended"), style = MaterialTheme.typography.titleSmall, color = scheme.onErrorContainer)
            Text(tr("Admin server menangguhkan vault ini, jadi sinkron berhenti. Data di HP ini tetap utuh dan bisa diekspor.", "The server admin suspended this vault, so sync has stopped. Data on this phone is intact and can be exported."), style = MaterialTheme.typography.bodySmall, color = scheme.onErrorContainer)
        }
        return
    }
    val id = status?.vaultId ?: vm.prefs.vaultId ?: ""
    val payLink = status?.payUrl?.let { "$it${if (it.contains('?')) "&" else "?"}v=${android.net.Uri.encode(id)}" }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.medium)).background(scheme.tertiaryContainer).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(tr("Menunggu aktivasi", "Awaiting activation"), style = MaterialTheme.typography.titleSmall, color = scheme.onTertiaryContainer)
        Text(tr("Server ini berbayar. Sinkron dijeda sampai vault-mu diaktifkan. Aplikasi tetap bisa dipakai offline dan datamu aman di HP.", "This server is paid. Sync is paused until your vault is activated. The app still works offline and your data is safe on the phone."), style = MaterialTheme.typography.bodySmall, color = scheme.onTertiaryContainer)
        Text(tr("ID vault", "Vault ID"), style = MaterialTheme.typography.labelSmall, color = scheme.onTertiaryContainer)
        Text(id, style = MaterialTheme.typography.bodyMedium, color = scheme.onTertiaryContainer)
        if (payLink != null) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { QrCode(payLink, 140.dp) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (payLink != null) AppButton(tr("Buka halaman bayar", "Open payment page"), { runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(payLink))) } }, size = BtnSize.S)
            AppButton(tr("Salin ID", "Copy ID"), { clipboard.setText(androidx.compose.ui.text.AnnotatedString(id)); vm.say(copied) }, style = BtnStyle.Outlined, size = BtnSize.S)
            AppButton(tr("Periksa lagi", "Check again"), { vm.runSync() }, style = BtnStyle.Text, size = BtnSize.S)
        }
    }
}

/** M01 / M01b / M01c / M01d / M02. Tablet (≥ 600 dp) uses 2 columns. */
@Composable
fun HomeScreen(vm: AppViewModel) {
    val ledger by vm.ledger.collectAsState()
    val rates by vm.rates.collectAsState()
    val settings by vm.settings.collectAsState()
    val offline by vm.offline.collectAsState()
    val blockedReason by vm.blocked.collectAsState()
    val groups by vm.groups.collectAsState()
    LaunchedEffect(settings.connected) { vm.loadGroups() }
    val currency = ledger.profile?.primaryCurrency ?: "IDR"
    val name = ledger.profile?.name ?: ""
    val today = LocalDate.now(ZoneOffset.UTC)
    val (from, until) = monthBounds(today)
    val flow = periodFlow(ledger.liveTxs, ledger.liveWallets.associateBy { it.id }, currency, rates, from, until)
    val total = totalBalance(ledger.liveWallets, ledger.txs, currency, rates)
    val invested = com.arthatis.wallet.core.investmentTotal(ledger.liveWallets, ledger.txs, currency, rates)?.takeIf { it != 0L }
    val debt = com.arthatis.wallet.core.creditTotal(ledger.liveWallets, ledger.txs, currency, rates)?.takeIf { it != 0L }
    val extras = listOfNotNull(
        invested?.let { tr("Investasi ${formatMinor(it, currency)}", "Investments ${formatMinor(it, currency)}") },
        debt?.let { tr("Kartu kredit ${formatMinor(it, currency)}", "Credit cards ${formatMinor(it, currency)}") },
    ).joinToString(" · ")
    var filter by remember { mutableStateOf(0) }
    var dialOpen by remember { mutableStateOf(false) }

    val chip = when {
        blockedReason == "payment_required" -> tr("Menunggu aktivasi", "Awaiting activation")
        blockedReason == "suspended" -> tr("Ditangguhkan", "Suspended")
        settings.connected && !offline -> tr("Tersinkron", "Synced")
        settings.connected -> tr("Offline · menunggu sinkron", "Offline · waiting to sync")
        else -> tr("Offline · data lokal", "Offline · local data")
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp
        Page {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(if (name.isBlank()) tr("Halo", "Hello") else tr("Halo, $name", "Hello, $name"), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                    Hint(dateTitle(today))
                }
                Ollie(Pose.Wave, 40.dp)
            }
            AccessCard(vm)
            BalanceHero(
                total = total?.let { formatMinor(it, currency) } ?: "—",
                subtitle = if (total == null) tr("Kurs belum tersedia. Ketuk Pengaturan → Mata uang & kurs.", "Rates unavailable. Open Settings → Currency & rates.") else tr("${ledger.liveWallets.size} dompet · total kas dalam $currency", "${ledger.liveWallets.size} wallets · cash total in $currency") + (if (extras.isEmpty()) "" else "\n$extras"),
                statusChip = chip,
                incomeLabel = tr("Masuk", "Income"), income = flow?.let { formatMinor(it.incomeMinor, currency, Sign.Plus) } ?: "—",
                expenseLabel = tr("Keluar", "Expense"), expense = flow?.let { formatMinor(it.expenseMinor, currency, Sign.Minus) } ?: "—",
            )
            if (!wide) ChipRow {
                AppChip(tr("Semua", "All"), filter == 0, { filter = 0 })
                AppChip(tr("Pribadi", "Personal"), filter == 1, { filter = 1 })
                AppChip("Bareng", filter == 2, { filter = 2 })
            }
            SectionTitle(tr("Dompet", "Wallets"), tr("Lihat semua", "See all"), { vm.selectTab(Tab.Wallets) })
            val personal = ledger.liveWallets
            val shown = if (wide) personal else personal.take(2)
            if (filter != 2) WalletGrid(shown, ledger, vm, wide)
            if (filter != 1) groups.filter { it.status == "active" }.forEach { g -> SharedAsWallet(g, vm) }
            AppButton(tr("Tambah dompet", "Add wallet"), { vm.go(Route.AddWallet) }, Modifier.fillMaxWidth(), style = BtnStyle.Outlined, icon = Icons.Rounded.Add)
            SectionTitle(tr("Transaksi terbaru", "Recent transactions"), if (ledger.liveTxs.isEmpty()) null else tr("Lihat semua", "See all"), { vm.selectTab(Tab.Transactions) })
            if (ledger.liveTxs.isEmpty()) {
                EmptyState(Pose.Sleepy, tr("Belum ada transaksi", "No transactions yet"), tr("Catatan pertamamu akan muncul di sini. Mulai dari yang kecil — kopi pagi tadi, misalnya.", "Your first entry will show up here. Start small — this morning's coffee, perhaps."), action = tr("Tambah transaksi pertama", "Add your first transaction"), onAction = { vm.go(Route.AddTx()) })
            } else TxList(ledger.liveTxs.take(if (wide) 5 else 3), ledger, vm)
        }
        if (dialOpen) Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f)).clickable(interactionSource = null, indication = null) { dialOpen = false })
        SpeedDialFab(
            actions = listOf(
                DialAction(tr("Tambah manual", "Add manually"), Icons.Rounded.Edit) { vm.go(Route.AddTx()) },
                DialAction("Chat AI", Icons.Rounded.AutoAwesome) { vm.go(Route.Chat) },
                DialAction(tr("Scan struk", "Scan receipt"), Icons.Rounded.DocumentScanner) { vm.go(Route.Receipt) },
            ),
            open = dialOpen, onOpenChange = { dialOpen = it }, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }
}

@Composable
private fun WalletGrid(wallets: List<Wallet>, ledger: Ledger, vm: AppViewModel, wide: Boolean) {
    val items: @Composable (Wallet) -> Unit = { w ->
        WalletCard(w.name, walletMeta(w), lastUsed(w, ledger), formatMinor(walletBalance(w, ledger.txs), w.currency), walletIcon(w), tint = walletTint(w)) { vm.go(Route.WalletDetail(w.id)) }
    }
    if (wide) wallets.chunked(2).forEach { pair ->
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { pair.forEach { Column(Modifier.weight(1f)) { items(it) } }; if (pair.size == 1) Column(Modifier.weight(1f)) {} }
    } else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { wallets.forEach { items(it) } }
}

@Composable
private fun SharedAsWallet(g: GroupSummary, vm: AppViewModel) {
    val meta = g.meta ?: return
    WalletCard(meta.name, tr("Bareng · ${meta.currency}", "Shared · ${meta.currency}"), tr("${g.memberCount} anggota", "${g.memberCount} members"), "", iconFor("wallet"), shared = true) { vm.go(Route.BarengDetail(g.id)) }
}

/** Dompet tab: the full list (the design links "Lihat semua" here). */
@Composable
fun WalletsScreen(vm: AppViewModel) {
    val ledger by vm.ledger.collectAsState()
    Column(Modifier.fillMaxSize()) {
        TopBar(tr("Dompet", "Wallets"), actions = { AppIconButton(Icons.Rounded.Add, { vm.go(Route.AddWallet) }, description = tr("Tambah dompet", "Add wallet")) })
        Page(center = ledger.liveWallets.isEmpty()) {
            if (ledger.liveWallets.isEmpty()) EmptyState(Pose.Sleepy, tr("Belum ada dompet", "No wallets yet"), tr("Tambahkan dompet pertamamu.", "Add your first wallet."), action = tr("Tambah dompet", "Add wallet"), onAction = { vm.go(Route.AddWallet) })
            ledger.liveWallets.forEach { w ->
                WalletCard(w.name, walletMeta(w), lastUsed(w, ledger), formatMinor(walletBalance(w, ledger.txs), w.currency), walletIcon(w), tint = walletTint(w)) { vm.go(Route.WalletDetail(w.id)) }
            }
            if (ledger.liveWallets.size > 1) AppButton(tr("Transfer antar dompet", "Transfer between wallets"), { vm.go(Route.Transfer(null)) }, Modifier.fillMaxWidth(), style = BtnStyle.Tonal, icon = Icons.Rounded.SwapHoriz)
        }
    }
}

/** M03 */
@Composable
fun WalletDetailScreen(vm: AppViewModel, id: String) {
    val ledger by vm.ledger.collectAsState()
    val rates by vm.rates.collectAsState()
    val wallet = ledger.wallet(id)
    if (wallet == null || wallet.deletedAt != null) { LaunchedEffect(Unit) { vm.back() }; return }
    val today = LocalDate.now(ZoneOffset.UTC)
    val (from, until) = monthBounds(today)
    val prev = monthBounds(today.minusMonths(1))
    val map = mapOf(wallet.id to wallet)
    val cur = periodFlow(ledger.liveTxs, map, wallet.currency, rates, from, until)
    val old = periodFlow(ledger.liveTxs, map, wallet.currency, rates, prev.first, prev.second)
    val txs = ledger.liveTxs.filter { it.walletId == id || it.toWalletId == id }
    val month = java.time.format.DateTimeFormatter.ofPattern("MMM", locale()).format(today)
    var confirmDelete by remember { mutableStateOf(false) }
    val lastMo = tr("bln lalu", "last mo.")
    fun delta(now: Long?, before: Long?): String? {
        if (now == null || before == null || before == 0L) return null
        val pct = (now - before) * 100.0 / before
        return "${if (pct >= 0) "+" else "−"}${"%.1f".format(kotlin.math.abs(pct)).replace('.', ',')}% vs $lastMo"
    }
    Column(Modifier.fillMaxSize()) {
        TopBar(wallet.name, onBack = { vm.back() })
        Page {
            AppCard(padding = 20.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Hint(tr("Saldo ${wallet.name}", "${wallet.name} balance"))
                    Text(formatMinor(walletBalance(wallet, ledger.txs), wallet.currency), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
                    Hint(walletMeta(wallet))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard(tr("Masuk $month", "Income $month"), formatMinor(cur?.incomeMinor ?: 0, wallet.currency), delta(cur?.incomeMinor, old?.incomeMinor), Modifier.weight(1f))
                StatCard(tr("Keluar $month", "Expense $month"), formatMinor(cur?.expenseMinor ?: 0, wallet.currency), delta(cur?.expenseMinor, old?.expenseMinor), Modifier.weight(1f))
            }
            SectionTitle(tr("Transaksi ${wallet.name}", "${wallet.name} transactions"))
            if (txs.isEmpty()) EmptyState(Pose.Sleepy, tr("Belum ada transaksi", "No transactions yet"), tr("Transaksi di dompet ini akan muncul di sini.", "Transactions in this wallet will show up here."), ollieSize = 96.dp) else TxList(txs, ledger, vm)
            AppButton(tr("Transfer ke dompet lain", "Transfer to another wallet"), { vm.go(Route.Transfer(wallet.id)) }, Modifier.fillMaxWidth(), style = BtnStyle.Tonal, icon = Icons.Rounded.SwapHoriz)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppButton(tr("Tambah transaksi", "Add transaction"), { vm.go(Route.AddTx(walletId = wallet.id)) }, Modifier.weight(1f), style = BtnStyle.Outlined, size = BtnSize.S, icon = Icons.Rounded.Add)
                AppButton(tr("Hapus", "Delete"), { confirmDelete = true }, Modifier.weight(1f), style = BtnStyle.Outlined, size = BtnSize.S, icon = Icons.Rounded.Delete)
            }
            Hint(tr("Mata uang dompet tidak bisa diubah setelah dibuat.", "A wallet's currency can't be changed after it's created."))
        }
    }
    if (confirmDelete) ConfirmDialog(
        tr("Hapus ${wallet.name}?", "Delete ${wallet.name}?"),
        tr("Dompet dan ${txs.size} transaksinya dihapus dari perangkat ini dan perangkat tersinkron.", "The wallet and its ${txs.size} transactions are removed from this device and synced devices."),
        tr("Hapus", "Delete"), { confirmDelete = false }) { confirmDelete = false; vm.deleteWallet(wallet); vm.back() }
}

@Composable
fun ConfirmDialog(title: String, body: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(body) },
        confirmButton = { androidx.compose.material3.TextButton(onConfirm) { Text(confirm, color = MaterialTheme.colorScheme.error) } },
        dismissButton = { androidx.compose.material3.TextButton(onDismiss) { Text(tr("Batal", "Cancel")) } },
    )
}

/** M04 */
@Composable
fun TransactionsScreen(vm: AppViewModel) {
    val ledger by vm.ledger.collectAsState()
    val groups by vm.groups.collectAsState()
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(0) }
    val q = query.trim().lowercase()
    val all = ledger.liveTxs.filter { tx ->
        (filter == 0 || (filter == 1 && tx.kind == EntryKind.income) || (filter == 2 && tx.kind == EntryKind.expense)) &&
            (q.isEmpty() || tx.note.lowercase().contains(q) || ledger.wallet(tx.walletId)?.name?.lowercase()?.contains(q) == true ||
                ledger.category(tx.categoryId)?.name?.lowercase()?.contains(q) == true)
    }
    Column(Modifier.fillMaxSize()) {
        TopBar(tr("Transaksi", "Transactions"))
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SearchField(query, { query = it }, tr("Cari transaksi…", "Search transactions…"))
            ChipRow {
                AppChip(tr("Semua", "All"), filter == 0, { filter = 0 })
                AppChip(tr("Pemasukan", "Income"), filter == 1, { filter = 1 })
                AppChip(tr("Pengeluaran", "Expenses"), filter == 2, { filter = 2 })
                AppChip("Bareng", filter == 3, { filter = 3 })
            }
        }
        val listEmpty = if (filter == 3) groups.isEmpty() else all.isEmpty()
        Page(center = listEmpty) {
            if (filter == 3) {
                if (groups.isEmpty()) EmptyState(Pose.HappyCoin, tr("Belum ada wallet bareng", "No shared wallets yet"), tr("Buat satu di tab Bareng, lalu undang teman atau keluarga.", "Create one in the Together tab, then invite friends or family."), ollieSize = 96.dp) else groups.filter { it.status == "active" }.forEach { SharedAsWallet(it, vm) }
            } else if (all.isEmpty()) {
                EmptyState(Pose.Sleepy, tr("Tidak ada transaksi", "No transactions"), if (q.isNotEmpty()) tr("Coba kata kunci lain.", "Try another keyword.") else tr("Catatanmu akan muncul di sini.", "Your entries will show up here."))
            } else {
                all.groupBy { dayOf(it.occurredAt) }.toSortedMap(compareByDescending { it }).forEach { (day, items) ->
                    Hint(dateTitle(day))
                    TxList(items, ledger, vm)
                }
            }
        }
    }
}
