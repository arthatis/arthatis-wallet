package com.arthatis.wallet.core

import java.math.BigDecimal
import java.math.RoundingMode

/** [group] separates thousands, [decimal] the fraction. Symbols are always a prefix. */
data class CurrencyInfo(
    val code: String, val scale: Int, val symbol: String, val group: Char = ',', val decimal: Char = '.',
    /** Trailing fraction zeros are dropped down to this many digits (`Rp14.125.000` with 0, `$5.00` with 2). */
    val minFraction: Int = 2,
)

/**
 * Every currency keeps 4 decimals (crypto/forex-style amounts), so 1 minor unit = 1/10.000 of the major unit.
 * IDR hides an all-zero fraction; the others always show at least 2 digits. IDR and EUR group with `.` and use `,`
 * for decimals; the rest the other way round.
 */
const val MONEY_SCALE = 4
val supportedCurrencies = listOf(
    CurrencyInfo("IDR", MONEY_SCALE, "Rp", '.', ',', minFraction = 0),
    CurrencyInfo("USD", MONEY_SCALE, "$"),
    CurrencyInfo("SGD", MONEY_SCALE, "S$"),
    CurrencyInfo("MYR", MONEY_SCALE, "RM"),
    CurrencyInfo("EUR", MONEY_SCALE, "\u20ac", '.', ','),
)

fun currencyInfo(code: String): CurrencyInfo =
    supportedCurrencies.firstOrNull { it.code == code } ?: CurrencyInfo(code, MONEY_SCALE, code)

enum class Sign { None, Plus, Minus }

private fun group(digits: String, separator: Char): String =
    digits.reversed().chunked(3).joinToString(separator.toString()).reversed()

/** Per-currency format: `Rp14.125.000`, `$1,060.79`, `\u20ac1.060,79`. Pass [sign] for `+Rp8.500.000` / `−Rp25.000`. */
fun formatMinor(amountMinor: Long, currency: String, sign: Sign = Sign.None): String {
    val info = currencyInfo(currency)
    val negative = amountMinor < 0
    val abs = BigDecimal.valueOf(kotlin.math.abs(amountMinor), info.scale)
    val plain = abs.setScale(info.scale, RoundingMode.HALF_UP).toPlainString()
    val parts = plain.split('.')
    val whole = group(parts[0], info.group)
    val fraction = if (info.scale == 0) "" else parts[1].trimEnd('0').padEnd(info.minFraction, '0')
    val text = if (fraction.isEmpty()) whole else "$whole${info.decimal}$fraction"
    val prefix = when {
        sign == Sign.Plus -> "+"
        sign == Sign.Minus -> "−"
        negative -> "−"
        else -> ""
    }
    return "$prefix${info.symbol}$text"
}

/**
 * Short form for amounts that do not fit: `Rp4,1k`, `$6.7m`, `Rp7,2b`, `$1.5t` (thousand, million, billion, trillion).
 * Below 1.000 the full amount is returned. The decimal mark follows the currency.
 */
fun formatCompact(amountMinor: Long, currency: String, sign: Sign = Sign.None): String {
    val info = currencyInfo(currency)
    val major = BigDecimal.valueOf(kotlin.math.abs(amountMinor), info.scale)
    val units = listOf(BigDecimal("1000000000000") to "t", BigDecimal("1000000000") to "b", BigDecimal("1000000") to "m", BigDecimal("1000") to "k")
    val (div, suffix) = units.firstOrNull { major >= it.first } ?: return formatMinor(amountMinor, currency, sign)
    var short = major.divide(div, 1, RoundingMode.DOWN).stripTrailingZeros()
    if (short.scale() < 0) short = short.setScale(0)
    val text = short.toPlainString().replace('.', info.decimal) + suffix
    val prefix = when {
        sign == Sign.Plus -> "+"
        sign == Sign.Minus -> "−"
        amountMinor < 0 -> "−"
        else -> ""
    }
    return "$prefix${info.symbol}$text"
}

/**
 * Compact form of a string made by [formatMinor] (`Rp14.125.000` → `Rp14,1m`), so a view can fall back to it when the
 * full amount does not fit. Null when [formatted] is not a money string.
 */
fun compactOf(formatted: String): String? {
    val sign = when (formatted.firstOrNull()) { '+' -> Sign.Plus; '−', '-' -> Sign.Minus; else -> Sign.None }
    val body = if (sign == Sign.None) formatted else formatted.drop(1)
    val info = supportedCurrencies.sortedByDescending { it.symbol.length }.firstOrNull { body.startsWith(it.symbol) } ?: return null
    val number = body.removePrefix(info.symbol).replace(info.group.toString(), "").replace(info.decimal, '.')
    val value = number.toBigDecimalOrNull() ?: return null
    val minor = runCatching { toMinor(value, info.code) }.getOrNull() ?: return null
    return formatCompact(if (sign == Sign.Minus) -minor else minor, info.code, if (sign == Sign.Plus) Sign.Plus else Sign.None)
}

