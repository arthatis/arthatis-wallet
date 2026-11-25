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
    private val wise = Wallet("wise", "Wise", WalletKind.rekening, "USD", 1_000_000, updatedAt = 1)
    private val rates = Rates(mapOf("USD" to BigDecimal("16450"), "SGD" to BigDecimal("12180")))

    @Test fun formatsLikeTheDesign() {
        assertEquals("Rp14.125.000", formatMinor(rp(14_125_000), "IDR"))
        assertEquals("+Rp8.500.000", formatMinor(rp(8_500_000), "IDR", Sign.Plus))
        assertEquals("−Rp25.000", formatMinor(rp(25_000), "IDR", Sign.Minus))
        assertEquals("Rp1.234,5", formatMinor(12_345_000, "IDR"))
        assertEquals("Rp0,0001", formatMinor(1, "IDR"))
        assertEquals("$100.00", formatMinor(1_000_000, "USD"))
        assertEquals("$60.79", formatMinor(607_900, "USD"))
        assertEquals("$1,060.79", formatMinor(10_607_900, "USD"))
        assertEquals("$1,060.7925", formatMinor(10_607_925, "USD"))
        assertEquals("$0.001", formatMinor(10, "USD"))
        assertEquals("S$5.00", formatMinor(50_000, "SGD"))
        assertEquals("RM1,234.50", formatMinor(12_345_000, "MYR"))
        assertEquals("\u20ac1.234,56", formatMinor(12_345_600, "EUR"))
        assertEquals("−\u20ac0,05", formatMinor(-500, "EUR"))
    }

    @Test fun transferShowsConversionCardNumbers() {
        val tx = assertNotNull(buildTransfer("t", bca, wise, rp(1_000_000), rates, null, 0, 0))
        assertEquals(607_903, tx.toAmountMinor)
        assertEquals("16450", tx.rate)
        // Saldo BCA setelah transfer: Rp5.240.000.
        assertEquals(rp(5_240_000), walletBalance(bca, listOf(tx)))
        assertEquals(1_000_000 + 607_903, walletBalance(wise, listOf(tx)))
    }

    @Test fun manualRateOverridesApi() {
        val tx = assertNotNull(buildTransfer("t", bca, wise, rp(1_000_000), rates, BigDecimal("16000"), 0, 0))
        assertEquals(625_000, tx.toAmountMinor)
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
        assertNull(parseChat("transfer 50k dari BCA ke bca", "IDR", wallets, cats))
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
        assertEquals(125_000L, parseAmountToken("12.50", "USD"))
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
        assertEquals("12.5599", sanitizeAmountInput("12.55999", "USD"))
        assertEquals("0.5", sanitizeAmountInput(".5", "USD"))
        assertEquals("7", sanitizeAmountInput("007", "USD"))
        assertEquals("", sanitizeAmountInput("", "USD"))
    }

    @Test
    fun cashTotalLeavesCardsAndInvestmentsOut() {
        val cash = Wallet("a", "A", WalletKind.biasa, "IDR", 1_000, 0, 0)
        val card = Wallet("b", "B", WalletKind.kartu, "IDR", -300, 1, 0)
        val fund = Wallet("c", "C", WalletKind.investasi, "IDR", 500, 2, 0)
        val all = listOf(cash, card, fund)
        assertEquals(1_000L, cashTotal(all, emptyList(), "IDR", Rates()))
        assertEquals(-300L, creditTotal(all, emptyList(), "IDR", Rates()))
        assertEquals(500L, investmentTotal(all, emptyList(), "IDR", Rates()))
    }
}

class TransferChatTest {
    private val wallets = listOf(NamedRef("bca", "BCA"), NamedRef("syariah", "BCA Syariah"), NamedRef("gopay", "GoPay"), NamedRef("ovo", "OVO"))
    private val cats = defaultCategories.map { CategoryRef(it.key, it.key, it.name, it.kind) }

