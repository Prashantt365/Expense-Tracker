package com.peyo.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.peyo.app.R
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * One card of the tour: what the feature is called, a sentence on what it does, and a short
 * animation of it happening. [art] is handed the animation's progress, 0 to 1, once per loop.
 */
private class Feature(
    val title: String,
    val body: String,
    val art: @Composable (t: Float) -> Unit
)

private val features = listOf(
    Feature(
        "Every expense, in a few taps",
        "Add what you spend in seconds: the date, time and category are already filled in. " +
            "Widgets and shortcuts put it on your home screen."
    ) { ExpensesArt(it) },
    Feature(
        "Share a screenshot, skip the typing",
        "Share a payment screenshot or a receipt with Peyo. It reads the amount and the merchant " +
            "for you, and keeps the image with the expense."
    ) { ScreenshotArt(it) },
    Feature(
        "Bring in your bank statement",
        "Import a statement PDF and Peyo picks out every transaction in it. Tick the ones to " +
            "keep, and they are added in one go."
    ) { StatementArt(it) },
    Feature(
        "Split it, and know who owes what",
        "Share a bill equally or your own way. Peyo keeps a running balance for each person, and " +
            "settling up is one tap."
    ) { SplitArt(it) },
    Feature(
        "See where your money goes",
        "Month by month, by category and by person, with what others owe you kept out of your " +
            "own spending."
    ) { InsightsArt(it) },
    Feature(
        "Yours, backed up, offline-first",
        "Everything is saved on this phone first and works with no connection. Sign in to back " +
            "it up and bring it to a new phone."
    ) { SyncArt(it) }
)

/** How long one loop of a card's animation runs, including the pause on its finished frame. */
private const val LOOP_MILLIS = 4200

/**
 * The tour a new install opens on: one card per feature, swiped through or skipped.
 *
 * The pager has one page more than there are features, left blank. Swiping on from the last card
 * lands there and finishes the tour, so running out of cards behaves the same as tapping
 * Get started -- nobody is left pushing at a last page that will not move.
 */
