package com.arthatis.wallet.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.arthatis.wallet.AppViewModel
import com.arthatis.wallet.Route
import com.arthatis.wallet.core.WalletKind
import com.arthatis.wallet.core.evaluateExpression
import com.arthatis.wallet.core.supportedCurrencies
import com.arthatis.wallet.core.toMinor
import com.arthatis.wallet.data.HOSTED_URL
import com.arthatis.wallet.data.ServerMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.launch

/** M16: logo, wavy progress, version. Tap anywhere to continue. */
@Composable
fun LoadingScreen(vm: AppViewModel) {
    // 0 → 75% on its own, then hold until local init is done (unknown duration), then finish and continue.
    val progress = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(0.75f, androidx.compose.animation.core.tween(1100, easing = androidx.compose.animation.core.FastOutSlowInEasing))
        vm.ready.first { it }
        progress.animateTo(1f, androidx.compose.animation.core.tween(250))
        delay(150)
        vm.leaveSplash()
    }
    Column(
        Modifier.fillMaxSize().clickable { vm.leaveSplash() }.padding(horizontal = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Ollie(Pose.Wave, 120.dp)
        Spacer(Modifier.height(16.dp))
        Text("Arthatis Wallet", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(tr("Dompet yang tetap jalan tanpa internet.", "A wallet that works without internet."), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(32.dp))
        WavyProgress(fraction = progress.value)
        Spacer(Modifier.height(12.dp))
        Hint(tr("Memuat dompetmu…", "Loading your wallet…"))
        Spacer(Modifier.height(48.dp))
        Hint("v${com.arthatis.wallet.BuildConfig.VERSION_NAME} · AGPL-3.0")
    }
}

/** M00: hero + 3 benefits + two actions. */
@Composable
fun WelcomeScreen(vm: AppViewModel) {
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Ollie(Pose.Wave, 160.dp)
        Text(tr("Dompet yang tetap jalan tanpa internet.", "A wallet that works without internet."), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
        Body(tr("Catat, foto struk, dan tanya AI tanpa akun. Data tinggal di perangkatmu.", "Log, photograph receipts and ask AI without an account. Your data stays on your device."))
        Spacer(Modifier.height(8.dp))
        Benefit(Icons.Rounded.Bolt, tr("Catat cepat", "Log fast"), tr("Keypad, suara, atau foto struk — tanpa internet.", "Keypad, voice or receipt photo — no internet."))
        Benefit(Icons.Rounded.Lock, tr("Privat by default", "Private by default"), tr("E2EE. Server tidak bisa membaca datamu.", "E2EE. The server cannot read your data."))
        Benefit(Icons.Rounded.Groups, tr("Wallet bareng", "Shared wallets"), tr("Nabung dan investasi bersama, transparan.", "Save and invest together, transparently."))
        Spacer(Modifier.weight(1f, fill = false).height(16.dp))
        AppButton(tr("Mulai tanpa akun", "Start without an account"), { vm.go(Route.Onboarding) }, Modifier.fillMaxWidth())
        AppButton(tr("Saya sudah punya akun", "I already have an account"), { vm.go(Route.Restore) }, Modifier.fillMaxWidth(), style = BtnStyle.Text)
        Hint(tr("Tanpa akun · tanpa iklan · tanpa paywall", "No account · no ads · no paywall"))
    }
}

@Composable
private fun Benefit(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String) {
    AppCard {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                Hint(body)
            }
        }
    }
}

/** Server choice, shared by onboarding step 4 and Pengaturan → Server. */
@Composable
fun ServerPicker(mode: ServerMode, onMode: (ServerMode) -> Unit, url: String, onUrl: (String) -> Unit, tested: Boolean?, onTest: () -> Unit, testing: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ServerOption(mode == ServerMode.Hosted, tr("wallet.arthatis.com", "wallet.arthatis.com"), tr("Server Arthatis (hosted). Untuk web mirror dan wallet bareng.", "Arthatis hosting. For the web mirror and shared wallets.")) { onMode(ServerMode.Hosted) }
        ServerOption(mode == ServerMode.Private, tr("VPS pribadi", "My own VPS"), tr("Jalankan server sendiri dengan Docker. Data tetap di servermu.", "Run your own server with Docker. Data stays on your server.")) { onMode(ServerMode.Private) }
        ServerOption(mode == ServerMode.None, tr("Lewati dulu (mobile only)", "Skip for now (mobile only)"), tr("Semua jalan di HP. Web mirror dan Bareng nonaktif.", "Everything runs on the phone. Web mirror and shared wallets are off.")) { onMode(ServerMode.None) }
        if (mode == ServerMode.Private) {
            AppTextField(url, onUrl, tr("URL server", "Server URL"), supporting = tr("Contoh: https://wallet.domainmu.com", "Example: https://wallet.yourdomain.com"), keyboard = KeyboardType.Uri)
            AppButton(if (tested == true) tr("Terhubung ✓", "Reachable ✓") else tr("Tes koneksi", "Test connection"), onTest, style = BtnStyle.Tonal, size = BtnSize.S, loading = testing)
            if (tested == false) Text(tr("Server tidak merespons. Periksa URL dan HTTPS.", "No response. Check the URL and HTTPS."), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ServerOption(selected: Boolean, title: String, body: String, onClick: () -> Unit) {
    AppCard(onClick = onClick) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.RadioButton(selected, onClick)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                Hint(body)
            }
        }
    }
}

