package com.example.expensetracker.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingFlat
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.expensetracker.AnalyticsReport
import com.example.expensetracker.Period
import com.example.expensetracker.ui.theme.MoneyTextStyle
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun AnalyticsScreen(
    report: AnalyticsReport,
    period: Period,
    onPeriodChange: (Period) -> Unit,
    onSettle: (personId: Long, name: String) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        // Extra room at the foot so the floating action button never sits on top of a chart.
        contentPadding = PaddingValues(top = 8.dp, bottom = 112.dp)
    ) {
        item { PeriodPicker(period, onPeriodChange) }

        if (!report.hasData) {
            item {
                EmptyState(
                    icon = Icons.Default.Insights,
                    title = "Nothing to chart yet",
                    body = "Record an expense, or share a payment screenshot with Peyo, and this " +
                        "dashboard fills itself in.",
                    action = { Button(onAdd) { Text("Add your first expense") } }
                )
            }
            return@LazyColumn
        }

        item { HeadlineCard(report) }
        item { OthersCard(report) }
        item { TrendSection(report) }
        item { CategorySection(report) }
        if (report.people.isNotEmpty()) item { PeopleSection(report, onSettle) }
        item { PatternSection(report) }
    }
}

@Composable
private fun PeriodPicker(period: Period, onChange: (Period) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        Period.entries.forEachIndexed { index, entry ->
            SegmentedButton(
                selected = period == entry,
                onClick = { onChange(entry) },
                shape = SegmentedButtonDefaults.itemShape(index, Period.entries.size),
                label = { Text(entry.label, maxLines = 1) }
            )
        }
    }
}

/**
 * My own spending, with everything other people owe already stripped out.
 *
 * The one figure on this screen that answers "what did I spend", so it is given the whole card, a
 * tinted ground of its own and the largest type in the app. Everything else on the dashboard
 * qualifies it.
 */
@Composable
private fun HeadlineCard(report: AnalyticsReport) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(
            Brush.linearGradient(
                listOf(scheme.primaryContainer, scheme.secondaryContainer)
            )
        )
    ) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "My own spending",
                style = MaterialTheme.typography.labelLarge,
                color = scheme.onPrimaryContainer.copy(alpha = 0.8f)
            )
            Text(
                money(report.minePaise),
                style = MaterialTheme.typography.displaySmall.merge(MoneyTextStyle)
                    .copy(textAlign = androidx.compose.ui.text.style.TextAlign.Start),
                fontWeight = FontWeight.Bold,
                color = scheme.onPrimaryContainer
            )
            Text(
                "Excludes what others owe you. Gross outlay was ${money(report.grossPaise)}.",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onPrimaryContainer.copy(alpha = 0.75f)
            )
            report.monthOverMonth?.let { change -> TrendPill(change) }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = scheme.onPrimaryContainer.copy(alpha = 0.15f))
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth()) {
                Stat("Expenses", report.expenseCount.toString(), Modifier.weight(1f))
                Stat("Per day", money(report.dailyAveragePaise), Modifier.weight(1f))
                Stat("Still owed", money(report.outstandingPaise), Modifier.weight(1f))
            }
        }
    }
}

/**
 * Month over month, as a pill rather than a coloured sentence.
 *
 * Spending less is the good direction here, so the colours are deliberately not the stock
 * red-is-down of a stock ticker: up is the warning tone, down is the settled tone.
 */
