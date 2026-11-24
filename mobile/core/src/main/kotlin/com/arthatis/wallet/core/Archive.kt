package com.arthatis.wallet.core

import com.arthatis.wallet.core.crypto.Crypto
import kotlinx.serialization.Serializable

/** Contents of an `.arthatis` backup. Shared (Bareng) wallets are listed for reference only; they live on the server. */
@Serializable
data class ArchiveData(
    val version: Int = 1,
    val createdAt: Long,
    val device: String = "",
    val wallets: List<Wallet> = emptyList(),
    val categories: List<Category> = emptyList(),
    val transactions: List<Tx> = emptyList(),
    val recurring: List<Recurring> = emptyList(),
    val rates: List<RateOverride> = emptyList(),
    val profile: Profile? = null,
    val sharedWallets: List<String> = emptyList(),
)

private val MAGIC = "ARTHATIS1\n".toByteArray()

/** `.arthatis` = magic + the JSON wrapped with the recovery key (Argon2id → XChaCha20-Poly1305). */
fun encodeArchive(data: ArchiveData, recoveryKey: ByteArray, memoryKiB: Int = 65536): ByteArray =
    MAGIC + Crypto.wrapWithRecoveryKey(recoveryKey, ledgerJson.encodeToString(ArchiveData.serializer(), data).toByteArray(), memoryKiB)

sealed interface ArchiveResult {
    data class Ok(val data: ArchiveData) : ArchiveResult
    data object NotArchive : ArchiveResult
    data object WrongKey : ArchiveResult
}

fun decodeArchive(bytes: ByteArray, recoveryKey: ByteArray, memoryKiB: Int = 65536): ArchiveResult {
    if (bytes.size <= MAGIC.size || !bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) return ArchiveResult.NotArchive
    val plain = runCatching { Crypto.unwrapWithRecoveryKey(recoveryKey, bytes.copyOfRange(MAGIC.size, bytes.size), memoryKiB) }
        .getOrNull() ?: return ArchiveResult.WrongKey
    return runCatching { ArchiveResult.Ok(ledgerJson.decodeFromString(ArchiveData.serializer(), String(plain))) }
        .getOrDefault(ArchiveResult.NotArchive)
}

/** What "Gabungkan" would change: counts of records that are new or newer than what the phone has. */
data class MergePlan(
    val wallets: List<Wallet>, val categories: List<Category>, val transactions: List<Tx>,
    val recurring: List<Recurring>, val rates: List<RateOverride>, val profile: Profile?,
    val skipped: Int,
)

private fun <T : Syncable> pick(incoming: List<T>, existing: List<T>): Pair<List<T>, Int> {
    val byId = existing.associateBy { it.id }
    val take = incoming.filter { val local = byId[it.id]; local == null || it.updatedAt > local.updatedAt }
    return take to (incoming.size - take.size)
}

/** Same id and not newer = skipped, so importing the same file twice changes nothing. */
fun planMerge(incoming: ArchiveData, local: ArchiveData): MergePlan {
    val (w, sw) = pick(incoming.wallets, local.wallets)
    val (c, sc) = pick(incoming.categories, local.categories)
    val (t, st) = pick(incoming.transactions, local.transactions)
    val (r, sr) = pick(incoming.recurring, local.recurring)
    val (o, so) = pick(incoming.rates, local.rates)
    val profile = incoming.profile?.takeIf { local.profile == null || it.updatedAt > local.profile.updatedAt }
    return MergePlan(w, c, t, r, o, profile, sw + sc + st + sr + so)
}

/** One line per live transaction, for spreadsheets. */
fun toCsv(data: ArchiveData): String {
    val wallets = data.wallets.associateBy { it.id }
    val cats = data.categories.associateBy { it.id }
    fun q(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
    val sb = StringBuilder("date,wallet,kind,category,amount,currency,to_wallet,to_amount,note\n")
    data.transactions.filter { it.deletedAt == null }.sortedBy { it.occurredAt }.forEach { t ->
        val w = wallets[t.walletId]
        val cur = w?.currency ?: ""
        sb.append(dayOf(t.occurredAt)).append(',')
            .append(q(w?.name ?: "")).append(',').append(t.kind.name).append(',')
            .append(q(cats[t.categoryId]?.name ?: "")).append(',')
            .append(toMajor(t.amountMinor, cur.ifBlank { "IDR" }).toPlainString()).append(',').append(cur).append(',')
            .append(q(t.toWalletId?.let { wallets[it]?.name } ?: "")).append(',')
            .append(t.toAmountMinor?.let { toMajor(it, wallets[t.toWalletId]?.currency ?: "IDR").toPlainString() } ?: "").append(',')
            .append(q(t.note)).append('\n')
    }
    return sb.toString()
}

private fun splitCsv(line: String): List<String> {
    val out = mutableListOf<String>(); val cur = StringBuilder(); var quoted = false; var i = 0
    while (i < line.length) {
        val c = line[i]
        when {
            quoted && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { cur.append('"'); i++ }
            c == '"' -> quoted = !quoted
            c == ',' && !quoted -> { out += cur.toString(); cur.clear() }
            else -> cur.append(c)
        }
        i++
    }
    out += cur.toString()
    return out
}

private fun stableId(prefix: String, vararg parts: String): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(parts.joinToString("\u0000").toByteArray())
    return prefix + digest.take(10).joinToString("") { "%02x".format(it) }
}

