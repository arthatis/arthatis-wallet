package com.arthatis.wallet.core

import java.math.BigDecimal

/** What a receipt line is. Charges add to the total, discounts subtract (their amount is negative), payments don't count. */
enum class LineRole { Item, Charge, Discount, Payment }

data class ReceiptLine(val text: String, val amountMinor: Long?, val isTotal: Boolean, val uncertain: Boolean, val role: LineRole = LineRole.Item)

private val numberToken = Regex("""[0-9OolI][0-9OolI.,]*[0-9OolI]|[0-9]""")
private val totalWords = listOf("total", "jumlah", "grand total", "amount due", "tagihan", "bayar")
/** Charges that change what was paid: tax, service, fees, delivery, rounding. */
private val chargeWords = listOf("ppn", "pajak", "tax", "pb1", "service", "servis", "biaya", "admin", "ongkir", "ongkos kirim", "delivery", "packaging", "kemasan", "pembulatan", "rounding", "donasi")
private val discountWords = listOf("diskon", "discount", "disc", "hemat", "voucher", "potongan", "promo", "cashback")
/** How the bill was paid, and change: never part of the spending. */
private val paymentWords = listOf("tunai", "cash", "kembali", "kembalian", "change", "debit", "kredit", "credit", "qris", "gopay", "ovo", "dana", "shopeepay", "linkaja", "edc", "card", "kartu")
/** Information only (no money of its own). */
private val infoWords = listOf("subtotal", "sub total", "dpp", "item", "qty", "jumlah item", "total item")

private fun hasWord(text: String, words: List<String>) = words.any { w -> Regex("""\b${Regex.escape(w)}\b""").containsMatchIn(text) }

/** Role of a line from its words. */
fun lineRole(text: String): LineRole? {
    val t = text.lowercase()
    return when {
        hasWord(t, infoWords) -> null
        hasWord(t, discountWords) -> LineRole.Discount
        hasWord(t, chargeWords) -> LineRole.Charge
        hasWord(t, paymentWords) -> LineRole.Payment
        else -> LineRole.Item
    }
}

private fun fixDigits(token: String) = token.replace('O', '0').replace('o', '0').replace('l', '1').replace('I', '1')

/** Turns a token like `25.000`, `25,000`, `1.250.000` or `12.50` into minor units, or null. */
fun parseAmountToken(token: String, currency: String): Long? {
    val scale = currencyInfo(currency).scale
    val clean = fixDigits(token)
    val last = maxOf(clean.lastIndexOf('.'), clean.lastIndexOf(','))
    val decimals = if (last >= 0) clean.length - last - 1 else 0
    val value = if (scale > 0 && last >= 0 && decimals in 1..2) {
        BigDecimal(clean.substring(0, last).replace(".", "").replace(",", "") + "." + clean.substring(last + 1))
    } else {
        BigDecimal(clean.replace(".", "").replace(",", ""))
    }
    return runCatching { toMinor(value, currency) }.getOrNull()?.takeIf { it > 0 }
}

/** OCR text → lines with an amount. Totals are flagged; lines whose digits needed OCR fixes are marked uncertain. */
fun parseReceipt(raw: String, currency: String): List<ReceiptLine> =
    raw.lines().map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull { line ->
        val token = numberToken.findAll(line).lastOrNull()?.value ?: return@mapNotNull null
        val amount = parseAmountToken(token, currency) ?: return@mapNotNull null
        val label = line.removeSuffix(token).trim().trimEnd(':', '.', '-', ' ', 'R', 'p').ifBlank { line }
        val needsFix = token != fixDigits(token)
        val lower = line.lowercase()
        ReceiptLine(label, amount, totalWords.any { lower.contains(it) }, needsFix || amount > toMinor(BigDecimal(100_000_000), currency))
    }

/**
 * A receipt split into what was bought ([items]), what else changed the bill or how it was paid ([extras]: tax, service,
 * fees and rounding add; discounts subtract (negative amount); payment and change don't count), and the printed total.
 */
data class ReceiptItems(val items: List<ReceiptLine>, val extras: List<ReceiptLine>, val totalMinor: Long?) {
    /** Extras to tick by default: the charges and discounts, but only when items + those make exactly the printed total. */
    fun defaultExtras(): Set<ReceiptLine> {
        val adjust = extras.filter { it.role == LineRole.Charge || it.role == LineRole.Discount }
        val sum = items.sumOf { it.amountMinor ?: 0 } + adjust.sumOf { it.amountMinor ?: 0 }
        return if (totalMinor != null && adjust.isNotEmpty() && sum == totalMinor) adjust.toSet() else emptySet()
    }
}

/**
 * Sorts receipt lines into products, extras and the printed total. Subtotal/quantity lines are dropped. When no product
 * line is found, the total becomes the single item.
 */
fun receiptItems(lines: List<ReceiptLine>): ReceiptItems {
    val total = lines.lastOrNull { it.isTotal && !it.text.lowercase().contains("sub") && lineRole(it.text) != LineRole.Payment }?.amountMinor
    val sorted = lines.filter { !it.isTotal || lineRole(it.text) == LineRole.Payment }.mapNotNull { l ->
        val role = if (l.role != LineRole.Item) l.role else lineRole(l.text) ?: return@mapNotNull null
        val amount = l.amountMinor?.let { if (role == LineRole.Discount) -kotlin.math.abs(it) else it }
        l.copy(role = role, amountMinor = amount, isTotal = false)
    }
    val items = sorted.filter { it.role == LineRole.Item }
    val extras = sorted.filter { it.role != LineRole.Item }
    if (items.isNotEmpty()) return ReceiptItems(items, extras, total)
    return ReceiptItems(listOfNotNull(total?.let { ReceiptLine("Total", it, isTotal = true, uncertain = false) }), extras, null)
}

/** Every amount written in [ocrText], as minor units of [currency]; used to check that an AI-read amount really is on the receipt. */
fun amountsIn(ocrText: String, currency: String): Set<Long> =
    numberToken.findAll(ocrText).mapNotNull { parseAmountToken(it.value, currency) }.toSet()

/** Turns ticked receipt lines into expense amounts per line for "split per item": discounts and other negatives are taken off the largest items, so no line is negative. */
fun splitReceipt(selected: List<ReceiptLine>): List<Pair<String, Long>> {
    val positives = selected.filter { (it.amountMinor ?: 0) > 0 }.map { it.text to it.amountMinor!! }.toMutableList()
    var minus = -selected.sumOf { (it.amountMinor ?: 0).coerceAtMost(0) }
    for (i in positives.indices.sortedByDescending { positives[it].second }) {
        if (minus <= 0) break
        val take = minOf(minus, positives[i].second)
        positives[i] = positives[i].first to (positives[i].second - take); minus -= take
    }
    return positives.filter { it.second > 0 }
}
