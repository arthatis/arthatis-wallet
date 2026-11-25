package com.arthatis.wallet.core

/**
 * One wallet in a report, in the wallet's own currency. [openingMinor] is the balance at the start of the range
 * (the wallet's opening balance plus everything before), [closingMinor] at the end. The opening balance is never income.
 */
data class ReportWallet(val wallet: Wallet, val openingMinor: Long, val inMinor: Long, val outMinor: Long, val closingMinor: Long)

/** Amount per category, in the report currency. [categoryId] null = uncategorised. */
data class ReportCategory(val categoryId: String?, val amountMinor: Long, val count: Int)

/** Transactions of one category (and kind), newest first, with their total in the report currency. */
data class ReportGroup(val kind: EntryKind, val categoryId: String?, val totalMinor: Long, val transactions: List<Tx>)

/**
 * Financial report for transactions with `from <= occurredAt < until` (millis). Totals are in [currency].
 * Balances are net worth (cash + investments − card debt): [openingMinor] at `from`, [netWorthMinor] at `until`.
 * Income and expenses come from transactions only; wallet opening balances are part of [openingMinor], never income.
 */
data class Report(
    val from: Long,
    val until: Long,
    val currency: String,
    val openingMinor: Long,
    val netWorthMinor: Long,
    val incomeMinor: Long,
    val expenseMinor: Long,
    val expenses: List<ReportCategory>,
    val incomes: List<ReportCategory>,
    val wallets: List<ReportWallet>,
    /** Newest first. */
    val transactions: List<Tx>,
    /** Expense groups, then income, then transfers; each by total, largest first. */
    val groups: List<ReportGroup>,
) {
    val netMinor get() = incomeMinor - expenseMinor
}

/** Builds a [Report]. Null when a rate needed to convert into [currency] is missing. */
fun buildReport(wallets: List<Wallet>, txs: List<Tx>, currency: String, rates: Rates, from: Long, until: Long): Report? {
    val live = wallets.filter { it.deletedAt == null }
    val byId = live.associateBy { it.id }
    val inRange = txs.filter { it.deletedAt == null && it.occurredAt >= from && it.occurredAt < until && byId.containsKey(it.walletId) }
    val beforeStart = txs.filter { it.occurredAt < from }
    val upToEnd = txs.filter { it.occurredAt < until }
    val flow = periodFlow(inRange, byId, currency, rates, from, until) ?: return null
    fun value(tx: Tx): Long? = rates.convert(tx.amountMinor, byId.getValue(tx.walletId).currency, currency)
    fun group(kind: EntryKind): List<ReportCategory>? {
        val sums = LinkedHashMap<String?, Pair<Long, Int>>()
        for (tx in inRange) {
            if (tx.kind != kind) continue
            val v = value(tx) ?: return null
            val (sum, n) = sums[tx.categoryId] ?: (0L to 0)
            sums[tx.categoryId] = (sum + v) to (n + 1)
        }
        return sums.map { (id, v) -> ReportCategory(id, v.first, v.second) }.sortedByDescending { it.amountMinor }
    }
    val expenses = group(EntryKind.expense) ?: return null
    val incomes = group(EntryKind.income) ?: return null
    val newestFirst = inRange.sortedByDescending { it.occurredAt }
    val groups = listOf(EntryKind.expense, EntryKind.income, EntryKind.transfer).flatMap { kind ->
        newestFirst.filter { it.kind == kind }.groupBy { it.categoryId }.map { (id, items) ->
            ReportGroup(kind, id, items.sumOf { value(it) ?: return null }, items)
        }.sortedByDescending { it.totalMinor }
    }
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
        ReportWallet(w, walletBalance(w, beforeStart), into, out, walletBalance(w, upToEnd))
    }
    val opening = totalBalance(live, beforeStart, currency, rates) ?: return null
    val netWorth = totalBalance(live, upToEnd, currency, rates) ?: return null
    return Report(from, until, currency, opening, netWorth, flow.incomeMinor, flow.expenseMinor, expenses, incomes, walletLines, newestFirst, groups)
}