fun toMinor(amount: BigDecimal, currency: String): Long {
    val scale = currencyInfo(currency).scale
    return amount.movePointRight(scale).setScale(0, RoundingMode.HALF_UP).longValueExact()
}

fun toMajor(amountMinor: Long, currency: String): BigDecimal =
    BigDecimal.valueOf(amountMinor, currencyInfo(currency).scale)

/** Keypad expression. `k` and `rb` multiply the preceding number by 1_000. */
fun evaluateExpression(raw: String): BigDecimal? {
    val input = raw.trim().lowercase().replace("rb", "k").replace(',', '.')
    if (input.isEmpty()) return null
    return try {
        Parser(input).parse()
    } catch (_: ArithmeticException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}

private class Parser(private val input: String) {
    private var index = 0

    fun parse(): BigDecimal {
        val value = expression()
        if (index != input.length) throw IllegalArgumentException("trailing")
        return value
    }

    private fun expression(): BigDecimal {
        var value = term()
        while (true) {
            when (peek()) {
                '+' -> { index++; value = value.add(term()) }
                '-' -> { index++; value = value.subtract(term()) }
                else -> return value
            }
        }
    }

    private fun term(): BigDecimal {
        var value = factor()
        while (true) {
            when (peek()) {
                '*' -> { index++; value = value.multiply(factor()) }
                '/' -> {
                    index++
                    val divisor = factor()
                    if (divisor.compareTo(BigDecimal.ZERO) == 0) throw ArithmeticException("zero")
                    value = value.divide(divisor, 8, RoundingMode.HALF_UP).stripTrailingZeros()
                }
                else -> return value
            }
        }
    }

    private fun factor(): BigDecimal {
        if (peek() == '-') {
            index++
            return factor().negate()
        }
        val start = index
        while (index < input.length && (input[index].isDigit() || input[index] == '.')) index++
        if (start == index) throw IllegalArgumentException("number")
        var value = BigDecimal(input.substring(start, index))
        if (peek() == 'k') {
            index++
            value = value.multiply(BigDecimal("1000"))
        }
        return value
    }

    private fun peek(): Char? = if (index < input.length) input[index] else null
}

/** Text shown in an amount field: [text] plus where each raw offset lands, so the cursor survives the added separators. */
class AmountDisplay(val text: String, private val toDisplay: IntArray) {
    fun originalToDisplay(offset: Int): Int = toDisplay[offset.coerceIn(0, toDisplay.size - 1)]
    fun displayToOriginal(offset: Int): Int = toDisplay.indices.firstOrNull { toDisplay[it] >= offset } ?: (toDisplay.size - 1)
}

/**
 * [raw] is what is stored: digits, `.` as the decimal point, and operators. This only decorates it for the eye
 * with the currency's thousands separator and decimal mark; parsing the stored text never needs the separators.
 */
fun displayAmount(raw: String, currency: String): AmountDisplay {
    val info = currencyInfo(currency)
    val out = StringBuilder()
    val map = IntArray(raw.length + 1)
    var i = 0
    while (i < raw.length) {
        if (raw[i].isDigit() && !isFraction(raw, i)) {
            var j = i
            while (j < raw.length && raw[j].isDigit()) j++
            for (k in 0 until j - i) {
                map[i + k] = out.length
                out.append(raw[i + k])
                val left = j - i - k - 1
                if (left > 0 && left % 3 == 0) out.append(info.group)
            }
            i = j
        } else {
            map[i] = out.length
            out.append(if (raw[i] == '.') info.decimal else raw[i])
            i++
        }
    }
    map[raw.length] = out.length
    return AmountDisplay(out.toString(), map)
}

private fun isFraction(raw: String, index: Int): Boolean {
    var k = index - 1
    while (k >= 0 && raw[k].isDigit()) k--
    return k >= 0 && raw[k] == '.'
}

/** Cleans what the user typed into the stored form: digits, either decimal key becomes `.`, and no decimals for whole-unit currencies. */
fun sanitizeAmountInput(typed: String, currency: String): String {
    val scale = currencyInfo(currency).scale
    val whole = StringBuilder()
    val fraction = StringBuilder()
    var point = false
    for (c in typed) {
        when {
            c.isDigit() -> if (!point) whole.append(c) else if (fraction.length < scale) fraction.append(c)
            (c == '.' || c == ',') && scale > 0 && !point -> point = true
        }
    }
    val digits = whole.toString().trimStart('0').ifEmpty { if (whole.isNotEmpty() || point) "0" else "" }
    return if (point) "$digits.$fraction" else digits
}
