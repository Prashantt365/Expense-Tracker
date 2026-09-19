package com.example.expensetracker.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.expensetracker.AppCurrency
import com.example.expensetracker.ExpenseInput
import com.example.expensetracker.SplitCalculator
import com.example.expensetracker.SplitMode
import com.example.expensetracker.SplitResult
import com.example.expensetracker.data.Category
import com.example.expensetracker.data.Expense
import com.example.expensetracker.data.Person
import com.example.expensetracker.ui.theme.MoneyTextStyle
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpenseEditor(
    input: ExpenseInput,
    categories: List<Category>,
    people: List<Person>,
    fromScreenshot: Boolean,
    error: String?,
    duplicateOf: Expense?,
    onDismiss: () -> Unit,
    onSave: (ExpenseInput, force: Boolean) -> Unit
) {
    var draft by remember(input) { mutableStateOf(input) }
    var showPersonMenu by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<AttachmentPreview?>(null) }
    var pickingDate by remember { mutableStateOf(false) }
    var pickingTime by remember { mutableStateOf(false) }

    // A launcher shortcut or a widget opens this before the database has produced its categories,
    // so the initial pick is the hard-coded fallback and can even be a name that is not on the
    // list. Once they arrive, a draft the user has not touched follows the real first category;
    // one they have chosen from is left alone.
    var categoryChosen by remember(input) { mutableStateOf(input.id != 0L) }
    LaunchedEffect(categories) {
        if (categories.isEmpty()) return@LaunchedEffect
        if (!categoryChosen || categories.none { it.name == draft.category }) {
            draft = draft.copy(category = categories.first().name)
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(5)
    ) { uris -> if (uris.isNotEmpty()) draft = draft.copy(newAttachments = draft.newAttachments + uris) }

    // Preview the split live, using the same calculator that will run on save, so what the summary
    // shows and what gets stored can never drift apart.
    val totalPaise = amountToMinorUnits(draft.amount) ?: 0L
    val splitPreview = SplitCalculator.compute(totalPaise, draft.splitMode, draft.shares)
    val computedShares = (splitPreview as? SplitResult.Valid)
        ?.shares
        ?.filter { it.personId != null }
        ?.associate { it.personId!! to it.amountPaise }
        .orEmpty()
    val assignedPaise = computedShares.values.sum()
    val myShare = (splitPreview as? SplitResult.Valid)?.myShare ?: (totalPaise - assignedPaise)
    val splitProblem = (splitPreview as? SplitResult.Invalid)?.message?.takeIf { draft.shares.isNotEmpty() }
    val previews = draft.existingAttachments
        .filter { it.id !in draft.removedAttachmentIds }
        .map { AttachmentPreview.Stored(it.id, it.path) } +
        draft.newAttachments.map { AttachmentPreview.Picked(it) }

    FullScreenOverlay(onDismiss) {
        OverlayBar(onDismiss) {
            Text(
                when {
                    draft.id != 0L -> "Edit expense"
                    fromScreenshot -> "Review receipt"
                    else -> "Add expense"
                }
            )
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (fromScreenshot && draft.id == 0L) PeyoCard(
                container = MaterialTheme.colorScheme.tertiaryContainer
            ) {
                Text(
                    "These values were read off your screenshot. Check them before saving.",
                    Modifier.padding(14.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
            duplicateOf?.let { DuplicateWarning(it) }
            error?.let { AlertCard(it, icon = Icons.Default.Warning) }

            AmountField(draft.amount) { draft = draft.copy(amount = it) }

            OutlinedTextField(
                draft.merchant,
                { draft = draft.copy(merchant = it) },
                label = { Text("Merchant or payee") },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                draft.note,
                { draft = draft.copy(note = it) },
                label = { Text("Your note") },
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            )

            Section("When")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    { pickingDate = true },
                    { Text(shortDate(draft.paidAt)) },
                    leadingIcon = {
                        Icon(Icons.Default.CalendarToday, null, Modifier.size(16.dp))
                    },
                    shape = MaterialTheme.shapes.small
                )
                AssistChip(
                    { pickingTime = true },
                    { Text(timeLabel(draft.paidAt)) },
                    leadingIcon = {
                        Icon(Icons.Default.Schedule, null, Modifier.size(16.dp))
                    },
                    shape = MaterialTheme.shapes.small
                )
            }

            Section("Category")
            if (categories.isEmpty()) Text(
                "No categories yet. Add some under Settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            ) else FlowChips(categories.map { it.name }, draft.category) {
                categoryChosen = true
                draft = draft.copy(category = it)
            }

            Section("Attachments")
            if (previews.isNotEmpty()) LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(previews.size) { index ->
                    val preview = previews[index]
                    AttachmentThumb(
                        preview = preview,
                        onOpen = { viewing = preview },
                        onRemove = {
                            draft = when (preview) {
                                is AttachmentPreview.Stored -> draft.copy(
                                    removedAttachmentIds =
                                        draft.removedAttachmentIds + preview.id
                                )
                                is AttachmentPreview.Picked -> draft.copy(
                                    newAttachments = draft.newAttachments - preview.uri
                                )
                            }
                        }
                    )
                }
            } else Text(
                "No attachment yet. A shared receipt is attached automatically.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton({
                picker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            }) {
                Icon(Icons.Default.AddAPhoto, null, Modifier.size(18.dp))
                Gap()
                Text("Add attachment")
            }

            Section("Split with people")
            if (people.isEmpty()) Text(
                "Add people under Settings to split an expense.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (people.isNotEmpty()) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SplitMode.entries.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = draft.splitMode == mode,
                            onClick = {
                                // Rupees and percentages are different units, so carrying
                                // the typed figures across would turn "420" into 420%.
                                // Equal ignores them, so switching via Equal keeps them.
                                val unitsChanged = mode != draft.splitMode &&
                                    mode != SplitMode.EQUAL &&
                                    draft.splitMode != SplitMode.EQUAL
                                draft = draft.copy(
                                    splitMode = mode,
                                    shares = if (unitsChanged) draft.shares.mapValues { "" }
                                    else draft.shares
                                )
                            },
                            shape = SegmentedButtonDefaults.itemShape(index, SplitMode.entries.size),
                            label = { Text(mode.label) }
                        )
                    }
                }
                Text(
                    when (draft.splitMode) {
                        SplitMode.CUSTOM -> "Type each person's share. Leave one blank to leave them out."
                        SplitMode.EQUAL -> "Divided evenly across everyone added, plus you."
                        SplitMode.PERCENT -> "Type each person's percentage. Yours is whatever is left."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            draft.shares.forEach { (personId, share) ->
                val person = people.firstOrNull { it.id == personId } ?: return@forEach
                ShareRow(
                    name = person.name,
                    value = share,
                    mode = draft.splitMode,
                    computed = computedShares[personId],
                    onChange = { draft = draft.copy(shares = draft.shares + (personId to it)) },
                    onRemove = { draft = draft.copy(shares = draft.shares - personId) }
                )
            }
            val available = people.filter { it.id !in draft.shares.keys }
            if (available.isNotEmpty()) Box {
                OutlinedButton({ showPersonMenu = true }) {
                    Icon(Icons.Default.PersonAdd, null, Modifier.size(18.dp))
                    Gap()
                    Text("Add person")
                }
                DropdownMenu(showPersonMenu, { showPersonMenu = false }) {
                    available.forEach { person ->
                        DropdownMenuItem(
                            text = { Text(person.name) },
                            leadingIcon = { PersonAvatar(person.name, size = 28.dp) },
                            onClick = {
                                draft = draft.copy(shares = draft.shares + (person.id to ""))
                                showPersonMenu = false
                            }
                        )
                    }
                }
            }
            if (draft.shares.isNotEmpty()) {
                SplitSummary(totalPaise, assignedPaise, myShare, splitProblem)
            }
            Spacer(Modifier.height(16.dp))
        }
        // A full-width button pinned to the foot rather than a text action in the app bar: on a
        // tall phone the save action was the one control furthest from the thumb, on the screen
        // where it is pressed every single time.
        OverlayAction(
            label = if (duplicateOf != null) "Save anyway" else "Save expense",
            enabled = draft.amount.isNotBlank()
        ) { onSave(draft, duplicateOf != null) }
    }

    if (pickingDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = draft.paidAt)
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton({
                    // The picker reports UTC midnight for the day tapped. Only the date is taken
                    // from it and the time already on the draft is kept, which is both what the
                    // user expects and what stops a tap on today shifting an expense across a day
                    // boundary on a phone west of Greenwich.
                    state.selectedDateMillis?.let { draft = draft.copy(paidAt = withDate(draft.paidAt, it)) }
                    pickingDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton({ pickingDate = false }) { Text("Cancel") } }
        ) { DatePicker(state) }
    }

    if (pickingTime) {
        val calendar = Calendar.getInstance().apply { timeInMillis = draft.paidAt }
        val state = rememberTimePickerState(
            initialHour = calendar.get(Calendar.HOUR_OF_DAY),
            initialMinute = calendar.get(Calendar.MINUTE)
        )
        AlertDialog(
            onDismissRequest = { pickingTime = false },
            title = { Text("Time") },
            text = { Box(Modifier.fillMaxWidth(), Alignment.Center) { TimePicker(state) } },
            confirmButton = {
                TextButton({
                    draft = draft.copy(paidAt = withTime(draft.paidAt, state.hour, state.minute))
                    pickingTime = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton({ pickingTime = false }) { Text("Cancel") } }
        )
    }

    viewing?.let { preview ->
        BackHandler { viewing = null }
        Surface(Modifier.fillMaxSize()) {
            Box(
                Modifier.fillMaxSize().clickable { viewing = null },
                contentAlignment = Alignment.Center
            ) {
                rememberThumbnail(preview, maxPx = 2048)?.let {
                    Image(it, "Attachment", Modifier.fillMaxWidth(), contentScale = ContentScale.Fit)
                } ?: CircularProgressIndicator()
            }
        }
    }
}

/** The day from [dayMillis] with the time of day from [keepTimeFrom]. */
private fun withDate(keepTimeFrom: Long, dayMillis: Long): Long {
    val utc = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
        timeInMillis = dayMillis
    }
    return Calendar.getInstance().apply {
        timeInMillis = keepTimeFrom
        set(Calendar.YEAR, utc.get(Calendar.YEAR))
        set(Calendar.MONTH, utc.get(Calendar.MONTH))
        set(Calendar.DAY_OF_MONTH, utc.get(Calendar.DAY_OF_MONTH))
    }.timeInMillis
}

