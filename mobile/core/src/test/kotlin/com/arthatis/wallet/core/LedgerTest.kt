package com.arthatis.wallet.core

import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LedgerTest {
    private val bca = Wallet("bca", "BCA", WalletKind.rekening, "IDR", 6_240_000, updatedAt = 1)
    private val wise = Wallet("wise", "Wise", WalletKind.rekening, "USD", 10_000, updatedAt = 1)
    private val rates = Rates(mapOf("USD" to BigDecimal("16450"), "SGD" to BigDecimal("12180")))

    @Test fun formatsLikeTheDesign() {
        assertEquals("Rp14.125.000", formatMinor(14_125_000, "IDR"))
        assertEquals("+Rp8.500.000", formatMinor(8_500_000, "IDR", Sign.Plus))
        assertEquals("−Rp25.000", formatMinor(25_000, "IDR", Sign.Minus))
        assertEquals("$100,00", formatMinor(10_000, "USD"))
        assertEquals("$60,79", formatMinor(6_079, "USD"))
        assertEquals("S$5,00", formatMinor(500, "SGD"))
    }

    @Test fun transferShowsConversionCardNumbers() {
        val tx = assertNotNull(buildTransfer("t", bca, wise, 1_000_000, rates, null, 0, 0))
        assertEquals(6_079, tx.toAmountMinor)
        assertEquals("16450", tx.rate)
        // Saldo BCA setelah transfer: Rp5.240.000.
        assertEquals(5_240_000, walletBalance(bca, listOf(tx)))
        assertEquals(10_000 + 6_079, walletBalance(wise, listOf(tx)))
    }

    @Test fun manualRateOverridesApi() {
        val tx = assertNotNull(buildTransfer("t", bca, wise, 1_000_000, rates, BigDecimal("16000"), 0, 0))
        assertEquals(6_250, tx.toAmountMinor)
        assertEquals(16_000.0, parseRateInput("16.000")!!.toDouble())
        assertNull(parseRateInput(""))
        assertNull(parseRateInput("0"))
    }

    @Test fun missingRateRefusesToGuess() {
        assertNull(buildTransfer("t", bca, wise, 1_000_000, Rates(), null, 0, 0))
        assertNull(totalBalance(listOf(bca, wise), emptyList(), "IDR", Rates()))
    }

    @Test fun totalIsInPrimaryCurrency() {
        // 6.240.000 + 100 USD * 16450 = 7.885.000
        assertEquals(7_885_000, totalBalance(listOf(bca, wise), emptyList(), "IDR", rates))
        assertEquals("16.450", formatRate(BigDecimal("16450")))
    }

    @Test fun deletedTransactionsDoNotCount() {
        val live = Tx("a", "bca", null, null, EntryKind.expense, 25_000, note = "", occurredAt = 0, updatedAt = 1)
        val gone = live.copy(id = "b", deletedAt = 5)
        assertEquals(6_215_000, walletBalance(bca, listOf(live, gone)))
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
        assertEquals(25_000, d.amountMinor)
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
        assertEquals(listOf(4_500L, 12_000L, 16_500L, 20_000L), lines.map { it.amountMinor })
        assertEquals(true, lines[2].isTotal)
        assertEquals(false, lines[0].isTotal)
    }

    @Test fun flagsOcrFixedDigitsAsUncertain() {
        val line = parseReceipt("Kopi  2O.OOO", "IDR").single()
        assertEquals(20_000L, line.amountMinor)
        assertEquals(true, line.uncertain)
    }

    @Test fun decimalCurrencyKeepsCents() {
        assertEquals(1_250L, parseAmountToken("12.50", "USD"))
        assertEquals(1_250_000L, parseAmountToken("1.250.000", "IDR"))
    }
}

class BudgetTest {
    @Test fun spentAndRemainingPerCategory() {
        val bca = Wallet("bca", "BCA", WalletKind.rekening, "IDR", 0, updatedAt = 1)
        val day = LocalDate.of(2026, 11, 20)
        val txs = listOf(
            Tx("a", "bca", null, "cat_food", EntryKind.expense, 25_000, occurredAt = epochMillis(day), updatedAt = 1),
            Tx("b", "bca", null, "cat_food", EntryKind.expense, 1_215_000, occurredAt = epochMillis(day.minusDays(1)), updatedAt = 1),
            Tx("c", "bca", null, "cat_food", EntryKind.expense, 99_000, occurredAt = epochMillis(day.minusMonths(1)), updatedAt = 1),
        )
        val line = budgetLines(listOf(Budget("b1", "cat_food", 1_500_000, 1)), txs, mapOf("bca" to bca), "IDR", Rates(), day)!!.single()
        assertEquals(1_240_000, line.spentMinor)
        assertEquals(260_000, line.remainingMinor)
    }
}
