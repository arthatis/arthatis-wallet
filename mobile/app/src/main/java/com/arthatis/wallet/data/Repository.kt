package com.arthatis.wallet.data

import com.arthatis.wallet.core.Budget
import com.arthatis.wallet.core.Category
import com.arthatis.wallet.core.Entity
import com.arthatis.wallet.core.EntryKind
import com.arthatis.wallet.core.Profile
import com.arthatis.wallet.core.RateOverride
import com.arthatis.wallet.core.Recurring
import com.arthatis.wallet.core.Syncable
import com.arthatis.wallet.core.Tx
import com.arthatis.wallet.core.Wallet
import com.arthatis.wallet.core.defaultCategories
import com.arthatis.wallet.core.ledgerJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer
import java.util.UUID

data class Ledger(
    val wallets: List<Wallet> = emptyList(),
    val categories: List<Category> = emptyList(),
    val txs: List<Tx> = emptyList(),
    val recurring: List<Recurring> = emptyList(),
    val overrides: List<RateOverride> = emptyList(),
    val budgets: List<Budget> = emptyList(),
    val profile: Profile? = null,
) {
    val liveWallets get() = wallets.filter { it.deletedAt == null }.sortedBy { it.position }
    val liveTxs get() = txs.filter { it.deletedAt == null }.sortedByDescending { it.occurredAt }
    val liveCategories get() = categories.filter { it.deletedAt == null }.sortedBy { it.position }
    fun wallet(id: String?) = wallets.firstOrNull { it.id == id }
    fun category(id: String?) = categories.firstOrNull { it.id == id }
}

fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(20)

/** Single source of truth on the phone. Every write marks the row dirty for sync. */
class Repository(private val dao: WalletDao, private val clock: () -> Long = System::currentTimeMillis) {
    val ledger: Flow<Ledger> = dao.observeRecords().map { rows -> decode(rows) }

    suspend fun snapshot(): Ledger = decode(dao.records())

    private fun decode(rows: List<RecordRow>): Ledger {
        fun <T> of(entity: String, ser: KSerializer<T>) =
            rows.filter { it.entity == entity }.mapNotNull { runCatching { ledgerJson.decodeFromString(ser, it.json) }.getOrNull() }
        return Ledger(
            wallets = of(Entity.WALLET, Wallet.serializer()),
            categories = of(Entity.CATEGORY, Category.serializer()),
            txs = of(Entity.TRANSACTION, Tx.serializer()),
            recurring = of(Entity.RECURRING, Recurring.serializer()),
            overrides = of(Entity.RATE, RateOverride.serializer()),
            budgets = of(Entity.BUDGET, Budget.serializer()),
            profile = of(Entity.SETTING, Profile.serializer()).firstOrNull { it.id == "profile" },
        )
    }

    private suspend fun write(entity: String, record: Syncable, json: String) {
        dao.upsert(listOf(RecordRow(entity, record.id, json, record.updatedAt, record.deletedAt, dirty = true)))
    }

    fun now() = clock()

    suspend fun saveWallet(w: Wallet) { val r = w.copy(updatedAt = now()); write(Entity.WALLET, r, ledgerJson.encodeToString(Wallet.serializer(), r)) }
    suspend fun saveCategory(c: Category) { val r = c.copy(updatedAt = now()); write(Entity.CATEGORY, r, ledgerJson.encodeToString(Category.serializer(), r)) }
    suspend fun saveTx(t: Tx) { val r = t.copy(updatedAt = now()); write(Entity.TRANSACTION, r, ledgerJson.encodeToString(Tx.serializer(), r)) }
    suspend fun saveRecurring(x: Recurring) { val r = x.copy(updatedAt = now()); write(Entity.RECURRING, r, ledgerJson.encodeToString(Recurring.serializer(), r)) }
    suspend fun saveOverride(o: RateOverride) { val r = o.copy(updatedAt = now()); write(Entity.RATE, r, ledgerJson.encodeToString(RateOverride.serializer(), r)) }
    suspend fun saveBudget(b: Budget) { val r = b.copy(updatedAt = now()); write(Entity.BUDGET, r, ledgerJson.encodeToString(Budget.serializer(), r)) }
    suspend fun saveProfile(p: Profile) { val r = p.copy(updatedAt = now()); write(Entity.SETTING, r, ledgerJson.encodeToString(Profile.serializer(), r)) }

    suspend fun deleteTx(t: Tx) { val n = now(); saveTx(t.copy(deletedAt = n)) }
    suspend fun deleteRecurring(x: Recurring) { saveRecurring(x.copy(deletedAt = now())) }
    suspend fun clearOverride(o: RateOverride) { saveOverride(o.copy(deletedAt = now())) }

    /** Deleting a wallet also deletes the transactions that touch it. */
    suspend fun deleteWallet(wallet: Wallet) {
        val ledger = snapshot()
        ledger.txs.filter { it.deletedAt == null && (it.walletId == wallet.id || it.toWalletId == wallet.id) }.forEach { deleteTx(it) }
        saveWallet(wallet.copy(deletedAt = now()))
    }

    /** Default categories cannot be deleted, only hidden; ids are stable so devices never duplicate them. */
    suspend fun ensureDefaults() {
        val have = snapshot().categories.mapNotNull { it.key }.toSet()
        defaultCategories.forEachIndexed { index, d ->
            if (d.key !in have) saveCategory(Category("cat_${d.key}", d.key, d.name, d.kind, d.color, false, index, 0))
        }
    }

    suspend fun nextWalletPosition(): Int = (snapshot().wallets.maxOfOrNull { it.position } ?: -1) + 1

    /** Used after a restore or when connecting a server: push everything. */
    suspend fun markAllDirty() = dao.markAllDirty()

    suspend fun eraseAll() {
        dao.clearRecords(); dao.clearGroups(); dao.clearKv()
    }
}

fun EntryKind.isOutflow() = this == EntryKind.expense
