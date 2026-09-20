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
    onAddPeople: () -> Unit,
    modifier: Modifier = Modifier
) {
    var settling by remember { mutableStateOf<PersonBalance?>(null) }

    if (balances.isEmpty()) {
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

    val totalOwed = balances.sumOf { it.owedPaise }
    val owing = balances.filter { it.owedPaise > 0 }
    val settled = balances.filter { it.owedPaise <= 0 }
    val largest = owing.maxOfOrNull { it.owedPaise }?.takeIf { it > 0 } ?: 1L

    LazyColumn(
        modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item { OwedHeroCard(totalOwed, owing.size, balances.size) }

        if (owing.isNotEmpty()) {
            item { Section("Owes you") }
            items(owing, key = { it.personId }) { balance ->
                BalanceRow(balance, largest) { settling = balance }
            }
        }
        if (settled.isNotEmpty()) {
            item { Section("All square") }
            items(settled, key = { it.personId }) { balance ->
                BalanceRow(balance, largest) { settling = balance }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }

    settling?.let { balance -> SettleDialog(vm, balance) { settling = null } }
}

@Composable
private fun OwedHeroCard(totalOwed: Long, owingCount: Int, peopleCount: Int) {
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
                    totalOwed <= 0 -> "Everybody is square with you."
                    owingCount == 1 -> "1 of $peopleCount people still owes you."
                    else -> "$owingCount of $peopleCount people still owe you."
                },
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onPrimaryContainer.copy(alpha = 0.75f)
            )
        }
    }
}

@Composable
private fun BalanceRow(balance: PersonBalance, largestPaise: Long, onClick: () -> Unit) {
    val palette = chartPalette
    val owes = balance.owedPaise > 0
    PeyoCard(onClick = onClick) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            PersonAvatar(balance.name, size = 42.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(balance.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                if (owes) {
                    Text(
                        "owes you",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    ProgressTrack(
                        balance.owedPaise.toFloat() / largestPaise,
                        palette.others,
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
                balance.owedPaise,
                style = MaterialTheme.typography.titleMedium,
                color = if (owes) palette.others else MaterialTheme.colorScheme.onSurfaceVariant,
                weight = FontWeight.Bold
            )
        }
    }
}

/** One person's unsettled shares, each clearable on its own or all at once. */
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
                    Text(
                        "${shares.size} unsettled share${if (shares.size == 1) "" else "s"}, " +
                            "${money(shares.sumOf { it.amountPaise })} in total",
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
                                        shortDate(share.paidAt),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
            }) { Text("Settle all") } else TextButton(onDismiss) { Text("Close") }
        },
        dismissButton = {
            if (shares.isNotEmpty()) TextButton(onDismiss) { Text("Close") }
            else TextButton({ vm.reopenEverything(balance.personId) }) { Text("Reopen settled") }
        }
    )
}
