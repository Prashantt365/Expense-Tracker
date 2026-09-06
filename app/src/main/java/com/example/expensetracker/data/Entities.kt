package com.example.expensetracker.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

@Entity(tableName = "expenses")
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
    val sourceUri: String? = null
)

/** The editable pick-list. Expenses store their category by name so deleting one cannot orphan them. */
@Entity(tableName = "categories", indices = [Index(value = ["name"], unique = true)])
data class Category(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val sortOrder: Int = 0
)

@Entity(tableName = "people", indices = [Index(value = ["name"], unique = true)])
data class Person(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val note: String = ""
)

@Entity(
    tableName = "expense_splits",
    foreignKeys = [
        ForeignKey(Expense::class, ["id"], ["expenseId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(Person::class, ["id"], ["personId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("expenseId"), Index("personId")]
)
data class ExpenseSplit(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val expenseId: Long,
    /** null is my own share of the bill, which is never owed to anybody. */
    val personId: Long?,
    val amountPaise: Long,
    /** Null until the person pays me back. Balances only count unsettled shares. */
    val settledAt: Long? = null
)

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
    val outstandingPaise: Long get() = splits.filter { it.personId != null && it.settledAt == null }.sumOf { it.amountPaise }
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
