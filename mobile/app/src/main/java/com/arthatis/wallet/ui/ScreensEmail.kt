package com.arthatis.wallet.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.arthatis.wallet.AppViewModel
import com.arthatis.wallet.Route
import com.arthatis.wallet.core.EntryKind
import com.arthatis.wallet.core.dayOf
import com.arthatis.wallet.core.formatMinor
import com.arthatis.wallet.email.EmailDraft
import kotlinx.coroutines.launch

private data class ImapPreset(val label: String, val host: String, val port: Int)
private val presets = listOf(ImapPreset("Gmail", "imap.gmail.com", 993), ImapPreset("Outlook", "outlook.office365.com", 993), ImapPreset("Yahoo", "imap.mail.yahoo.com", 993))

/** Settings → Transactions from email: IMAP account (app password), watched senders, on/off, check now. */
@Composable
fun EmailSyncScreen(vm: AppViewModel) {
    val prefs = vm.prefs
    val scope = rememberCoroutineScope()
    val busy by vm.emailBusy.collectAsState()
    var enabled by remember { mutableStateOf(prefs.emailEnabled) }
    var host by remember { mutableStateOf(prefs.emailHost) }
    var port by remember { mutableStateOf(prefs.emailPort.toString()) }
    var user by remember { mutableStateOf(prefs.emailUser) }
    var password by remember { mutableStateOf("") }
    val hasPassword = prefs.emailPassword != null
    var senders by remember { mutableStateOf(prefs.emailSenders.joinToString("\n")) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }
    val ok = tr("Terhubung ✓", "Connected ✓")
    val saved = tr("Tersimpan.", "Saved.")
    val portInt = port.toIntOrNull() ?: 993
    val canSave = user.isNotBlank() && host.isNotBlank() && (hasPassword || password.isNotBlank())

    Column(Modifier.fillMaxSize()) {
        TopBar(tr("Transaksi dari email", "Transactions from email"), onBack = { vm.back() })
        Page {
            Hint(tr(
                "Arthatis membaca email notifikasi bank & e-wallet langsung dari HP ini (IMAP), lalu membuat draf yang kamu konfirmasi. Email tidak lewat server Arthatis. Isinya hanya keluar HP bila AI server / API pribadi aktif di AI & privasi.",
                "Arthatis reads bank & e-wallet notification emails straight from this phone (IMAP) and makes drafts you confirm. Email never goes through the Arthatis server. Its text only leaves the phone when server AI / your own key is on in AI & privacy.",
            ))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Aktifkan (cek tiap ±15 menit)", "Turn on (checks every ~15 min)"), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                AppSwitch(enabled, { enabled = it })
            }
            FieldLabel(tr("Penyedia", "Provider"))
            ChipRow { presets.forEach { p -> AppChip(p.label, host == p.host, { host = p.host; port = p.port.toString() }) } }
            AppTextField(user, { user = it }, tr("Alamat email", "Email address"), keyboard = KeyboardType.Email)
            AppTextField(password, { password = it }, tr("App password", "App password"), keyboard = KeyboardType.Password,
                supporting = if (hasPassword) tr("Tersimpan terenkripsi. Kosongkan untuk tetap memakai yang lama.", "Stored encrypted. Leave empty to keep it.")
                else tr("Gmail/Yahoo/Outlook: buat “app password” di pengaturan keamanan akun (butuh verifikasi 2 langkah). Jangan pakai kata sandi utama.", "Gmail/Yahoo/Outlook: create an “app password” in your account's security settings (needs 2-step verification). Don't use your main password."),
                trailing = null)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppTextField(host, { host = it }, tr("Server IMAP", "IMAP server"), Modifier.weight(2f))
                AppTextField(port, { port = it.filter(Char::isDigit).take(5) }, "Port", Modifier.weight(1f), keyboard = KeyboardType.Number)
            }
            AppTextField(senders, { senders = it }, tr("Pengirim yang dipantau (satu per baris)", "Watched senders (one per line)"), singleLine = false,
                supporting = tr("Potongan alamat pengirim, mis. klikbca.com, gopay, ovo.id. Email lain diabaikan.", "Part of the sender address, e.g. klikbca.com, gopay, ovo.id. Other email is ignored."))
            testResult?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (it == ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppButton(tr("Tes koneksi", "Test connection"), {
                    scope.launch {
                        testing = true
                        val err = vm.testEmail(host.trim(), portInt, user.trim(), password.ifBlank { prefs.emailPassword ?: "" })
                        testResult = err ?: ok; testing = false
                    }
                }, Modifier.weight(1f), style = BtnStyle.Outlined, size = BtnSize.S, loading = testing, enabled = canSave)
                AppButton(tr("Simpan", "Save"), {
                    vm.saveEmailSettings(enabled, host.trim(), portInt, user.trim(), password.ifBlank { null }, senders.lines())
                    password = ""; vm.say(saved)
                }, Modifier.weight(1f), size = BtnSize.S, enabled = canSave)
            }
            if (hasPassword) {
                AppButton(tr("Cek email sekarang", "Check email now"), { vm.checkEmailNow() }, Modifier.fillMaxWidth(), style = BtnStyle.Tonal, loading = busy)
                AppButton(tr("Lihat draf dari email", "View email drafts"), { vm.go(Route.EmailDrafts) }, Modifier.fillMaxWidth(), style = BtnStyle.Text)
                Hint(buildString {
                    if (prefs.emailLastCheck > 0) append(tr("Cek terakhir: ", "Last check: ")).append(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(prefs.emailLastCheck)))
                    prefs.emailLastError?.let { append("\n").append(tr("Galat terakhir: ", "Last error: ")).append(it) }
                })
                AppButton(tr("Hapus akun email", "Remove email account"), { confirmRemove = true }, Modifier.fillMaxWidth(), style = BtnStyle.Text)
            }
        }
    }
    if (confirmRemove) ConfirmDialog(
        tr("Hapus akun email?", "Remove email account?"),
        tr("Alamat, app password, dan semua draf email yang belum dikonfirmasi dihapus dari HP ini.", "The address, app password and every unconfirmed email draft are removed from this phone."),
        tr("Hapus", "Remove"), { confirmRemove = false },
    ) { confirmRemove = false; vm.removeEmailAccount(); vm.back() }
}

