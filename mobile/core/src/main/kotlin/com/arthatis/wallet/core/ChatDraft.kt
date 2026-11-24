package com.arthatis.wallet.core

import java.math.BigDecimal

data class NamedRef(val id: String, val name: String)

data class CategoryRef(val id: String, val key: String?, val name: String, val kind: EntryKind)

data class ChatDraft(
    val kind: EntryKind,
    val amountMinor: Long,
    val currency: String,
    val note: String,
    val categoryId: String?,
    val walletId: String?,
    val toWalletId: String?,
)

private val amountPattern = Regex("""(\d+(?:[.,]\d+)?)(?:\s*)(jt|juta|rb|ribu|k|m)?""", RegexOption.IGNORE_CASE)

/** Keyword → default category key. */
private val keywords = mapOf(
    "lunch" to "food", "makan" to "food", "food" to "food", "kopi" to "food", "bakso" to "food",
    "grab" to "transport", "gojek" to "transport", "transport" to "transport", "ojek" to "transport",
    "bensin" to "fuel", "bbm" to "fuel", "shell" to "fuel", "fuel" to "fuel",
    "tokopedia" to "shopping", "shopping" to "shopping", "belanja" to "shopping",
    "listrik" to "bills", "pln" to "bills", "bill" to "bills", "tagihan" to "bills",
    "sewa" to "home", "rent" to "home",
    "netflix" to "fun", "movie" to "fun", "bioskop" to "fun",
    "obat" to "health", "dokter" to "health", "apotek" to "health", "kesehatan" to "health",
    "gaji" to "salary", "salary" to "salary",
)

/**
 * Turns a sentence into a draft only when the amount and a category (or transfer wallets) are present.
 * Anything else is rejected. This is the last-resort parser used when no AI engine answers.
 */
fun parseChat(
    sentence: String,
    currency: String,
    wallets: List<NamedRef>,
    categories: List<CategoryRef>,
): ChatDraft? {
    val text = sentence.trim()
    if (text.isEmpty()) return null
    val amount = readAmount(text) ?: return null
    val lower = text.lowercase()
    val mentioned = mentionedWallets(lower, wallets)
    val strong = strongTransfer.containsMatchIn(lower)
    if (strong || (weakTransfer.containsMatchIn(lower) && mentioned.isNotEmpty())) {
        val (from, to) = transferEnds(lower, wallets, mentioned)
        // A missing side is chosen on the draft card; both sides the same is not a transfer.
        if (from != null && from.id == to?.id) return null
        return ChatDraft(EntryKind.transfer, toMinor(amount, currency), currency, text, null, from?.id, to?.id)
    }
    val key = keywords.entries.firstOrNull { lower.contains(it.key) }?.value
    val category = (key?.let { k -> categories.firstOrNull { it.key == k } }
        ?: categories.firstOrNull { lower.contains(it.name.lowercase()) }) ?: return null
    return ChatDraft(
        kind = category.kind,
        amountMinor = toMinor(amount, currency),
        currency = currency,
        note = text,
        categoryId = category.id,
        walletId = mentioned.firstOrNull()?.id,
        toWalletId = null,
    )
}

/** Words that always mean a move between own wallets. */
private val strongTransfer = Regex("""\b(transfer|trf|tf|pindah\w*|top\s?up|isi\s+saldo|tarik\s+tunai|setor\s+tunai)\b""")
/** Words that mean a transfer only when a wallet is named ("kirim paket 20k" is an expense). */
private val weakTransfer = Regex("""\b(kirim|send|move)\b""")

private fun squash(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

/** Finds a wallet named in [text]: case, spaces and punctuation are ignored; the longest name wins ("BCA Syariah" over "BCA"). */
fun matchWallet(text: String, wallets: List<NamedRef>): NamedRef? {
    val t = squash(text)
    if (t.isEmpty()) return null
    return wallets.filter { squash(it.name).isNotEmpty() && t.contains(squash(it.name)) }.maxByOrNull { squash(it.name).length }
        ?: wallets.firstOrNull { squash(it.name).contains(t) && t.length >= 3 }
}

private fun mentionedWallets(lower: String, wallets: List<NamedRef>): List<NamedRef> {
    val text = squash(lower)
    // Ordered by first appearance, so "BCA … OVO" reads left to right; longer names first so "BCA Syariah" beats "BCA".
    return wallets.filter { squash(it.name).isNotEmpty() && text.contains(squash(it.name)) }
        .sortedByDescending { squash(it.name).length }
        .fold(mutableListOf<Pair<Int, NamedRef>>()) { acc, w ->
            val at = text.indexOf(squash(w.name))
            if (acc.none { (pos, other) -> at >= pos && at < pos + squash(other.name).length }) acc += at to w
            acc
        }.sortedBy { it.first }.map { it.second }
}

/** Source and destination: "dari X ke Y", "ke Y" only, "dari X" only, or the order the wallets were named in. */
private fun transferEnds(lower: String, wallets: List<NamedRef>, mentioned: List<NamedRef>): Pair<NamedRef?, NamedRef?> {
    val fromText = Regex("""\b(?:dari|from)\s+(.+?)(?=\s+(?:ke|to|kepada)\s|$)""").find(lower)?.groupValues?.get(1)
    val toText = Regex("""\b(?:ke|to|kepada)\s+(.+?)(?=\s+(?:dari|from)\s|$)""").find(lower)?.groupValues?.get(1)
    var from = fromText?.let { matchWallet(it, wallets) }
    var to = toText?.let { matchWallet(it, wallets) }
    if (from == null && to == null) {
        // "top up gopay 100k": the one wallet named is where the money goes.
        if (mentioned.size == 1 && Regex("""\b(top\s?up|isi\s+saldo|setor)\b""").containsMatchIn(lower)) return null to mentioned[0]
        return mentioned.getOrNull(0) to mentioned.getOrNull(1)
    }
    val rest = mentioned.filter { it.id != from?.id && it.id != to?.id }
    if (from == null && fromText == null) from = rest.firstOrNull()
    if (to == null && toText == null) to = rest.firstOrNull { it.id != from?.id }
    return from to to
}

private fun readAmount(text: String): BigDecimal? {
    val match = amountPattern.find(text) ?: return null
    val number = match.groupValues[1].replace(',', '.').toBigDecimalOrNull() ?: return null
    val multiplier = when (match.groupValues[2].lowercase()) {
        "k", "rb", "ribu" -> BigDecimal("1000")
        "jt", "juta", "m" -> BigDecimal("1000000")
        else -> BigDecimal.ONE
    }
    return number.multiply(multiplier)
}