    @Test fun readsLooseTransferSentences() {
        val a = assertNotNull(parseChat("tf 50k dari bca ke ovo", "IDR", wallets, cats))
        assertEquals(EntryKind.transfer, a.kind); assertEquals("bca", a.walletId); assertEquals("ovo", a.toWalletId); assertEquals(rp(50_000), a.amountMinor)
        val b = assertNotNull(parseChat("kirim 100rb ke gopay", "IDR", wallets, cats))
        assertEquals(null, b.walletId); assertEquals("gopay", b.toWalletId)
        val c = assertNotNull(parseChat("Top up Go Pay 200k", "IDR", wallets, cats))
        assertEquals("gopay", c.toWalletId)
        val d = assertNotNull(parseChat("pindahin 1jt bca syariah ke BCA", "IDR", wallets, cats))
        assertEquals("syariah", d.walletId); assertEquals("bca", d.toWalletId)
    }

    @Test fun kirimWithoutWalletIsNotATransfer() {
        assertNull(parseChat("kirim paket 20k", "IDR", wallets, cats))
    }

    @Test fun looseWalletMatch() {
        assertEquals("gopay", matchWallet("go-pay", wallets)?.id)
        assertEquals("syariah", matchWallet("bca syariah", wallets)?.id)
    }
}

class MoneyTest {
    @Test fun compactForm() {
        assertEquals("Rp4,1k", formatCompact(rp(4_150), "IDR"))
        assertEquals("$6.7m", formatCompact(toMinor(java.math.BigDecimal("6750000"), "USD"), "USD"))
        assertEquals("Rp7,2b", formatCompact(rp(7_200_000_000), "IDR"))
        assertEquals("Rp1t", formatCompact(rp(1_000_000_000_000), "IDR"))
        assertEquals("−Rp2m", formatCompact(-rp(2_000_000), "IDR"))
        assertEquals("Rp950", formatCompact(rp(950), "IDR"))
        assertEquals("Rp14,1m", compactOf("Rp14.125.000"))
        assertEquals("+S$2.5k", compactOf("+S$2,500.00"))
        assertEquals("−\u20ac1,2m", compactOf("−\u20ac1.234.567,89"))
        assertNull(compactOf("12 transaksi"))
    }

    @Test fun netWorthIncludesInvestmentsAndCardDebt() {
        val cash = Wallet("c", "Tunai", WalletKind.biasa, "IDR", rp(1_000_000), updatedAt = 1)
        val inv = Wallet("i", "Saham", WalletKind.investasi, "IDR", rp(5_000_000), updatedAt = 1)
        val card = Wallet("k", "Kartu", WalletKind.kartu, "IDR", -rp(300_000), updatedAt = 1)
        assertEquals(rp(5_700_000), totalBalance(listOf(cash, inv, card), emptyList(), "IDR", Rates()))
        assertEquals(rp(1_000_000), cashTotal(listOf(cash, inv, card), emptyList(), "IDR", Rates()))
        assertEquals(listOf("i", "c", "k"), walletsByBalance(listOf(cash, inv, card), emptyList(), "IDR", Rates()).map { it.id })
    }

    @Test fun reportForRange() {
        val w = Wallet("c", "Tunai", WalletKind.biasa, "IDR", rp(100_000), updatedAt = 1)
        val day = 86_400_000L
        val txs = listOf(
            Tx("1", "c", null, "food", EntryKind.expense, rp(20_000), occurredAt = 5 * day, updatedAt = 1),
            Tx("2", "c", null, "salary", EntryKind.income, rp(50_000), occurredAt = 6 * day, updatedAt = 1),
            Tx("3", "c", null, "food", EntryKind.expense, rp(5_000), occurredAt = 20 * day, updatedAt = 1),
        )
        val r = assertNotNull(buildReport(listOf(w), txs, "IDR", Rates(), 0, 10 * day))
        assertEquals(rp(50_000), r.incomeMinor); assertEquals(rp(20_000), r.expenseMinor)
        assertEquals(rp(130_000), r.netWorthMinor)
        assertEquals(2, r.transactions.size)
        assertEquals("food", r.expenses.single().categoryId)
        assertEquals(rp(130_000), r.wallets.single().closingMinor)
    }
}

