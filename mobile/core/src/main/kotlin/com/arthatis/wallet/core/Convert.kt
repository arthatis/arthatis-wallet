package com.arthatis.wallet.core

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/** `quote`-per-`base` pair, written `USD/IDR` in the UI (1 USD = 16.450 IDR). */
data class RatePair(val base: String, val quote: String) {
    val key: String get() = "$base/$quote"
}

/** The pair a transfer shows: the non-IDR side against IDR, or `from/to` when neither is IDR. */
fun pairFor(from: String, to: String): RatePair = when {
    to == "IDR" -> RatePair(from, "IDR")
    from == "IDR" -> RatePair(to, "IDR")
    else -> RatePair(from, to)
}

/** Foreign currency to IDR, as published by the API (`USD` → 16450). IDR is implicit 1. */
class Rates(private val toIdr: Map<String, BigDecimal> = emptyMap(), private val manual: Map<String, BigDecimal> = emptyMap()) {
    fun api(pair: RatePair): BigDecimal? = cross(pair, toIdr)

    /** Manual override wins; empty override falls back to the API rate. */
    fun effective(pair: RatePair): BigDecimal? = manual[pair.key] ?: api(pair)

    fun isManual(pair: RatePair) = manual.containsKey(pair.key)

    private fun cross(pair: RatePair, table: Map<String, BigDecimal>): BigDecimal? {
        val base = if (pair.base == "IDR") BigDecimal.ONE else table[pair.base] ?: return null
        val quote = if (pair.quote == "IDR") BigDecimal.ONE else table[pair.quote] ?: return null
        return base.divide(quote, MathContext(10, RoundingMode.HALF_UP)).stripTrailingZeros()
    }

    /** Converts [amountMinor] using the effective rate for the pair between the two currencies. */
    fun convert(amountMinor: Long, from: String, to: String): Long? {
        if (from == to) return amountMinor
        val pair = pairFor(from, to)
        val rate = effective(pair) ?: return null
        val major = toMajor(amountMinor, from)
        val converted = if (pair.base == from) major.multiply(rate) else major.divide(rate, 10, RoundingMode.HALF_UP)
        return toMinor(converted, to)
    }
}

/** Parses `16.450`, `16450`, `16.450,5`. Returns null for anything that is not a positive number. */
fun parseRateInput(raw: String): BigDecimal? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    val normalized = if (text.contains(',')) text.replace(".", "").replace(',', '.') else
        if (Regex("""^\d{1,3}(\.\d{3})+$""").matches(text)) text.replace(".", "") else text
    return normalized.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
}

/** Display like the design: `16.450`. Keeps up to 4 decimals for small rates. */
fun formatRate(rate: BigDecimal): String {
    val scaled = if (rate >= BigDecimal(100)) rate.setScale(0, RoundingMode.HALF_UP) else rate.setScale(4, RoundingMode.HALF_UP).stripTrailingZeros()
    val plain = scaled.toPlainString()
    val parts = plain.split('.')
    val whole = parts[0].reversed().chunked(3).joinToString(".").reversed()
    return if (parts.size > 1) "$whole,${parts[1]}" else whole
}
