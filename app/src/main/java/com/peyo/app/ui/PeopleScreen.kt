package com.peyo.app.ui

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.peyo.app.ExpenseViewModel
import com.peyo.app.data.PersonBalance
import com.peyo.app.data.SharedShare
import androidx.compose.runtime.LaunchedEffect
import com.peyo.app.ui.theme.MoneyTextStyle

/**
 * Who owes what, and the way to clear it.
 *
 * Everybody is listed, settled or not, because a person dropping off the screen the moment they
 * pay you back removes the one place their history can be reopened from. They are just ordered and
 * toned so that the outstanding balances read first.
 */
@Composable
fun PeopleScreen(
    vm: ExpenseViewModel,
    balances: List<PersonBalance>,
    /** What other Peyo users have recorded against my number. */
    shared: List<SharedShare> = emptyList(),
    onMarkShared: (splitId: String) -> Unit = {},
    onAddPeople: () -> Unit,
    modifier: Modifier = Modifier
) {
    var settling by remember { mutableStateOf<PersonBalance?>(null) }
    var viewingShared by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { vm.refreshShared() }

    if (balances.isEmpty() && shared.none { it.open }) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(
                icon = Icons.Default.Group,
                title = "No people yet",
                body = "Add the people you share bills with, then split an expense and Peyo keeps " +
                    "track of who owes you what.",
                action = {
                    Button(onAddPeople) {
                        Icon(Icons.Default.GroupAdd, null, Modifier.size(18.dp))
                        Gap()
                        Text("Add people")
                    }
                }
            )
        }
        return
    }

    // Each balance is already netted: what they owe me less what I owe them.
    val owing = balances.filter { it.owedPaise > 0 }
    val owed = balances.filter { it.owedPaise < 0 }
    val square = balances.filter { it.owedPaise == 0L }
    val totalOwed = owing.sumOf { it.owedPaise }
    val totalIOwe = owed.sumOf { -it.owedPaise }
    val largest = balances.maxOfOrNull { kotlin.math.abs(it.owedPaise) }?.takeIf { it > 0 } ?: 1L

    LazyColumn(
        modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item { OwedHeroCard(totalOwed, totalIOwe, owing.size, owed.size) }

        if (owing.isNotEmpty()) {
            item { Section("Owes you") }
            items(owing, key = { it.personId }) { balance ->
                BalanceRow(balance, largest) { settling = balance }
            }
        }
        if (owed.isNotEmpty()) {
            item { Section("You owe") }
            items(owed, key = { it.personId }) { balance ->
                BalanceRow(balance, largest) { settling = balance }
            }
        }
        // Kept apart from my own people: these were recorded on somebody else's phone, against my
        // number, and the most I can do with them is say they are paid.
        val sharedOpen = shared.filter { it.open }.groupBy { it.ownerId }
        if (sharedOpen.isNotEmpty()) {
            item { Section("Shared with you") }
            items(sharedOpen.entries.toList(), key = { "shared-" + it.key }) { (ownerId, rows) ->
                SharedOwnerRow(rows) { viewingShared = ownerId }
            }
        }
        if (square.isNotEmpty()) {
            item { Section("All square") }
            items(square, key = { it.personId }) { balance ->
                BalanceRow(balance, largest) { settling = balance }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }

    settling?.let { balance -> SettleDialog(vm, balance) { settling = null } }
    viewingShared?.let { ownerId ->
        val rows = shared.filter { it.ownerId == ownerId && it.open }
        if (rows.isEmpty()) viewingShared = null
        else SharedDialog(rows, onMark = onMarkShared) { viewingShared = null }
    }
}

/** One other user, and the net of what they have recorded between us. */
@Composable
private fun SharedOwnerRow(rows: List<SharedShare>, onClick: () -> Unit) {
    val owner = rows.first().ownerName
    val iOwe = rows.filter { it.iOwe }.sumOf { it.amountPaise }
    val theyOwe = rows.filterNot { it.iOwe }.sumOf { it.amountPaise }
    val net = theyOwe - iOwe
    val tone = if (net < 0) MaterialTheme.colorScheme.error else chartPalette.others
    PeyoCard(onClick = onClick) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            PersonAvatar(owner, size = 42.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(owner, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(
                    when {
                        net < 0 -> "says you owe them"
                        net > 0 -> "says they owe you"
                        else -> "you are even"
                    } + " • ${rows.size} item${if (rows.size == 1) "" else "s"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            Text(
                sharedAmount(kotlin.math.abs(net), rows.first().currency),
                style = MaterialTheme.typography.titleMedium.merge(MoneyTextStyle),
                fontWeight = FontWeight.Bold,
                color = tone
            )
        }
    }
}

/**
 * The open items one other user has recorded with me. Each can be marked settled, which tells
 * them on their next sync; I cannot change the amounts, which are theirs.
 */
@Composable
private fun SharedDialog(rows: List<SharedShare>, onMark: (String) -> Unit, onDismiss: () -> Unit) {
    val owner = rows.first().ownerName
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { PersonAvatar(owner, size = 44.dp) },
        title = { Text(owner, textAlign = TextAlign.Center) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Recorded on $owner's phone. Mark an item settled once it is paid and " +
                        "$owner will see it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Column(
                    Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    rows.forEachIndexed { index, share ->
                        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Row(
                            Modifier.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CategoryBadge(share.category.ifBlank { "Other" }, size = 32.dp)
                            Column(Modifier.weight(1f)) {
                                Text(
                                    share.merchant.ifBlank { share.category.ifBlank { "Expense" } },
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1
                                )
                                Text(
                                    (if (share.iOwe) "you owe" else "they owe you") + " • " +
                                        shortDate(share.paidAt),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (share.iOwe) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                sharedAmount(share.amountPaise, share.currency),
                                style = MaterialTheme.typography.bodyMedium.merge(MoneyTextStyle),
                                fontWeight = FontWeight.Medium
                            )
                            TextButton({ onMark(share.splitId) }) {
                                Text(if (share.iOwe) "Paid" else "Received")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Close") } }
    )
}

/** An amount in the currency its owner recorded it in, which need not be this phone's. */
private fun sharedAmount(minor: Long, currency: String): String =
    if (currency == com.peyo.app.AppCurrency.code) money(minor)
    else "$currency " + "%,.2f".format(minor / 100.0)

@Composable
private fun OwedHeroCard(totalOwed: Long, totalIOwe: Long, owingCount: Int, owedCount: Int) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(
            // Sage into green rather than green into amber: the amber reads as a warning
            // everywhere else in the app, and a balance you are owed is not one.
            Brush.linearGradient(listOf(scheme.secondaryContainer, scheme.primaryContainer))
        )
    ) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Owed to you",
                style = MaterialTheme.typography.labelLarge,
                color = scheme.onPrimaryContainer.copy(alpha = 0.8f)
            )
            Text(
                money(totalOwed),
                style = MaterialTheme.typography.displaySmall.merge(MoneyTextStyle)
                    .copy(textAlign = TextAlign.Start),
                fontWeight = FontWeight.Bold,
                color = scheme.onPrimaryContainer
            )
            Text(
                when {
                    totalOwed <= 0 && totalIOwe <= 0 -> "Everybody is square with you."
                    owingCount == 0 -> "Nobody owes you anything right now."
                    owingCount == 1 -> "1 person still owes you."
                    else -> "$owingCount people still owe you."
                },
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onPrimaryContainer.copy(alpha = 0.75f)
            )
            // What I owe sits on the same card, so the two directions are read together.
            if (totalIOwe > 0) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = scheme.onPrimaryContainer.copy(alpha = 0.15f))
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "You owe",
                            style = MaterialTheme.typography.labelLarge,
                            color = scheme.onPrimaryContainer.copy(alpha = 0.8f)
                        )
                        Text(
                            if (owedCount == 1) "to 1 person" else "to $owedCount people",
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onPrimaryContainer.copy(alpha = 0.75f)
                        )
                    }
                    Text(
                        money(totalIOwe),
                        style = MaterialTheme.typography.titleLarge.merge(MoneyTextStyle),
                        fontWeight = FontWeight.Bold,
                        color = scheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun BalanceRow(balance: PersonBalance, largestPaise: Long, onClick: () -> Unit) {
    val palette = chartPalette
    val scheme = MaterialTheme.colorScheme
    val net = balance.owedPaise
    val tone = when {
        net > 0 -> palette.others
        net < 0 -> scheme.error
        else -> scheme.onSurfaceVariant
    }
    PeyoCard(onClick = onClick) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            PersonAvatar(balance.name, size = 42.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(balance.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                if (net != 0L) {
                    Text(
                        // When money runs both ways the net hides how it was reached, so both
                        // sides are spelled out under it.
                        if (balance.owedToMePaise > 0 && balance.iOwePaise > 0) {
                            "owes you ${money(balance.owedToMePaise)} • you owe ${money(balance.iOwePaise)}"
                        } else if (net > 0) "owes you" else "you owe ${balance.name}",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1
                    )
                    ProgressTrack(
                        kotlin.math.abs(net).toFloat() / largestPaise,
                        tone,
                        height = 5.dp
                    )
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            null,
                            Modifier.size(14.dp),
                            tint = palette.settled
                        )
                        Text(
                            "all settled up",
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.settled
                        )
                    }
                }
            }
            MoneyText(
                kotlin.math.abs(net),
                style = MaterialTheme.typography.titleMedium,
                color = tone,
                weight = FontWeight.Bold
            )
        }
    }
}

