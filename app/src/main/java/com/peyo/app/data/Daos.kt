package com.peyo.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Deleting an expense, a person or a category marks it rather than removes it, so that the removal
 * can reach another device: a row dropped outright leaves nothing to say it ever existed, and the
 * next push from a phone that still has it would put it back. Every read therefore has to exclude
 * the marked rows, which is what the deletedAt clauses below are doing.
 *
 * Splits are the exception. One belongs entirely to its expense and the editor already replaces
 * the whole set on every save, so a split is removed outright and the set is pushed as a set. That
 * keeps the wholesale-replace the editor already does from turning into a stream of tombstones.
 */
@Dao
interface ExpenseDao {
    @Transaction
    @Query("SELECT * FROM expenses WHERE deletedAt IS NULL ORDER BY paidAt DESC")
    fun observeAll(): Flow<List<ExpenseDetails>>

    @Insert
    suspend fun insert(expense: Expense): Long

    @Update
    suspend fun update(expense: Expense)

    /** Deleting is a write, so it can be pushed. syncedAt is cleared to queue it. */
    @Query("UPDATE expenses SET deletedAt = :now, updatedAt = :now, syncedAt = NULL WHERE id = :id")
    suspend fun tombstone(id: Long, now: Long)

    @Query("SELECT * FROM expenses WHERE id = :id")
    suspend fun byId(id: Long): Expense?

    /**
     * Sharing the same receipt twice is the case worth catching, so an exact match on the source
     * screenshot counts as a duplicate no matter how much later it arrives.
     */
    @Query("SELECT * FROM expenses WHERE sourceUri = :sourceUri AND id != :ignoreId AND deletedAt IS NULL LIMIT 1")
    suspend fun findBySource(sourceUri: String, ignoreId: Long): Expense?

    /** Otherwise the same amount to the same payee inside [windowMillis] is a likely re-entry. */
    @Query(
        """
        SELECT * FROM expenses
        WHERE amountPaise = :amountPaise
          AND LOWER(TRIM(merchant)) = LOWER(TRIM(:merchant))
          AND ABS(paidAt - :paidAt) <= :windowMillis
          AND id != :ignoreId
          AND deletedAt IS NULL
        LIMIT 1
        """
    )
    suspend fun findSimilar(amountPaise: Long, merchant: String, paidAt: Long, windowMillis: Long, ignoreId: Long): Expense?

    @Insert
    suspend fun insertSplits(splits: List<ExpenseSplit>)

    @Query("DELETE FROM expense_splits WHERE expenseId = :expenseId")
    suspend fun clearSplits(expenseId: Long)

    @Query(
        "UPDATE expense_splits SET settledAt = :now, updatedAt = :now, syncedAt = NULL " +
            "WHERE id = :splitId AND settledAt IS NULL"
    )
    suspend fun settleShare(splitId: Long, now: Long)

    @Query(
        "UPDATE expense_splits SET settledAt = :now, updatedAt = :now, syncedAt = NULL " +
            "WHERE personId = :personId AND settledAt IS NULL"
    )
    suspend fun settleEverything(personId: Long, now: Long)

    @Query(
        "UPDATE expense_splits SET settledAt = NULL, updatedAt = :now, syncedAt = NULL " +
            "WHERE personId = :personId AND settledAt IS NOT NULL"
    )
    suspend fun reopenEverything(personId: Long, now: Long)

    @Insert
    suspend fun insertAttachments(attachments: List<Attachment>)

    @Query("SELECT * FROM attachments WHERE expenseId = :expenseId")
    suspend fun attachmentsFor(expenseId: Long): List<Attachment>

    @Query("DELETE FROM attachments WHERE id IN (:ids)")
    suspend fun deleteAttachments(ids: List<Long>)

    /**
     * A split whose expense has been deleted is excluded by the EXISTS clause rather than by a
     * join, so that a person with no splits at all still comes back with a zero balance.
     */
    @Query(
        """
        SELECT p.id AS personId, p.name AS name,
               COALESCE(SUM(CASE WHEN s.settledAt IS NULL THEN s.amountPaise ELSE 0 END), 0) AS owedPaise
        FROM people p
        LEFT JOIN expense_splits s
               ON s.personId = p.id
              AND EXISTS (SELECT 1 FROM expenses e WHERE e.id = s.expenseId AND e.deletedAt IS NULL)
        WHERE p.deletedAt IS NULL
        GROUP BY p.id, p.name
        ORDER BY owedPaise DESC, p.name
        """
    )
    fun observeBalances(): Flow<List<PersonBalance>>

