package com.arthatis.wallet.export

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.arthatis.wallet.core.ArchiveData
import java.io.File

/** Plain SQLite copy of the ledger for people who want to query it themselves. */
object SqliteExport {
    fun build(context: Context, data: ArchiveData): ByteArray {
        val file = File(context.cacheDir, "export.sqlite").also { it.delete() }
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            db.execSQL("CREATE TABLE wallets (id TEXT PRIMARY KEY, name TEXT, kind TEXT, currency TEXT, opening_minor INTEGER)")
            db.execSQL("CREATE TABLE categories (id TEXT PRIMARY KEY, name TEXT, kind TEXT)")
            db.execSQL("CREATE TABLE transactions (id TEXT PRIMARY KEY, wallet_id TEXT, to_wallet_id TEXT, category_id TEXT, kind TEXT, amount_minor INTEGER, to_amount_minor INTEGER, rate TEXT, note TEXT, occurred_at INTEGER)")
            db.beginTransaction()
            data.wallets.filter { it.deletedAt == null }.forEach { db.execSQL("INSERT INTO wallets VALUES (?,?,?,?,?)", arrayOf<Any?>(it.id, it.name, it.kind.name, it.currency, it.openingMinor)) }
            data.categories.filter { it.deletedAt == null }.forEach { db.execSQL("INSERT INTO categories VALUES (?,?,?)", arrayOf<Any?>(it.id, it.name, it.kind.name)) }
            data.transactions.filter { it.deletedAt == null }.forEach {
                db.execSQL("INSERT INTO transactions VALUES (?,?,?,?,?,?,?,?,?,?)", arrayOf<Any?>(it.id, it.walletId, it.toWalletId, it.categoryId, it.kind.name, it.amountMinor, it.toAmountMinor, it.rate, it.note, it.occurredAt))
            }
            db.setTransactionSuccessful(); db.endTransaction()
        } finally { db.close() }
        return file.readBytes().also { file.delete() }
    }
}
