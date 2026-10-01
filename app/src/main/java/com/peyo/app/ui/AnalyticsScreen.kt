package com.peyo.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingFlat
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.peyo.app.AnalyticsReport
import com.peyo.app.Period
import com.peyo.app.label
import com.peyo.app.monthName
import com.peyo.app.ui.theme.MoneyTextStyle
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun AnalyticsScreen(
    report: AnalyticsReport,
    period: Period,
    /** Every month that can be picked, newest first. Always holds at least the current one. */
    months: List<YearMonth>,
    /** Whether anything has been recorded at all, in any period. */
    hasAnyExpenses: Boolean,
    /** Who to greet; blank when not signed in or no name was given. */
    firstName: String,
    onPeriodChange: (Period) -> Unit,
    onSettle: (personId: Long, name: String) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier
) {
    // The cards rise in one after another when the dashboard first opens. Anything composed after
    // that -- a card scrolled into view, a period switched -- is simply there.
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }

    LazyColumn(
        modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        // Extra room at the foot so the floating action button never sits on top of a chart.
        contentPadding = PaddingValues(top = 4.dp, bottom = 112.dp)
    ) {
        item { PeriodHeader(period, months, firstName, onPeriodChange) }

        if (!report.hasData) {
            item {
                if (hasAnyExpenses) EmptyState(
                    icon = Icons.Default.CalendarMonth,
                    title = "Nothing recorded in ${inSentence(period)}",
                    body = "Pick another month above, or add an expense to this one.",
                    action = { Button(onAdd) { Text("Add an expense") } }
                ) else EmptyState(
                    icon = Icons.Default.Insights,
                    title = "Nothing to chart yet",
                    body = "Record an expense, or share a payment screenshot with Peyo, and this " +
                        "dashboard fills itself in.",
                    action = { Button(onAdd) { Text("Add your first expense") } }
                )
            }
            return@LazyColumn
        }

        item { Reveal(0, entered) { HeadlineCard(report) } }
        item { Reveal(1, entered) { OthersCard(report) } }
        item { Reveal(2, entered) { TrendSection(report) } }
        item { Reveal(3, entered) { CategorySection(report) } }
        if (report.people.isNotEmpty()) item { Reveal(4, entered) { PeopleSection(report, onSettle) } }
        item { Reveal(5, entered) { PatternSection(report) } }
    }
}

/** "This month" reads as "this month" mid-sentence; a month's name keeps its capital. */
private fun inSentence(period: Period): String =
    if (period is Period.Month && period.label() != "This month") period.label()
    else period.label().lowercase()

/** Fades and lifts its content in, staggered by [order], unless the screen has already entered. */
@Composable
private fun Reveal(order: Int, entered: Boolean, content: @Composable () -> Unit) {
    val state = remember { MutableTransitionState(entered).apply { targetState = true } }
    AnimatedVisibility(
        state,
        enter = fadeIn(tween(320, delayMillis = order * 60)) +
            slideInVertically(tween(320, delayMillis = order * 60)) { it / 8 }
    ) { content() }
}

/**
 * A greeting, and the period in view as a dropdown.
 *
 * The current month is what nearly everyone opens the app to see, so it is the default and the
 * only one on screen until asked; earlier months, the year and all time are one tap away in the
 * menu, and the arrows step a month at a time for a quick look back.
 */
@Composable
private fun PeriodHeader(
    period: Period,
    months: List<YearMonth>,
    firstName: String,
    onChange: (Period) -> Unit
) {
    val today = LocalDate.now()
    val scheme = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                greeting(LocalTime.now()) + if (firstName.isBlank()) "" else ", $firstName",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                periodCaption(period, today),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant
            )
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                Surface(
                    onClick = { open = true },
                    shape = CircleShape,
                    color = scheme.secondaryContainer,
                    contentColor = scheme.onSecondaryContainer
                ) {
                    Row(
                        Modifier.padding(start = 14.dp, end = 10.dp, top = 9.dp, bottom = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.CalendarMonth, null, Modifier.size(18.dp))
                        Text(
                            period.label(today),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                        Icon(Icons.Default.ExpandMore, "Choose period", Modifier.size(20.dp))
                    }
                }
                DropdownMenu(
                    expanded = open,
                    onDismissRequest = { open = false },
                    modifier = Modifier.heightIn(max = 380.dp)
                ) {
                    fun pick(choice: Period) {
                        open = false
                        onChange(choice)
                    }
                    months.forEach { month ->
                        val choice = Period.Month(month)
                        PeriodOption(choice.label(today), period == choice) { pick(choice) }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    PeriodOption(Period.Year.label(today), period == Period.Year) { pick(Period.Year) }
                    PeriodOption(Period.All.label(today), period == Period.All) { pick(Period.All) }
                }
            }

            // Stepping only makes sense between months; the year and all time have no neighbours.
            (period as? Period.Month)?.let { selected ->
                val index = months.indexOf(selected.month)
                val older = if (index >= 0) months.getOrNull(index + 1) else null
                val newer = if (index >= 0) months.getOrNull(index - 1) else null
                IconButton({ older?.let { onChange(Period.Month(it)) } }, enabled = older != null) {
                    Icon(Icons.Default.ChevronLeft, "Previous month")
                }
                IconButton({ newer?.let { onChange(Period.Month(it)) } }, enabled = newer != null) {
                    Icon(Icons.Default.ChevronRight, "Next month")
                }
            }
        }
    }
}

