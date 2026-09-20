package com.peyo.app.data

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import java.util.UUID

/**
 * What every row that leaves the device carries.
 *
 * [remoteId] rather than [id] is what names a row to the server. The local id is a SQLite
 * autoincrement, so two phones on the same account both start at 1 and would claim each other's
 * rows; a UUID minted where the row is created cannot collide.
 *
 * [deletedAt] is why deleting is a write rather than a removal. A row dropped outright on this
 * phone leaves nothing to tell another phone it ever went, and the next push from that phone
 * would put it straight back.
 *
 * [syncedAt] is null while a row has local changes the server has not accepted yet, which is what
 * the push looks for. [updatedAt] is set from the device clock on every local write and is what a
 * conflict is judged on.
 */
interface Synced {
    val remoteId: String
    val updatedAt: Long
    val deletedAt: Long?
    val syncedAt: Long?
}

fun newRemoteId(): String = UUID.randomUUID().toString()

@Entity(tableName = "expenses", indices = [Index(value = ["remoteId"], unique = true)])
data class Expense(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val amountPaise: Long,
    val category: String,
    val note: String,
    val merchant: String,
    val paidAt: Long,
    /**
     * The Uri of the screenshot this expense was created from, kept only to recognise the same
     * share arriving twice. The image itself lives in [Attachment]: a share grant does not outlive
     * the activity, so the Uri is not readable later.
     */
    val sourceUri: String? = null,
    // The defaults are declared so that the schema Room expects matches the one the migration
    // leaves behind. Adding a NOT NULL column to a populated table needs a DEFAULT, and Room
    // compares defaults when it validates, so omitting these here fails the upgrade at runtime.
    @ColumnInfo(defaultValue = "''") override val remoteId: String = newRemoteId(),
    @ColumnInfo(defaultValue = "0") override val updatedAt: Long = System.currentTimeMillis(),
    override val deletedAt: Long? = null,
    override val syncedAt: Long? = null
) : Synced

/** The editable pick-list. Expenses store their category by name so deleting one cannot orphan them. */
@Entity(
    tableName = "categories",
    indices = [Index(value = ["name"], unique = true), Index(value = ["remoteId"], unique = true)]
)
data class Category(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val sortOrder: Int = 0,
    // The defaults are declared so that the schema Room expects matches the one the migration
    // leaves behind. Adding a NOT NULL column to a populated table needs a DEFAULT, and Room
    // compares defaults when it validates, so omitting these here fails the upgrade at runtime.
    @ColumnInfo(defaultValue = "''") override val remoteId: String = newRemoteId(),
    @ColumnInfo(defaultValue = "0") override val updatedAt: Long = System.currentTimeMillis(),
    override val deletedAt: Long? = null,
    override val syncedAt: Long? = null
) : Synced

@Entity(
    tableName = "people",
    indices = [Index(value = ["name"], unique = true), Index(value = ["remoteId"], unique = true)]
)
data class Person(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val note: String = "",
    // The defaults are declared so that the schema Room expects matches the one the migration
    // leaves behind. Adding a NOT NULL column to a populated table needs a DEFAULT, and Room
    // compares defaults when it validates, so omitting these here fails the upgrade at runtime.
    @ColumnInfo(defaultValue = "''") override val remoteId: String = newRemoteId(),
    @ColumnInfo(defaultValue = "0") override val updatedAt: Long = System.currentTimeMillis(),
    override val deletedAt: Long? = null,
    override val syncedAt: Long? = null
) : Synced

@Entity(
    tableName = "expense_splits",
    foreignKeys = [
        ForeignKey(Expense::class, ["id"], ["expenseId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(Person::class, ["id"], ["personId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("expenseId"), Index("personId"), Index(value = ["remoteId"], unique = true)]
)
data class ExpenseSplit(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val expenseId: Long,
    /** null is my own share of the bill, which is never owed to anybody. */
    val personId: Long?,
    val amountPaise: Long,
    /** Null until the person pays me back. Balances only count unsettled shares. */
    val settledAt: Long? = null,
    // The defaults are declared so that the schema Room expects matches the one the migration
    // leaves behind. Adding a NOT NULL column to a populated table needs a DEFAULT, and Room
    // compares defaults when it validates, so omitting these here fails the upgrade at runtime.
    @ColumnInfo(defaultValue = "''") override val remoteId: String = newRemoteId(),
    @ColumnInfo(defaultValue = "0") override val updatedAt: Long = System.currentTimeMillis(),
    override val deletedAt: Long? = null,
    override val syncedAt: Long? = null
) : Synced

/** Which table a conflict is about. Stored as text so a new one cannot renumber the old ones. */
enum class SyncedTable(val local: String) {
    EXPENSE("expense"), PERSON("person"), CATEGORY("category"), SPLIT("split");

    companion object {
        fun of(stored: String): SyncedTable? = entries.firstOrNull { it.local == stored }
    }
}

/**
 * A row that changed on this phone and on the server since the two last agreed.
 *
 * Both versions are kept verbatim, as the JSON each side would have sent, rather than as a merged
 * row. Until the user has chosen there is no correct row to store, and writing either one into the
 * table would be the silent overwrite this whole mechanism exists to avoid. The local row stays
 * exactly as it was, so a conflict left unresolved costs nothing: the phone goes on showing what
 * its owner last typed.
 */
@Entity(
    tableName = "sync_conflicts",
    indices = [Index(value = ["entity", "remoteId"], unique = true)]
)
data class SyncConflict(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** One of [SyncedTable.local]. */
    val entity: String,
    val remoteId: String,
    val localJson: String,
    val remoteJson: String,
    val localUpdatedAt: Long,
    val remoteUpdatedAt: Long,
    val detectedAt: Long
) {
    val table: SyncedTable? get() = SyncedTable.of(entity)
}

/**
 * Attachments stay on the device by design: the screenshots are the bulky part and the part the
 * user is least likely to want on a server, so they are the one thing reinstalling does lose.
 */
@Entity(
    tableName = "attachments",
    foreignKeys = [ForeignKey(Expense::class, ["id"], ["expenseId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("expenseId")]
)
data class Attachment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val expenseId: Long,
    /** Absolute path inside app-private storage, written by AttachmentStore. */
    val path: String,
    val addedAt: Long
)

data class ExpenseDetails(
    @Embedded val expense: Expense,
    @Relation(parentColumn = "id", entityColumn = "expenseId") val splits: List<ExpenseSplit>,
    @Relation(parentColumn = "id", entityColumn = "expenseId") val attachments: List<Attachment>
) {
    /** What other people still owe me on this expense. */
    val outstandingPaise: Long
        get() = splits.filter { it.personId != null && it.settledAt == null && it.deletedAt == null }
            .sumOf { it.amountPaise }
}

/** One row per person, carrying what they currently owe. */
data class PersonBalance(
    val personId: Long,
    val name: String,
    val owedPaise: Long
)

/** An unsettled share, joined with the expense it belongs to, for the settle-up list. */
data class OutstandingShare(
    val splitId: Long,
    val expenseId: Long,
    val amountPaise: Long,
    val merchant: String,
    val category: String,
    val paidAt: Long
)
