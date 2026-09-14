package com.example.expensetracker.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        Expense::class, Category::class, Person::class, ExpenseSplit::class, Attachment::class,
        SyncConflict::class
    ],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun expenseDao(): ExpenseDao
    abstract fun categoryDao(): CategoryDao
    abstract fun personDao(): PersonDao
    abstract fun syncDao(): SyncDao

    companion object {
        val DEFAULT_CATEGORIES = listOf("Food", "Transport", "Bills", "Shopping", "Health", "Other")

        /** The tables that leave the device. Attachments deliberately do not. */
        private val SYNCED_TABLES = listOf("expenses", "categories", "people", "expense_splits")

        /**
         * A version 4 UUID built out of SQLite's own primitives, since it has no uuid() of its own.
         *
         * randomblob() is re-evaluated for every row an UPDATE touches, which is the reason this
         * backfills a distinct id per row rather than one value repeated across the table -- and a
         * repeat would fail the unique index the migration goes on to create.
         */
        private const val UUID_EXPRESSION =
            "lower(hex(randomblob(4))) || '-' || lower(hex(randomblob(2))) || '-4' || " +
                "substr(lower(hex(randomblob(2))), 2) || '-' || " +
                "substr('89ab', abs(random()) % 4 + 1, 1) || " +
                "substr(lower(hex(randomblob(2))), 2) || '-' || lower(hex(randomblob(6)))"

        /**
         * Gives every existing row the identity it needs to be synced.
         *
         * Existing rows are stamped with the migration time rather than 0. updatedAt decides which
         * side of a conflict is newer, and a row left at the epoch would lose to anything the
         * server held, including a stale copy.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val now = System.currentTimeMillis()
                SYNCED_TABLES.forEach { table ->
                    db.execSQL("ALTER TABLE `$table` ADD COLUMN `remoteId` TEXT NOT NULL DEFAULT ''")
                    db.execSQL("ALTER TABLE `$table` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE `$table` ADD COLUMN `deletedAt` INTEGER")
                    db.execSQL("ALTER TABLE `$table` ADD COLUMN `syncedAt` INTEGER")
                    db.execSQL(
                        "UPDATE `$table` SET `remoteId` = ($UUID_EXPRESSION), `updatedAt` = $now " +
                            "WHERE `remoteId` = ''"
                    )
                    db.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS `index_${table}_remoteId` " +
                            "ON `$table` (`remoteId`)"
                    )
                }
            }
        }

        /**
         * Somewhere to park a row that changed on both sides at once.
         *
         * The index is unique on the row a conflict is about, so the same disagreement found again
         * on the next sync replaces the entry rather than stacking up another copy of it.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sync_conflicts` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`entity` TEXT NOT NULL, `remoteId` TEXT NOT NULL, " +
                        "`localJson` TEXT NOT NULL, `remoteJson` TEXT NOT NULL, " +
                        "`localUpdatedAt` INTEGER NOT NULL, `remoteUpdatedAt` INTEGER NOT NULL, " +
                        "`detectedAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_conflicts_entity_remoteId` " +
                        "ON `sync_conflicts` (`entity`, `remoteId`)"
                )
            }
        }

        /**
         * Seeds the pick-list whenever it is found empty, so the app is never left with no
         * categories to choose from.
         *
         * This has to run in onOpen rather than the more obvious hooks. onCreate fires only when
         * the file is first created, which misses the rebuild that a destructive migration
         * performs on an existing install; and onDestructiveMigration is invoked from inside
         * dropAllTables, before the tables are recreated, so an insert there fails outright.
         *
         * The trade-off is that deleting every category brings the defaults back on next launch.
         * That is preferable to stranding the user with a picker they cannot choose from.
         */
        val seedCategories = object : RoomDatabase.Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                val empty = db.query("SELECT COUNT(*) FROM categories WHERE deletedAt IS NULL").use { cursor ->
                    cursor.moveToFirst() && cursor.getInt(0) == 0
                }
                if (!empty) return
                val now = System.currentTimeMillis()
                DEFAULT_CATEGORIES.forEachIndexed { index, name ->
                    db.execSQL(
                        "INSERT OR IGNORE INTO categories (name, sortOrder, remoteId, updatedAt) " +
                            "VALUES (?, ?, ($UUID_EXPRESSION), ?)",
                        arrayOf(name, index, now)
                    )
                }
            }
        }
    }
}
