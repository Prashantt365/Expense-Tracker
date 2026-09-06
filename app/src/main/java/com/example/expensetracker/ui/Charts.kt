package com.example.expensetracker.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A categorical palette that stays legible on both the light and dark Material surfaces, so a
 * chart does not have to be re-tuned per theme.
 */
object ChartColors {
    private val palette = listOf(
        Color(0xFF6750A4), Color(0xFF2E7D6F), Color(0xFFB3541E), Color(0xFF3B6FB6),
        Color(0xFF8E4585), Color(0xFF77702A), Color(0xFF9C4146), Color(0xFF4B6358)
    )

    fun at(index: Int): Color = palette[index.mod(palette.size)]

    /** Reserved roles, kept consistent everywhere: mine vs what is carried for other people. */
    val mine = Color(0xFF6750A4)
    val others = Color(0xFFB3541E)
    val settled = Color(0xFF2E7D6F)
}

@Composable
fun ChartLegend(entries: List<Pair<String, Color>>, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        entries.forEach { (label, color) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(color))
                Text(label, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/**
 * Twelve months of spend, each bar stacked as my own share beneath what was assigned to others.
 * Bars are drawn against the largest month so the shape of the year is readable at a glance.
 */
@Composable
fun MonthlyBars(
    points: List<StackedBar>,
    modifier: Modifier = Modifier,
    height: Int = 160
) {
    val max = points.maxOfOrNull { it.lower + it.upper }?.takeIf { it > 0 } ?: 1L
    val outline = MaterialTheme.colorScheme.outlineVariant
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(height.dp)) {
            val slot = size.width / points.size
            val barWidth = (slot * 0.55f).coerceAtMost(28.dp.toPx())
            // A single baseline: without it, short bars float with no reference.
            drawLine(
                outline,
                Offset(0f, size.height),
                Offset(size.width, size.height),
                strokeWidth = 1.dp.toPx()
            )
            points.forEachIndexed { index, point ->
                val centre = slot * index + slot / 2
                val left = centre - barWidth / 2
                val lowerHeight = size.height * (point.lower.toFloat() / max)
                val upperHeight = size.height * (point.upper.toFloat() / max)

                if (upperHeight > 0f) drawRect(
                    color = ChartColors.others,
                    topLeft = Offset(left, size.height - lowerHeight - upperHeight),
                    size = Size(barWidth, upperHeight)
                )
                if (lowerHeight > 0f) drawRect(
                    color = ChartColors.mine,
                    topLeft = Offset(left, size.height - lowerHeight),
                    size = Size(barWidth, lowerHeight)
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            points.forEach { point ->
                Text(
                    point.label,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    maxLines = 1
                )
            }
        }
    }
}

data class StackedBar(val label: String, val lower: Long, val upper: Long)

/** Category mix as a ring, with the period total in the middle. */
@Composable
fun DonutChart(
    slices: List<Float>,
    centreLabel: String,
    centreCaption: String,
    modifier: Modifier = Modifier,
    diameter: Int = 168
) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    Box(modifier.size(diameter.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val thickness = 26.dp.toPx()
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
            slices.forEachIndexed { index, fraction ->
                val sweep = fraction * 360f
                // A hairline gap keeps adjacent slices distinguishable without a border colour.
                drawArc(
                    color = ChartColors.at(index),
                    startAngle = start + 0.6f,
                    sweepAngle = (sweep - 1.2f).coerceAtLeast(0.6f),
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = thickness, cap = StrokeCap.Butt)
                )
                start += sweep
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(centreLabel, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(centreCaption, style = MaterialTheme.typography.labelSmall)
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
    caption: String? = null
) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    Column(modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        }
        Canvas(Modifier.fillMaxWidth().height(8.dp).padding(top = 3.dp)) {
            val radius = size.height / 2
            drawRoundRect(
                color = track,
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius)
            )
            val width = size.width * fraction.coerceIn(0f, 1f)
            if (width > 0f) drawRoundRect(
                color = color,
                size = Size(width.coerceAtLeast(radius * 2), size.height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius)
            )
        }
        caption?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
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
    val total = (settledPaise + outstandingPaise).coerceAtLeast(1)
    val track = MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier.fillMaxWidth().height(10.dp)) {
        val radius = size.height / 2
        drawRoundRect(color = track, cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius))

        val span = size.width * scale.coerceIn(0f, 1f)
        val settledWidth = span * (settledPaise.toFloat() / total)
        if (settledWidth > 0f) drawRoundRect(
            color = ChartColors.settled,
            size = Size(settledWidth, size.height),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius)
        )
        val outstandingWidth = span * (outstandingPaise.toFloat() / total)
        if (outstandingWidth > 0f) drawRoundRect(
            color = ChartColors.others,
            topLeft = Offset(span - outstandingWidth, 0f),
            size = Size(outstandingWidth, size.height),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius)
        )
    }
}

/** Which days of the week the money actually goes out on. */
@Composable
fun WeekdayBars(points: List<Pair<String, Long>>, modifier: Modifier = Modifier) {
    val max = points.maxOfOrNull { it.second }?.takeIf { it > 0 } ?: 1L
    val track = MaterialTheme.colorScheme.surfaceVariant
    Row(modifier.fillMaxWidth().height(96.dp), verticalAlignment = Alignment.Bottom) {
        points.forEach { (label, paise) ->
            Column(
                Modifier.weight(1f).fillMaxHeight(),
                verticalArrangement = Arrangement.Bottom,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Canvas(Modifier.width(18.dp).weight(1f)) {
                    val radius = 4.dp.toPx()
                    val barHeight = (size.height * (paise.toFloat() / max)).coerceAtLeast(2f)
                    drawRoundRect(
                        color = track,
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius)
                    )
                    drawRoundRect(
                        color = ChartColors.mine,
                        topLeft = Offset(0f, size.height - barHeight),
                        size = Size(size.width, barHeight),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius)
                    )
                }
                Text(label, style = MaterialTheme.typography.labelSmall, fontSize = 10.sp, maxLines = 1)
            }
        }
    }
}
