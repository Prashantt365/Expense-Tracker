package com.peyo.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.peyo.app.data.ExpenseDetails

/**
 * Everything recorded, newest first, filed under the day it happened.
 *
 * The list used to be a flat stack of identical cards with the date buried in the third line of
 * each, which made "what did I spend on Saturday" a scrolling exercise. Day headings turn the same
 * rows into a ledger, and the running total against each heading answers the question the headings
 * invite.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TransactionsScreen(
    expenses: List<ExpenseDetails>,
    categories: List<String>,
    onEdit: (ExpenseDetails) -> Unit,
    onDelete: (ExpenseDetails) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier
) {
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var category by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<ExpenseDetails?>(null) }

    if (expenses.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(
                icon = Icons.AutoMirrored.Filled.ReceiptLong,
                title = "No expenses yet",
                body = "Add one by hand, or share a payment screenshot with Peyo and the amount, " +
                    "payee and category are read off it for you.",
                action = { Button(onAdd) { Text("Add an expense") } }
            )
        }
        return
    }

    val filtered = remember(expenses, query, category) {
        val needle = query.trim().lowercase()
        expenses.filter { details ->
            val e = details.expense
            (category == null || e.category == category) &&
                (needle.isEmpty() ||
                    e.merchant.lowercase().contains(needle) ||
                    e.note.lowercase().contains(needle) ||
                    e.category.lowercase().contains(needle))
        }
    }
    // Grouped here rather than in a Room query: the list is already in memory and already sorted
    // by paidAt, so the grouping is a single pass and the day headings cannot fall out of step
    // with the filtering above them.
    val days = remember(filtered) { filtered.groupBy { dayKey(it.expense.paidAt) } }

    Column(modifier.fillMaxSize()) {
        SearchAndFilter(
            query = query,
            onQuery = { query = it },
            searching = searching,
            onSearching = { searching = it; if (!it) query = "" },
            categories = categories,
            selected = category,
            onCategory = { category = it }
        )

        if (filtered.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = Icons.Default.SearchOff,
                    title = "Nothing matches",
                    body = "No expense matches that search and filter.",
                    action = {
                        TextButton({ query = ""; category = null; searching = false }) {
                            Text("Clear filters")
                        }
                    }
                )
            }
            return
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 112.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            days.forEach { (_, rows) ->
                stickyHeader(key = "head-${rows.first().expense.id}") {
                    DayHeading(
                        heading = dayHeading(rows.first().expense.paidAt),
                        totalPaise = rows.sumOf { it.expense.amountPaise }
                    )
                }
                items(rows, key = { it.expense.id }) { details ->
                    ExpenseRow(
                        details = details,
                        onEdit = { onEdit(details) },
                        onDelete = { pendingDelete = details }
                    )
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            icon = { Icon(Icons.Default.Delete, null) },
            title = { Text("Delete this expense?") },
            text = {
                Text(
                    "${money(target.expense.amountPaise)} • " +
                        target.expense.merchant.ifBlank { target.expense.category } +
                        if (target.attachments.isNotEmpty()) "\n\nIts attachments will be deleted too."
                        else ""
                )
            },
            confirmButton = {
                Button(
                    { onDelete(target); pendingDelete = null },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) { Text("Delete") }
            },
            dismissButton = { TextButton({ pendingDelete = null }) { Text("Cancel") } }
        )
    }
}

/**
 * The search field and the category chips.
 *
 * The field is collapsed behind an icon until it is asked for, because on the screen people open
 * most often a permanently visible search box costs a row of transactions and earns nothing.
 */
@Composable
private fun SearchAndFilter(
    query: String,
    onQuery: (String) -> Unit,
    searching: Boolean,
    onSearching: (Boolean) -> Unit,
    categories: List<String>,
    selected: String?,
    onCategory: (String?) -> Unit
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        AnimatedVisibility(searching, enter = fadeIn(tween(150)), exit = fadeOut(tween(150))) {
            OutlinedTextField(
                query,
                onQuery,
                Modifier.fillMaxWidth(),
                placeholder = { Text("Search payee, note or category") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    IconButton({ onSearching(false) }) { Icon(Icons.Default.Close, "Close search") }
                },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)
            )
        }
        // One scrolling row rather than a wrapping block. Wrapping put six categories on two
        // lines and pushed the first transaction most of the way down the screen, on the tab
        // whose entire job is showing transactions.
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!searching) item {
                FilterChip(
                    selected = false,
                    onClick = { onSearching(true) },
                    label = { Text("Search") },
                    leadingIcon = { Icon(Icons.Default.Search, null, Modifier.size(16.dp)) },
                    shape = MaterialTheme.shapes.small
                )
            }
            item {
                FilterChip(
                    selected = selected == null,
                    onClick = { onCategory(null) },
                    label = { Text("All") },
                    shape = MaterialTheme.shapes.small
                )
            }
            items(categories, key = { it }) { name ->
                FilterChip(
                    selected = selected == name,
                    onClick = { onCategory(if (selected == name) null else name) },
                    label = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { Icon(categoryIcon(name), null, Modifier.size(16.dp)) },
                    shape = MaterialTheme.shapes.small
                )
            }
        }
    }
}

/** The day, and what that day came to. Sticks to the top while its own rows are on screen. */
@Composable
private fun DayHeading(heading: String, totalPaise: Long) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)
            .padding(top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            heading,
            Modifier.weight(1f),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            money(totalPaise),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ExpenseRow(details: ExpenseDetails, onEdit: () -> Unit, onDelete: () -> Unit) {
    val e = details.expense
    var menu by remember { mutableStateOf(false) }
    val palette = chartPalette

    PeyoCard(onClick = onEdit) {
        Row(
            Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CategoryBadge(e.category)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    e.merchant.ifBlank { e.category },
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    listOf(e.category, e.note).filter { it.isNotBlank() }.joinToString(" • "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        timeLabel(e.paidAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    if (details.attachments.isNotEmpty()) Tag(
                        "${details.attachments.size}",
                        icon = Icons.Default.AttachFile
                    )
                    if (details.outstandingPaise > 0) Tag(
                        "${money(details.outstandingPaise)} owed",
                        container = palette.others.copy(alpha = 0.16f),
                        content = palette.others
                    )
                }
            }
            MoneyText(e.amountPaise, weight = FontWeight.Bold)
            Box {
                IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "More actions") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Edit") },
                        leadingIcon = { Icon(Icons.Default.Edit, null) },
                        onClick = { menu = false; onEdit() }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        leadingIcon = { Icon(Icons.Default.Delete, null) },
                        onClick = { menu = false; onDelete() }
                    )
                }
            }
        }
    }
}
