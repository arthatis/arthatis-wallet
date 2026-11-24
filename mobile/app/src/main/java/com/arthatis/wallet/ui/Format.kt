package com.arthatis.wallet.ui

import androidx.compose.runtime.Composable
import com.arthatis.wallet.core.Category
import com.arthatis.wallet.core.EntryKind
import com.arthatis.wallet.core.Sign
import com.arthatis.wallet.core.Tx
import com.arthatis.wallet.core.dayOf
import com.arthatis.wallet.core.formatMinor
import com.arthatis.wallet.data.Ledger
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun locale(): Locale = Locale.forLanguageTag(if (LocalLang.current == "en") "en" else "id-ID")

/** `Jumat, 20 November 2026`. */
@Composable
fun dateTitle(day: LocalDate): String =
    DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", locale()).format(day).replaceFirstChar { it.uppercase() }

/** `20 Nov`. */
@Composable
fun shortDate(day: LocalDate): String = "${day.dayOfMonth} ${day.month.getDisplayName(TextStyle.SHORT, locale())}"

@Composable
fun categoryName(c: Category?, ledger: Ledger? = null): String {
    if (c == null) return tr("Tanpa kategori", "Uncategorised")
    val en = when (c.key) {
        "food" -> "Food"; "shopping" -> "Shopping"; "bills" -> "Bills"; "transport" -> "Transport"; "fuel" -> "Fuel"
        "fun" -> "Entertainment"; "home" -> "Home"; "health" -> "Health"; "salary" -> "Salary"
        "other_income" -> "Income"; "transfer" -> "Transfer"; else -> null
    }
    return if (en != null && LocalLang.current == "en") en else c.name
}

fun txSign(kind: EntryKind) = when (kind) { EntryKind.income -> Sign.Plus; EntryKind.expense -> Sign.Minus; EntryKind.transfer -> Sign.None }

@Composable
fun txTitle(tx: Tx, ledger: Ledger): String =
    tx.note.ifBlank { if (tx.kind == EntryKind.transfer) tr("Transfer", "Transfer") else categoryName(ledger.category(tx.categoryId)) }

/** `Pemasukan · BCA · 20 Nov`. */
@Composable
fun txSupporting(tx: Tx, ledger: Ledger): String {
    val kindLabel = when (tx.kind) {
        EntryKind.income -> categoryName(ledger.category(tx.categoryId)).takeIf { tx.categoryId != null } ?: tr("Pemasukan", "Income")
        EntryKind.expense -> categoryName(ledger.category(tx.categoryId))
        EntryKind.transfer -> "${ledger.wallet(tx.walletId)?.name ?: "?"} → ${ledger.wallet(tx.toWalletId)?.name ?: "?"}"
    }
    return if (tx.kind == EntryKind.transfer) "$kindLabel · ${shortDate(dayOf(tx.occurredAt))}"
    else "$kindLabel · ${ledger.wallet(tx.walletId)?.name ?: "?"} · ${shortDate(dayOf(tx.occurredAt))}"
}

fun txAmount(tx: Tx, ledger: Ledger): String {
    val cur = ledger.wallet(tx.walletId)?.currency ?: "IDR"
    return formatMinor(tx.amountMinor, cur, txSign(tx.kind))
}