@Composable
fun FeatureTour(onFinished: () -> Unit) {
    val finish by rememberUpdatedState(onFinished)
    val pager = rememberPagerState { features.size + 1 }
    val scope = rememberCoroutineScope()
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { if (it >= features.size) finish() }
    }
    val current = pager.currentPage.coerceAtMost(features.size - 1)
    val onLast = current == features.size - 1
    val scheme = MaterialTheme.colorScheme

    Surface(Modifier.fillMaxSize(), color = scheme.background) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().height(56.dp).padding(start = 24.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    painterResource(R.drawable.peyo_wordmark),
                    contentDescription = "Peyo",
                    Modifier.height(26.dp),
                    colorFilter = ColorFilter.tint(scheme.primary)
                )
                Spacer(Modifier.weight(1f))
                AnimatedVisibility(!onLast, enter = fadeIn(), exit = fadeOut()) {
                    TextButton({ finish() }) { Text("Skip") }
                }
            }

            HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { page ->
                if (page < features.size) {
                    // How far this page is from the centre: 0 when it is the one in view, ±1 a
                    // whole page away. Drives the parallax and the fade of the words.
                    val offset = (pager.currentPage - page) + pager.currentPageOffsetFraction
                    FeaturePage(features[page], active = pager.settledPage == page, offset = offset)
                } else {
                    Box(Modifier.fillMaxSize())
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    features.indices.forEach { index -> Dot(selected = index == current) }
                }
                Spacer(Modifier.weight(1f))
                Button({
                    if (onLast) finish()
                    else scope.launch { pager.animateScrollToPage(current + 1) }
                }) {
                    Text(if (onLast) "Get started" else "Next")
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun Dot(selected: Boolean) {
    val width by animateDpAsState(if (selected) 22.dp else 8.dp, tween(250), label = "dot")
    val colour by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        tween(250),
        label = "dotColour"
    )
    Box(Modifier.height(8.dp).width(width).clip(CircleShape).background(colour))
}

@Composable
private fun FeaturePage(feature: Feature, active: Boolean, offset: Float) {
    // Runs only on the card in view, and from the start each time it arrives, so the animation is
    // seen from its beginning. A card half swiped in shows its finished frame instead.
    val progress = remember { Animatable(REST) }
    LaunchedEffect(active) {
        if (!active) {
            progress.snapTo(REST)
            return@LaunchedEffect
        }
        while (true) {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(LOOP_MILLIS, easing = LinearEasing))
        }
    }
    val scheme = MaterialTheme.colorScheme
    val distance = abs(offset).coerceAtMost(1f)

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Box(
            Modifier.fillMaxWidth().weight(1f).clip(MaterialTheme.shapes.extraLarge)
                .background(
                    Brush.verticalGradient(
                        listOf(scheme.primaryContainer.copy(alpha = 0.55f), scheme.surfaceContainerLow)
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier.graphicsLayer {
                    // The art drifts a little against the swipe, so it reads as sitting behind
                    // the card rather than printed on it.
                    translationX = offset * size.width * 0.35f
                    val scale = 1f - distance * 0.12f
                    scaleX = scale
                    scaleY = scale
                    alpha = 1f - distance * 0.6f
                },
                contentAlignment = Alignment.Center
            ) {
                // A short fade at the end of each loop, so starting over is not a jump cut.
                val t = progress.value
                Box(Modifier.graphicsLayer { alpha = 1f - phase(t, 0.94f, 1f) }) { feature.art(t) }
            }
        }
        Spacer(Modifier.height(28.dp))
        Column(
            Modifier.fillMaxWidth().graphicsLayer {
                alpha = 1f - distance * 1.4f
                translationX = offset * 60.dp.toPx()
            },
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                feature.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                feature.body,
                style = MaterialTheme.typography.bodyLarge,
                color = scheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** Where a card that is not in view rests: every element of its art in place. */
private const val REST = 0.85f

/** Progress through one stretch, [from] to [to], of a loop at [t], eased. */
private fun phase(t: Float, from: Float, to: Float): Float =
    FastOutSlowInEasing.transform(((t - from) / (to - from)).coerceIn(0f, 1f))

/** Fades and lifts something in as [p] goes from 0 to 1. */
private fun Modifier.rise(p: Float, distance: Dp = 24.dp): Modifier = graphicsLayer {
    alpha = p
    translationY = (1f - p) * distance.toPx()
}

/** Pops something in from small as [p] goes from 0 to 1. */
private fun Modifier.pop(p: Float): Modifier = graphicsLayer {
    alpha = p
    val scale = 0.6f + 0.4f * p
    scaleX = scale
    scaleY = scale
}

// --- The art -----------------------------------------------------------------------------------

@Composable
private fun ExpensesArt(t: Float) {
    val rows = listOf(
        Triple(Icons.Default.Restaurant, "Lunch", 240L),
        Triple(Icons.Default.DirectionsCar, "Cab home", 180L),
        Triple(Icons.Default.ShoppingBag, "Groceries", 1120L)
    )
    val shown = rows.mapIndexed { index, _ -> phase(t, 0.12f + index * 0.16f, 0.34f + index * 0.16f) }
    val total = rows.indices.sumOf { (rows[it].third * shown[it]).toDouble() }
    val scheme = MaterialTheme.colorScheme

    Column(Modifier.width(260.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(
            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(scheme.primary)
                .padding(16.dp)
        ) {
            Text("Today", style = MaterialTheme.typography.labelLarge, color = scheme.onPrimary.copy(alpha = 0.8f))
            Text(
                money((total * 100).roundToInt().toLong()),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = scheme.onPrimary
            )
        }
        rows.forEachIndexed { index, (icon, label, amount) ->
            Row(
                Modifier.fillMaxWidth().rise(shown[index]).clip(MaterialTheme.shapes.medium)
                    .background(scheme.surface).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                IconBubble(icon, chartPalette.at(index + 1))
                Text(label, Modifier.weight(1f), fontWeight = FontWeight.Medium)
                Text(money(amount * 100), fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ScreenshotArt(t: Float) {
    val scheme = MaterialTheme.colorScheme
    val scan = phase(t, 0.06f, 0.46f)
    val scanAlpha = 1f - phase(t, 0.46f, 0.52f)

    Box(Modifier.size(width = 280.dp, height = 290.dp), contentAlignment = Alignment.Center) {
        // The phone, showing a payment receipt.
        Box(
            Modifier.size(width = 150.dp, height = 260.dp).clip(RoundedCornerShape(24.dp))
                .background(scheme.surface)
                .border(3.dp, scheme.onSurface.copy(alpha = 0.75f), RoundedCornerShape(24.dp))
        ) {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(Icons.Default.CheckCircle, null, Modifier.size(30.dp), tint = chartPalette.settled)
                Placeholder(70.dp, 8.dp)
                Placeholder(96.dp, 18.dp, scheme.onSurface.copy(alpha = 0.55f))
                Spacer(Modifier.height(6.dp))
                repeat(4) { Placeholder(if (it % 2 == 0) 110.dp else 84.dp, 7.dp) }
            }
            // The scanning line, sweeping down the screenshot as it is read.
            Box(
                Modifier.fillMaxWidth().height(36.dp)
                    .graphicsLayer {
                        translationY = scan * (260.dp.toPx() - 36.dp.toPx())
                        alpha = scanAlpha
                    }
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, scheme.primary.copy(alpha = 0.35f), scheme.primary)
                        )
                    )
            )
        }
        // What was read off it.
        Chip("Paid ${money(45000)}", Alignment.TopEnd, phase(t, 0.5f, 0.6f), x = 0.dp, y = 84.dp)
        Chip("Swiggy", Alignment.CenterStart, phase(t, 0.58f, 0.68f), x = 0.dp, y = 10.dp)
        Chip("Food", Alignment.BottomEnd, phase(t, 0.66f, 0.76f), x = (-6).dp, y = (-40).dp)
    }
}

@Composable
private fun BoxScope.Chip(text: String, alignment: Alignment, p: Float, x: Dp, y: Dp) {
    Row(
        Modifier.align(alignment).offset(x, y).pop(p).clip(CircleShape)
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            Icons.Default.CheckCircle,
            null,
            Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onTertiaryContainer
        )
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onTertiaryContainer
        )
    }
}

@Composable
private fun StatementArt(t: Float) {
    val scheme = MaterialTheme.colorScheme
    val amounts = listOf(1299L, 450L, 2100L, 89L, 640L)
    val found = phase(t, 0.7f, 0.8f)

    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(
            Modifier.width(240.dp).clip(MaterialTheme.shapes.large).background(scheme.surface)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.PictureAsPdf, null, Modifier.size(26.dp), tint = scheme.error)
                Text("statement.pdf", fontWeight = FontWeight.SemiBold)
            }
            amounts.forEachIndexed { index, amount ->
                val ticked = phase(t, 0.14f + index * 0.1f, 0.22f + index * 0.1f)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.RadioButtonUnchecked,
                            null,
                            Modifier.size(20.dp),
                            tint = scheme.outline
                        )
                        Icon(
                            Icons.Default.CheckCircle,
                            null,
                            Modifier.size(20.dp).pop(ticked),
                            tint = scheme.primary
                        )
                    }
                    Box(Modifier.weight(1f)) { Placeholder(if (index % 2 == 0) 90.dp else 70.dp, 8.dp) }
                    Text(
                        money(amount * 100),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (ticked > 0.5f) scheme.onSurface else scheme.onSurfaceVariant
                    )
                }
            }
        }
        Text(
            "${amounts.size} transactions found",
            Modifier.rise(found).clip(CircleShape).background(scheme.primary)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = scheme.onPrimary
        )
    }
}

