package com.example.expensetracker.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.expensetracker.AnalyticsReport
import com.example.expensetracker.Period
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun AnalyticsScreen(
    report: AnalyticsReport,
    period: Period,
    onPeriodChange: (Period) -> Unit,
    onSettle: (personId: Long, name: String) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        // Extra room at the foot so the floating action button never sits on top of a chart.
        contentPadding = PaddingValues(top = 20.dp, bottom = 96.dp)
    ) {
        item { PeriodPicker(period, onPeriodChange) }

        if (!report.hasData) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Nothing to chart yet", fontWeight = FontWeight.Bold)
                        Text(
                            "Add an expense or share a payment screenshot, and this dashboard fills in.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
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
                label = { Text(entry.label) }
            )
        }
    }
}

/** My own spending, with everything other people owe already stripped out. */
@Composable
private fun HeadlineCard(report: AnalyticsReport) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("My own spending", style = MaterialTheme.typography.titleSmall)
            Text(
                money(report.minePaise),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Excludes what others owe you. Gross outlay was ${money(report.grossPaise)}.",
                style = MaterialTheme.typography.bodySmall
            )
            report.monthOverMonth?.let { change ->
                val percent = (abs(change) * 100).roundToInt()
                val direction = if (change >= 0) "more" else "less"
                Text(
                    "$percent% $direction than last month",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (change >= 0) MaterialTheme.colorScheme.error else ChartColors.settled
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Stat("Expenses", report.expenseCount.toString())
                Stat("Per day", money(report.dailyAveragePaise))
            }
        }
    }
}

/** What the group costs me: assigned, recovered, and what I am still carrying. */
@Composable
private fun OthersCard(report: AnalyticsReport) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Spent on other people", style = MaterialTheme.typography.titleSmall)
            Text(
                money(report.outstandingPaise),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Effective cost after settlements. You put out ${money(report.onOthersPaise)} " +
                    "and ${money(report.settledPaise)} has come back.",
                style = MaterialTheme.typography.bodySmall
            )
            if (report.onOthersPaise > 0) {
                SettlementBar(report.settledPaise, report.outstandingPaise)
                ChartLegend(
                    listOf("Recovered" to ChartColors.settled, "Still owed" to ChartColors.others)
                )
            }
        }
    }
}

@Composable
private fun TrendSection(report: AnalyticsReport) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Section("Last 12 months")
        Text(
            "Each bar splits what you bore from what you fronted for others.",
            style = MaterialTheme.typography.bodySmall
        )
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MonthlyBars(
                    report.monthly.map { StackedBar(it.label, it.minePaise, it.othersPaise) }
                )
                ChartLegend(listOf("Mine" to ChartColors.mine, "On others" to ChartColors.others))
            }
        }
    }
}

@Composable
private fun CategorySection(report: AnalyticsReport) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Section("Where my money goes")
        if (report.categories.isEmpty()) {
            Text("No spending of your own in this period.", style = MaterialTheme.typography.bodySmall)
            return@Column
        }
        Card(Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                DonutChart(
                    slices = report.categories.map { it.fraction },
                    centreLabel = money(report.minePaise),
                    centreCaption = report.period.label
                )
                Column(Modifier.fillMaxWidth()) {
                    report.categories.forEachIndexed { index, slice ->
                        BarRow(
                            label = slice.category,
                            value = money(slice.paise),
                            fraction = slice.fraction,
                            color = ChartColors.at(index),
                            caption = "${(slice.fraction * 100).roundToInt()}% of your spending"
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PeopleSection(report: AnalyticsReport, onSettle: (Long, String) -> Unit) {
    val worst = report.people.maxOfOrNull { it.sharedPaise }?.takeIf { it > 0 } ?: 1L
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Section("By person")
        Text(
            "What each person still costs you, once their settlements are taken off.",
            style = MaterialTheme.typography.bodySmall
        )
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                report.people.forEach { person ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(person.name, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                            Text(money(person.outstandingPaise), fontWeight = FontWeight.Bold)
                            // Settling is what you want the moment you see a balance, so it
                            // belongs here rather than only on the People tab.
                            if (person.outstandingPaise > 0) TextButton(
                                { onSettle(person.personId, person.name) }
                            ) { Text("Settle") }
                        }
                        // Scaled against the biggest sharer so the rows can be read against
                        // each other, not just as each person's own recovered/owed ratio.
                        SettlementBar(
                            settledPaise = person.settledPaise,
                            outstandingPaise = person.outstandingPaise,
                            scale = person.sharedPaise.toFloat() / worst
                        )
                        Text(
                            "${money(person.sharedPaise)} shared across ${person.expenseCount} " +
                                "expense${if (person.expenseCount == 1) "" else "s"} • " +
                                "${(person.recoveredFraction * 100).roundToInt()}% recovered",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
                ChartLegend(
                    listOf("Recovered" to ChartColors.settled, "Still owed" to ChartColors.others)
                )
            }
        }
    }
}

@Composable
private fun PatternSection(report: AnalyticsReport) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Section("Patterns")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Spending by day of week", style = MaterialTheme.typography.titleSmall)
                WeekdayBars(report.weekdays.map { it.label to it.paise })
                report.largest?.let { biggest ->
                    HorizontalDivider()
                    Text("Largest expense", style = MaterialTheme.typography.titleSmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(biggest.expense.merchant.ifBlank { biggest.expense.category })
                            Text(
                                "${biggest.expense.category} • ${shortDate(biggest.expense.paidAt)}",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        Text(money(biggest.expense.amountPaise), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}