/** M15: name, primary currency, first wallet, then the server choice (step 4, added to the design). */
@Composable
fun OnboardingScreen(vm: AppViewModel) {
    var name by remember { mutableStateOf("") }
    var currency by remember { mutableStateOf("IDR") }
    var walletName by remember { mutableStateOf("") }
    var icon by remember { mutableStateOf("wallet") }
    var color by remember { mutableStateOf<String?>(null) }
    var opening by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(ServerMode.None) }
    var url by remember { mutableStateOf("") }
    var tested by remember { mutableStateOf<Boolean?>(null) }
    var testing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val gate = remember { GateState() }
    val scope = rememberCoroutineScope()
    val walletDefault = tr("Tunai", "Cash")

    Column(Modifier.fillMaxSize()) {
        TopBar(tr("Kenalan dulu, yuk.", "Let's get acquainted."), onBack = { vm.back() })
        Page {
            Body(tr("Empat langkah cepat — setelah itu dompetmu siap dipakai, tetap tanpa akun.", "Four quick steps — then your wallet is ready, still no account."))
            Step("1 · ${tr("Nama kamu", "Your name")}") {
                AppTextField(name, { name = it }, tr("Nama", "Name"), supporting = tr("Dipakai untuk sapaan dan wallet bareng.", "Used for the greeting and shared wallets."))
            }
            Step("2 · ${tr("Mata uang utama", "Main currency")}") {
                ChipRow { supportedCurrencies.forEach { c -> AppChip("${c.code} ${c.symbol}", currency == c.code, { currency = c.code }) } }
            }
            Step("3 · ${tr("Wallet pertama", "First wallet")}") {
                AppTextField(walletName, { walletName = it }, tr("Nama dompet", "Wallet name"), supporting = tr("Contoh: Tunai, BCA, GoPay.", "Example: Cash, BCA, GoPay."))
                WalletStyleFields(icon, { icon = it }, color, { color = it })
                AmountField(opening, { opening = it }, currency, tr("Saldo awal (opsional)", "Opening balance (optional)"), supporting = tr("Dalam $currency.", "In $currency."))
            }
            Step("4 · ${tr("Hubungkan server", "Connect a server")}") {
                ServerPicker(mode, { mode = it; tested = null }, url, { url = it; tested = null }, tested, {
                    scope.launch { testing = true; tested = vm.testServer(url); testing = false }
                }, testing)
                if (mode != ServerMode.None) GateFields(vm, gate, mode, url, tested == true)
            }
            val ready = name.isNotBlank() && (mode != ServerMode.Private || tested == true) && (mode == ServerMode.None || gate.ready)
            AppButton(tr("Selesai & mulai", "Finish & start"), {
                busy = true
                val minor = evaluateExpression(opening)?.let { runCatching { toMinor(it, currency) }.getOrNull() } ?: 0L
                vm.finishOnboarding(name.trim(), currency, walletName.trim().ifBlank { walletDefault }, WalletKind.biasa, icon, color, minor, mode, url.trim().trimEnd('/'), gate.email.trim(), gate.code)
            }, Modifier.fillMaxWidth(), enabled = ready, loading = busy)
        }
    }
}

/** What the chosen server asks of a new vault (email code on gated servers; activation note on hosted ones). */
class GateState {
    var info by mutableStateOf<com.arthatis.wallet.sync.ServerInfo?>(null)
    var email by mutableStateOf("")
    var code by mutableStateOf("")
    var sent by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    val needsEmail get() = info?.registration == "email"
    /** True when the server needs nothing more from the user, or the code is filled in. */
    val ready get() = !needsEmail || code.length == 6
}

