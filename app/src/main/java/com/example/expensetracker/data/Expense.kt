package com.example.expensetracker.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "expenses")
data class Expense(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val amountPaise: Long,
    val category: String,
    val note: String,
    val merchant: String,
    val paidAt: Long,
    val receiptUri: String? = null
)

@androidx.room.Dao
interface ExpenseDao {
    @androidx.room.Query("SELECT * FROM expenses ORDER BY paidAt DESC")
    fun observeAll(): kotlinx.coroutines.flow.Flow<List<Expense>>

    @androidx.room.Insert
    suspend fun insert(expense: Expense)

    @androidx.room.Delete
    suspend fun delete(expense: Expense)
}

@androidx.room.Database(entities = [Expense::class], version = 1, exportSchema = false)
abstract class AppDatabase : androidx.room.RoomDatabase() {
    abstract fun expenseDao(): ExpenseDao
}
