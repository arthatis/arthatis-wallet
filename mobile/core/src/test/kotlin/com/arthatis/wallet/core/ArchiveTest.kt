package com.arthatis.wallet.core

import com.arthatis.wallet.core.crypto.Crypto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ArchiveTest {
    private val wallet = Wallet("w1", "BCA", WalletKind.rekening, "IDR", 1_000, updatedAt = 10)
    private val tx = Tx("t1", "w1", null, null, EntryKind.expense, 25_000, note = "Bakso, \"Pak Kumis\"", occurredAt = 1_763_600_000_000, updatedAt = 10)
    private val data = ArchiveData(createdAt = 5, device = "Pixel 8", wallets = listOf(wallet), transactions = listOf(tx))

    @Test fun encryptedRoundTripAndWrongKey() {
        val rk = Crypto.newRecoveryKey()
        val file = encodeArchive(data, rk, memoryKiB = 1024)
        assertEquals(data, assertIs<ArchiveResult.Ok>(decodeArchive(file, rk, 1024)).data)
        assertIs<ArchiveResult.WrongKey>(decodeArchive(file, Crypto.newRecoveryKey(), 1024))
        assertIs<ArchiveResult.NotArchive>(decodeArchive("garbage".toByteArray(), rk, 1024))
        assertTrue(!String(file).contains("Bakso"))
    }

    @Test fun mergeSkipsSameAndOlderKeepsNewer() {
        val local = data
        val incoming = data.copy(
            wallets = listOf(wallet.copy(name = "BCA Utama", updatedAt = 20)),
            transactions = listOf(tx, tx.copy(id = "t2", updatedAt = 11)),
        )
        val plan = planMerge(incoming, local)
        assertEquals(listOf("BCA Utama"), plan.wallets.map { it.name })
        assertEquals(listOf("t2"), plan.transactions.map { it.id })
        assertEquals(1, plan.skipped)
        assertEquals(0, planMerge(local, local).transactions.size + planMerge(local, local).wallets.size)
    }

    @Test fun csvEscapesAndFormats() {
        val csv = toCsv(data).trim().lines()
        assertEquals("date,wallet,kind,category,amount,currency,to_wallet,to_amount,note", csv[0])
        assertTrue(csv[1].contains("\"Bakso, \"\"Pak Kumis\"\"\""))
        assertTrue(csv[1].contains(",25000,IDR,"))
    }
}

class CsvImportTest {
    @Test fun csvRoundTripsAndIsIdempotent() {
        val wallets = listOf(Wallet("w1", "BCA", WalletKind.rekening, "IDR", 0, updatedAt = 1))
        val tx = Tx("t1", "w1", null, "cat_food", EntryKind.expense, 25_000, note = "Bakso, enak", occurredAt = epochMillis(java.time.LocalDate.of(2026, 11, 20)), updatedAt = 1)
        val cats = listOf(Category("cat_food", "food", "Makanan", EntryKind.expense, "#1B6D35", false, 0, 1))
        val csv = toCsv(ArchiveData(createdAt = 1, wallets = wallets, categories = cats, transactions = listOf(tx)))
        val a = csvToArchive(csv, 5)!!
        assertEquals(1, a.transactions.size)
        assertEquals(25_000L, a.transactions[0].amountMinor)
        assertEquals("Bakso, enak", a.transactions[0].note)
        assertEquals("BCA", a.wallets[0].name)
        assertEquals(a.transactions[0].id, csvToArchive(csv, 9)!!.transactions[0].id)
        assertEquals(null, csvToArchive("nope", 1))
    }
}
