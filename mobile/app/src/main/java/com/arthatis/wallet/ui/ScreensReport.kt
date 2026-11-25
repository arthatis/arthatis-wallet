package com.arthatis.wallet.ui

import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PictureAsPdf
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.arthatis.wallet.AppViewModel
import com.arthatis.wallet.core.EntryKind
import com.arthatis.wallet.core.Report
import com.arthatis.wallet.core.Sign
import com.arthatis.wallet.core.buildReport
import com.arthatis.wallet.core.dayOf
import com.arthatis.wallet.core.epochMillis
import com.arthatis.wallet.core.formatMinor
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Every text the PDF needs, resolved in Compose (language, category names) so the writer itself is plain code. */
private class ReportText(
    val title: String, val period: String, val generated: String, val summary: String, val opening: String, val netWorth: String,
    val income: String, val expense: String, val net: String, val byExpense: String, val byIncome: String,
    val wallets: String, val walletHead: List<String>, val txHead: List<String>, val transactions: String, val kindName: (EntryKind) -> String,
    val none: String, val page: String, val footer: String,
    val categoryName: (String?) -> String, val walletName: (String?) -> String, val date: (Long) -> String,
)

@Composable
private fun kindLabels(): (EntryKind) -> String {
    val e = tr("Pengeluaran", "Expense"); val i = tr("Pemasukan", "Income"); val t = tr("Transfer", "Transfer")
    return { k -> when (k) { EntryKind.expense -> e; EntryKind.income -> i; EntryKind.transfer -> t } }
}

/** Financial report for a date range (default: last 30 days up to today), saved as a PDF made on the phone. */
@Composable
fun ReportScreen(vm: AppViewModel) {
    val ledger by vm.ledger.collectAsState()
    val rates by vm.rates.collectAsState()
    val context = LocalContext.current
    val today = LocalDate.now(ZoneOffset.UTC)
    var fromMillis by remember { mutableStateOf(epochMillis(today.minusDays(30))) }
    var toMillis by remember { mutableStateOf(epochMillis(today)) }
    var pending by remember { mutableStateOf<ByteArray?>(null) }
    val currency = ledger.profile?.primaryCurrency ?: "IDR"
    val from = dayOf(fromMillis); val to = dayOf(toMillis)
    val valid = !from.isAfter(to)
    // The end day is included: the range runs to the start of the next day.
    val report = if (valid) buildReport(ledger.wallets, ledger.txs, currency, rates, fromMillis, epochMillis(to.plusDays(1))) else null
    val saved = tr("Laporan PDF tersimpan.", "PDF report saved.")
    val failed = tr("Gagal menyimpan PDF.", "Couldn't save the PDF.")
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val bytes = pending; pending = null
        if (uri == null || bytes == null) return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } }
            .onSuccess { vm.say(saved) }.onFailure { vm.say(failed) }
    }
    val fmt = DateTimeFormatter.ofPattern("d MMM yyyy", locale())
    val catNames = ledger.categories.associate { it.id to categoryName(it) }
    val uncategorised = tr("Tanpa kategori", "Uncategorised")
    val text = ReportText(
        title = tr("Laporan Keuangan", "Financial Report"),
        period = "${fmt.format(from)} – ${fmt.format(to)}",
        generated = tr("Dibuat ", "Generated ") + fmt.format(today) + " · Arthatis Wallet",
        summary = tr("Ringkasan", "Summary"),
        opening = tr("Saldo awal periode (bukan pemasukan)", "Opening balance (not income)"),
        netWorth = tr("Saldo akhir periode (kas + investasi − utang kartu)", "Closing balance (cash + investments − card debt)"),
        income = tr("Pemasukan", "Income"), expense = tr("Pengeluaran", "Expenses"), net = tr("Selisih", "Net"),
        byExpense = tr("Pengeluaran per kategori", "Expenses by category"), byIncome = tr("Pemasukan per kategori", "Income by category"),
        wallets = tr("Per dompet", "By wallet"),
        walletHead = listOf(tr("Dompet", "Wallet"), tr("Awal", "Opening"), tr("Masuk", "In"), tr("Keluar", "Out"), tr("Akhir", "Closing")),
        txHead = listOf(tr("Tanggal", "Date"), tr("Keterangan", "Description"), tr("Dompet", "Wallet"), tr("Nominal", "Amount")),
        transactions = tr("Transaksi per kategori", "Transactions by category"), none = tr("Tidak ada data.", "No data."),
        kindName = kindLabels(),
        page = tr("Halaman", "Page"),
        footer = tr("Dibuat di perangkat. Nominal dalam mata uang masing-masing dompet kecuali disebut lain.", "Made on the device. Amounts are in each wallet's currency unless stated."),
        categoryName = { id -> id?.let { catNames[it] } ?: uncategorised },
        walletName = { id -> id?.let { ledger.wallet(it)?.name } ?: "?" },
        date = { millis -> fmt.format(dayOf(millis)) },
    )

    Column(Modifier.fillMaxSize()) {
        TopBar(tr("Laporan keuangan", "Financial report"), onBack = { vm.back() })
        Page {
            Hint(tr("Pilih rentang tanggal. Laporan dibuat di HP ini sebagai PDF; tidak ada yang diunggah.", "Pick a date range. The report is made on this phone as a PDF; nothing is uploaded."))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FieldLabel(tr("Dari", "From")); DateChip(fromMillis, { fromMillis = it })
                FieldLabel(tr("Sampai", "To")); DateChip(toMillis, { toMillis = it })
            }
            if (!valid) Text(tr("Tanggal awal harus sebelum tanggal akhir.", "The start date must be before the end date."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            if (valid && report == null) Hint(tr("Kurs belum tersedia untuk mengonversi semua dompet. Buka Pengaturan → Mata uang & kurs.", "Rates are missing for some wallets. Open Settings → Currency & rates."))
            report?.let { r ->
                AppCard(padding = 20.dp) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Hint(tr("Saldo akhir periode", "Closing balance"))
                        FitAmount(formatMinor(r.netWorthMinor, currency), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
                        Hint(tr("Saldo awal ${formatMinor(r.openingMinor, currency)} (bukan pemasukan)", "Opening ${formatMinor(r.openingMinor, currency)} (not income)"))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatCard(text.income, formatMinor(r.incomeMinor, currency, Sign.Plus), modifier = Modifier.weight(1f))
                    StatCard(text.expense, formatMinor(r.expenseMinor, currency, Sign.Minus), modifier = Modifier.weight(1f))
                }
                Hint(tr("${r.transactions.size} transaksi · ${r.expenses.size} kategori pengeluaran · ${r.wallets.size} dompet", "${r.transactions.size} transactions · ${r.expenses.size} expense categories · ${r.wallets.size} wallets"))
            }
            AppButton(tr("Buat PDF", "Create PDF"), {
                val r = report ?: return@AppButton
                pending = writeReportPdf(r, text, ledger)
                save.launch("arthatis-laporan-$from-$to.pdf")
            }, Modifier.fillMaxWidth(), enabled = report != null, icon = Icons.Rounded.PictureAsPdf)
        }
    }
}

