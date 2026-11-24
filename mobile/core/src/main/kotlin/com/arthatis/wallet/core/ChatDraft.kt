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
    val mentioned = wallets.filter { lower.contains(it.name.lowercase()) }
    val transfer = lower.contains("transfer") || lower.contains("pindah")
    if (transfer) {
        val directed = directedWallets(lower, wallets)
        val from = directed?.first ?: mentioned.getOrNull(0)
        val to = directed?.second ?: mentioned.getOrNull(1)
        if (from == null || to == null || from.id == to.id) return null
        return ChatDraft(EntryKind.transfer, toMinor(amount, currency), currency, text, null, from.id, to.id)
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

private fun directedWallets(lower: String, wallets: List<NamedRef>): Pair<NamedRef, NamedRef>? {
    val match = Regex("""(?:from|dari)\s+(.+?)\s+(?:to|ke)\s+(.+)$""").find(lower) ?: return null
    val from = wallets.firstOrNull { match.groupValues[1].contains(it.name.lowercase()) } ?: return null
    val to = wallets.firstOrNull { match.groupValues[2].contains(it.name.lowercase()) } ?: return null
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
