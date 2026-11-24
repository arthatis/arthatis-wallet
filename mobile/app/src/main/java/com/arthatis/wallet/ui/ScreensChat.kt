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
    var walletId by remember { mutableStateOf(d.walletId ?: ledger.liveWallets.firstOrNull()?.id) }
    AppCard(padding = 16.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("Draf transaksi", "Draft entry"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(formatMinor(d.amountMinor, ledger.wallet(walletId)?.currency ?: d.currency, txSign(d.kind)), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
            Hint(listOfNotNull(cat?.let { categoryName(it) }, d.note).joinToString(" · "))
            WalletPicker(ledger.wallet(walletId), ledger, { walletId = it.id })
            AppButton(tr("Simpan draf", "Save draft"), { vm.confirmDraft(d, walletId) }, Modifier.fillMaxWidth(), size = BtnSize.S)
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
    var scanned by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var split by remember { mutableStateOf(false) }
    var engineUsed by remember { mutableStateOf<com.arthatis.wallet.ai.Engine?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var categoryId by remember { mutableStateOf<String?>(null) }
    var walletId by remember { mutableStateOf(ledger.liveWallets.firstOrNull()?.id) }
    val file = remember { File(ctx.cacheDir, "receipt.jpg") }
    val uri = remember { FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file) }
    val wallet = ledger.wallet(walletId)
    val cur = wallet?.currency ?: currency

    val shoot = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (!ok) return@rememberLauncherForActivityResult
        busy = true
        val bitmap = BitmapFactory.decodeFile(file.path)
        if (bitmap == null) { busy = false; return@rememberLauncherForActivityResult }
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { text ->
                // Text was read on the phone. Structuring it goes through the same AI engine as chat (so switching AI switches this too).
                scope.launch {
                    val read = vm.readReceipt(text.text)
                    lines.clear()
                    val total = read.lines.lastOrNull { it.isTotal }
                    read.lines.forEach { lines += RLine(it, if (total != null) it == total else true) }
                    read.categoryId?.let { categoryId = it }
                    engineUsed = read.engine
                    scanned = true
                    busy = false
                }
            }
            .addOnCompleteListener { file.delete() }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) shoot.launch(uri) else vm.say(vm.t("Izin kamera ditolak.", "Camera permission denied.")) }
    fun capture() {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) shoot.launch(uri) else permission.launch(Manifest.permission.CAMERA)
    }

    val picked = lines.filter { it.selected }.mapNotNull { it.line.amountMinor }
    val cats = ledger.liveCategories.filter { !it.hidden && it.kind == EntryKind.expense }
    Column(Modifier.fillMaxSize()) {
        TopBar(tr("Scan struk", "Scan receipt"), onBack = { vm.back() })
        Page {
            Hint(tr("Foto diproses di perangkat dan tidak diunggah. Periksa angka yang bertanda.", "The photo is processed on this device and never uploaded. Check flagged numbers."))
            AppButton(if (scanned) tr("Scan ulang", "Scan again") else tr("Ambil foto struk", "Take receipt photo"), ::capture, Modifier.fillMaxWidth(), style = if (scanned) BtnStyle.Tonal else BtnStyle.Filled, icon = Icons.Rounded.CameraAlt, loading = busy)
            engineUsed?.let { Hint(tr("Dibaca oleh: ", "Read by: ") + when (it) { com.arthatis.wallet.ai.Engine.Nano -> "Gemini Nano"; com.arthatis.wallet.ai.Engine.Server -> tr("AI server", "Server AI"); com.arthatis.wallet.ai.Engine.Cloud -> tr("API pribadi", "Your API key"); com.arthatis.wallet.ai.Engine.Local -> tr("parser di HP", "on-device parser") }) }
            if (scanned && lines.isEmpty()) EmptyState(Pose.Worried, tr("Tidak ada angka terbaca", "No amounts were read"), tr("Coba foto lebih dekat dan terang.", "Try a closer, brighter photo."), ollieSize = 96.dp)
            lines.forEachIndexed { i, rl ->
                AppCard(padding = 8.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(rl.selected, { lines[i] = rl.copy(selected = it) })
                        Column(Modifier.weight(1f)) {
                            Text(rl.line.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                            if (rl.line.isTotal) Hint(tr("Total", "Total"))
                            if (rl.line.uncertain) Text(tr("Periksa angka ini", "Check this number"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        }
                        Text(formatMinor(rl.line.amountMinor ?: 0, cur), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
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
                    if (split) lines.filter { it.selected && it.line.amountMinor != null }.forEach { rl ->
                        vm.saveTx(Tx(newId(), w.id, null, categoryId, EntryKind.expense, rl.line.amountMinor!!, note = rl.line.text, occurredAt = now, updatedAt = 0))
                    } else vm.saveTx(Tx(newId(), w.id, null, categoryId, EntryKind.expense, total, note = tr0("Struk", "Receipt"), occurredAt = now, updatedAt = 0))
                    vm.back(); vm.say(vm.t("Tersimpan.", "Saved."))
                }, Modifier.fillMaxWidth(), enabled = picked.isNotEmpty() && wallet != null && categoryId != null)
                if (categoryId == null) Hint(tr("Pilih kategori dulu.", "Choose a category first."))
            }
        }
    }
}

private fun tr0(id: String, en: String) = if (java.util.Locale.getDefault().language == "en") en else id
