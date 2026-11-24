package com.arthatis.wallet.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.QrCodeScanner
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.arthatis.wallet.AppViewModel
import com.arthatis.wallet.Route
import com.arthatis.wallet.core.csvToArchive
import com.arthatis.wallet.core.toCsv
import com.arthatis.wallet.scan.QrScanner
import com.arthatis.wallet.sync.Vault
import kotlinx.coroutines.launch

/** M11 */
@Composable
fun SecurityScreen(vm: AppViewModel) {
    val settings by vm.settings.collectAsState()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var sessions by remember { mutableStateOf<List<Vault.WebSession>?>(null) }
    var newKey by remember { mutableStateOf<String?>(null) }
    var email by remember { mutableStateOf("") }
    var emailVerified by remember { mutableStateOf(false) }
    var emailCodeSent by remember { mutableStateOf(false) }
    var emailCode by remember { mutableStateOf("") }
    var emailError by remember { mutableStateOf<String?>(null) }
    var emailBusy by remember { mutableStateOf(false) }
    LaunchedEffect(settings.connected) { if (settings.connected) runCatching { vm.vault.emailState() }.onSuccess { emailVerified = it.first; emailCodeSent = it.second && !it.first } }
    val errCooldown = tr("Tunggu semenit sebelum meminta kode lagi.", "Wait a minute before asking for another code.")
    val errNoMail = tr("Server ini belum punya pengiriman email (SMTP). Hubungi pengelola server.", "This server has no email delivery (SMTP) configured. Ask the server admin.")
    val errWrong = tr("Kode salah.", "Wrong code.")
    val errLocked = tr("Terlalu banyak percobaan. Minta kode baru nanti.", "Too many attempts. Ask for a new code later.")
    val errTaken = tr("Email ini sudah dipakai vault lain.", "This email is already used by another vault.")
    val errExpired = tr("Kode kedaluwarsa. Minta kode baru.", "The code expired. Ask for a new one.")
    val errGeneric = tr("Gagal. Periksa koneksi ke server.", "Failed. Check the server connection.")
    fun message(code: String) = when (code) { "resend_cooldown" -> errCooldown; "mail_not_configured" -> errNoMail; "wrong_code" -> errWrong; "too_many_attempts" -> errLocked; "email_in_use" -> errTaken; "no_pending_code" -> errExpired; else -> errGeneric }
    fun reload() { scope.launch { sessions = if (settings.connected) runCatching { vm.vault.webSessions() }.getOrNull() else emptyList() } }
    LaunchedEffect(settings.connected) { reload() }
    Column(Modifier.fillMaxSize()) {
        TopBar(tr("Keamanan & enkripsi", "Security & encryption"), onBack = { vm.back() })
        Page {
            AppCard(padding = 20.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        androidx.compose.material3.Icon(Icons.Rounded.Lock, null, tint = MaterialTheme.colorScheme.primary)
                        Text(tr("E2EE aktif", "E2EE on"), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    }
                    Hint("TLS 1.3 · XChaCha20-Poly1305 · Argon2id · X25519 + ML-KEM-768")
                    Body(tr("Server hanya menyimpan ciphertext. Kunci tidak pernah keluar dari perangkat.", "The server only stores ciphertext. Keys never leave the device."))
                    AppButton(if (settings.recoveryCreated) tr("Buat recovery key baru", "Create a new recovery key") else tr("Buat recovery key", "Create recovery key"), { scope.launch { newKey = vm.createRecoveryKey() } }, style = BtnStyle.Tonal, size = BtnSize.S)
                    if (!settings.recoveryCreated) Hint(tr("Belum dibuat. Tanpa recovery key, data tidak bisa dipulihkan di HP baru.", "Not created yet. Without a recovery key, data can't be restored on a new phone."))
                }
            }
            if (settings.connected) {
                SectionTitle(tr("Sesi web aktif", "Active web sessions"))
                val list = sessions
                if (list.isNullOrEmpty()) EmptyState(Pose.Thinking, tr("Belum ada sesi web", "No web sessions yet"), tr("Tautkan browser untuk melihat dan mengelola datamu dari layar besar.", "Link a browser to view and manage your data on a bigger screen."), ollieSize = 96.dp)
                list?.forEach { s ->
                    AppCard { Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(s.label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface); Hint(tr("Terakhir aktif ", "Last active ") + shortDate(com.arthatis.wallet.core.dayOf(s.lastSeenAt))) }
                        AppButton(tr("Cabut", "Revoke"), { scope.launch { runCatching { vm.vault.revokeSession(s.id) }; reload() } }, style = BtnStyle.Text, size = BtnSize.XS)
                    } }
                }
                AppButton(tr("Tambah sesi web", "Add web session"), { vm.go(Route.LinkWeb) }, Modifier.fillMaxWidth(), icon = Icons.Rounded.QrCodeScanner)
                if (!list.isNullOrEmpty()) AppButton(tr("Cabut semua sesi web", "Revoke all web sessions"), { scope.launch { runCatching { vm.vault.revokeAllSessions() }; reload() } }, Modifier.fillMaxWidth(), style = BtnStyle.Outlined)
                SectionTitle(tr("Email pemulihan (opsional)", "Recovery email (optional)"))
                Hint(tr("Hanya untuk mengirim tautan pemulihan di HP baru. Kami kirim kode ke emailmu untuk memastikan itu milikmu. Data tetap butuh recovery key.", "Only used to send a recovery link on a new phone. We email you a code to confirm the address is yours. Your data still needs the recovery key."))
                if (emailVerified) {
                    Text(tr("Email terverifikasi ✓", "Email verified ✓"), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                    AppButton(tr("Hapus email", "Remove email"), { scope.launch { runCatching { vm.vault.removeEmail() }; emailVerified = false; emailCodeSent = false; email = "" } }, style = BtnStyle.Text, size = BtnSize.S)
                } else {
                    AppTextField(email, { email = it; emailError = null }, "Email", keyboard = KeyboardType.Email)
                    AppButton(if (emailCodeSent) tr("Kirim ulang kode", "Resend code") else tr("Kirim kode verifikasi", "Send verification code"), {
                        scope.launch { emailBusy = true; val e = vm.vault.requestEmailCode(email.trim()); emailBusy = false; emailError = e?.let(::message); if (e == null) emailCodeSent = true }
                    }, style = BtnStyle.Tonal, size = BtnSize.S, enabled = email.contains('@'), loading = emailBusy)
                    if (emailCodeSent) {
                        AppTextField(emailCode, { emailCode = it.filter(Char::isDigit).take(6); emailError = null }, tr("Kode 6 digit", "6-digit code"), keyboard = KeyboardType.Number, error = emailError != null)
                        AppButton(tr("Verifikasi", "Verify"), {
                            scope.launch { emailBusy = true; val e = vm.vault.verifyEmailCode(emailCode); emailBusy = false; emailError = e?.let(::message); if (e == null) { emailVerified = true; emailCodeSent = false; emailCode = "" } }
                        }, size = BtnSize.S, enabled = emailCode.length == 6, loading = emailBusy)
                    }
                    emailError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            } else Hint(tr("Sesi web butuh server. Hubungkan di Pengaturan → Server.", "Web sessions need a server. Connect one in Settings → Server."))
        }
    }
    newKey?.let { k ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {}, title = { Text("Recovery key") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(k, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Hint(tr("Tulis dan simpan di tempat aman. Kunci ini tidak ditampilkan lagi dan tidak bisa diminta ulang dari server.", "Write it down somewhere safe. It won't be shown again and the server can't recover it."))
                    AppButton(tr("Salin", "Copy"), { clipboard.setText(AnnotatedString(k)) }, style = BtnStyle.Tonal, size = BtnSize.S)
                }
            },
            confirmButton = { androidx.compose.material3.TextButton({ newKey = null }) { Text(tr("Sudah kusimpan", "I've saved it")) } },
        )
    }
}