@Composable
private fun PeriodOption(label: String, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal) },
        onClick = onClick,
        trailingIcon = {
            if (selected) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
        }
    )
}

private fun greeting(now: LocalTime): String = when (now.hour) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    else -> "Good evening"
}

private fun periodCaption(period: Period, today: LocalDate): String = when (period) {
    is Period.Month -> {
        val current = YearMonth.from(today)
        if (period.month != current) "Looking back at ${monthName(period.month)}"
        else when (val left = current.lengthOfMonth() - today.dayOfMonth) {
            0 -> "Last day of ${monthName(current)}"
            1 -> "1 day left in ${monthName(current)}"
            else -> "$left days left in ${monthName(current)}"
        }
    }
    Period.Year -> "${today.year} so far"
    Period.All -> "Everything you have recorded"
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
    val ink = scheme.onPrimary
    Box(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.extraLarge).background(
            Brush.linearGradient(listOf(scheme.primary, lerp(scheme.primary, scheme.tertiary, 0.45f)))
        )
    ) {
        // A soft disc off the corner gives the card some depth without an image behind it.
        Box(
            Modifier.align(Alignment.TopEnd).offset(x = 48.dp, y = (-56).dp).size(170.dp)
                .clip(CircleShape).background(ink.copy(alpha = 0.08f))
        )
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "My own spending • ${report.period.label()}",
                style = MaterialTheme.typography.labelLarge,
                color = ink.copy(alpha = 0.85f)
            )
            Text(
                money(report.minePaise),
                style = MaterialTheme.typography.displaySmall.merge(MoneyTextStyle)
                    .copy(textAlign = androidx.compose.ui.text.style.TextAlign.Start),
                fontWeight = FontWeight.Bold,
                color = ink
            )
            Text(
                "Excludes what others owe you. Gross outlay was ${money(report.grossPaise)}.",
                style = MaterialTheme.typography.bodySmall,
                color = ink.copy(alpha = 0.8f)
            )
            // Month over month only means something while a month is in view.
            if (report.period is Period.Month) report.monthOverMonth?.let { change ->
                TrendPill(change, report, ink)
            }

            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
                    .background(ink.copy(alpha = 0.12f)).padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                val label = ink.copy(alpha = 0.75f)
                Stat("Expenses", report.expenseCount.toString(), Modifier.weight(1f), ink, label)
                Stat("Per day", money(report.dailyAveragePaise), Modifier.weight(1f), ink, label)
                Stat("Still owed", money(report.outstandingPaise), Modifier.weight(1f), ink, label)
            }
        }
    }
}

/**
 * Month over month, as a pill rather than a coloured sentence.
 *
 * It sits on the brand-coloured headline, where the chart's warning and settled tones cannot hold
 * their contrast, so it takes the card's own ink and lets the arrow and the words carry the
 * direction. A month already over is compared whole with the one before, and named.
 */
@Composable
private fun TrendPill(change: Float, report: AnalyticsReport, colour: Color) {
    val percent = (abs(change) * 100).roundToInt()
    val rising = change > 0.005f
    val falling = change < -0.005f
    val against = if (report.comparedMonthToDate) "this time last month"
    else report.comparedMonth.minusMonths(1).month.getDisplayName(TextStyle.FULL, Locale.getDefault())
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
                rising -> "$percent% more than $against"
                falling -> "$percent% less than $against"
                else -> "Level with $against"
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
            // The other direction: bills somebody else paid for me, still to be paid back.
            if (report.iOwePaise > 0) Row(
                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "You owe others",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    money(report.iOwePaise),
                    style = MaterialTheme.typography.titleSmall.merge(MoneyTextStyle),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
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
                    centreCaption = report.period.label()
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
    // Its entrance comes from the screen's staggered reveal, like every other card.
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Section("Patterns")
        run {
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
