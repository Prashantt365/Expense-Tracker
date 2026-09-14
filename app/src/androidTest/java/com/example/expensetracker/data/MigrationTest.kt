package com.example.expensetracker.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The first real migration this app has had, and the one that decides whether anybody's history
 * survives the move to sync.
 *
 * The version 2 database is built here by hand rather than from an exported schema, which keeps
 * the test free of a room-testing dependency and of a schema file that would have to be kept
 * truthful by hand. What matters is covered either way: opening the migrated file through Room is
 * itself the assertion that the schema came out as the entities describe it, because Room checks
 * and throws if it did not.
 */
class MigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "migration-v2-to-v3.db"

    @Before fun clean() = context.deleteDatabase(name).let { }
    @After fun tidy() = context.deleteDatabase(name).let { }

    /** Exactly what Room generated at version 2, with a row in every table that now syncs. */
    private fun createVersion2() {
        val db = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null)
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `expenses` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`amountPaise` INTEGER NOT NULL, `category` TEXT NOT NULL, `note` TEXT NOT NULL, " +
                "`merchant` TEXT NOT NULL, `paidAt` INTEGER NOT NULL, `sourceUri` TEXT)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `categories` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, `sortOrder` INTEGER NOT NULL)"
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_categories_name` ON `categories` (`name`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `people` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, `note` TEXT NOT NULL)"
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_people_name` ON `people` (`name`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `expense_splits` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`expenseId` INTEGER NOT NULL, `personId` INTEGER, `amountPaise` INTEGER NOT NULL, " +
                "`settledAt` INTEGER, FOREIGN KEY(`expenseId`) REFERENCES `expenses`(`id`) ON DELETE CASCADE, " +
                "FOREIGN KEY(`personId`) REFERENCES `people`(`id`) ON DELETE CASCADE)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_expense_splits_expenseId` ON `expense_splits` (`expenseId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_expense_splits_personId` ON `expense_splits` (`personId`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `attachments` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`expenseId` INTEGER NOT NULL, `path` TEXT NOT NULL, `addedAt` INTEGER NOT NULL, " +
                "FOREIGN KEY(`expenseId`) REFERENCES `expenses`(`id`) ON DELETE CASCADE)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_attachments_expenseId` ON `attachments` (`expenseId`)")

        db.execSQL(
            "INSERT INTO expenses (amountPaise, category, note, merchant, paidAt, sourceUri) VALUES " +
                "(18200, 'Food', 'pizza', 'Swiggy', 1757689440000, NULL), " +
                "(45000, 'Transport', '', 'Uber', 1757600000000, 'content://shared/1'), " +
                "(99900, 'Bills', '', 'Airtel', 1757500000000, NULL)"
        )
        db.execSQL("INSERT INTO people (name, note) VALUES ('Rahul Sharma', ''), ('Priya', '')")
        db.execSQL("INSERT INTO categories (name, sortOrder) VALUES ('Food', 0), ('Transport', 1), ('Bills', 2)")
        db.execSQL(
            "INSERT INTO expense_splits (expenseId, personId, amountPaise, settledAt) VALUES " +
                "(1, 1, 9100, NULL), (1, NULL, 9100, NULL), (2, 2, 22500, NULL), (2, NULL, 22500, NULL)"
        )
        db.version = 2
        db.close()
    }

    private fun openMigrated(): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(AppDatabase.MIGRATION_2_3)
            .build()

    @Test fun keepsEveryRowAndSatisfiesRoomsOwnSchemaCheck() = runBlocking {
        createVersion2()
        val db = openMigrated()
        // Reading is what forces the upgrade, and Room validates the result as it opens.
        val expenses = db.expenseDao().observeAll().first()
        assertEquals(3, expenses.size)

        val swiggy = expenses.single { it.expense.merchant == "Swiggy" }
        assertEquals(18200, swiggy.expense.amountPaise)
        assertEquals("pizza", swiggy.expense.note)
        assertEquals(2, swiggy.splits.size)
        assertEquals(2, db.personDao().observeAll().first().size)
        db.close()
    }

    @Test fun givesEveryExistingRowAnIdentityOfItsOwn() = runBlocking {
        createVersion2()
        val db = openMigrated()
        val ids = db.expenseDao().observeAll().first().map { it.expense.remoteId }

        // A backfill that evaluated the expression once would hand every row the same id, and the
        // unique index the migration creates would have rejected it.
        assertEquals(ids.size, ids.distinct().size)
        ids.forEach { id ->
            assertEquals("expected a uuid, got '$id'", 36, id.length)
            assertNotEquals("", id)
        }
        db.close()
    }

    @Test fun leavesExistingRowsLookingRecentlyEditedRatherThanAncient() = runBlocking {
        createVersion2()
        val db = openMigrated()
        val stamps = db.expenseDao().observeAll().first().map { it.expense.updatedAt }

        // updatedAt decides which side of a conflict wins. A row left at the epoch would lose to
        // anything at all on the server, including a stale copy of itself.
        stamps.forEach { assertTrue("updatedAt was left at $it", it > 1_700_000_000_000L) }
        db.expenseDao().observeAll().first().forEach {
            assertEquals(null, it.expense.deletedAt)
            assertEquals(null, it.expense.syncedAt)
        }
        db.close()
    }
}