/** Scan the browser's QR; approval is gated behind fingerprint or PIN. */
@Composable
fun LinkWebScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var unlocked by remember { mutableStateOf(false) }
    var request by remember { mutableStateOf<Vault.LinkRequest?>(null) }
    var preview by remember { mutableStateOf<Vault.LinkPreview?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var camera by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { camera = it }
    val title = tr("Setujui sesi web", "Approve web session")
    val sub = tr("Buka kunci untuk menautkan browser", "Unlock to link a browser")
    val invalid = tr("QR bukan dari Arthatis Web.", "That QR isn't from Arthatis Web.")
    val expired = tr("QR kedaluwarsa. Muat ulang halaman web.", "QR expired. Reload the web page.")
    val done = tr("Sesi web ditautkan.", "Web session linked.")

    fun unlock() {
        val activity = context as? FragmentActivity ?: return
        val allowed = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (BiometricManager.from(context).canAuthenticate(allowed) != BiometricManager.BIOMETRIC_SUCCESS) { unlocked = true; return }
        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(context), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { unlocked = true }
            override fun onAuthenticationError(code: Int, message: CharSequence) { vm.back() }
        })
        prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle(title).setSubtitle(sub).setAllowedAuthenticators(allowed).build())
    }
    LaunchedEffect(Unit) { unlock() }
    LaunchedEffect(unlocked) { if (unlocked && !camera) permission.launch(Manifest.permission.CAMERA) }

    Column(Modifier.fillMaxSize()) {
        TopBar(title, onBack = { vm.back() })
        Page {
            Body(tr("Di browser, buka wallet.arthatis.com (atau webmu) dan pindai QR yang tampil.", "In the browser, open your wallet web page and scan the QR it shows."))
            val p = preview
            if (p == null && unlocked && camera) {
                Box(Modifier.fillMaxWidth().height(320.dp).clip(RoundedCornerShape(Radius.large))) {
                    QrScanner(Modifier.fillMaxSize()) { text ->
                        val req = vm.vault.parseLink(text)
                        if (req == null) { error = invalid; return@QrScanner }
                        scope.launch {
                            runCatching { vm.vault.scanLink(req) }.onSuccess { request = req; preview = it }.onFailure { error = expired }
                        }
                    }
                }
            }
            if (p != null) AppCard(padding = 20.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(p.label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    Hint(tr("Kode sesi harus sama dengan yang tampil di browser:", "The session code must match the one in the browser:"))
                    Text(p.code, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                    Hint(tr("Setujui hanya jika kamu yang membuka halaman itu. Browser akan bisa melihat, menambah, mengubah, dan menghapus datamu.", "Approve only if you opened that page. The browser can view, add, edit and delete your data."))
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            if (p != null && request != null) AppButton(tr("Setujui", "Approve"), {
                scope.launch { runCatching { vm.vault.approveLink(request!!, p) }.onSuccess { vm.back(); vm.say(done) }.onFailure { error = expired; preview = null } }
            }, Modifier.fillMaxWidth())
            if (p != null) AppButton(tr("Batal", "Cancel"), { preview = null; request = null }, Modifier.fillMaxWidth(), style = BtnStyle.Text)
        }
    }
}

/** M12 */
@Composable
fun DataBackupScreen(vm: AppViewModel, importPick: () -> Unit) {
    val ledger by vm.ledger.collectAsState()
    val settings by vm.settings.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var askKey by remember { mutableStateOf(false) }
    var keyText by remember { mutableStateOf("") }
    var keyError by remember { mutableStateOf(false) }
    var wipe by remember { mutableStateOf(false) }
    var pendingBytes by remember { mutableStateOf<ByteArray?>(null) }
    val written = tr("Ekspor selesai.", "Export finished.")
    val needKey = tr("Buat recovery key dulu di Keamanan & enkripsi.", "Create a recovery key first in Security & encryption.")

    fun write(uri: Uri?) {
        val bytes = pendingBytes ?: return
        if (uri == null) { pendingBytes = null; return }
        runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } }.onSuccess { vm.markExported(); vm.say(written) }
        pendingBytes = null
    }
    val saveArthatis = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { write(it) }
    val saveCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { write(it) }
    val saveSqlite = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.sqlite3")) { write(it) }
    val stamp = java.time.LocalDate.now().toString()
    val live = ledger.liveTxs.size
    val dbSize = remember(live) { runCatching { java.io.File(context.getDatabasePath("arthatis-wallet.db").path).length() / 1024 }.getOrDefault(0) }

    Column(Modifier.fillMaxSize()) {
        TopBar(tr("Data & backup", "Data & backup"), onBack = { vm.back() })
        Page {
            AppCard(padding = 20.dp) { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("Ekspor data", "Export data"), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Hint(tr("File .arthatis terenkripsi dengan recovery key, atau CSV / SQLite. Termasuk wallet pribadi; wallet bareng hanya dicantumkan namanya.", "The .arthatis file is encrypted with your recovery key; or CSV / SQLite. Personal wallets are included; shared wallets are listed by name only."))
                AppButton(tr("Ekspor .arthatis", "Export .arthatis"), { if (settings.recoveryCreated) { keyText = ""; keyError = false; askKey = true } else vm.say(needKey) }, Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppButton("CSV", { scope.launch { pendingBytes = toCsv(vm.archiveData()).toByteArray(); saveCsv.launch("arthatis-$stamp.csv") } }, Modifier.weight(1f), style = BtnStyle.Tonal, size = BtnSize.S)
                    AppButton("SQLite", { scope.launch { pendingBytes = com.arthatis.wallet.export.SqliteExport.build(context, vm.archiveData()); saveSqlite.launch("arthatis-$stamp.sqlite") } }, Modifier.weight(1f), style = BtnStyle.Tonal, size = BtnSize.S)
                }
            } }
            AppCard(padding = 20.dp) { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("Laporan keuangan", "Financial report"), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Hint(tr("Ringkasan, per kategori, per dompet, dan daftar transaksi dalam rentang tanggal, sebagai PDF.", "Summary, by category, by wallet and the transaction list for a date range, as a PDF."))
                AppButton(tr("Buat laporan PDF", "Create PDF report"), { vm.go(Route.Report) }, Modifier.fillMaxWidth(), style = BtnStyle.Tonal)
            } }
            AppCard(padding = 20.dp) { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("Impor data", "Import data"), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Hint(tr("Gabungkan atau ganti data yang ada di perangkat ini.", "Merge into or replace the data on this device."))
                AppButton(tr("Pilih file .arthatis / CSV", "Choose .arthatis / CSV file"), importPick, Modifier.fillMaxWidth(), style = BtnStyle.Outlined)
            } }
            Hint(buildString {
                if (settings.lastExportAt > 0) append(tr("Ekspor terakhir · ", "Last export · ")).append(shortDate(com.arthatis.wallet.core.dayOf(settings.lastExportAt))).append(" · ")
                append(tr("$live transaksi", "$live transactions"))
            })
            Hint(tr("Penyimpanan lokal: ${dbSize} KB.", "Local storage: ${dbSize} KB."))
            AppCard(padding = 20.dp) { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("Zona berbahaya", "Danger zone"), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                Hint(tr("Hapus semua data di perangkat ini. Pastikan kamu sudah mengekspor cadangan.", "Delete all data on this device. Make sure you've exported a backup."))
                AppButton(tr("Hapus semua data", "Delete all data"), { wipe = true }, Modifier.fillMaxWidth(), style = BtnStyle.Outlined)
            } }
        }
    }
    if (askKey) androidx.compose.material3.AlertDialog(
        onDismissRequest = { askKey = false }, title = { Text(tr("Recovery key", "Recovery key")) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Hint(tr("Masukkan recovery key untuk mengenkripsi file.", "Enter your recovery key to encrypt the file."))
            AppTextField(keyText, { keyText = it; keyError = false }, "ABCD-EFGH-…", error = keyError)
        } },
        confirmButton = { androidx.compose.material3.TextButton({
            scope.launch {
                val bytes = vm.encodeBackup(vm.archiveData(), keyText)
                if (bytes == null) keyError = true else { pendingBytes = bytes; askKey = false; saveArthatis.launch("arthatis-$stamp.arthatis") }
            }
        }) { Text(tr("Ekspor", "Export")) } },
        dismissButton = { androidx.compose.material3.TextButton({ askKey = false }) { Text(tr("Batal", "Cancel")) } },
    )
    if (wipe) ConfirmDialog(tr("Hapus semua data?", "Delete all data?"), tr("Semua dompet dan transaksi di HP ini dihapus permanen. Salinan di server (jika ada) tidak ikut terhapus.", "Every wallet and transaction on this phone is deleted permanently. Any copy on the server is not deleted."), tr("Hapus", "Delete"), { wipe = false }) { wipe = false; vm.wipeEverything() }
}

