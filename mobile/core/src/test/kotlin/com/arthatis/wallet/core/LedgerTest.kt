package com.arthatis.wallet.core

import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Rupiah in minor units (IDR keeps 4 decimals). */
private fun rp(n: Long) = n * 10_000L

class LedgerTest {
    private val bca = Wallet("bca", "BCA", WalletKind.rekening, "IDR", rp(6_240_000), updatedAt = 1)
    private val wise = Wallet("wise", "Wise", WalletKind.rekening, "USD", 10_000, updatedAt = 1)
    private val rates = Rates(mapOf("USD" to BigDecimal("16450"), "SGD" to BigDecimal("12180")))

    @Test fun formatsLikeTheDesign() {
        assertEquals("Rp14.125.000", formatMinor(rp(14_125_000), "IDR"))
        assertEquals("+Rp8.500.000", formatMinor(rp(8_500_000), "IDR", Sign.Plus))
        assertEquals("−Rp25.000", formatMinor(rp(25_000), "IDR", Sign.Minus))
        assertEquals("Rp1.234,5", formatMinor(12_345_000, "IDR"))
        assertEquals("Rp0,0001", formatMinor(1, "IDR"))
        assertEquals("$100.00", formatMinor(10_000, "USD"))
        assertEquals("$60.79", formatMinor(6_079, "USD"))
        assertEquals("$1,060.79", formatMinor(106_079, "USD"))
        assertEquals("S$5.00", formatMinor(500, "SGD"))
        assertEquals("RM1,234.50", formatMinor(123_450, "MYR"))
        assertEquals("\u20ac1.234,56", formatMinor(123_456, "EUR"))
        assertEquals("−\u20ac0,05", formatMinor(-5, "EUR"))
    }

    @Test fun transferShowsConversionCardNumbers() {
        val tx = assertNotNull(buildTransfer("t", bca, wise, rp(1_000_000), rates, null, 0, 0))
        assertEquals(6_079, tx.toAmountMinor)
        assertEquals("16450", tx.rate)
        // Saldo BCA setelah transfer: Rp5.240.000.
        assertEquals(rp(5_240_000), walletBalance(bca, listOf(tx)))
        assertEquals(10_000 + 6_079, walletBalance(wise, listOf(tx)))
    }

    @Test fun manualRateOverridesApi() {
        val tx = assertNotNull(buildTransfer("t", bca, wise, rp(1_000_000), rates, BigDecimal("16000"), 0, 0))
        assertEquals(6_250, tx.toAmountMinor)
        assertEquals(16_000.0, parseRateInput("16.000")!!.toDouble())
        assertNull(parseRateInput(""))
        assertNull(parseRateInput("0"))
    }

    @Test fun missingRateRefusesToGuess() {
        assertNull(buildTransfer("t", bca, wise, rp(1_000_000), Rates(), null, 0, 0))
        assertNull(totalBalance(listOf(bca, wise), emptyList(), "IDR", Rates()))
    }

    @Test fun totalIsInPrimaryCurrency() {
        // 6.240.000 + 100 USD * 16450 = 7.885.000
        assertEquals(rp(7_885_000), totalBalance(listOf(bca, wise), emptyList(), "IDR", rates))
        assertEquals("16.450", formatRate(BigDecimal("16450")))
    }

    @Test fun deletedTransactionsDoNotCount() {
        val live = Tx("a", "bca", null, null, EntryKind.expense, rp(25_000), note = "", occurredAt = 0, updatedAt = 1)
        val gone = live.copy(id = "b", deletedAt = 5)
        assertEquals(rp(6_215_000), walletBalance(bca, listOf(live, gone)))
    }

    @Test fun recurringReminderFiresTheDayBefore() {
        val r = Recurring("r", "Netflix", "bca", "fun", EntryKind.expense, 54_000, Frequency.monthly, dayOfMonth = 21, updatedAt = 1)
        assertEquals(true, reminderDue(r, LocalDate.of(2026, 11, 20)))
        assertEquals(false, reminderDue(r, LocalDate.of(2026, 11, 19)))
        assertEquals(true, dueToday(r, LocalDate.of(2026, 11, 21), LocalDate.of(2026, 10, 21)))
        assertEquals(false, dueToday(r, LocalDate.of(2026, 11, 21), LocalDate.of(2026, 11, 21)))
    }

    @Test fun keypadExpressions() {
        assertEquals(BigDecimal("45000"), evaluateExpression("45k"))
        assertEquals(BigDecimal("32000"), evaluateExpression("12rb+20rb"))
        assertNull(evaluateExpression("1/0"))
    }
}

class ChatTest {
    private val wallets = listOf(NamedRef("bca", "BCA"), NamedRef("cash", "Tunai"))
    private val cats = defaultCategories.map { CategoryRef(it.key, it.key, it.name, it.kind) }

