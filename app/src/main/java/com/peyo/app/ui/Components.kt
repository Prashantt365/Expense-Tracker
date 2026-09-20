package com.peyo.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.LocalGroceryStore
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.peyo.app.ui.theme.LocalChartPalette
import com.peyo.app.ui.theme.MoneyTextStyle
import java.util.Locale

// --- Type and headings ----------------------------------------------------------------------

/**
 * A heading above a group of cards. Uppercase and small rather than large and bold: the cards
 * below it already carry their own titles, and two competing bold lines is what made the settings
 * screen read as an undifferentiated stack.
 */
@Composable
fun Section(title: String, modifier: Modifier = Modifier) {
    Text(
        title.uppercase(Locale.getDefault()),
        modifier.padding(start = 4.dp, top = 8.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        letterSpacing = 1.sp
    )
}

/** An amount, in figures that line up when several are stacked in a column. */
@Composable
fun MoneyText(
    paise: Long,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = Color.Unspecified,
    weight: FontWeight? = FontWeight.SemiBold
) {
    Text(
        money(paise),
        modifier,
        style = style.merge(MoneyTextStyle),
        color = color,
        fontWeight = weight,
        maxLines = 1
    )
}

// --- Surfaces -------------------------------------------------------------------------------

/**
 * The card used everywhere in place of the Material default.
 *
 * Flat, on a container tone rather than an elevated white: the screens are dense enough that a
 * shadow under every row turns into visual noise, and a tonal difference separates a card from the
 * background just as well while leaving the charts inside it uncontested.
 */
@Composable
fun PeyoCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    container: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = CardDefaults.cardColors(containerColor = container)
    val shape = MaterialTheme.shapes.large
    if (onClick != null) {
        Card(onClick, modifier.fillMaxWidth(), shape = shape, colors = colors, content = content)
    } else {
        Card(modifier.fillMaxWidth(), shape = shape, colors = colors, content = content)
    }
}

/** The same card in the error tone, for anything that needs a decision or that went wrong. */
@Composable
fun AlertCard(
    text: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    PeyoCard(modifier, onClick, MaterialTheme.colorScheme.errorContainer) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            icon?.let { Icon(it, null, tint = MaterialTheme.colorScheme.onErrorContainer) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                title?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            trailing?.invoke()
        }
    }
}

