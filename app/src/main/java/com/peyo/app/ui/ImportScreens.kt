package com.peyo.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.peyo.app.ContactCandidate
import com.peyo.app.ImportState
import com.peyo.app.data.Category

/**
 * Review of everything lifted out of a statement. Nothing is written until this is confirmed:
 * the parser reads a PDF by shape and will sometimes be wrong, so the user is the final check.
 */
@Composable
fun ImportReviewScreen(
    state: ImportState,
    categories: List<Category>,
    onToggle: (Int) -> Unit,
    onToggleAll: (Boolean) -> Unit,
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
                    // Determinate as soon as the page count is known, because a bar that only
                    // spins says nothing about whether a forty page statement is nearly done.
                    if (state.total > 0) LinearProgressIndicator(
                        progress = { state.page.toFloat() / state.total },
                        modifier = Modifier.fillMaxWidth()
                    ) else LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(
                        "Pages are read on this device, so a long statement takes a moment.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {}
        )

        is ImportState.Failed -> AlertDialog(
            onDismissRequest = onDismiss,
            icon = { Icon(Icons.Default.Warning, null) },
            title = { Text("Import failed") },
            text = { Text(state.message) },
            confirmButton = { Button(onDismiss) { Text("Close") } }
        )

        is ImportState.Review -> {
            val total = state.selected.sumOf { state.rows[it].amountPaise }
            FullScreenOverlay(onDismiss) {
                OverlayBar(onDismiss) {
                    Column {
                        Text("Review ${state.rows.size} rows")
                        Text(
                            "${state.selected.size} ticked • ${money(total)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                "Untick anything that is not an expense. Money coming in is " +
                                    "already unticked. Rows that duplicate an existing expense " +
                                    "are skipped on import.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Section("Category for these")
                            FlowChips(
                                categories.map { it.name },
                                state.category,
                                onSelect = onCategory
                            )
                            Row(
                                Modifier.fillMaxWidth().padding(top = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Spacer(Modifier.weight(1f))
                                TextButton({ onToggleAll(true) }) { Text("Tick all") }
                                TextButton({ onToggleAll(false) }) { Text("Untick all") }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                    itemsIndexed(state.rows) { index, row ->
                        val ticked = index in state.selected
                        Row(
                            Modifier.fillMaxWidth().clickable { onToggle(index) }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(ticked, { onToggle(index) })
                            Column(Modifier.weight(1f)) {
                                Text(
                                    row.description.ifBlank { "(no description)" },
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        row.date?.let(::shortDate) ?: "date not read",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (row.isCredit) Tag(
                                        "money in",
                                        container = MaterialTheme.colorScheme.tertiaryContainer,
                                        content = MaterialTheme.colorScheme.onTertiaryContainer
                                    )
                                }
                            }
                            MoneyText(
                                row.amountPaise,
                                weight = FontWeight.Bold,
                                color = if (ticked) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                }
                OverlayAction(
                    "Import ${state.selected.size}",
                    state.selected.isNotEmpty(),
                    onConfirm
                )
            }
        }
    }
}

/** Contacts offered for import, with anything that looks like a duplicate flagged and unticked. */
@Composable
fun ContactPickerScreen(
    candidates: List<ContactCandidate>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    FullScreenOverlay(onDismiss) {
        OverlayBar(onDismiss) { Text("Add from contacts") }

        if (candidates.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = Icons.Default.PersonSearch,
                    title = "No names found",
                    body = "None of the contacts on this phone have a name Peyo can read.",
                    action = { TextButton(onDismiss) { Text("Close") } }
                )
            }
            return@FullScreenOverlay
        }

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            item {
                Text(
                    "Names that look like someone you already have are flagged and left " +
                        "unticked. Tick one only if it really is a different person.",
                    Modifier.padding(bottom = 12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            items(candidates, key = { it.name }) { candidate ->
                Row(
                    Modifier.fillMaxWidth().clickable { onToggle(candidate.name) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Checkbox(candidate.name in selected, { onToggle(candidate.name) })
                    PersonAvatar(candidate.name, size = 32.dp)
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text(candidate.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
            item { Spacer(Modifier.height(8.dp)) }
        }
        OverlayAction("Add ${selected.size}", selected.isNotEmpty(), onConfirm)
    }
}