/** A4 portrait, drawn with the framework's PdfDocument (no library). Long tables continue on new pages. */
private fun writeReportPdf(r: Report, t: ReportText, ledger: com.arthatis.wallet.data.Ledger): ByteArray {
    val doc = PdfDocument()
    val width = 595; val height = 842; val margin = 40f; val right = width - margin
    val title = Paint().apply { textSize = 20f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); isAntiAlias = true; color = 0xFF1B6D35.toInt() }
    val h2 = Paint().apply { textSize = 13f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); isAntiAlias = true; color = 0xFF181D18.toInt() }
    val body = Paint().apply { textSize = 10f; isAntiAlias = true; color = 0xFF181D18.toInt() }
    val muted = Paint(body).apply { color = 0xFF5C5F5C.toInt(); textSize = 9f }
    val bold = Paint(body).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
    val rule = Paint().apply { color = 0xFFC2C9BE.toInt(); strokeWidth = 0.6f }
    var pageNo = 0
    lateinit var page: PdfDocument.Page
    var y = 0f

    fun finish() {
        page.canvas.drawText("${t.page} $pageNo", right - muted.measureText("${t.page} $pageNo"), height - 24f, muted)
        doc.finishPage(page)
    }
    fun newPage() {
        if (pageNo > 0) finish()
        pageNo++
        page = doc.startPage(PdfDocument.PageInfo.Builder(width, height, pageNo).create())
        y = margin + 10f
    }
    fun ensure(space: Float) { if (y + space > height - 48f) newPage() }
    fun fit(text: String, paint: Paint, max: Float): String {
        if (paint.measureText(text) <= max) return text
        var s = text
        while (s.isNotEmpty() && paint.measureText("$s…") > max) s = s.dropLast(1)
        return "$s…"
    }
    fun right(text: String, x: Float, paint: Paint) = page.canvas.drawText(text, x - paint.measureText(text), y, paint)
    fun section(name: String) { ensure(40f); y += 14f; page.canvas.drawText(name, margin, y, h2); y += 6f; page.canvas.drawLine(margin, y, right, y, rule); y += 14f }
    fun row(left: String, value: String, paint: Paint = body) { ensure(16f); page.canvas.drawText(fit(left, paint, 330f), margin, y, paint); right(value, right, paint); y += 15f }

    newPage()
    page.canvas.drawText(t.title, margin, y + 10f, title); y += 30f
    page.canvas.drawText(t.period, margin, y, body); y += 14f
    page.canvas.drawText(t.generated, margin, y, muted); y += 8f

    section(t.summary)
    row(t.opening, formatMinor(r.openingMinor, r.currency))
    row(t.income, formatMinor(r.incomeMinor, r.currency, Sign.Plus))
    row(t.expense, formatMinor(r.expenseMinor, r.currency, Sign.Minus))
    row(t.net, formatMinor(r.netMinor, r.currency, if (r.netMinor >= 0) Sign.Plus else Sign.Minus))
    row(t.netWorth, formatMinor(r.netWorthMinor, r.currency), bold)

    section("${t.byExpense} (${r.currency})")
    if (r.expenses.isEmpty()) row(t.none, "")
    r.expenses.forEach { c ->
        val pct = if (r.expenseMinor > 0) " · ${"%.1f".format(c.amountMinor * 100.0 / r.expenseMinor)}%" else ""
        row("${t.categoryName(c.categoryId)} (${c.count})$pct", formatMinor(c.amountMinor, r.currency))
    }
    section("${t.byIncome} (${r.currency})")
    if (r.incomes.isEmpty()) row(t.none, "")
    r.incomes.forEach { c -> row("${t.categoryName(c.categoryId)} (${c.count})", formatMinor(c.amountMinor, r.currency)) }

    section(t.wallets)
    val wx = floatArrayOf(margin, 250f, 345f, 440f, right)
    ensure(16f)
    page.canvas.drawText(t.walletHead[0], wx[0], y, bold); (1..4).forEach { right(t.walletHead[it], wx[it], bold) }; y += 15f
    r.wallets.forEach { w ->
        ensure(16f)
        page.canvas.drawText(fit(w.wallet.name, body, 110f), wx[0], y, body)
        right(formatMinor(w.openingMinor, w.wallet.currency), wx[1], body)
        right(formatMinor(w.inMinor, w.wallet.currency), wx[2], body)
        right(formatMinor(w.outMinor, w.wallet.currency), wx[3], body)
        right(formatMinor(w.closingMinor, w.wallet.currency), wx[4], body)
        y += 15f
    }

    section("${t.transactions} (${r.transactions.size})")
    val tx = floatArrayOf(margin, 110f, 400f, right)
    fun txHeader() { page.canvas.drawText(t.txHead[0], tx[0], y, muted); page.canvas.drawText(t.txHead[1], tx[1], y, muted); page.canvas.drawText(t.txHead[2], tx[2], y, muted); right(t.txHead[3], tx[3], muted); y += 14f }
    if (r.transactions.isEmpty()) row(t.none, "")
    r.groups.forEach { g ->
        // Group header: category, kind, count and subtotal in the report currency.
        ensure(48f); y += 6f
        page.canvas.drawText(fit("${t.categoryName(g.categoryId)} · ${t.kindName(g.kind)} (${g.transactions.size})", bold, 360f), margin, y, bold)
        right(formatMinor(g.totalMinor, r.currency), right, bold); y += 15f
        txHeader()
        g.transactions.forEach { item ->
            if (y + 16f > height - 48f) { newPage(); txHeader() }
            val wallet = ledger.wallet(item.walletId)
            val description = when (item.kind) {
                EntryKind.transfer -> "${item.note.ifBlank { "Transfer" }} → ${t.walletName(item.toWalletId)}" + (item.feeMinor?.takeIf { it > 0 }?.let { " (fee ${formatMinor(it, ledger.wallet(item.toWalletId)?.currency ?: r.currency)})" } ?: "")
                else -> item.note.ifBlank { t.categoryName(item.categoryId) }
            }
            val sign = when (item.kind) { EntryKind.income -> Sign.Plus; EntryKind.expense -> Sign.Minus; EntryKind.transfer -> Sign.None }
            page.canvas.drawText(t.date(item.occurredAt), tx[0], y, body)
            page.canvas.drawText(fit(description, body, 280f), tx[1], y, body)
            page.canvas.drawText(fit(wallet?.name ?: "?", body, 80f), tx[2], y, body)
            right(formatMinor(item.amountMinor, wallet?.currency ?: r.currency, sign), tx[3], body)
            y += 15f
        }
    }
    ensure(30f); y += 12f
    page.canvas.drawText(t.footer, margin, y, muted)
    finish()
    return ByteArrayOutputStream().also { doc.writeTo(it); doc.close() }.toByteArray()
}
