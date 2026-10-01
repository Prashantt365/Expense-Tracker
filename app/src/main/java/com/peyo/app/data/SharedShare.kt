package com.peyo.app.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * A share somebody else recorded against my mobile number, as their account holds it.
 *
 * Read-only here and never synced up: it is the other person's row, kept on this phone only so it
 * can be shown offline and so the background check can tell a new one from one already seen. The
 * server's copy is the truth, and every refresh replaces these rows wholesale.
 */
@Entity(tableName = "shared_shares")
data class SharedShare(
    /** The other account's split id, which is what marking it paid names. */
    @PrimaryKey val splitId: String,
    val expenseId: String,
    val ownerId: String,
    /** Whoever recorded it, as their account is named. */
    val ownerName: String,
    val ownerPhone: String,
    val merchant: String,
    val category: String,
    val note: String,
    val paidAt: Long,
    val amountPaise: Long,
    val currency: String,
    /** True when I owe the owner; false when the owner recorded that I paid for them. */
    val iOwe: Boolean,
    val settledAt: Long?,
    /** The owner deleted the expense or the share, or took my number off the person. */
    val removed: Boolean,
    val updatedAt: Long,
    /** When a notification was shown for it; null until then. Kept across refreshes. */
    val notifiedAt: Long? = null
) {
    val open: Boolean get() = settledAt == null && !removed
}

@Dao
interface SharedShareDao {
    @Query("SELECT * FROM shared_shares WHERE removed = 0 ORDER BY paidAt DESC")
    fun observeVisible(): Flow<List<SharedShare>>

    @Query("SELECT * FROM shared_shares")
    suspend fun all(): List<SharedShare>

    @Upsert
    suspend fun upsert(rows: List<SharedShare>)

    @Query("DELETE FROM shared_shares WHERE splitId NOT IN (:keep)")
    suspend fun deleteAllExcept(keep: List<String>)

    @Query("DELETE FROM shared_shares")
    suspend fun clear()

    @Query("UPDATE shared_shares SET notifiedAt = :now WHERE splitId IN (:ids)")
    suspend fun markNotified(ids: List<String>, now: Long)
}