private fun withTime(millis: Long, hour: Int, minute: Int): Long =
    Calendar.getInstance().apply {
        timeInMillis = millis
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

/**
 * The amount, given the whole top of the form.
 *
 * It was one labelled text field among four, the same size as the note. It is the only field the
 * expense cannot be saved without, and on a receipt review it is the one figure worth checking, so
 * it is typed at display size with the currency symbol standing beside it rather than inside a
 * label.
 */
@Composable
private fun AmountField(value: String, onChange: (String) -> Unit) {
    PeyoCard(container = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                AppCurrency.currency.symbol,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Gap(4.dp)
            OutlinedTextField(
                value,
                onChange,
                Modifier.weight(1f),
                placeholder = {
                    Text(
                        "0",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.outline
                    )
                },
                textStyle = MaterialTheme.typography.headlineMedium.merge(MoneyTextStyle)
                    .copy(textAlign = TextAlign.Start),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent
                )
            )
        }
    }
}

@Composable
private fun DuplicateWarning(existing: Expense) = AlertCard(
    title = "Looks like a duplicate",
    text = "${money(existing.amountPaise)} to ${existing.merchant.ifBlank { existing.category }} " +
        "is already recorded on ${shortDate(existing.paidAt)}. Save anyway if this really is a " +
        "second payment.",
    icon = Icons.Default.Warning
)