/** M13 */
@Composable
fun ImportPreviewScreen(vm: AppViewModel) {
    val staged by vm.pendingImport.collectAsState()
    val scope = rememberCoroutineScope()
    var keyText by remember { mutableStateOf("") }
    var keyError by remember { mutableStateOf<String?>(null) }
    var replace by remember { mutableStateOf(false) }
    var confirmReplace by remember { mutableStateOf(false) }
    val s = staged
    if (s == null) { LaunchedEffect(Unit) { vm.back() }; return }
    val data = s.data
    val done = tr("Impor selesai.", "Import finished.")
    Column(Modifier.fillMaxSize()) {
        TopBar(s.name, onBack = { vm.clearImport(); vm.back() })
        Page {
            if (data == null) {
                Body(tr("File ini terenkripsi. Masukkan recovery key yang dipakai saat ekspor.", "This file is encrypted. Enter the recovery key used when exporting."))
                AppTextField(keyText, { keyText = it; keyError = null }, "Recovery key", error = keyError != null, supporting = when (keyError) { "key" -> tr("Recovery key salah.", "Wrong recovery key."); "file" -> tr("Bukan file .arthatis yang valid.", "Not a valid .arthatis file."); else -> null })
                AppButton(tr("Buka", "Unlock"), { keyError = vm.unlockImport(keyText) }, Modifier.fillMaxWidth(), enabled = keyText.isNotBlank())
            } else {
                Hint("${(s.bytes.size / 1024.0 / 1024.0).let { "%.1f".format(it).replace('.', ',') }} MB · ${tr("dibuat", "created")} ${shortDate(com.arthatis.wallet.core.dayOf(data.createdAt))}" + if (data.device.isNotBlank()) " · ${tr("dari", "from")} ${data.device}" else "")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatCard(tr("Transaksi", "Transactions"), data.transactions.count { it.deletedAt == null }.toString(), modifier = Modifier.weight(1f))
                    StatCard(tr("Dompet", "Wallets"), data.wallets.count { it.deletedAt == null }.toString(), modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatCard(tr("Kategori", "Categories"), data.categories.count { it.deletedAt == null }.toString(), modifier = Modifier.weight(1f))
                    StatCard(tr("Wallet bareng", "Shared wallets"), data.sharedWallets.size.toString(), data.sharedWallets.joinToString().ifBlank { null }, Modifier.weight(1f))
                }
                if (data.sharedWallets.isNotEmpty()) Hint(tr("Wallet bareng tidak ikut diimpor; datanya tetap di server dan keanggotaanmu.", "Shared wallets aren't imported; they stay on the server with your membership."))
                SectionTitle(tr("Cara impor", "Import mode"))
                ChipRow { AppChip(tr("Gabungkan", "Merge"), !replace, { replace = false }); AppChip(tr("Ganti semua", "Replace all"), replace, { replace = true }) }
                Hint(if (replace) tr("Hapus data lama di perangkat ini, lalu pakai isi file.", "Erase this device's data, then use the file's contents.") else tr("Transaksi yang sama akan dilewati. Tidak ada data yang hilang.", "Identical entries are skipped. Nothing is lost."))
                val n = data.transactions.count { it.deletedAt == null }
                AppButton(tr("Impor $n transaksi", "Import $n transactions"), { if (replace) confirmReplace = true else scope.launch { vm.applyImport(false); vm.reset(Route.Main); vm.say(done) } }, Modifier.fillMaxWidth())
            }
        }
    }
    if (confirmReplace) ConfirmDialog(tr("Ganti semua data?", "Replace all data?"), tr("Data lama di perangkat ini dihapus dan tidak bisa dikembalikan.", "The old data on this device is deleted and can't be recovered."), tr("Ganti", "Replace"), { confirmReplace = false }) {
        confirmReplace = false; scope.launch { vm.applyImport(true); vm.reset(Route.Main); vm.say(done) }
    }
}