@Composable
private fun TrendPill(change: Float) {
    val palette = chartPalette
    val percent = (abs(change) * 100).roundToInt()
    val rising = change > 0.005f
    val falling = change < -0.005f
    val colour = when {
        rising -> palette.others
        falling -> palette.settled
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val icon = when {
        rising -> Icons.AutoMirrored.Filled.TrendingUp
        falling -> Icons.AutoMirrored.Filled.TrendingDown
        else -> Icons.AutoMirrored.Filled.TrendingFlat
    }
    Row(
        Modifier.padding(top = 2.dp).clip(CircleShape).background(colour.copy(alpha = 0.16f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(icon, null, Modifier.size(15.dp), tint = colour)
        Text(
            when {
                rising -> "$percent% more than last month"
                falling -> "$percent% less than last month"
                else -> "Level with last month"
            },
            style = MaterialTheme.typography.labelMedium,
            color = colour
        )
    }
}

/** What the group costs me: assigned, recovered, and what I am still carrying. */
@Composable
private fun OthersCard(report: AnalyticsReport) {
    val palette = chartPalette
    PeyoCard {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Spent on other people", style = MaterialTheme.typography.titleSmall)
            MoneyText(
                report.outstandingPaise,
                style = MaterialTheme.typography.headlineMedium,
                weight = FontWeight.Bold
            )
            Text(
                "Effective cost after settlements. You put out ${money(report.onOthersPaise)} " +
                    "and ${money(report.settledPaise)} has come back.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (report.onOthersPaise > 0) {
                SettlementBar(report.settledPaise, report.outstandingPaise)
                ChartLegend(
                    listOf("Recovered" to palette.settled, "Still owed" to palette.others)
                )
            }
        }
    }
}

@Composable
private fun TrendSection(report: AnalyticsReport) {
    val palette = chartPalette
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Section("Last 12 months")
        PeyoCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Each bar splits what you bore from what you fronted for others.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                MonthlyBars(report.monthly.map { StackedBar(it.label, it.minePaise, it.othersPaise) })
                ChartLegend(listOf("Mine" to palette.mine, "On others" to palette.others))
            }
        }
    }
}

@Composable
private fun CategorySection(report: AnalyticsReport) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Section("Where my money goes")
        if (report.categories.isEmpty()) {
            PeyoCard {
                Text(
                    "No spending of your own in this period -- every expense was assigned to " +
                        "somebody else.",
                    Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return@Column
        }
        PeyoCard {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                DonutChart(
                    slices = report.categories.map { it.fraction to categoryColor(it.category) },
                    centreLabel = moneyCompact(report.minePaise),
                    centreCaption = report.period.label
                )
                Column(Modifier.fillMaxWidth()) {
                    report.categories.forEach { slice ->
                        BarRow(
                            label = slice.category,
                            value = money(slice.paise),
                            fraction = slice.fraction,
                            color = categoryColor(slice.category),
                            caption = "${(slice.fraction * 100).roundToInt()}% of your spending",
                            leading = { CategoryBadge(slice.category, size = 34.dp) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PeopleSection(report: AnalyticsReport, onSettle: (Long, String) -> Unit) {
    val palette = chartPalette
    val worst = report.people.maxOfOrNull { it.sharedPaise }?.takeIf { it > 0 } ?: 1L
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Section("By person")
        PeyoCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    "What each person still costs you, once their settlements are taken off.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                report.people.forEach { person ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            PersonAvatar(person.name, size = 34.dp)
                            Text(
                                person.name,
                                Modifier.weight(1f),
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
                            )
                            MoneyText(person.outstandingPaise, weight = FontWeight.Bold)
                            // Settling is what you want the moment you see a balance, so it
                            // belongs here rather than only on the People tab.
                            if (person.outstandingPaise > 0) TextButton(
                                { onSettle(person.personId, person.name) }
                            ) { Text("Settle") }
                        }
                        // Scaled against the biggest sharer so the rows can be read against each
                        // other, not just as each person's own recovered/owed ratio.
                        SettlementBar(
                            settledPaise = person.settledPaise,
                            outstandingPaise = person.outstandingPaise,
                            scale = person.sharedPaise.toFloat() / worst
                        )
                        Text(
                            "${money(person.sharedPaise)} shared across ${person.expenseCount} " +
                                "expense${if (person.expenseCount == 1) "" else "s"} • " +
                                "${(person.recoveredFraction * 100).roundToInt()}% recovered",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                ChartLegend(listOf("Recovered" to palette.settled, "Still owed" to palette.others))
            }
        }
    }
}

@Composable
private fun PatternSection(report: AnalyticsReport) {
    var shown by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) { shown = true }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Section("Patterns")
        AnimatedVisibility(
            shown,
            enter = fadeIn(tween(400)) + slideInVertically(tween(400)) { it / 6 }
        ) {
            PeyoCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Spending by day of week", style = MaterialTheme.typography.titleSmall)
                    WeekdayBars(report.weekdays.map { it.label to it.paise })
                    report.largest?.let { biggest ->
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Text("Largest expense", style = MaterialTheme.typography.titleSmall)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CategoryBadge(biggest.expense.category, size = 38.dp)
                            Column(Modifier.weight(1f)) {
                                Text(
                                    biggest.expense.merchant.ifBlank { biggest.expense.category },
                                    maxLines = 1
                                )
                                Text(
                                    "${biggest.expense.category} • ${shortDate(biggest.expense.paidAt)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            MoneyText(biggest.expense.amountPaise, weight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