@Composable
private fun SplitSummary(totalPaise: Long, assignedPaise: Long, myShare: Long, problem: String?) {
    val over = problem != null || myShare < 0
    PeyoCard(
        container = if (over) MaterialTheme.colorScheme.errorContainer
        else MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SummaryLine("Total", money(totalPaise))
            SummaryLine("Others owe", money(assignedPaise))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SummaryLine("Your share", if (over) "—" else money(myShare), bold = true)
            if (over) Text(
                problem ?: "Shares add up to more than the total.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun SummaryLine(label: String, value: String, bold: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium.merge(MoneyTextStyle),
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium
        )
    }
}

@Composable
private fun ShareRow(
    name: String,
    value: String,
    mode: SplitMode,
    computed: Long?,
    onChange: (String) -> Unit,
    onRemove: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        PersonAvatar(name, size = 36.dp)
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            // In equal and percent mode the figure is derived, so show what it worked out to.
            if (mode != SplitMode.CUSTOM) Text(
                computed?.let { "owes ${money(it)}" } ?: "owes nothing yet",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (mode != SplitMode.EQUAL) OutlinedTextField(
            value,
            onChange,
            label = {
                Text(if (mode == SplitMode.PERCENT) "%" else AppCurrency.currency.symbol)
            },
            singleLine = true,
            shape = MaterialTheme.shapes.small,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.width(120.dp)
        )
        IconButton(onRemove) {
            Icon(Icons.Default.Close, "Remove $name", Modifier.size(20.dp))
        }
    }
}

@Composable
private fun AttachmentThumb(preview: AttachmentPreview, onOpen: () -> Unit, onRemove: () -> Unit) {
    Box {
        val bitmap = rememberThumbnail(preview)
        Box(
            Modifier.size(96.dp).clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable(onClick = onOpen),
            contentAlignment = Alignment.Center
        ) {
            bitmap?.let {
                Image(it, "Attachment", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } ?: Icon(
                Icons.Default.Add,
                null,
                Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.outline
            )
        }
        FilledIconButton(
            onRemove,
            Modifier.align(Alignment.TopEnd).size(28.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            )
        ) { Icon(Icons.Default.Close, "Remove attachment", Modifier.size(16.dp)) }
    }
}
