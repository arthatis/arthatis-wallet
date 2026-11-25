package com.arthatis.wallet.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import kotlinx.coroutines.launch
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.arthatis.wallet.AppViewModel
import com.arthatis.wallet.Route
import com.arthatis.wallet.core.EntryKind
import com.arthatis.wallet.core.ReceiptLine
import com.arthatis.wallet.core.Tx
import com.arthatis.wallet.core.formatMinor
import com.arthatis.wallet.core.parseReceipt
import com.arthatis.wallet.data.newId
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.time.LocalTime

/** M06 */
@Composable
fun ChatScreen(vm: AppViewModel) {
    val messages by vm.chat.collectAsState()
    val thinking by vm.thinking.collectAsState()
    val ledger by vm.ledger.collectAsState()
    val engine by produceState("On-device") { value = vm.engineLabel() }
    var input by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(messages.size, thinking) { if (messages.isNotEmpty()) list.animateScrollToItem(messages.size) }
    val now = { LocalTime.now().let { "%02d:%02d".format(it.hour, it.minute) } }
    val unknown = tr("Aku belum bisa menjawab itu. Tulis transaksi seperti “bakso 25k”, atau tanya ringkasan minggu ini.", "I can't answer that yet. Write an entry like “lunch 25k”, or ask for this week's summary.")
    val saved = tr("Tersimpan ✓", "Saved ✓")
    val s1 = tr("Ringkasan minggu ini", "This week's summary"); val s2 = tr("Bandingkan dengan bulan lalu", "Compare with last month"); val s3 = tr("Sisa budget", "Budget left")
    Column(Modifier.fillMaxSize().imePadding()) {
        TopBar("Ollie · Chat AI", onBack = { vm.back() })
        Hint(engine, Modifier.padding(horizontal = 16.dp))
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), state = list, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Bubble(false, tr("Halo! Aku Ollie. Tanya apa saja soal keuanganmu — semua diproses di perangkat ini.", "Hi! I'm Ollie. Ask anything about your money — it's all processed on this device.")) }
            items(messages) { m ->
                when {
                    m.fromUser -> Bubble(true, m.text, m.time)
                    m.draft != null -> DraftCard(vm, m, ledger)
                    m.text == "UNKNOWN" -> Bubble(false, unknown, m.time)
                    m.text == "SAVED" -> Bubble(false, saved, m.time)
                    else -> Bubble(false, m.text, m.time)
                }
            }
            if (thinking) item { Bubble(false, "…") }
        }
        // Quick prompts: 16 dp gutter like the rest of the page, and they scroll sideways instead of overflowing.
        // The row spans the full width; empty 8 dp spacers at both ends (+ 8 dp gap) make the 16 dp gutter, so no chip is clipped while scrolling.
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Spacer(Modifier.width(8.dp))
            AppChip(s1, false, { vm.ask(s1, now()) }); AppChip(s2, false, { vm.ask(s2, now()) }); AppChip(s3, false, { vm.ask(s3, now()) })
            Spacer(Modifier.width(8.dp))
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { SearchField(input, { input = it }, tr("Tanya Ollie…", "Ask Ollie…")) }
            Fab(Icons.AutoMirrored.Rounded.Send, { vm.ask(input, now()); input = "" }, description = tr("Kirim", "Send"))
        }
    }
}

@Composable
private fun Bubble(mine: Boolean, text: String, time: String = "") {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier.widthIn(max = 300.dp).background(if (mine) scheme.primaryContainer else scheme.surfaceContainerHigh, RoundedCornerShape(16.dp)).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = if (mine) scheme.onPrimaryContainer else scheme.onSurface)
            if (time.isNotEmpty()) Text(time, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
        }
    }
}