class EmailEntryTest {
    @Test fun readsDebitAndCreditNotices() {
        val a = assertNotNull(parseBankEmail("Notifikasi Transaksi", "Transaksi debit kartu Anda sebesar Rp 150.000,00 di merchant TOKO MAJU", "IDR"))
        assertEquals(EntryKind.expense, a.kind); assertEquals(rp(150_000), a.amountMinor); assertEquals("IDR", a.currency)
        val b = assertNotNull(parseBankEmail("Dana masuk", "Anda menerima transfer masuk IDR 2,500,000.00 dari BUDI", "IDR"))
        assertEquals(EntryKind.income, b.kind); assertEquals(rp(2_500_000), b.amountMinor)
        val c = assertNotNull(parseBankEmail("Payment receipt", "You paid USD 12.50 at Netflix", "IDR"))
        assertEquals("USD", c.currency); assertEquals(125_000L, c.amountMinor)
        assertNull(parseBankEmail("Promo", "Diskon hingga 50% untuk kamu!", "IDR"))
    }

    @Test fun htmlAndSenders() {
        assertEquals("Halo\nTotal Rp 10.000", htmlToText("<html><style>p{}</style><p>Halo</p><table><tr><td>Total</td><td>Rp&nbsp;10.000</td></tr></table></html>"))
        assertEquals(true, isWatchedSender("BCA <noreply@klikbca.com>", defaultEmailSenders))
        assertEquals(false, isWatchedSender("news@tokopedia.com", defaultEmailSenders))
    }
}

class Round2Test {
    private val day = 86_400_000L

    @Test fun openingBalanceIsNeverIncome() {
        val w = Wallet("c", "Tunai", WalletKind.biasa, "IDR", rp(5_000_000), updatedAt = 1)
        val flow = periodFlow(emptyList(), mapOf("c" to w), "IDR", Rates(), 0, 30 * day)!!
        assertEquals(0L, flow.incomeMinor)
        val tx = Tx("1", "c", null, "food", EntryKind.expense, rp(100_000), occurredAt = 10 * day, updatedAt = 1)
        val r = buildReport(listOf(w), listOf(tx), "IDR", Rates(), 5 * day, 20 * day)!!
        assertEquals(0L, r.incomeMinor)
        assertEquals(rp(5_000_000), r.openingMinor)
        assertEquals(rp(4_900_000), r.netWorthMinor)
        assertEquals(rp(5_000_000), r.wallets.single().openingMinor)
        assertEquals(0L, r.wallets.single().inMinor)
    }

    @Test fun reportGroupsByCategory() {
        val w = Wallet("c", "Tunai", WalletKind.biasa, "IDR", 0, updatedAt = 1)
        val txs = listOf(
            Tx("1", "c", null, "food", EntryKind.expense, rp(10_000), occurredAt = 1 * day, updatedAt = 1),
            Tx("2", "c", null, "food", EntryKind.expense, rp(20_000), occurredAt = 2 * day, updatedAt = 1),
            Tx("3", "c", null, "fun", EntryKind.expense, rp(5_000), occurredAt = 3 * day, updatedAt = 1),
        )
        val g = buildReport(listOf(w), txs, "IDR", Rates(), 0, 10 * day)!!.groups
        assertEquals(listOf("food", "fun"), g.map { it.categoryId })
        assertEquals(rp(30_000), g[0].totalMinor)
        assertEquals(listOf("2", "1"), g[0].transactions.map { it.id })
    }

    @Test fun transferByReceivedAmountAndFee() {
        val bca = Wallet("bca", "BCA", WalletKind.biasa, "IDR", 0, updatedAt = 1)
        val wise = Wallet("wise", "Wise", WalletKind.biasa, "USD", 0, updatedAt = 1)
        val usd = { n: String -> toMinor(BigDecimal(n), "USD") }
        // Sent Rp1.645.000, got $100 gross, $1 fee: rate 16450, wallet gets $99.
        val t = assertNotNull(buildTransfer("t", bca, wise, rp(1_645_000), Rates(), null, 0, 0, receivedMinor = usd("100"), feeMinor = usd("1")))
        assertEquals("16450", t.rate)
        assertEquals(usd("99"), t.toAmountMinor)
        assertEquals(usd("1"), t.feeMinor)
        assertEquals(usd("99"), walletBalance(wise, listOf(t)))
        val other = Wallet("ovo", "OVO", WalletKind.biasa, "IDR", 0, updatedAt = 1)
        val same = assertNotNull(buildTransfer("s", bca, other, rp(100_000), Rates(), null, 0, 0, feeMinor = rp(2_500), categoryId = "transfer"))
        assertEquals(rp(97_500), same.toAmountMinor); assertEquals("transfer", same.categoryId)
        assertNull(buildTransfer("x", bca, other, rp(1_000), Rates(), null, 0, 0, feeMinor = rp(2_000)))
    }

