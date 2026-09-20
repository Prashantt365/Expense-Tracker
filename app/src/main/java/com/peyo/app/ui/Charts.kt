package com.peyo.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.peyo.app.ui.theme.ChartPalette
import com.peyo.app.ui.theme.LocalChartPalette

/**
 * The palette every chart draws with, taken from the theme so that a category keeps its hue when
 * the phone switches between light and dark instead of being redrawn in a colour that vanishes
 * into the surface behind it.
 */
val chartPalette: ChartPalette
    @Composable get() = LocalChartPalette.current

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChartLegend(entries: List<Pair<String, Color>>, modifier: Modifier = Modifier) {
    FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        entries.forEach { (label, color) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(Modifier.size(9.dp).clip(CircleShape).background(color))
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

data class StackedBar(val label: String, val lower: Long, val upper: Long)

/**
 * Twelve months of spend, each bar stacked as my own share beneath what was assigned to others.
 *
 * Bars grow from the baseline on first composition and whenever the period changes, which is what
 * makes a switch between This month and All time read as the same chart rescaling rather than as
 * a new one appearing. The current month is marked, because a partial month sitting at the right
 * hand end otherwise looks like a collapse in spending.
 */
@Composable
fun MonthlyBars(
    points: List<StackedBar>,
    modifier: Modifier = Modifier,
    height: Int = 168
) {
    val palette = chartPalette
    val max = points.maxOfOrNull { it.lower + it.upper }?.takeIf { it > 0 } ?: 1L
    val outline = MaterialTheme.colorScheme.outlineVariant
    val highlight = MaterialTheme.colorScheme.onSurfaceVariant
    val grow by animateFloatAsState(if (points.isEmpty()) 0f else 1f, tween(650), label = "bars")

    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(height.dp)) {
            if (points.isEmpty()) return@Canvas
            val slot = size.width / points.size
            val barWidth = (slot * 0.5f).coerceAtMost(26.dp.toPx())
            val radius = CornerRadius(barWidth / 3)

            // Three faint gridlines, so a bar can be read as a value rather than only against its
            // neighbours. Drawn under the bars and in the outline tone, never in a content colour.
            listOf(0.25f, 0.5f, 0.75f).forEach { at ->
                val y = size.height * (1f - at)
                drawLine(
                    outline.copy(alpha = 0.4f),
                    Offset(0f, y),
                    Offset(size.width, y),
                    strokeWidth = 1.dp.toPx()
                )
            }
            drawLine(outline, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())

            points.forEachIndexed { index, point ->
                val centre = slot * index + slot / 2
                val left = centre - barWidth / 2
                val lowerHeight = size.height * (point.lower.toFloat() / max) * grow
                val upperHeight = size.height * (point.upper.toFloat() / max) * grow

                if (upperHeight > 0f) drawRoundRect(
                    color = palette.others,
                    topLeft = Offset(left, size.height - lowerHeight - upperHeight),
                    size = Size(barWidth, upperHeight + lowerHeight.coerceAtMost(radius.y * 2)),
                    cornerRadius = radius
                )
                if (lowerHeight > 0f) drawRoundRect(
                    color = palette.mine,
                    topLeft = Offset(left, size.height - lowerHeight),
                    size = Size(barWidth, lowerHeight),
                    cornerRadius = radius
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            points.forEachIndexed { index, point ->
                val isCurrent = index == points.lastIndex
                Text(
                    point.label,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.sp,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                    color = if (isCurrent) highlight else MaterialTheme.colorScheme.outline,
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )
            }
        }
    }
}

/** Category mix as a ring, with the period total in the middle. */
@Composable
fun DonutChart(
    slices: List<Pair<Float, Color>>,
    centreLabel: String,
    centreCaption: String,
    modifier: Modifier = Modifier,
    diameter: Int = 176
) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val sweepFraction by animateFloatAsState(1f, tween(700), label = "donut")

    Box(modifier.size(diameter.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val thickness = 24.dp.toPx()
            val inset = thickness / 2
            val arcSize = Size(size.width - thickness, size.height - thickness)
            drawArc(
                color = track,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = thickness)
            )
            var start = -90f
            slices.forEach { (fraction, colour) ->
                val sweep = fraction * 360f * sweepFraction
                // A hairline gap keeps adjacent slices distinguishable without a border colour,
                // and the cap is butt rather than round: a rounded cap on a one per cent slice
                // draws a dot wider than the slice itself, which reads as a stray mark sitting on
                // top of the ring rather than as part of it.
                drawArc(
                    color = colour,
                    startAngle = start + 0.8f,
                    sweepAngle = (sweep - 1.6f).coerceAtLeast(0.4f),
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = thickness, cap = StrokeCap.Butt)
                )
                start += sweep
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                centreLabel,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
            Text(
                centreCaption,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** A labelled row with a proportional bar: used for both categories and people. */
@Composable
fun BarRow(
    label: String,
    value: String,
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
    caption: String? = null,
    leading: @Composable (() -> Unit)? = null
) {
    Row(
        modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1
                )
                Text(
                    value,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            ProgressTrack(fraction, color, Modifier.padding(top = 5.dp), height = 7.dp)
            caption?.let {
                Text(
                    it,
                    Modifier.padding(top = 3.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * A person's shared total split into what came back and what I am still carrying, so the
 * recovered and outstanding parts can be compared directly.
 */
@Composable
fun SettlementBar(
    settledPaise: Long,
    outstandingPaise: Long,
    modifier: Modifier = Modifier,
    /**
     * How much of the full width this row is entitled to. Pass each person's total over the
     * largest person's total to make the rows comparable with each other; leave at 1 for a
     * standalone bar, where only the recovered/owed ratio matters.
     */
    scale: Float = 1f
) {
    val palette = chartPalette
    val total = (settledPaise + outstandingPaise).coerceAtLeast(1)
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val grow by animateFloatAsState(1f, tween(600), label = "settlement")

    Canvas(modifier.fillMaxWidth().height(10.dp)) {
        val radius = CornerRadius(size.height / 2)
        drawRoundRect(color = track, cornerRadius = radius)

        val span = size.width * scale.coerceIn(0f, 1f) * grow
        val settledWidth = span * (settledPaise.toFloat() / total)
        if (settledWidth > 0f) drawRoundRect(
            color = palette.settled,
            size = Size(settledWidth, size.height),
            cornerRadius = radius
        )
        val outstandingWidth = span * (outstandingPaise.toFloat() / total)
        if (outstandingWidth > 0f) drawRoundRect(
            color = palette.others,
            topLeft = Offset(span - outstandingWidth, 0f),
            size = Size(outstandingWidth, size.height),
            cornerRadius = radius
        )
    }
}

/** Which days of the week the money actually goes out on. */
@Composable
fun WeekdayBars(points: List<Pair<String, Long>>, modifier: Modifier = Modifier) {
    val palette = chartPalette
    val max = points.maxOfOrNull { it.second }?.takeIf { it > 0 } ?: 1L
    val busiest = points.maxByOrNull { it.second }?.first
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val grow by animateFloatAsState(1f, tween(600), label = "weekdays")

    Row(modifier.fillMaxWidth().height(104.dp), verticalAlignment = Alignment.Bottom) {
        points.forEach { (label, paise) ->
            val isBusiest = label == busiest && paise > 0
            Column(
                Modifier.weight(1f).fillMaxHeight(),
                verticalArrangement = Arrangement.Bottom,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Canvas(Modifier.width(20.dp).weight(1f)) {
                    val radius = CornerRadius(6.dp.toPx())
                    val barHeight = (size.height * (paise.toFloat() / max) * grow).coerceAtLeast(3f)
                    drawRoundRect(color = track, cornerRadius = radius)
                    drawRoundRect(
                        color = if (isBusiest) palette.mine else palette.mine.copy(alpha = 0.55f),
                        topLeft = Offset(0f, size.height - barHeight),
                        size = Size(size.width, barHeight),
                        cornerRadius = radius
                    )
                }
                Text(
                    label,
                    Modifier.padding(top = 6.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    fontWeight = if (isBusiest) FontWeight.Bold else FontWeight.Normal,
                    color = if (isBusiest) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.outline,
                    maxLines = 1
                )
            }
        }
    }
}