/**
 * What a screen shows before it has anything to show.
 *
 * An empty list was two lines of plain text pinned to the middle of the screen, which reads as a
 * failure. An icon, a sentence and the button that fixes it reads as a starting point.
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: @Composable (() -> Unit)? = null
) {
    Column(
        modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            Modifier.size(72.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, Modifier.size(34.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        action?.let { Spacer(Modifier.height(4.dp)); it() }
    }
}

/** A label under a figure, as used in the rows of small statistics beneath a headline. */
@Composable
fun Stat(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = Color.Unspecified
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            value,
            style = MaterialTheme.typography.titleMedium.merge(MoneyTextStyle).copy(textAlign = TextAlign.Start),
            fontWeight = FontWeight.Bold,
            color = valueColor,
            maxLines = 1
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// --- Identity -------------------------------------------------------------------------------

/**
 * A person's initials on a colour derived from their name.
 *
 * The colour is a hash rather than a stored field, so the same person is the same colour on every
 * screen and on a second phone, without a migration to carry it.
 */
@Composable
fun PersonAvatar(name: String, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val colour = LocalChartPalette.current.at(hashOf(name))
    Box(
        modifier.size(size).clip(CircleShape).background(colour.copy(alpha = 0.18f))
            .border(1.dp, colour.copy(alpha = 0.35f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            initials(name),
            style = MaterialTheme.typography.labelLarge,
            color = colour,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * A stable colour index for a name.
 *
 * String.hashCode is specified by the language rather than left to the implementation, so this
 * gives the same person the same colour on every device and after every reinstall without a
 * stored field. Summing the character codes would do that too and was the first attempt, but it
 * spreads badly over a short palette -- Food, Health and Shopping all landed on the same green.
 */
private fun hashOf(text: String): Int = text.lowercase(Locale.ROOT).hashCode()

private fun initials(name: String): String {
    val parts = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    return when {
        parts.isEmpty() -> "?"
        parts.size == 1 -> parts[0].take(2).uppercase(Locale.getDefault())
        else -> (parts.first().take(1) + parts.last().take(1)).uppercase(Locale.getDefault())
    }
}

/**
 * An icon for a category, matched on the name.
 *
 * Categories are free text the user edits, so this cannot be a field on a fixed list. The keywords
 * cover the seeded defaults and the names people actually type; anything unrecognised gets the
 * generic mark, which is an honest answer rather than a blank.
 */
fun categoryIcon(category: String): ImageVector {
    val name = category.lowercase(Locale.ROOT)
    fun has(vararg words: String) = words.any { it in name }
    return when {
        has("coffee", "cafe", "tea", "bar") -> Icons.Default.LocalCafe
        has("grocer", "supermarket", "market", "kirana") -> Icons.Default.LocalGroceryStore
        has("food", "restaurant", "dining", "eat", "lunch", "dinner", "meal", "snack") ->
            Icons.Default.Restaurant
        has("bike", "cycle", "scooter") -> Icons.AutoMirrored.Filled.DirectionsBike
        has("transport", "taxi", "cab", "uber", "fuel", "petrol", "car", "travel") ->
            Icons.Default.DirectionsCar
        has("flight", "trip", "holiday", "vacation") -> Icons.Default.Flight
        has("phone", "mobile", "internet", "broadband", "recharge") -> Icons.Default.Phone
        has("bill", "utility", "electric", "water", "gas", "power") -> Icons.Default.Bolt
        has("rent", "house", "home", "maintenance") -> Icons.Default.Home
        has("clothes", "apparel", "fashion", "wear") -> Icons.Default.Checkroom
        has("shop", "amazon", "store", "purchase") -> Icons.Default.ShoppingBag
        has("health", "doctor", "medicine", "pharmacy", "hospital", "clinic") ->
            Icons.Default.MedicalServices
        has("fitness", "gym", "sport", "yoga") -> Icons.Default.Favorite
        has("game", "gaming") -> Icons.Default.SportsEsports
        has("movie", "cinema", "entertain", "subscription", "music") -> Icons.Default.Movie
        has("education", "course", "school", "college", "tuition") -> Icons.Default.School
        has("book", "reading", "stationery") -> Icons.AutoMirrored.Filled.MenuBook
        has("gift", "donat", "charity") -> Icons.Default.CardGiftcard
        has("pet", "dog", "cat") -> Icons.Default.Pets
        has("other", "misc") -> Icons.Default.Category
        has("receipt", "invoice", "tax") -> Icons.AutoMirrored.Filled.ReceiptLong
        else -> Icons.Default.AutoAwesome
    }
}

/**
 * The colour a category is drawn in, everywhere it appears.
 *
 * Derived from the name rather than from its rank in the current period. Rank was the obvious
 * choice and the wrong one: Food would be green in a month it led the list and blue in a month it
 * did not, so the colour would carry no information at all. Two categories can collide, which
 * costs nothing -- every chart that uses these also labels its rows.
 */
@Composable
fun categoryColor(category: String): Color = LocalChartPalette.current.at(hashOf(category))

/** The category icon in its own tinted tile, as it appears against every transaction row. */
@Composable
fun CategoryBadge(category: String, modifier: Modifier = Modifier, size: Dp = 42.dp) {
    val colour = categoryColor(category)
    Box(
        modifier.size(size).clip(MaterialTheme.shapes.medium).background(colour.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(categoryIcon(category), null, Modifier.size(size * 0.5f), tint = colour)
    }
}

// --- Chips ----------------------------------------------------------------------------------

/**
 * A wrapping row of single-choice chips.
 *
 * This used to chunk the options three to a row, which put "Transport" and "Shopping" on lines of
 * their own and left half the width empty beside "Food". FlowRow packs by measured width, so the
 * rows fill out and the count per line follows the font size the user actually set.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FlowChips(
    options: List<String>,
    selected: String,
    modifier: Modifier = Modifier,
    withIcons: Boolean = true,
    onSelect: (String) -> Unit
) {
    FlowRow(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(option, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = if (!withIcons) null else {
                    { Icon(categoryIcon(option), null, Modifier.size(FilterChipDefaults.IconSize)) }
                },
                shape = MaterialTheme.shapes.small
            )
        }
    }
}

/** A small read-only pill, for a count or a state that is shown rather than chosen. */
@Composable
fun Tag(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    container: Color = MaterialTheme.colorScheme.secondaryContainer,
    content: Color = MaterialTheme.colorScheme.onSecondaryContainer
) {
    Row(
        modifier.clip(MaterialTheme.shapes.extraSmall).background(container)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        icon?.let { Icon(it, null, Modifier.size(13.dp), tint = content) }
        Text(text, style = MaterialTheme.typography.labelSmall, color = content, maxLines = 1)
    }
}

// --- Bars -----------------------------------------------------------------------------------

/** A thin proportional bar that grows into place, used inside list rows. */
@Composable
fun ProgressTrack(
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 6.dp,
    track: Color = MaterialTheme.colorScheme.surfaceContainerHighest
) {
    val animated by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(500), label = "progress")
    val barColor by animateColorAsState(color, label = "progressColour")
    Box(modifier.fillMaxWidth().height(height).clip(CircleShape).background(track)) {
        if (animated > 0f) Box(
            Modifier.fillMaxWidth(animated).height(height).clip(CircleShape).background(barColor)
        )
    }
}

/** A fixed-width gap, spelled once so call sites stay on one line. */
@Composable
fun Gap(width: Dp = 8.dp) = Spacer(Modifier.width(width))

/**
 * A screen that covers the app, rendered in the activity's own window rather than a dialog.
 *
 * The editor, the statement review and the contact picker are all whole screens. Compose's Dialog
 * would give each one a window of its own, and inside that window the layout came out wrong in a
 * way no amount of padding fixed: the window is inset for the status bar but reports the insets
 * again to the content, so a column of app bar, scrolling middle and pinned action measured the
 * bars twice and squeezed the action at the foot to a few pixels against the bottom of the screen.
 *
 * Drawing them in the activity's window instead costs nothing -- they were already full screen --
 * and they inherit the edge-to-edge setup the activity already has. [BackHandler] is what keeps
 * the system back gesture dismissing them, which the dialog used to provide.
 */
@Composable
fun FullScreenOverlay(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    BackHandler(onBack = onDismiss)
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding(), content = content)
    }
}

/** The action pinned to the foot of a [FullScreenOverlay]: one button, always reachable. */
@Composable
fun OverlayAction(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Button(onClick, Modifier.fillMaxWidth().padding(16.dp), enabled = enabled) { Text(label) }
    }
}

/** The app bar at the head of a [FullScreenOverlay]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverlayBar(onDismiss: () -> Unit, title: @Composable () -> Unit) {
    TopAppBar(
        title = title,
        navigationIcon = { IconButton(onDismiss) { Icon(Icons.Default.Close, "Cancel") } },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        windowInsets = WindowInsets(0)
    )
}