@Composable
private fun SplitArt(t: Float) {
    val scheme = MaterialTheme.colorScheme
    val people = listOf("Priya", "Asha", "Rahul")
    val angles = listOf(-90.0, 30.0, 150.0)
    val radius = 100.dp
    val outward = people.indices.map { phase(t, 0.14f + it * 0.07f, 0.44f + it * 0.07f) }
    val settled = phase(t, 0.66f, 0.76f)
    val line = scheme.primary.copy(alpha = 0.35f)

    Box(Modifier.size(280.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val centre = Offset(size.width / 2, size.height / 2)
            angles.forEachIndexed { index, angle ->
                val r = radius.toPx() * outward[index]
                val end = Offset(
                    centre.x + (cos(Math.toRadians(angle)) * r).toFloat(),
                    centre.y + (sin(Math.toRadians(angle)) * r).toFloat()
                )
                drawLine(line, centre, end, strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
            }
        }
        people.forEachIndexed { index, name ->
            val r = radius * outward[index]
            Column(
                Modifier.offset(
                    x = r * cos(Math.toRadians(angles[index])).toFloat(),
                    y = r * sin(Math.toRadians(angles[index])).toFloat() + 12.dp
                ).graphicsLayer { alpha = outward[index] },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                PersonAvatar(name, size = 50.dp)
                Text(
                    money(30000),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Column(
            Modifier.size(92.dp).clip(CircleShape).background(scheme.primary),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("Dinner", style = MaterialTheme.typography.labelMedium, color = scheme.onPrimary.copy(alpha = 0.8f))
            Text(money(90000), fontWeight = FontWeight.Bold, color = scheme.onPrimary)
        }
        Row(
            Modifier.align(Alignment.BottomCenter).rise(settled).clip(CircleShape)
                .background(scheme.surface).padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(Icons.Default.CheckCircle, null, Modifier.size(16.dp), tint = chartPalette.settled)
            Text("Rahul settled up", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun InsightsArt(t: Float) {
    val scheme = MaterialTheme.colorScheme
    val palette = chartPalette
    val slices = listOf(0.45f to palette.at(1), 0.3f to palette.at(3), 0.25f to palette.at(5))
    val sweep = phase(t, 0.04f, 0.46f)
    val bars = listOf(0.55f, 0.8f, 0.45f, 0.95f, 0.65f)
    val pill = phase(t, 0.68f, 0.78f)

    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(26.dp)) {
            Box(Modifier.size(130.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = 18.dp.toPx()
                    val inset = stroke / 2
                    val arcSize = Size(size.width - stroke, size.height - stroke)
                    drawArc(
                        scheme.outlineVariant.copy(alpha = 0.4f), 0f, 360f, false,
                        Offset(inset, inset), arcSize, style = Stroke(stroke)
                    )
                    var start = -90f
                    slices.forEach { (fraction, colour) ->
                        // Each slice draws only the part of the sweep that has reached it.
                        val full = fraction * 360f
                        val reached = (sweep * 360f - (start + 90f)).coerceIn(0f, full)
                        if (reached > 0f) drawArc(
                            colour, start, reached, false, Offset(inset, inset), arcSize,
                            style = Stroke(stroke, cap = StrokeCap.Butt)
                        )
                        start += full
                    }
                }
                Text(moneyCompact(1240000), fontWeight = FontWeight.Bold)
            }
            Row(
                Modifier.height(110.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                bars.forEachIndexed { index, height ->
                    val grown = phase(t, 0.3f + index * 0.06f, 0.56f + index * 0.06f)
                    Box(
                        Modifier.width(14.dp).height(110.dp * height * grown)
                            .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                            .background(if (index == bars.lastIndex) scheme.primary else scheme.primary.copy(alpha = 0.4f))
                    )
                }
            }
        }
        Row(
            Modifier.rise(pill).clip(CircleShape).background(scheme.surface)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(Icons.AutoMirrored.Filled.TrendingDown, null, Modifier.size(18.dp), tint = palette.settled)
            Text(
                "12% less than last month",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun SyncArt(t: Float) {
    val scheme = MaterialTheme.colorScheme
    val done = phase(t, 0.5f, 0.6f)
    val offline = phase(t, 0.22f, 0.32f)
    val backed = phase(t, 0.62f, 0.72f)

    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(26.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBubble(Icons.Default.PhoneAndroid, scheme.primary, size = 84.dp)
            // Rows on their way up: three dots running along the line, twice a loop, so the
            // start of one loop picks up exactly where the last left off.
            Box(Modifier.width(110.dp).height(20.dp), contentAlignment = Alignment.CenterStart) {
                Box(
                    Modifier.fillMaxWidth().height(3.dp).clip(CircleShape)
                        .background(scheme.primary.copy(alpha = 0.2f))
                )
                repeat(3) { index ->
                    val along = (t * 2f + index / 3f) % 1f
                    Box(
                        Modifier.offset(x = (110 - 10).dp * along).size(10.dp).clip(CircleShape)
                            .background(scheme.primary.copy(alpha = 1f - abs(along - 0.5f)))
                    )
                }
            }
            Box {
                IconBubble(Icons.Default.Cloud, scheme.tertiary, size = 84.dp)
                Icon(
                    Icons.Default.CheckCircle,
                    null,
                    Modifier.align(Alignment.BottomEnd).size(28.dp).pop(done).clip(CircleShape)
                        .background(scheme.surface),
                    tint = chartPalette.settled
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TagPill(Icons.Default.WifiOff, "Works offline", Modifier.rise(offline))
            TagPill(Icons.Default.CheckCircle, "Backed up", Modifier.rise(backed))
        }
    }
}

// --- Small parts the art is built from -------------------------------------------------------

@Composable
private fun IconBubble(icon: ImageVector, colour: Color, size: Dp = 38.dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(colour.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, Modifier.size(size * 0.5f), tint = colour)
    }
}

@Composable
private fun TagPill(icon: ImageVector, text: String, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    }
}

/** A grey bar standing in for a line of text. */
@Composable
private fun Placeholder(
    width: Dp,
    height: Dp,
    colour: Color = MaterialTheme.colorScheme.outlineVariant,
    modifier: Modifier = Modifier
) {
    Box(modifier.width(width).height(height).clip(CircleShape).background(colour))
}
