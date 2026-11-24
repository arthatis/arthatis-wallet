package com.arthatis.wallet.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

fun List<Tx>.live() = filter { it.deletedAt == null }

/** Opening balance plus every live transaction touching the wallet. */
fun walletBalance(wallet: Wallet, txs: List<Tx>): Long {
    var total = wallet.openingMinor
    for (tx in txs) {
        if (tx.deletedAt != null) continue
        when (tx.kind) {
            EntryKind.income -> if (tx.walletId == wallet.id) total += tx.amountMinor
            EntryKind.expense -> if (tx.walletId == wallet.id) total -= tx.amountMinor
            EntryKind.transfer -> {
                if (tx.walletId == wallet.id) total -= tx.amountMinor
                if (tx.toWalletId == wallet.id) total += tx.toAmountMinor ?: tx.amountMinor
            }
        }
    }
    return total
}

/**
 * Net worth in [currency]: cash + investments + credit cards (a card's negative balance is debt and subtracts).
 * Null when a needed rate is unavailable.
 */
fun totalBalance(wallets: List<Wallet>, txs: List<Tx>, currency: String, rates: Rates): Long? =
    sumBalances(wallets, txs, currency, rates)

/** Cash wallets only (everything that is neither investment nor credit card). */
fun cashTotal(wallets: List<Wallet>, txs: List<Tx>, currency: String, rates: Rates): Long? =
    sumBalances(wallets.filter { !it.kind.isCredit && !it.kind.isInvestment }, txs, currency, rates)

/** Investment wallets only. */
fun investmentTotal(wallets: List<Wallet>, txs: List<Tx>, currency: String, rates: Rates): Long? =
    sumBalances(wallets.filter { it.kind.isInvestment }, txs, currency, rates)

/** Credit-card wallets only: negative = debt. */
fun creditTotal(wallets: List<Wallet>, txs: List<Tx>, currency: String, rates: Rates): Long? =
    sumBalances(wallets.filter { it.kind.isCredit }, txs, currency, rates)

private fun sumBalances(wallets: List<Wallet>, txs: List<Tx>, currency: String, rates: Rates): Long? {
    var total = 0L
    for (wallet in wallets) {
        if (wallet.deletedAt != null) continue
        total += rates.convert(walletBalance(wallet, txs), wallet.currency, currency) ?: return null
    }
    return total
}

data class Flow(val incomeMinor: Long, val expenseMinor: Long)

/** Income and expense in [currency] for transactions within [from] until [until] (millis). Transfers are neutral. */
fun periodFlow(
    txs: List<Tx>,
    wallets: Map<String, Wallet>,
    currency: String,
    rates: Rates,
    from: Long,
    until: Long,
): Flow? {
    var income = 0L
    var expense = 0L
    for (tx in txs) {
        if (tx.deletedAt != null || tx.occurredAt < from || tx.occurredAt >= until) continue
        val wallet = wallets[tx.walletId] ?: continue
        val value = rates.convert(tx.amountMinor, wallet.currency, currency) ?: return null
        when (tx.kind) {
            EntryKind.income -> income += value
            EntryKind.expense -> expense += value
            EntryKind.transfer -> Unit
        }
    }
    return Flow(income, expense)
}

/** Wallets by balance in [currency], highest first. A wallet whose rate is missing is ranked by its own amount. */
fun walletsByBalance(wallets: List<Wallet>, txs: List<Tx>, currency: String, rates: Rates): List<Wallet> =
    wallets.sortedByDescending { w -> walletBalance(w, txs).let { rates.convert(it, w.currency, currency) ?: it } }

fun monthBounds(day: LocalDate, zone: ZoneId = ZoneOffset.UTC): Pair<Long, Long> {
    val start = day.withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val end = day.withDayOfMonth(1).plusMonths(1).atStartOfDay(zone).toInstant().toEpochMilli()
    return start to end
}

fun dayOf(millis: Long, zone: ZoneId = ZoneOffset.UTC): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

fun epochMillis(day: LocalDate, zone: ZoneId = ZoneOffset.UTC): Long = day.atStartOfDay(zone).toInstant().toEpochMilli()

/** Resolves a transfer's destination amount from the effective rate, honoring a manual rate. */
fun buildTransfer(
    id: String,
    from: Wallet,
    to: Wallet,
    amountMinor: Long,
    rates: Rates,
    manualRate: java.math.BigDecimal?,
    occurredAt: Long,
    now: Long,
    note: String = "",
): Tx? {
    if (from.id == to.id || amountMinor <= 0) return null
    if (from.currency == to.currency) {
        return Tx(id, from.id, to.id, null, EntryKind.transfer, amountMinor, amountMinor, null, note, occurredAt, null, now)
    }
    val pair = pairFor(from.currency, to.currency)
    val effective = manualRate ?: rates.effective(pair) ?: return null
    val manualTable = Rates(emptyMap(), mapOf(pair.key to effective))
    val received = manualTable.convert(amountMinor, from.currency, to.currency) ?: return null
    return Tx(id, from.id, to.id, null, EntryKind.transfer, amountMinor, received, effective.toPlainString(), note, occurredAt, null, now)
}

data class BudgetLine(val categoryId: String, val budgetMinor: Long, val spentMinor: Long) {
    val remainingMinor get() = budgetMinor - spentMinor
    val fraction get() = if (budgetMinor > 0) spentMinor.toFloat() / budgetMinor else 0f
}

/** Spent vs budget per category for the month containing [day], all in [currency]. Null if a rate is missing. */
fun budgetLines(
    budgets: List<Budget>, txs: List<Tx>, wallets: Map<String, Wallet>, currency: String, rates: Rates, day: LocalDate,
): List<BudgetLine>? {
    val (from, until) = monthBounds(day)
    val spent = HashMap<String, Long>()
    for (tx in txs) {
        if (tx.deletedAt != null || tx.kind != EntryKind.expense || tx.occurredAt < from || tx.occurredAt >= until) continue
        val category = tx.categoryId ?: continue
        val wallet = wallets[tx.walletId] ?: continue
        spent[category] = (spent[category] ?: 0L) + (rates.convert(tx.amountMinor, wallet.currency, currency) ?: return null)
    }
    return budgets.filter { it.deletedAt == null }.map { BudgetLine(it.categoryId, it.monthlyMinor, spent[it.categoryId] ?: 0L) }
}
