package com.example.expensetracker.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** A local row number paired with the id the server knows it by. */
data class IdPair(val id: Long, val remoteId: String)

/**
 * Everything syncing needs that the ordinary screens do not.
 *
 * Tombstoned rows are deliberately *not* filtered out here. A deleted row is exactly what has to
 * be pushed, and a deleted row on the server is exactly what has to be applied locally, so this is
 * the one place that has to see the whole table.
 */
@Dao
interface SyncDao {

    // Rows with local changes the server has not taken yet.

    @Query("SELECT * FROM expenses WHERE syncedAt IS NULL")
    suspend fun expensesToPush(): List<Expense>

    @Query("SELECT * FROM people WHERE syncedAt IS NULL")
    suspend fun peopleToPush(): List<Person>

    @Query("SELECT * FROM categories WHERE syncedAt IS NULL")
    suspend fun categoriesToPush(): List<Category>

    @Query("SELECT * FROM expense_splits WHERE syncedAt IS NULL")
    suspend fun splitsToPush(): List<ExpenseSplit>

    @Query("SELECT COUNT(*) FROM expenses WHERE syncedAt IS NULL")
    fun observePendingExpenses(): Flow<Int>

    // Finding the local row a pulled one refers to.

    @Query("SELECT * FROM expenses WHERE remoteId = :remoteId LIMIT 1")
    suspend fun expenseByRemoteId(remoteId: String): Expense?

    @Query("SELECT * FROM people WHERE remoteId = :remoteId LIMIT 1")
    suspend fun personByRemoteId(remoteId: String): Person?

    @Query("SELECT * FROM categories WHERE remoteId = :remoteId LIMIT 1")
    suspend fun categoryByRemoteId(remoteId: String): Category?

    @Query("SELECT * FROM expense_splits WHERE remoteId = :remoteId LIMIT 1")
    suspend fun splitByRemoteId(remoteId: String): ExpenseSplit?

    // Translating between the two numbering schemes, for splits.

    @Query("SELECT id, remoteId FROM expenses")
    suspend fun expenseIds(): List<IdPair>

    @Query("SELECT id, remoteId FROM people")
    suspend fun personIds(): List<IdPair>

    // Writing a pulled row. REPLACE would renumber the row and break its splits, so an insert and
    // an update are kept apart and the caller decides which it is doing.

    @Insert suspend fun insertExpense(expense: Expense): Long
    @Insert suspend fun insertPerson(person: Person): Long
    @Insert suspend fun insertCategory(category: Category): Long
    @Insert suspend fun insertSplit(split: ExpenseSplit): Long

    @Update suspend fun updateExpense(expense: Expense)
    @Update suspend fun updatePerson(person: Person)
    @Update suspend fun updateCategory(category: Category)
    @Update suspend fun updateSplit(split: ExpenseSplit)

    // Marking a pushed row as settled with the server.

    @Query("UPDATE expenses SET syncedAt = :at WHERE id IN (:ids)")
    suspend fun markExpensesSynced(ids: List<Long>, at: Long)

    @Query("UPDATE people SET syncedAt = :at WHERE id IN (:ids)")
    suspend fun markPeopleSynced(ids: List<Long>, at: Long)

    @Query("UPDATE categories SET syncedAt = :at WHERE id IN (:ids)")
    suspend fun markCategoriesSynced(ids: List<Long>, at: Long)

    @Query("UPDATE expense_splits SET syncedAt = :at WHERE id IN (:ids)")
    suspend fun markSplitsSynced(ids: List<Long>, at: Long)

    /** Everything belonging to the previous account, on signing out or signing in as someone else. */
    @Query("UPDATE expenses SET syncedAt = NULL")
    suspend fun markAllExpensesUnsynced()

    @Query("UPDATE people SET syncedAt = NULL")
    suspend fun markAllPeopleUnsynced()

    @Query("UPDATE categories SET syncedAt = NULL")
    suspend fun markAllCategoriesUnsynced()

    @Query("UPDATE expense_splits SET syncedAt = NULL")
    suspend fun markAllSplitsUnsynced()

    // Conflicts.

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun recordConflict(conflict: SyncConflict)

    @Query("SELECT * FROM sync_conflicts ORDER BY detectedAt DESC")
    fun observeConflicts(): Flow<List<SyncConflict>>

    @Query("SELECT COUNT(*) FROM sync_conflicts")
    fun observeConflictCount(): Flow<Int>

    @Query("SELECT * FROM sync_conflicts WHERE id = :id")
    suspend fun conflict(id: Long): SyncConflict?

    @Query("DELETE FROM sync_conflicts WHERE id = :id")
    suspend fun clearConflict(id: Long)

    @Query("DELETE FROM sync_conflicts")
    suspend fun clearAllConflicts()
}