    @Test fun parsesAmountCategoryAndWallet() {
        val d = assertNotNull(parseChat("bakso 25k pakai tunai", "IDR", wallets, cats))
        assertEquals(rp(25_000), d.amountMinor)
        assertEquals("food", d.categoryId)
        assertEquals("cash", d.walletId)
    }

    @Test fun parsesTransferAndRejectsUnknown() {
        val t = assertNotNull(parseChat("transfer 1jt dari BCA ke Tunai", "IDR", wallets, cats))
        assertEquals(EntryKind.transfer, t.kind)
        assertEquals("bca", t.walletId)
        assertEquals("cash", t.toWalletId)
        assertNull(parseChat("halo apa kabar", "IDR", wallets, cats))
        assertNull(parseChat("sesuatu 25k", "IDR", wallets, cats))
    }
}

class ReceiptTest {
    @Test fun readsItemsAndTotal() {
        val lines = parseReceipt("INDOMARET\nAqua 600ml  4.500\nRoti  12.000\nTotal  16.500\nTunai 20.000", "IDR")
        assertEquals(listOf(rp(4_500), rp(12_000), rp(16_500), rp(20_000)), lines.map { it.amountMinor })
        assertEquals(true, lines[2].isTotal)
        assertEquals(false, lines[0].isTotal)
    }

    @Test fun flagsOcrFixedDigitsAsUncertain() {
        val line = parseReceipt("Kopi  2O.OOO", "IDR").single()
        assertEquals(rp(20_000), line.amountMinor)
        assertEquals(true, line.uncertain)
    }

    @Test fun decimalCurrencyKeepsCents() {
        assertEquals(1_250L, parseAmountToken("12.50", "USD"))
        assertEquals(rp(1_250_000), parseAmountToken("1.250.000", "IDR"))
    }
}

class BudgetTest {
    @Test fun spentAndRemainingPerCategory() {
        val bca = Wallet("bca", "BCA", WalletKind.rekening, "IDR", 0, updatedAt = 1)
        val day = LocalDate.of(2026, 11, 20)
        val txs = listOf(
            Tx("a", "bca", null, "cat_food", EntryKind.expense, rp(25_000), occurredAt = epochMillis(day), updatedAt = 1),
            Tx("b", "bca", null, "cat_food", EntryKind.expense, rp(1_215_000), occurredAt = epochMillis(day.minusDays(1)), updatedAt = 1),
            Tx("c", "bca", null, "cat_food", EntryKind.expense, rp(99_000), occurredAt = epochMillis(day.minusMonths(1)), updatedAt = 1),
        )
        val line = budgetLines(listOf(Budget("b1", "cat_food", rp(1_500_000), 1)), txs, mapOf("bca" to bca), "IDR", Rates(), day)!!.single()
        assertEquals(rp(1_240_000), line.spentMinor)
        assertEquals(rp(260_000), line.remainingMinor)
    }

    @Test
    fun amountInputDisplaysSeparatorsPerCurrency() {
        assertEquals("1.234.567", displayAmount("1234567", "IDR").text)
        assertEquals("1,234,567.5", displayAmount("1234567.5", "USD").text)
        assertEquals("1.234.567,5", displayAmount("1234567.5", "EUR").text)
        assertEquals("1.000+2.500", displayAmount("1000+2500", "IDR").text)
        val shown = displayAmount("1234", "IDR")
        assertEquals(5, shown.originalToDisplay(4))
        assertEquals(4, shown.displayToOriginal(5))
    }

    @Test
    fun amountInputIsSanitized() {
        assertEquals("1234", sanitizeAmountInput("1234", "IDR"))
        assertEquals("1.5", sanitizeAmountInput("1,5", "IDR"))
        assertEquals("1.2345", sanitizeAmountInput("1.23456", "IDR"))
        assertEquals("12.5", sanitizeAmountInput("12,5", "EUR"))
        assertEquals("12.55", sanitizeAmountInput("12.559", "USD"))
        assertEquals("0.5", sanitizeAmountInput(".5", "USD"))
        assertEquals("7", sanitizeAmountInput("007", "USD"))
        assertEquals("", sanitizeAmountInput("", "USD"))
    }

    @Test
    fun creditAndInvestmentStayOutOfCashTotal() {
        val cash = Wallet("a", "A", WalletKind.biasa, "IDR", 1_000, 0, 0)
        val card = Wallet("b", "B", WalletKind.kartu, "IDR", -300, 1, 0)
        val fund = Wallet("c", "C", WalletKind.investasi, "IDR", 500, 2, 0)
        val all = listOf(cash, card, fund)
        assertEquals(1_000L, totalBalance(all, emptyList(), "IDR", Rates()))
        assertEquals(-300L, creditTotal(all, emptyList(), "IDR", Rates()))
        assertEquals(500L, investmentTotal(all, emptyList(), "IDR", Rates()))
    }
}
