package com.arthatis.wallet.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val ledgerJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

@Serializable enum class WalletKind { rekening, tunai, ewallet, investasi }
@Serializable enum class EntryKind { expense, income, transfer }
@Serializable enum class Frequency { daily, weekly, monthly, yearly }

/** Every synced record carries these. Delete = [deletedAt] set. */
interface Syncable {
    val id: String
    val updatedAt: Long
    val deletedAt: Long?
}

@Serializable
data class Wallet(
    override val id: String,
    val name: String,
    val kind: WalletKind,
    /** Fixed at creation: 1 wallet = 1 currency. */
    val currency: String,
    val openingMinor: Long = 0,
    val position: Int = 0,
    override val updatedAt: Long,
    override val deletedAt: Long? = null,
) : Syncable

@Serializable
data class Category(
    override val id: String,
    /** Set for design defaults; they can be hidden but not deleted. */
    val key: String? = null,
    val name: String,
    val kind: EntryKind,
    val color: String,
    val hidden: Boolean = false,
    val position: Int = 0,
    override val updatedAt: Long,
    override val deletedAt: Long? = null,
) : Syncable

@Serializable
data class Tx(
    override val id: String,
    val walletId: String,
    val toWalletId: String? = null,
    val categoryId: String? = null,
    val kind: EntryKind,
    /** In the source wallet currency. */
    val amountMinor: Long,
    /** Transfers only: what the destination wallet receives, in its own currency. */
    val toAmountMinor: Long? = null,
    /** Transfers across currencies: the pair rate used, e.g. `16450`. */
    val rate: String? = null,
    val note: String = "",
    val occurredAt: Long,
    val recurringId: String? = null,
    override val updatedAt: Long,
    override val deletedAt: Long? = null,
) : Syncable

@Serializable
data class Recurring(
    override val id: String,
    val name: String,
    val walletId: String,
    val categoryId: String,
    val kind: EntryKind,
    val amountMinor: Long,
    val frequency: Frequency,
    val dayOfMonth: Int? = null,
    val weekday: Int? = null,
    val month: Int? = null,
    val reminder: Boolean = true,
    val paused: Boolean = false,
    val lastRunDay: Long? = null,
    override val updatedAt: Long,
    override val deletedAt: Long? = null,
) : Syncable

/** Manual rate for a pair such as `USD/IDR`. Absent or deleted = use the API. */
@Serializable
data class RateOverride(
    override val id: String,
    val rate: String,
    override val updatedAt: Long,
    override val deletedAt: Long? = null,
) : Syncable

/** Monthly budget for one expense category, in the profile's primary currency. */
@Serializable
data class Budget(
    override val id: String,
    val categoryId: String,
    val monthlyMinor: Long,
    override val updatedAt: Long,
    override val deletedAt: Long? = null,
) : Syncable

/** Small synced settings: `profile` holds name + primary currency. */
@Serializable
data class Profile(
    override val id: String = "profile",
    val name: String,
    val primaryCurrency: String,
    override val updatedAt: Long,
    override val deletedAt: Long? = null,
) : Syncable

object Entity {
    const val WALLET = "wallet"
    const val CATEGORY = "category"
    const val TRANSACTION = "transaction"
    const val RECURRING = "recurring"
    const val RATE = "rate_override"
    const val SETTING = "setting"
    const val BUDGET = "budget"
}

data class DefaultCategory(val key: String, val name: String, val kind: EntryKind, val color: String)

/** Categories seen in the design (Makanan, Belanja, Tagihan, Transport, Hiburan, BBM, Gaji…). */
val defaultCategories = listOf(
    DefaultCategory("food", "Makanan", EntryKind.expense, "#1B6D35"),
    DefaultCategory("shopping", "Belanja", EntryKind.expense, "#6750A4"),
    DefaultCategory("bills", "Tagihan", EntryKind.expense, "#8B5000"),
    DefaultCategory("transport", "Transport", EntryKind.expense, "#3A646F"),
    DefaultCategory("fuel", "BBM", EntryKind.expense, "#00696E"),
    DefaultCategory("fun", "Hiburan", EntryKind.expense, "#B3261E"),
    DefaultCategory("home", "Rumah", EntryKind.expense, "#506352"),
    DefaultCategory("health", "Kesehatan", EntryKind.expense, "#6750A4"),
    DefaultCategory("salary", "Gaji", EntryKind.income, "#1B6D35"),
    DefaultCategory("other_income", "Pemasukan", EntryKind.income, "#3A646F"),
    DefaultCategory("transfer", "Transfer", EntryKind.transfer, "#717970"),
)
