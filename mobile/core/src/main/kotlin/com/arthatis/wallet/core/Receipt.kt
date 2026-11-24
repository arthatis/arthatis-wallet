package com.arthatis.wallet.core

import java.math.BigDecimal

data class ReceiptLine(val text: String, val amountMinor: Long?, val isTotal: Boolean, val uncertain: Boolean)

private val numberToken = Regex("""[0-9OolI][0-9OolI.,]*[0-9OolI]|[0-9]""")
private val totalWords = listOf("total", "jumlah", "grand total", "amount due", "tagihan", "bayar")

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
