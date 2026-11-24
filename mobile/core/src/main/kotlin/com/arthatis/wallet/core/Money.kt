package com.arthatis.wallet.core

import java.math.BigDecimal
import java.math.RoundingMode

data class CurrencyInfo(val code: String, val scale: Int, val symbol: String)

/** Currencies from the design: IDR, USD, SGD. One wallet holds exactly one. */
val supportedCurrencies = listOf(
    CurrencyInfo("IDR", 0, "Rp"),
    CurrencyInfo("USD", 2, "$"),
    CurrencyInfo("SGD", 2, "S$"),
)

fun currencyInfo(code: String): CurrencyInfo =
    supportedCurrencies.firstOrNull { it.code == code } ?: CurrencyInfo(code, 2, code)

enum class Sign { None, Plus, Minus }

private fun group(digits: String): String =
    digits.reversed().chunked(3).joinToString(".").reversed()

/** Indonesian format: `Rp14.125.000`, `$60,79`. Pass [sign] for `+Rp8.500.000` / `−Rp25.000`. */
fun formatMinor(amountMinor: Long, currency: String, sign: Sign = Sign.None): String {
    val info = currencyInfo(currency)
    val negative = amountMinor < 0
    val abs = BigDecimal.valueOf(kotlin.math.abs(amountMinor), info.scale)
    val plain = abs.setScale(info.scale, RoundingMode.HALF_UP).toPlainString()
    val parts = plain.split('.')
    val whole = group(parts[0])
    val text = if (info.scale == 0) whole else "$whole,${parts[1]}"
    val prefix = when {
        sign == Sign.Plus -> "+"
        sign == Sign.Minus -> "−"
        negative -> "−"
        else -> ""
    }
    return "$prefix${info.symbol}$text"
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