    @Test fun cardsNeverRankAsTopAssets() {
        val cash = Wallet("c", "Tunai", WalletKind.biasa, "IDR", rp(10_000), updatedAt = 1)
        val card = Wallet("k", "Kartu", WalletKind.kartu, "IDR", rp(50_000_000), updatedAt = 1, creditLimitMinor = rp(50_000_000))
        assertEquals(listOf("c", "k"), walletsByBalance(listOf(card, cash), emptyList(), "IDR", Rates()).map { it.id })
    }

    @Test fun cardReminderOnH3AndDueDay() {
        assertEquals(3, cardReminderDays(25, LocalDate.of(2026, 11, 22)))
        assertEquals(0, cardReminderDays(25, LocalDate.of(2026, 11, 25)))
        assertNull(cardReminderDays(25, LocalDate.of(2026, 11, 23)))
        // Day 31 in a 30-day month falls on the 30th.
        assertEquals(3, cardReminderDays(31, LocalDate.of(2026, 11, 27)))
        assertEquals(0, cardReminderDays(31, LocalDate.of(2027, 2, 28)))
    }

    @Test fun receiptKeepsOnlyProducts() {
        val lines = parseReceipt("JASMINE TEA  8.000\nTOTAL  8.000\nTUNAI 10.000\nKEMBALI 2.000", "IDR")
        val r = receiptItems(lines)
        assertEquals(listOf("JASMINE TEA"), r.items.map { it.text })
        assertEquals(rp(8_000), r.totalMinor)
        // Payment and change stay available as extras, never ticked by default.
        assertEquals(listOf(LineRole.Payment, LineRole.Payment), r.extras.map { it.role })
        assertEquals(emptySet(), r.defaultExtras())
        assertEquals(setOf(rp(8_000), rp(10_000), rp(2_000)), amountsIn("JASMINE TEA  8.000\nTOTAL  8.000\nTUNAI 10.000\nKEMBALI 2.000", "IDR"))
        assertEquals(listOf(rp(16_500)), receiptItems(parseReceipt("TOTAL 16.500", "IDR")).items.map { it.amountMinor })
    }
}

class ReceiptExtrasTest {
    @Test fun taxAndDiscountCountTowardTheTotal() {
        val r = receiptItems(parseReceipt("NASI GORENG 30.000\nES TEH 5.000\nSUBTOTAL 35.000\nDISKON 5.000\nPPN 10% 3.000\nTOTAL 33.000\nTUNAI 50.000\nKEMBALI 17.000", "IDR"))
        assertEquals(listOf("NASI GORENG", "ES TEH"), r.items.map { it.text })
        assertEquals(listOf(LineRole.Discount, LineRole.Charge, LineRole.Payment, LineRole.Payment), r.extras.map { it.role })
        assertEquals(-rp(5_000), r.extras[0].amountMinor)
        // Items 35.000 − 5.000 + 3.000 = 33.000 = printed total, so tax and discount are ticked.
        assertEquals(setOf(r.extras[0], r.extras[1]), r.defaultExtras())
        val split = splitReceipt(r.items + r.extras[0] + r.extras[1])
        assertEquals(rp(33_000), split.sumOf { it.second })
        assertEquals(true, split.all { it.second > 0 })
    }

    @Test fun extrasNotTickedWhenTheyDoNotMatch() {
        val r = receiptItems(parseReceipt("KOPI 20.000\nSERVICE 2.000\nTOTAL 25.000", "IDR"))
        assertEquals(emptySet(), r.defaultExtras())
        assertEquals(LineRole.Charge, r.extras.single().role)
    }
}
