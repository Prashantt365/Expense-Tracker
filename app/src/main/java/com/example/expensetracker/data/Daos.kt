package com.example.expensetracker.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ExpenseDao {
    @Transaction
    @Query("SELECT * FROM expenses ORDER BY paidAt DESC")
    fun observeAll(): Flow<List<ExpenseDetails>>

    @Insert
    suspend fun insert(expense: Expense): Long

    @Update
    suspend fun update(expense: Expense)

    @Delete
    suspend fun delete(expense: Expense)

    @Query("SELECT * FROM expenses WHERE id = :id")
    suspend fun byId(id: Long): Expense?

    /**
     * Sharing the same receipt twice is the case worth catching, so an exact match on the source
     * screenshot counts as a duplicate no matter how much later it arrives.
     */
    @Query("SELECT * FROM expenses WHERE sourceUri = :sourceUri AND id != :ignoreId LIMIT 1")
    suspend fun findBySource(sourceUri: String, ignoreId: Long): Expense?

    /** Otherwise the same amount to the same payee inside [windowMillis] is a likely re-entry. */
    @Query(
        """
        SELECT * FROM expenses
        WHERE amountPaise = :amountPaise
          AND LOWER(TRIM(merchant)) = LOWER(TRIM(:merchant))
          AND ABS(paidAt - :paidAt) <= :windowMillis
          AND id != :ignoreId
        LIMIT 1
        """
    )
    suspend fun findSimilar(amountPaise: Long, merchant: String, paidAt: Long, windowMillis: Long, ignoreId: Long): Expense?

    @Insert
    suspend fun insertSplits(splits: List<ExpenseSplit>)

    @Query("DELETE FROM expense_splits WHERE expenseId = :expenseId")
    suspend fun clearSplits(expenseId: Long)

    @Query("UPDATE expense_splits SET settledAt = :now WHERE id = :splitId AND settledAt IS NULL")
    suspend fun settleShare(splitId: Long, now: Long)

    @Query("UPDATE expense_splits SET settledAt = :now WHERE personId = :personId AND settledAt IS NULL")
    suspend fun settleEverything(personId: Long, now: Long)

    @Query("UPDATE expense_splits SET settledAt = NULL WHERE personId = :personId")
    suspend fun reopenEverything(personId: Long)

    @Insert
    suspend fun insertAttachments(attachments: List<Attachment>)

    @Query("SELECT * FROM attachments WHERE expenseId = :expenseId")
    suspend fun attachmentsFor(expenseId: Long): List<Attachment>

    @Query("DELETE FROM attachments WHERE id IN (:ids)")
    suspend fun deleteAttachments(ids: List<Long>)

    @Query(
        """
        SELECT p.id AS personId, p.name AS name,
               COALESCE(SUM(CASE WHEN s.settledAt IS NULL THEN s.amountPaise ELSE 0 END), 0) AS owedPaise
        FROM people p
        LEFT JOIN expense_splits s ON s.personId = p.id
        GROUP BY p.id, p.name
        ORDER BY owedPaise DESC, p.name
        """
    )
    fun observeBalances(): Flow<List<PersonBalance>>

    @Query(
        """
        SELECT s.id AS splitId, s.expenseId AS expenseId, s.amountPaise AS amountPaise,
               e.merchant AS merchant, e.category AS category, e.paidAt AS paidAt
        FROM expense_splits s
        JOIN expenses e ON e.id = s.expenseId
        WHERE s.personId = :personId AND s.settledAt IS NULL
        ORDER BY e.paidAt DESC
        """
    )
    fun observeOutstanding(personId: Long): Flow<List<OutstandingShare>>
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY sortOrder, name")
    fun observeAll(): Flow<List<Category>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(category: Category): Long

    @Update
    suspend fun update(category: Category)

    @Delete
    suspend fun delete(category: Category)

    /** Keeps already-recorded expenses pointing at the renamed category. */
    @Query("UPDATE expenses SET category = :newName WHERE category = :oldName")
    suspend fun renameOnExpenses(oldName: String, newName: String)

    @Query("SELECT COUNT(*) FROM expenses WHERE category = :name")
    suspend fun expenseCount(name: String): Int
}

@Dao
interface PersonDao {
    @Query("SELECT * FROM people ORDER BY name")
    fun observeAll(): Flow<List<Person>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(person: Person): Long

    @Update
    suspend fun update(person: Person)

    @Delete
    suspend fun delete(person: Person)

    @Query("SELECT COUNT(*) FROM expense_splits WHERE personId = :id AND settledAt IS NULL")
    suspend fun outstandingCount(id: Long): Int
}