@Composable
fun GateFields(vm: AppViewModel, gate: GateState, mode: ServerMode, url: String, reachable: Boolean) {
    val scope = rememberCoroutineScope()
    LaunchedEffect(mode, url, reachable) {
        gate.info = null; gate.sent = false; gate.code = ""; gate.error = null
        if (mode == ServerMode.Hosted || (mode == ServerMode.Private && reachable)) gate.info = vm.serverInfo(mode, url)
    }
    val info = gate.info ?: return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (info.activation) Hint(tr("Server ini berbayar: setelah terhubung, app menampilkan ID vault untuk diaktifkan lewat halaman bayar. Sebelum aktif, aplikasi tetap bisa dipakai offline.", "This server is paid: after connecting, the app shows a vault ID to activate on the payment page. Until it's active, the app still works offline."))
        if (gate.needsEmail) {
            Hint(tr("Server ini hanya menerima email yang didaftarkan pengelolanya. Kami kirim kode ke emailmu.", "This server only accepts emails its admin listed. We'll email you a code."))
            AppTextField(gate.email, { gate.email = it; gate.error = null }, "Email", keyboard = KeyboardType.Email)
            AppButton(if (gate.sent) tr("Kirim ulang kode", "Resend code") else tr("Kirim kode", "Send code"), {
                scope.launch { gate.busy = true; val e = vm.sendRegisterCode(mode, url, gate.email); gate.busy = false; gate.error = e?.let(vm::gateMessage); if (e == null) gate.sent = true }
            }, style = BtnStyle.Tonal, size = BtnSize.S, enabled = gate.email.contains('@'), loading = gate.busy)
            if (gate.sent) AppTextField(gate.code, { gate.code = it.filter(Char::isDigit).take(6); gate.error = null }, tr("Kode 6 digit", "6-digit code"), keyboard = KeyboardType.Number, error = gate.error != null)
            gate.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun Step(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        content()
    }
}

/** "Saya sudah punya akun": restore from an .arthatis file or from the server (new screen, not in the Figcraft file). */
@Composable
fun RestoreScreen(vm: AppViewModel, pickBackup: () -> Unit) {
    var mode by remember { mutableStateOf(ServerMode.Hosted) }
    var url by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var sent by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val base = if (mode == ServerMode.Hosted) HOSTED_URL else url.trim().trimEnd('/')
    Column(Modifier.fillMaxSize()) {
        TopBar(tr("Pulihkan akun", "Restore account"), onBack = { vm.back() })
        Page {
            Ollie(Pose.Mail, 96.dp, Modifier.align(Alignment.CenterHorizontally))
            Body(tr("Pulihkan dari file cadangan, atau dari servermu dengan tautan email dan recovery key.", "Restore from a backup file, or from your server with an email link and your recovery key."))
            AppButton(tr("Pilih file .arthatis", "Choose .arthatis file"), pickBackup, Modifier.fillMaxWidth(), style = BtnStyle.Tonal)
            SectionTitle(tr("Dari server", "From a server"))
            ChipRow {
                AppChip("wallet.arthatis.com", mode == ServerMode.Hosted, { mode = ServerMode.Hosted })
                AppChip(tr("VPS pribadi", "My VPS"), mode == ServerMode.Private, { mode = ServerMode.Private })
            }
            if (mode == ServerMode.Private) AppTextField(url, { url = it }, tr("URL server", "Server URL"), keyboard = KeyboardType.Uri)
            AppTextField(email, { email = it }, "Email", keyboard = KeyboardType.Email, supporting = tr("Email yang kamu daftarkan untuk pemulihan.", "The email you registered for recovery."))
            AppButton(if (sent) tr("Kirim ulang tautan", "Resend link") else tr("Kirim tautan pemulihan", "Send recovery link"), {
                scope.launch {
                    busy = true; failed = false
                    val http = com.arthatis.wallet.net.Http({ base }, { null })
                    sent = runCatching {
                        http.call("POST", "/v1/recovery/request", buildJsonObject { put("email", email.trim()) }, auth = false)
                    }.isSuccess
                    failed = !sent; busy = false
                }
            }, Modifier.fillMaxWidth(), style = BtnStyle.Outlined, enabled = email.contains('@') && base.isNotBlank(), loading = busy)
            if (sent) Hint(tr("Jika email terdaftar, tautan dikirim. Salin kode setelah “#token=” di tautan itu.", "If the email is registered, a link was sent. Copy the code after “#token=”."))
            AppTextField(token, { token = it }, tr("Kode dari tautan email", "Code from the email link"))
            AppTextField(key, { key = it }, "Recovery key", supporting = tr("Delapan kelompok empat huruf, contoh ABCD-EFGH-…", "Eight groups of four letters, e.g. ABCD-EFGH-…"))
            if (failed) Text(tr("Pemulihan gagal. Periksa kode dan recovery key.", "Restore failed. Check the code and recovery key."), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            AppButton(tr("Pulihkan", "Restore"), {
                scope.launch { busy = true; failed = !vm.restoreFromServer(mode, url.trim().trimEnd('/'), token, key); busy = false }
            }, Modifier.fillMaxWidth(), enabled = token.isNotBlank() && key.isNotBlank(), loading = busy)
        }
    }
}
