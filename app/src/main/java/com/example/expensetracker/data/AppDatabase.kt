package com.example.expensetracker.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Expense::class, Category::class, Person::class, ExpenseSplit::class, Attachment::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun expenseDao(): ExpenseDao
    abstract fun categoryDao(): CategoryDao
    abstract fun personDao(): PersonDao

    companion object {
        val DEFAULT_CATEGORIES = listOf("Food", "Transport", "Bills", "Shopping", "Health", "Other")

        /**
         * Seeds the pick-list whenever it is found empty, so the app is never left with no
         * categories to choose from.
         *
         * This has to run in onOpen rather than the more obvious hooks. onCreate fires only when
         * the file is first created, which misses the rebuild that fallbackToDestructiveMigration
         * performs on an existing install; and onDestructiveMigration is invoked from inside
         * dropAllTables, before the tables are recreated, so an insert there fails outright.
         *
         * The trade-off is that deleting every category brings the defaults back on next launch.
         * That is preferable to stranding the user with a picker they cannot choose from.
         */
        val seedCategories = object : RoomDatabase.Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                val empty = db.query("SELECT COUNT(*) FROM categories").use { cursor ->
                    cursor.moveToFirst() && cursor.getInt(0) == 0
                }
                if (!empty) return
                DEFAULT_CATEGORIES.forEachIndexed { index, name ->
                    db.execSQL(
                        "INSERT OR IGNORE INTO categories (name, sortOrder) VALUES (?, ?)",
                        arrayOf(name, index)
                    )
                }
            }
        }
    }
}