    /**
     * The one-shot reads the home screen widgets use, in place of collecting the observable
     * queries above.
     *
     * A widget is composed once, off the main thread, and handed to the launcher as a finished
     * set of RemoteViews; there is nothing there to observe with. Taking the first emission of a
     * Flow instead was the wrong shape twice over: it leaves the widget waiting on Room's
     * invalidation tracker for a value it only needs once, and if that first emission never
     * arrives the widget never composes at all and sits on its placeholder layout for good.
     *
     * They are also narrower than the screens' queries. The widget shows this month and the last
     * few rows, so that is what it asks for, rather than loading every expense ever recorded with
     * all of its splits and attachments to add up one month of them.
     */
    @Transaction
    @Query("SELECT * FROM expenses WHERE deletedAt IS NULL AND paidAt >= :from ORDER BY paidAt DESC")
    suspend fun detailsSince(from: Long): List<ExpenseDetails>

    @Transaction
    @Query("SELECT * FROM expenses WHERE deletedAt IS NULL ORDER BY paidAt DESC LIMIT :limit")
    suspend fun recentDetails(limit: Int): List<ExpenseDetails>

    @Query(
        """
        SELECT p.id AS personId, p.name AS name,
               COALESCE(SUM(CASE WHEN s.settledAt IS NULL THEN s.amountPaise ELSE 0 END), 0) AS owedPaise
        FROM people p
        LEFT JOIN expense_splits s
               ON s.personId = p.id
              AND EXISTS (SELECT 1 FROM expenses e WHERE e.id = s.expenseId AND e.deletedAt IS NULL)
        WHERE p.deletedAt IS NULL
        GROUP BY p.id, p.name
        ORDER BY owedPaise DESC, p.name
        """
    )
    suspend fun balancesNow(): List<PersonBalance>

    @Query(
        """
        SELECT s.id AS splitId, s.expenseId AS expenseId, s.amountPaise AS amountPaise,
               e.merchant AS merchant, e.category AS category, e.paidAt AS paidAt
        FROM expense_splits s
        JOIN expenses e ON e.id = s.expenseId
        WHERE s.personId = :personId AND s.settledAt IS NULL AND e.deletedAt IS NULL
        ORDER BY e.paidAt DESC
        """
    )
    fun observeOutstanding(personId: Long): Flow<List<OutstandingShare>>
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories WHERE deletedAt IS NULL ORDER BY sortOrder, name")
    fun observeAll(): Flow<List<Category>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(category: Category): Long

    @Update
    suspend fun update(category: Category)

    @Query("UPDATE categories SET deletedAt = :now, updatedAt = :now, syncedAt = NULL WHERE id = :id")
    suspend fun tombstone(id: Long, now: Long)

    /** Keeps already-recorded expenses pointing at the renamed category. */
    @Query(
        "UPDATE expenses SET category = :newName, updatedAt = :now, syncedAt = NULL " +
            "WHERE category = :oldName AND deletedAt IS NULL"
    )
    suspend fun renameOnExpenses(oldName: String, newName: String, now: Long)

    @Query("SELECT COUNT(*) FROM expenses WHERE category = :name AND deletedAt IS NULL")
    suspend fun expenseCount(name: String): Int

    /** A name freed by a tombstone has to be reclaimable, since the index does not know about it. */
    @Query("SELECT * FROM categories WHERE name = :name AND deletedAt IS NOT NULL LIMIT 1")
    suspend fun deletedByName(name: String): Category?
}

@Dao
interface PersonDao {
    @Query("SELECT * FROM people WHERE deletedAt IS NULL ORDER BY name")
    fun observeAll(): Flow<List<Person>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(person: Person): Long

    @Update
    suspend fun update(person: Person)

    @Query("UPDATE people SET deletedAt = :now, updatedAt = :now, syncedAt = NULL WHERE id = :id")
    suspend fun tombstone(id: Long, now: Long)

    @Query(
        """
        SELECT COUNT(*) FROM expense_splits s
        JOIN expenses e ON e.id = s.expenseId
        WHERE s.personId = :id AND s.settledAt IS NULL AND e.deletedAt IS NULL
        """
    )
    suspend fun outstandingCount(id: Long): Int

    @Query("SELECT * FROM people WHERE name = :name AND deletedAt IS NOT NULL LIMIT 1")
    suspend fun deletedByName(name: String): Person?

    @Query("SELECT * FROM people WHERE name = :name AND deletedAt IS NULL LIMIT 1")
    suspend fun activeByName(name: String): Person?
}