/** Drafts read from email: each waits for the user to pick the wallet/category and confirm, or discard. */
@Composable
fun EmailDraftsScreen(vm: AppViewModel) {
    val drafts by vm.emailDrafts.collectAsState()
    val busy by vm.emailBusy.collectAsState()
    LaunchedEffect(Unit) { vm.loadEmailDrafts() }
    Column(Modifier.fillMaxSize()) {
        TopBar(tr("Draf dari email", "Email drafts"), onBack = { vm.back() })
        Page(center = drafts.isEmpty()) {
            if (drafts.isEmpty()) EmptyState(Pose.Sleepy, tr("Tidak ada draf", "No drafts"), tr("Transaksi baru dari email bank & e-wallet akan muncul di sini untuk kamu konfirmasi.", "New transactions from bank & e-wallet email show up here for you to confirm."),
                action = if (vm.prefs.emailPassword != null) tr("Cek email sekarang", "Check email now") else tr("Atur email", "Set up email"),
                onAction = { if (vm.prefs.emailPassword != null) vm.checkEmailNow() else vm.go(Route.EmailSync) })
            if (busy) Hint(tr("Membaca email…", "Reading email…"))
            drafts.forEach { d -> EmailDraftCard(vm, d) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmailDraftCard(vm: AppViewModel, d: EmailDraft) {
    val ledger by vm.ledger.collectAsState()
    var walletId by remember(d.id) { mutableStateOf(d.walletId ?: ledger.liveWallets.firstOrNull { it.currency == d.currency }?.id) }
    var categoryId by remember(d.id) { mutableStateOf(d.categoryId) }
    val wallet = walletId?.let { ledger.wallet(it) }
    val cats = ledger.liveCategories.filter { !it.hidden && it.kind == d.kind }
    AppCard(padding = 16.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(formatMinor(d.amountMinor, d.currency, txSign(d.kind)), style = MaterialTheme.typography.headlineSmall, color = if (d.kind == EntryKind.income) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            Text(d.note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            Hint("${d.from.substringBefore('<').trim().ifBlank { d.from }} · ${shortDate(dayOf(d.receivedAt))} · ${d.subject}")
            if (wallet != null && wallet.currency != d.currency) Text(tr("Mata uang dompet (${wallet.currency}) berbeda dengan email (${d.currency}). Pilih dompet ${d.currency}.", "The wallet's currency (${wallet.currency}) differs from the email (${d.currency}). Pick a ${d.currency} wallet."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            WalletPicker(wallet, ledger, { walletId = it.id })
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                cats.forEach { c -> AppChip(categoryName(c), categoryId == c.id, { categoryId = c.id }, leading = categoryIcon(c.key)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppButton(tr("Buang", "Discard"), { vm.discardEmailDraft(d) }, Modifier.weight(1f), style = BtnStyle.Outlined, size = BtnSize.S)
                AppButton(tr("Simpan", "Save"), { vm.confirmEmailDraft(d, walletId!!, categoryId) }, Modifier.weight(1f), size = BtnSize.S,
                    enabled = wallet != null && wallet.currency == d.currency && categoryId != null)
            }
        }
    }
}
