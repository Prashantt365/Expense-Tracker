package com.example.expensetracker.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.expensetracker.ContactCandidate
import com.example.expensetracker.ImportState
import com.example.expensetracker.data.Category

/**
 * Review of everything lifted out of a statement. Nothing is written until this is confirmed:
 * the parser reads a PDF by shape and will sometimes be wrong, so the user is the final check.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportReviewDialog(
    state: ImportState,
    categories: List<Category>,
    onToggle: (Int) -> Unit,
    onCategory: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    when (state) {
        is ImportState.Idle -> Unit

        is ImportState.Reading -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Reading statement") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        if (state.total > 0) "Page ${state.page} of ${state.total}"
                        else "Opening the document…"
                    )
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(
                        "Pages are read on device, so a long statement takes a moment.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {}
        )

        is ImportState.Failed -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Import failed") },
            text = { Text(state.message) },
            confirmButton = { Button(onDismiss) { Text("Close") } }
        )

        is ImportState.Review -> Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize()) {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("Review ${state.rows.size} rows") },
                            navigationIcon = { IconButton(onDismiss) { Icon(Icons.Default.Close, "Cancel") } },
                            actions = {
                                TextButton(onConfirm, enabled = state.selected.isNotEmpty()) {
                                    Text("Import ${state.selected.size}")
                                }
                            }
                        )
                    }
                ) { padding ->
                    LazyColumn(
                        Modifier.padding(padding).fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    "Untick anything that is not an expense. Money coming in is " +
                                        "already unticked. Rows that duplicate an existing expense " +
                                        "are skipped on import.",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Section("Category for these")
                                FlowChips(categories.map { it.name }, state.category, onCategory)
                                HorizontalDivider(Modifier.padding(top = 8.dp))
                            }
                        }
                        itemsIndexed(state.rows) { index, row ->
                            Row(
                                Modifier.fillMaxWidth().clickable { onToggle(index) },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(index in state.selected, { onToggle(index) })
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        row.description.ifBlank { "(no description)" },
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 2
                                    )
                                    Text(
                                        buildString {
                                            append(row.date?.let(::shortDate) ?: "date not read")
                                            if (row.isCredit) append(" • money in")
                                        },
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                                Text(money(row.amountPaise), fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Contacts offered for import, with anything that looks like a duplicate flagged and unticked. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactPickerDialog(
    candidates: List<ContactCandidate>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("Add from contacts") },
                        navigationIcon = { IconButton(onDismiss) { Icon(Icons.Default.Close, "Cancel") } },
                        actions = {
                            TextButton(onConfirm, enabled = selected.isNotEmpty()) {
                                Text("Add ${selected.size}")
                            }
                        }
                    )
                }
            ) { padding ->
                if (candidates.isEmpty()) {
                    Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No contacts with names were found.")
                    }
                    return@Scaffold
                }
                LazyColumn(
                    Modifier.padding(padding).fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    item {
                        Text(
                            "Names that look like someone you already have are flagged and left " +
                                "unticked. Tick one only if it really is a different person.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                    }
                    items(candidates, key = { it.name }) { candidate ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onToggle(candidate.name) },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(candidate.name in selected, { onToggle(candidate.name) })
                            Column(Modifier.weight(1f)) {
                                Text(candidate.name)
                                val reason = when {
                                    candidate.existingPersonName != null ->
                                        "already in your people as ${candidate.existingPersonName}"
                                    candidate.duplicateOfEarlierContact ->
                                        "looks like another contact above"
                                    else -> null
                                }
                                reason?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