/** Chat AI never saves by itself: it shows a draft and waits for a tap. */
@Composable
private fun DraftCard(vm: AppViewModel, m: com.arthatis.wallet.ChatMessage, ledger: com.arthatis.wallet.data.Ledger) {
    val d = m.draft!!
    val cat = ledger.category(d.categoryId)
    val transfer = d.kind == EntryKind.transfer
    var walletId by remember { mutableStateOf(d.walletId ?: ledger.liveWallets.firstOrNull { it.id != d.toWalletId }?.id) }
    var toId by remember { mutableStateOf(d.toWalletId ?: if (transfer) ledger.liveWallets.firstOrNull { it.id != walletId }?.id else null) }
    val rates by vm.rates.collectAsState()
    val from = ledger.wallet(walletId); val to = toId?.let { ledger.wallet(it) }
    AppCard(padding = 16.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (transfer) tr("Draf transfer", "Draft transfer") else tr("Draf transaksi", "Draft entry"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(formatMinor(d.amountMinor, from?.currency ?: d.currency, if (transfer) com.arthatis.wallet.core.Sign.None else txSign(d.kind)), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
            Hint(listOfNotNull(cat?.let { categoryName(it) }, d.note).joinToString(" · "))
            if (transfer) FieldLabel(tr("Dari", "From"))
            WalletPicker(from, ledger, { walletId = it.id; if (toId == it.id) toId = null }, exclude = if (transfer) toId else null)
            if (transfer) {
                FieldLabel(tr("Ke", "To"))
                WalletPicker(to, ledger, { toId = it.id }, exclude = walletId)
                if (from != null && to != null && from.currency != to.currency) {
                    val received = rates.convert(d.amountMinor, from.currency, to.currency)
                    Hint(if (received != null) tr("Diterima ≈ ${formatMinor(received, to.currency)} (kurs API)", "Received ≈ ${formatMinor(received, to.currency)} (API rate)") else tr("Kurs belum tersedia; kamu akan diminta isi kurs manual.", "No rate yet; you'll be asked for a manual rate."))
                }
            }
            AppButton(tr("Simpan draf", "Save draft"), { vm.confirmDraft(d, walletId, toId) }, Modifier.fillMaxWidth(), size = BtnSize.S,
                enabled = from != null && (!transfer || (to != null && to.id != from.id)))
        }
    }
}

private data class RLine(val line: ReceiptLine, val selected: Boolean)

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
/** Receipt scan: camera app → on-device ML Kit OCR → review. The photo never leaves the phone and is deleted. */
@Composable
fun ReceiptScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    val ledger by vm.ledger.collectAsState()
    val currency = ledger.profile?.primaryCurrency ?: "IDR"
    val lines = remember { mutableStateListOf<RLine>() }
    val scan by vm.scan.collectAsState()
    var split by remember { mutableStateOf(false) }
    var engineUsed by remember { mutableStateOf<com.arthatis.wallet.ai.Engine?>(null) }
    var printedTotal by remember { mutableStateOf<Long?>(null) }
    var categoryId by remember { mutableStateOf<String?>(null) }
    var walletId by remember { mutableStateOf(ledger.liveWallets.firstOrNull()?.id) }
    val file = remember { File(ctx.cacheDir, "receipt.jpg") }
    val uri = remember { FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file) }
    val wallet = ledger.wallet(walletId)
    val cur = wallet?.currency ?: currency
    val busy = scan == AppViewModel.ScanState.Reading || scan == AppViewModel.ScanState.Structuring
    val scanned = scan is AppViewModel.ScanState.Done
    // Leaving the screen stops any scan still running.
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { vm.cancelScan() } }
    LaunchedEffect(scan) {
        val done = scan as? AppViewModel.ScanState.Done ?: return@LaunchedEffect
        lines.clear()
        done.read.lines.forEach { lines += RLine(it, true) }
        // Tax, service, discounts, payment and change: listed so they can be added; ticked only when they make the printed total.
        done.read.extras.forEach { lines += RLine(it, it in done.read.preselected) }
        printedTotal = done.read.totalMinor
        done.read.categoryId?.let { categoryId = it }
        engineUsed = done.read.engine
    }

    val shoot = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) vm.scanReceipt(file)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) shoot.launch(uri) else vm.say(vm.t("Izin kamera ditolak.", "Camera permission denied.")) }
    fun capture() {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) shoot.launch(uri) else permission.launch(Manifest.permission.CAMERA)
    }

    val picked = lines.filter { it.selected }.mapNotNull { it.line.amountMinor }
    val cats = ledger.liveCategories.filter { !it.hidden && it.kind == EntryKind.expense }
    Column(Modifier.fillMaxSize()) {
        TopBar(tr("Foto struk", "Receipt photo"), onBack = { vm.back() })
        Page {
            Hint(tr("Foto diproses di perangkat dan tidak diunggah. Periksa angka yang bertanda.", "The photo is processed on this device and never uploaded. Check flagged numbers."))
            AppButton(if (scanned) tr("Foto ulang", "Retake photo") else tr("Ambil foto struk", "Take receipt photo"), ::capture, Modifier.fillMaxWidth(), style = if (scanned) BtnStyle.Tonal else BtnStyle.Filled, icon = Icons.Rounded.CameraAlt, loading = busy)
            if (busy) Row(verticalAlignment = Alignment.CenterVertically) {
                Hint(if (scan == AppViewModel.ScanState.Reading) tr("Membaca teks di HP…", "Reading text on the phone…") else tr("Menyusun dengan AI… (maks. ±1 menit, lalu parser di HP)", "Structuring with AI… (up to ~1 min, then the on-device parser)"), Modifier.weight(1f))
                AppButton(tr("Batal", "Cancel"), { vm.cancelScan() }, style = BtnStyle.Text, size = BtnSize.S)
            }
            (scan as? AppViewModel.ScanState.Failed)?.let { f ->
                Text(if (f.reason == "image") tr("Foto tidak bisa dibuka. Coba ambil ulang.", "The photo couldn't be opened. Try again.") else tr("Teks struk tidak terbaca. Coba foto lebih dekat, terang, dan tegak.", "Couldn't read the receipt text. Try a closer, brighter, upright photo."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            engineUsed?.let { Hint(tr("Dibaca oleh: ", "Read by: ") + when (it) { com.arthatis.wallet.ai.Engine.Nano -> "Gemini Nano"; com.arthatis.wallet.ai.Engine.Server -> tr("AI server", "Server AI"); com.arthatis.wallet.ai.Engine.Cloud -> tr("API pribadi", "Your API key"); com.arthatis.wallet.ai.Engine.Local -> tr("parser di HP", "on-device parser") }) }
            if (scanned && lines.isEmpty()) EmptyState(Pose.Worried, tr("Tidak ada angka terbaca", "No amounts were read"), tr("Coba foto lebih dekat dan terang.", "Try a closer, brighter photo."), ollieSize = 96.dp)
            @Composable
            fun lineCard(i: Int, rl: RLine) {
                AppCard(padding = 8.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(rl.selected, { lines[i] = rl.copy(selected = it) })
                        Column(Modifier.weight(1f)) {
                            Text(rl.line.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                            when (rl.line.role) {
                                com.arthatis.wallet.core.LineRole.Charge -> Hint(tr("Pajak/biaya · menambah total", "Tax/fee · adds to the total"))
                                com.arthatis.wallet.core.LineRole.Discount -> Hint(tr("Diskon · mengurangi total", "Discount · lowers the total"))
                                com.arthatis.wallet.core.LineRole.Payment -> Hint(tr("Pembayaran/kembalian · biasanya tidak dihitung", "Payment/change · usually not counted"))
                                else -> if (rl.line.isTotal) Hint(tr("Total", "Total"))
                            }
                            if (rl.line.uncertain) Text(tr("Periksa angka ini", "Check this number"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        }
                        val amount = rl.line.amountMinor ?: 0
                        Text(formatMinor(amount, cur, if (amount < 0) com.arthatis.wallet.core.Sign.Minus else com.arthatis.wallet.core.Sign.None), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
            val itemIdx = lines.indices.filter { lines[it].line.role == com.arthatis.wallet.core.LineRole.Item }
            val extraIdx = lines.indices.filter { lines[it].line.role != com.arthatis.wallet.core.LineRole.Item }
            itemIdx.forEach { lineCard(it, lines[it]) }
            if (extraIdx.isNotEmpty()) {
                FieldLabel(tr("Pajak, diskon & lainnya", "Tax, discounts & more"))
                Hint(tr("Centang yang ikut dibayar. Pajak/biaya menambah, diskon mengurangi.", "Tick what was part of the bill. Tax/fees add, discounts subtract."))
                extraIdx.forEach { lineCard(it, lines[it]) }
            }
            printedTotal?.let { t ->
                val sum = lines.filter { it.selected }.sumOf { it.line.amountMinor ?: 0 }
                Hint(if (sum == t) tr("Total di struk ${formatMinor(t, cur)} · cocok dengan yang dicentang ✓", "Receipt total ${formatMinor(t, cur)} · matches the ticked lines ✓")
                    else tr("Total di struk ${formatMinor(t, cur)} · yang dicentang ${formatMinor(sum, cur)}. Periksa lagi (pajak/diskon?).", "Receipt total ${formatMinor(t, cur)} · ticked ${formatMinor(sum, cur)}. Please check (tax/discount?)."))
            }
            if (lines.isNotEmpty()) {
                FieldLabel(tr("Kategori", "Category"))
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    cats.forEach { c -> AppChip(categoryName(c), categoryId == c.id, { categoryId = c.id }, leading = categoryIcon(c.key)) }
                }
                FieldLabel(tr("Dompet", "Wallet"))
                WalletPicker(wallet, ledger, { walletId = it.id })
                Row(verticalAlignment = Alignment.CenterVertically) { Text(tr("Pisah per item", "Split per item"), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface); AppSwitch(split, { split = it }) }
                val total = picked.sum()
                AppButton(tr("Simpan ${formatMinor(total, cur)}", "Save ${formatMinor(total, cur)}"), {
                    val w = wallet ?: return@AppButton
                    val now = System.currentTimeMillis()
                    // Split: discounts come off the largest lines so no entry is negative; tax/fees get their own entry.
                    if (split) com.arthatis.wallet.core.splitReceipt(lines.filter { it.selected }.map { it.line }).forEach { (text, minor) ->
                        vm.saveTx(Tx(newId(), w.id, null, categoryId, EntryKind.expense, minor, note = text, occurredAt = now, updatedAt = 0))
                    } else vm.saveTx(Tx(newId(), w.id, null, categoryId, EntryKind.expense, total, note = tr0("Struk", "Receipt"), occurredAt = now, updatedAt = 0))
                    vm.back(); vm.say(vm.t("Tersimpan.", "Saved."))
                }, Modifier.fillMaxWidth(), enabled = picked.isNotEmpty() && picked.sum() > 0 && wallet != null && categoryId != null)
                if (categoryId == null) Hint(tr("Pilih kategori dulu.", "Choose a category first."))
            }
        }
    }
}

private fun tr0(id: String, en: String) = if (java.util.Locale.getDefault().language == "en") en else id
