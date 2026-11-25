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

/**
 * Cash and investment wallets by balance in [currency], highest first, then credit cards (a card's limit is not the
 * user's money, so a card never ranks as a top asset). A wallet whose rate is missing is ranked by its own amount.
 */
fun walletsByBalance(wallets: List<Wallet>, txs: List<Tx>, currency: String, rates: Rates): List<Wallet> {
    val (cards, assets) = wallets.partition { it.kind.isCredit }
    return assets.sortedByDescending { w -> walletBalance(w, txs).let { rates.convert(it, w.currency, currency) ?: it } } + cards
}

/** Balance of every wallet at [at] (millis, exclusive): opening balances + transactions before that moment, in [currency]. */
fun netWorthAt(wallets: List<Wallet>, txs: List<Tx>, currency: String, rates: Rates, at: Long): Long? =
    totalBalance(wallets, txs.filter { it.occurredAt < at }, currency, rates)

fun monthBounds(day: LocalDate, zone: ZoneId = ZoneOffset.UTC): Pair<Long, Long> {
    val start = day.withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val end = day.withDayOfMonth(1).plusMonths(1).atStartOfDay(zone).toInstant().toEpochMilli()
    return start to end
}

fun dayOf(millis: Long, zone: ZoneId = ZoneOffset.UTC): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

fun epochMillis(day: LocalDate, zone: ZoneId = ZoneOffset.UTC): Long = day.atStartOfDay(zone).toInstant().toEpochMilli()

/**
 * Builds a transfer. The destination amount comes from the effective rate (a manual rate wins), or, when the user only
 * knows both amounts, from [receivedMinor] (the gross amount received, in the destination currency; the rate is then
 * derived from it). An optional [feeMinor] (destination currency) is taken from what the recipient gets.
 * Null when a rate is missing, the wallets are the same, or the fee is larger than the amount received.
 */
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
    receivedMinor: Long? = null,
    feeMinor: Long = 0,
    categoryId: String? = null,
): Tx? {
    if (from.id == to.id || amountMinor <= 0 || feeMinor < 0) return null
    val fee = feeMinor.takeIf { it > 0 }
    if (from.currency == to.currency) {
        val net = amountMinor - feeMinor
        if (net < 0) return null
        return Tx(id, from.id, to.id, categoryId, EntryKind.transfer, amountMinor, net, null, note, occurredAt, null, now, feeMinor = fee)
    }
    val pair = pairFor(from.currency, to.currency)
    val gross: Long
    val effective: java.math.BigDecimal
    if (receivedMinor != null) {
        if (receivedMinor <= 0) return null
        gross = receivedMinor
        effective = rateFromAmounts(amountMinor, from.currency, receivedMinor, to.currency)
    } else {
        effective = manualRate ?: rates.effective(pair) ?: return null
        gross = Rates(emptyMap(), mapOf(pair.key to effective)).convert(amountMinor, from.currency, to.currency) ?: return null
    }
    val net = gross - feeMinor
    if (net < 0) return null
    return Tx(id, from.id, to.id, categoryId, EntryKind.transfer, amountMinor, net, effective.toPlainString(), note, occurredAt, null, now, feeMinor = fee)
}

/** The pair rate (as [pairFor] orients it) implied by sending [sentMinor] and receiving [receivedMinor]. */
fun rateFromAmounts(sentMinor: Long, fromCurrency: String, receivedMinor: Long, toCurrency: String): java.math.BigDecimal {
    val sent = toMajor(sentMinor, fromCurrency)
    val received = toMajor(receivedMinor, toCurrency)
    val pair = pairFor(fromCurrency, toCurrency)
    val mc = java.math.MathContext(10, java.math.RoundingMode.HALF_UP)
    // pair.base is what one unit is quoted in: USD/IDR = IDR per USD.
    return (if (pair.base == fromCurrency) received.divide(sent, mc) else sent.divide(received, mc)).stripTrailingZeros()
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
