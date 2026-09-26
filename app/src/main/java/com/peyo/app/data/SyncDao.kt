package com.peyo.app.data

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
    //
    // A row with an open conflict is held back until the user has chosen. Pushing it would
    // overwrite the server's copy -- the very version the conflict screen is offering to keep --
    // while that screen says nothing changes until a choice is made. The entity names are
    // SyncedTable.local, spelled out because a query has to be a constant.

    @Query(
        "SELECT * FROM expenses WHERE syncedAt IS NULL " +
            "AND remoteId NOT IN (SELECT remoteId FROM sync_conflicts WHERE entity = 'expense')"
    )
    suspend fun expensesToPush(): List<Expense>

    @Query(
        "SELECT * FROM people WHERE syncedAt IS NULL " +
            "AND remoteId NOT IN (SELECT remoteId FROM sync_conflicts WHERE entity = 'person')"
    )
    suspend fun peopleToPush(): List<Person>

    @Query(
        "SELECT * FROM categories WHERE syncedAt IS NULL " +
            "AND remoteId NOT IN (SELECT remoteId FROM sync_conflicts WHERE entity = 'category')"
    )
    suspend fun categoriesToPush(): List<Category>

    @Query(
        "SELECT * FROM expense_splits WHERE syncedAt IS NULL " +
            "AND remoteId NOT IN (SELECT remoteId FROM sync_conflicts WHERE entity = 'split')"
    )
    suspend fun splitsToPush(): List<ExpenseSplit>

    @Query("SELECT COUNT(*) FROM expenses WHERE syncedAt IS NULL")
    fun observePendingExpenses(): Flow<Int>

    /**
     * How much is waiting to go up, across every table that goes up.
     *
     * This is what automatic backup watches. Counting only the expenses would leave a renamed
     * person or a new category sitting unsent until something else happened to trigger a run.
     * Rows held back by a conflict are not counted: they are waiting on the user, not on a sync,
     * and counting them would schedule runs that cannot send them.
     */
    @Query(
        """
        SELECT (SELECT COUNT(*) FROM expenses       WHERE syncedAt IS NULL AND remoteId NOT IN
                  (SELECT remoteId FROM sync_conflicts WHERE entity = 'expense'))
             + (SELECT COUNT(*) FROM people         WHERE syncedAt IS NULL AND remoteId NOT IN
                  (SELECT remoteId FROM sync_conflicts WHERE entity = 'person'))
             + (SELECT COUNT(*) FROM categories     WHERE syncedAt IS NULL AND remoteId NOT IN
                  (SELECT remoteId FROM sync_conflicts WHERE entity = 'category'))
             + (SELECT COUNT(*) FROM expense_splits WHERE syncedAt IS NULL AND remoteId NOT IN
                  (SELECT remoteId FROM sync_conflicts WHERE entity = 'split'))
        """
    )
    fun observePendingCount(): Flow<Int>

    // Finding the local row a pulled one refers to.

    @Query("SELECT * FROM expenses WHERE remoteId = :remoteId LIMIT 1")
    suspend fun expenseByRemoteId(remoteId: String): Expense?

    @Query("SELECT * FROM people WHERE remoteId = :remoteId LIMIT 1")
    suspend fun personByRemoteId(remoteId: String): Person?

    @Query("SELECT * FROM categories WHERE remoteId = :remoteId LIMIT 1")
    suspend fun categoryByRemoteId(remoteId: String): Category?

    @Query("SELECT * FROM expense_splits WHERE remoteId = :remoteId LIMIT 1")
    suspend fun splitByRemoteId(remoteId: String): ExpenseSplit?

    /**
     * The other way a pulled row can already be here: under the same name but a different id.
     *
     * A fresh install seeds its own categories, and a phone that has never synced mints its own
     * ids for the people on it, so the server's "Food" and this device's "Food" are the same
     * category with two different UUIDs. Inserting the pulled one would break the unique index on
     * the name -- which is the crash a first restore after a reinstall used to end in -- so the
     * local row adopts the server's identity instead. Tombstoned rows are included deliberately:
     * a deleted name still occupies the index.
     */
    @Query("SELECT * FROM categories WHERE name = :name LIMIT 1")
    suspend fun categoryByName(name: String): Category?

    @Query("SELECT * FROM people WHERE name = :name LIMIT 1")
    suspend fun personByName(name: String): Person?

    // Translating between the two numbering schemes, for splits.

    @Query("SELECT id, remoteId FROM expenses")
    suspend fun expenseIds(): List<IdPair>

    @Query("SELECT id, remoteId FROM people")
    suspend fun personIds(): List<IdPair>

    // Reading the whole of a table, for a local backup file. Tombstones are included: a backup
    // that dropped them would resurrect everything the user has deleted when it was imported.

    @Query("SELECT * FROM expenses") suspend fun allExpenses(): List<Expense>
    @Query("SELECT * FROM people") suspend fun allPeople(): List<Person>
    @Query("SELECT * FROM categories") suspend fun allCategories(): List<Category>
    @Query("SELECT * FROM expense_splits") suspend fun allSplits(): List<ExpenseSplit>

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

    // Marking a pushed row as settled with the server, in bulk. The engine no longer uses these: it
    // settles each row through the update methods above, so it can check the row is still the
    // version it sent and give it the server's stamp.

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