/**
 * Everything still open between me and one person, in both directions, each clearable on its own
 * or all at once. Settling all squares both sides together: settling up is one payment of the
 * difference, whichever way it goes.
 */
@Composable
fun SettleDialog(vm: ExpenseViewModel, balance: PersonBalance, onDismiss: () -> Unit) {
    val shares by vm.outstandingFor(balance.personId).collectAsState(initial = emptyList())
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { PersonAvatar(balance.name, size = 44.dp) },
        title = { Text(balance.name, textAlign = TextAlign.Center) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (shares.isEmpty()) {
                    Text("Nothing outstanding.", textAlign = TextAlign.Center)
                } else {
                    val theyOwe = shares.filterNot { it.iOwe }.sumOf { it.amountPaise }
                    val iOwe = shares.filter { it.iOwe }.sumOf { it.amountPaise }
                    val net = theyOwe - iOwe
                    Text(
                        when {
                            net > 0 -> "${balance.name} owes you ${money(net)}"
                            net < 0 -> "You owe ${balance.name} ${money(-net)}"
                            else -> "You are even"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (net < 0) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface
                    )
                    if (theyOwe > 0 && iOwe > 0) Text(
                        "They owe you ${money(theyOwe)} and you owe them ${money(iOwe)}, so " +
                            "settling up is one payment of the difference.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // The list is capped and scrolls within itself, which is what keeps "Settle
                    // all" on screen. An unbounded column grew the dialog past the window on
                    // anyone with more than a handful of shares, pushing the buttons out of reach
                    // on exactly the balances that most needed clearing in one go.
                    Column(
                        Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        shares.forEachIndexed { index, share ->
                            if (index > 0) HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant
                            )
                            Row(
                                Modifier.padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                CategoryBadge(share.category, size = 32.dp)
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        share.merchant.ifBlank { share.category },
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1
                                    )
                                    Text(
                                        (if (share.iOwe) "they paid • you owe" else "you paid • they owe") +
                                            " • " + shortDate(share.paidAt),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (share.iOwe) MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                MoneyText(share.amountPaise)
                                TextButton({ vm.settleShare(share.splitId) }) { Text("Settle") }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (shares.isNotEmpty()) Button({
                vm.settleEverything(balance.personId); onDismiss()
            }) { Text("Settle up") } else TextButton(onDismiss) { Text("Close") }
        },
        dismissButton = {
            if (shares.isNotEmpty()) TextButton(onDismiss) { Text("Close") }
            else TextButton({ vm.reopenEverything(balance.personId) }) { Text("Reopen settled") }
        }
    )
}
