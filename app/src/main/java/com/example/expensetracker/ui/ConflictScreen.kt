package com.example.expensetracker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MergeType
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.expensetracker.data.SyncConflict
import com.example.expensetracker.data.SyncedTable
import com.example.expensetracker.sync.asJsonObject
import com.example.expensetracker.sync.stringOrNull
import org.json.JSONObject

/**
 * Describes one side of a conflict in the terms the user recognises it by, rather than as the row
 * it actually is. Which of two JSON blobs is right is not a question anybody can answer; which of
 * "Swiggy, 182, pizza" and "Swiggy, 220, pizza" they meant is.
 */
private fun describe(table: SyncedTable, json: JSONObject?): String {
    if (json == null) return "Unreadable"
    val deleted = !json.isNull("deleted_at")
    val body = when (table) {
        SyncedTable.EXPENSE -> listOfNotNull(
            json.stringOrNull("merchant"),
            money(json.optLong("amount_minor")),
            json.stringOrNull("note"),
            json.stringOrNull("category")
        ).joinToString(" • ")

        SyncedTable.PERSON -> listOfNotNull(json.stringOrNull("name"), json.stringOrNull("note"))
            .joinToString(" • ")

        SyncedTable.CATEGORY -> json.stringOrNull("name").orEmpty()

        SyncedTable.SPLIT -> {
            val settled = if (json.isNull("settled_at")) "unsettled" else "settled"
            "${money(json.optLong("amount_minor"))} • $settled"
        }
    }
    return if (deleted) "Deleted (was $body)" else body.ifBlank { "Empty" }
}

private fun SyncedTable.label(): String = when (this) {
    SyncedTable.EXPENSE -> "Expense"
    SyncedTable.PERSON -> "Person"
    SyncedTable.CATEGORY -> "Category"
    SyncedTable.SPLIT -> "Split"
}

private fun whenEdited(millis: Long): String = shortDate(millis)

/**
 * Every row that changed in two places at once, and the choice only the user can make.
 *
 * Nothing is decided for them and nothing expires: until one side is picked the phone goes on
 * showing what its owner last typed, so leaving this screen costs nothing. That is the point of
 * flagging rather than resolving -- the alternative silently throws one of the two edits away.
 */
@Composable
fun ConflictDialog(
    conflicts: List<SyncConflict>,
    onResolve: (conflictId: Long, keepLocal: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.AutoMirrored.Filled.MergeType, null) },
        title = {
            Text(
                if (conflicts.size == 1) "1 change needs a decision"
                else "${conflicts.size} changes need a decision"
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "These were edited on this phone and somewhere else since they last agreed. " +
                        "Nothing is changed until you choose.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LazyColumn(
                    Modifier.heightIn(max = 380.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(conflicts, key = { it.id }) { conflict ->
                        val table = conflict.table
                        PeyoCard(container = MaterialTheme.colorScheme.surfaceContainer) {
                            Column(
                                Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Tag(table?.label() ?: conflict.entity)
                                if (table == null) {
                                    // A conflict recorded by a newer build than this one. Showing
                                    // it as undecidable beats hiding a change that is being held.
                                    Text("This app cannot read this change.")
                                } else {
                                    Text(
                                        "On this phone, ${whenEdited(conflict.localUpdatedAt)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        describe(table, conflict.localJson.asJsonObject()),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                    Text(
                                        "Elsewhere, ${whenEdited(conflict.remoteUpdatedAt)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        describe(table, conflict.remoteJson.asJsonObject()),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Row(
                                        Modifier.padding(top = 4.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Button(
                                            { onResolve(conflict.id, true) },
                                            Modifier.weight(1f)
                                        ) { Text("Keep mine") }
                                        OutlinedButton(
                                            { onResolve(conflict.id, false) },
                                            Modifier.weight(1f)
                                        ) { Text("Use theirs") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Later") } }
    )
}

/** So the settings row and the dialog word the same thing the same way. */
internal fun conflictSummary(count: Int): String = when (count) {
    0 -> "Nothing waiting."
    1 -> "1 change was made in two places and needs your decision."
    else -> "$count changes were made in two places and need your decision."
}
