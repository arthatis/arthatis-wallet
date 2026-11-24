package com.arthatis.wallet.core

import java.math.BigDecimal

/** What a bank / e-wallet notification email says, before the user confirms it. Amount in the currency's minor units. */
data class EmailGuess(val kind: EntryKind, val amountMinor: Long, val currency: String, val counterpart: String?)

/** Senders watched by default: Indonesian banks and e-wallets (matched against the From address, case-insensitive). */
val defaultEmailSenders = listOf(
    "bca.co.id", "klikbca.com", "bankmandiri.co.id", "livin", "bni.co.id", "bri.co.id", "jago.com", "btpn.com", "jenius",
    "cimbniaga.co.id", "ocbc.id", "permatabank", "danamon", "seabank", "blu", "gojek.com", "gopay", "ovo.id", "dana.id",
    "shopeepay", "shopee.co.id", "linkaja", "flip.id",
)

/** True when [from] (an email address or "Name <address>") belongs to one of [senders]. */
fun isWatchedSender(from: String, senders: List<String>): Boolean {
    val f = from.lowercase()
    return senders.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.any { f.contains(it) }
}

/** Minimal HTML → text for notification emails: drops style/script, turns block tags into new lines, decodes common entities. */
fun htmlToText(html: String): String = html
    .replace(Regex("""(?is)<(style|script|head)[^>]*>.*?</\1>"""), " ")
    .replace(Regex("""(?i)<br\s*/?>|</(p|div|tr|li|h\d|table)>"""), "\n")
    .replace(Regex("""(?i)</t[dh]>"""), " ")
    .replace(Regex("""<[^>]+>"""), " ")
    .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
    .lines().map { it.replace(Regex("""[ \t ]+"""), " ").trim() }.filter { it.isNotEmpty() }.joinToString("\n")

private val amountRx = Regex("""(?i)\b(rp\.?|idr|usd|us\$|sgd|s\$|myr|rm|eur|€|\$)\s?([0-9][0-9.,]*)""")
private val outWords = listOf("debit", "pembayaran", "payment", "purchase", "pembelian", "transfer keluar", "tarik", "withdraw", "dibayar", "paid", "berhasil membayar", "pengeluaran", "spent", "top up", "topup")
private val inWords = listOf("kredit", "credit", "masuk", "diterima", "received", "incoming", "dana masuk", "uang masuk", "refund", "cashback", "gaji", "salary")

private fun currencyOf(symbol: String): String = when (symbol.lowercase().trimEnd('.')) {
    "rp", "idr" -> "IDR"; "usd", "us$", "$" -> "USD"; "sgd", "s$" -> "SGD"; "myr", "rm" -> "MYR"; "eur", "€" -> "EUR"; else -> "IDR"
}

/** Reads `150.000`, `150.000,00`, `150,000.00`, `1,500.5` as written in the email. */
private fun readNumber(raw: String, currency: String): BigDecimal? {
    val t = raw.trimEnd('.', ',')
    val lastDot = t.lastIndexOf('.'); val lastComma = t.lastIndexOf(',')
    val last = maxOf(lastDot, lastComma)
    val decimals = if (last >= 0) t.length - last - 1 else 0
    val plain = when {
        last < 0 -> t
        // One kind of separator followed by exactly 3 digits is a thousands group (Rp150.000 / $1,500).
        decimals == 3 && (lastDot < 0 || lastComma < 0) -> t.replace(".", "").replace(",", "")
        else -> t.substring(0, last).replace(".", "").replace(",", "") + "." + t.substring(last + 1)
    }
    return plain.toBigDecimalOrNull()?.takeIf { it.signum() > 0 && currencyInfo(currency).code.isNotEmpty() }
}

/**
 * Last-resort reader used when no AI engine answers: the first money amount in the email and whether it is money
 * going out or coming in. Null when there is no amount or the direction is unclear.
 */
fun parseBankEmail(subject: String, body: String, fallbackCurrency: String): EmailGuess? {
    val text = "$subject\n$body"
    val lower = text.lowercase()
    val m = amountRx.find(text) ?: return null
    val currency = currencyOf(m.groupValues[1]).takeIf { supportedCurrencies.any { c -> c.code == it } } ?: fallbackCurrency
    val value = readNumber(m.groupValues[2], currency) ?: return null
    val out = outWords.indexOfFirstIn(lower); val inn = inWords.indexOfFirstIn(lower)
    val kind = when {
        out < 0 && inn < 0 -> return null
        inn < 0 -> EntryKind.expense
        out < 0 -> EntryKind.income
        else -> if (out <= inn) EntryKind.expense else EntryKind.income
    }
    val counterpart = Regex("""(?i)\b(?:ke|kepada|to|merchant|di|at)\s*:?\s+([A-Z0-9][\w .&'-]{2,40})""").find(text)?.groupValues?.get(1)?.trim()
    return EmailGuess(kind, toMinor(value, currency), currency, counterpart)
}

private fun List<String>.indexOfFirstIn(text: String): Int = mapNotNull { w -> text.indexOf(w).takeIf { it >= 0 } }.minOrNull() ?: -1
