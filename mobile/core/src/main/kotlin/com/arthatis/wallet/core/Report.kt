package com.arthatis.wallet.core

/** One wallet in a report, in the wallet's own currency. [closingMinor] is the balance at the end of the range. */
data class ReportWallet(val wallet: Wallet, val inMinor: Long, val outMinor: Long, val closingMinor: Long)

/** Amount per category, in the report currency. [categoryId] null = uncategorised. */
data class ReportCategory(val categoryId: String?, val amountMinor: Long, val count: Int)

/**
 * Financial report for transactions with `from <= occurredAt < until` (millis). Totals are in [currency];
 * [netWorthMinor] is the net worth at the end of the range (cash + investments − card debt).
 */
data class Report(
    val from: Long,
    val until: Long,
    val currency: String,
    val netWorthMinor: Long,
    val incomeMinor: Long,
    val expenseMinor: Long,
    val expenses: List<ReportCategory>,
    val incomes: List<ReportCategory>,
    val wallets: List<ReportWallet>,
    /** Newest first. */
    val transactions: List<Tx>,
) {
    val netMinor get() = incomeMinor - expenseMinor
}

/** Builds a [Report]. Null when a rate needed to convert into [currency] is missing. */
fun buildReport(wallets: List<Wallet>, txs: List<Tx>, currency: String, rates: Rates, from: Long, until: Long): Report? {
    val live = wallets.filter { it.deletedAt == null }
    val byId = live.associateBy { it.id }
    val inRange = txs.filter { it.deletedAt == null && it.occurredAt >= from && it.occurredAt < until && byId.containsKey(it.walletId) }
    val upToEnd = txs.filter { it.occurredAt < until }
    val flow = periodFlow(inRange, byId, currency, rates, from, until) ?: return null
    fun group(kind: EntryKind): List<ReportCategory>? {
        val sums = LinkedHashMap<String?, Pair<Long, Int>>()
        for (tx in inRange) {
            if (tx.kind != kind) continue
            val value = rates.convert(tx.amountMinor, byId.getValue(tx.walletId).currency, currency) ?: return null
            val (sum, n) = sums[tx.categoryId] ?: (0L to 0)
            sums[tx.categoryId] = (sum + value) to (n + 1)
        }
        return sums.map { (id, v) -> ReportCategory(id, v.first, v.second) }.sortedByDescending { it.amountMinor }
    }
    val expenses = group(EntryKind.expense) ?: return null
    val incomes = group(EntryKind.income) ?: return null
    val walletLines = live.map { w ->
        var into = 0L; var out = 0L
        for (tx in inRange) {
            when (tx.kind) {
                EntryKind.income -> if (tx.walletId == w.id) into += tx.amountMinor
                EntryKind.expense -> if (tx.walletId == w.id) out += tx.amountMinor
                EntryKind.transfer -> {
                    if (tx.walletId == w.id) out += tx.amountMinor
                    if (tx.toWalletId == w.id) into += tx.toAmountMinor ?: tx.amountMinor
                }
            }
        }
        ReportWallet(w, into, out, walletBalance(w, upToEnd))
    }
    val netWorth = totalBalance(live, upToEnd, currency, rates) ?: return null
    return Report(from, until, currency, netWorth, flow.incomeMinor, flow.expenseMinor, expenses, incomes, walletLines,
        inRange.sortedByDescending { it.occurredAt })
}