/**
 * Reads the CSV written by [toCsv]. Wallets and categories are matched by name and created when missing.
 * Ids are derived from the row, so importing the same file twice adds nothing.
 */
fun csvToArchive(csv: String, now: Long): ArchiveData? {
    val rows = csv.lines().filter { it.isNotBlank() }
    if (rows.isEmpty() || rows[0].trim() != "date,wallet,kind,category,amount,currency,to_wallet,to_amount,note") return null
    val wallets = linkedMapOf<String, Wallet>(); val cats = linkedMapOf<String, Category>(); val txs = mutableListOf<Tx>()
    fun wallet(name: String, currency: String) = wallets.getOrPut(name) { Wallet(stableId("w", name, currency), name, WalletKind.biasa, currency, 0, wallets.size, now) }
    fun category(name: String, kind: EntryKind) = cats.getOrPut(name) {
        val d = defaultCategories.firstOrNull { it.name.equals(name, true) }
        if (d != null) Category("cat_${d.key}", d.key, d.name, d.kind, d.color, false, 0, 0) else Category(stableId("c", name), null, name, kind, "#717970", false, 100 + cats.size, now)
    }
    // A transfer's destination wallet has no currency column: take it from rows where that wallet is the source.
    val sourceCurrency = rows.drop(1).mapNotNull { l -> splitCsv(l).takeIf { it.size >= 9 && it[1].isNotBlank() }?.let { it[1] to it[5].ifBlank { "IDR" } } }.toMap()
    rows.drop(1).forEachIndexed { index, line ->
        val f = splitCsv(line); if (f.size < 9) return@forEachIndexed
        val kind = runCatching { EntryKind.valueOf(f[2]) }.getOrNull() ?: return@forEachIndexed
        val currency = f[5].ifBlank { "IDR" }
        val day = runCatching { java.time.LocalDate.parse(f[0]) }.getOrNull() ?: return@forEachIndexed
        val amount = f[4].toBigDecimalOrNull()?.let { runCatching { toMinor(it, currency) }.getOrNull() } ?: return@forEachIndexed
        val from = wallet(f[1], currency)
        val to = f[6].takeIf { it.isNotBlank() }?.let { wallet(it, sourceCurrency[it] ?: "IDR") }
        val toAmount = f[7].toBigDecimalOrNull()?.let { runCatching { toMinor(it, to?.currency ?: currency) }.getOrNull() }
        val cat = f[3].takeIf { it.isNotBlank() }?.let { category(it, kind) }
        txs += Tx(stableId("t", line, index.toString()), from.id, to?.id, cat?.id, kind, amount, toAmount, null, f[8], epochMillis(day), null, now)
    }
    return ArchiveData(createdAt = now, device = "CSV", wallets = wallets.values.toList(), categories = cats.values.toList(), transactions = txs)
}

/**
 * CSV rows carry names, not ids. Before merging, point them at the wallets and categories the phone already has
 * (matched by name) and drop rows identical to a live local transaction, so importing your own export changes nothing.
 */
fun reconcileCsv(incoming: ArchiveData, local: ArchiveData): ArchiveData {
    val walletMap = incoming.wallets.associate { w -> w.id to (local.wallets.firstOrNull { it.name.equals(w.name, true) && it.deletedAt == null } ?: w) }
    val catMap = incoming.categories.associate { c ->
        c.id to (local.categories.firstOrNull { it.deletedAt == null && (it.name.equals(c.name, true) || (c.key != null && it.key == c.key)) } ?: c)
    }
    val seen = local.transactions.filter { it.deletedAt == null }.map { sig(it) }.toMutableList()
    val txs = incoming.transactions.mapNotNull { t ->
        val m = t.copy(walletId = walletMap[t.walletId]?.id ?: t.walletId, toWalletId = t.toWalletId?.let { walletMap[it]?.id ?: it }, categoryId = t.categoryId?.let { catMap[it]?.id ?: it })
        val key = sig(m)
        if (seen.remove(key)) null else m
    }
    return incoming.copy(
        wallets = incoming.wallets.filter { walletMap[it.id] === it },
        categories = incoming.categories.filter { catMap[it.id] === it },
        transactions = txs,
    )
}

private fun sig(t: Tx) = listOf(t.walletId, t.toWalletId, t.categoryId, t.kind, t.amountMinor, t.toAmountMinor, t.note, dayOf(t.occurredAt))
