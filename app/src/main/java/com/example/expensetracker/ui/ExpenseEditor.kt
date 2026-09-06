package com.example.expensetracker.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.expensetracker.ExpenseInput
import com.example.expensetracker.data.Category
import com.example.expensetracker.data.Expense
import com.example.expensetracker.data.Person

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

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(5)) { uris ->
        if (uris.isNotEmpty()) draft = draft.copy(newAttachments = draft.newAttachments + uris)
    }

    val totalPaise = rupeesToPaise(draft.amount) ?: 0L
    val assignedPaise = draft.shares.values.sumOf { rupeesToPaise(it) ?: 0L }
    val myShare = totalPaise - assignedPaise
    val previews = draft.existingAttachments
        .filter { it.id !in draft.removedAttachmentIds }
        .map { AttachmentPreview.Stored(it.id, it.path) } + draft.newAttachments.map { AttachmentPreview.Picked(it) }

    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Text(
                                when {
                                    draft.id != 0L -> "Edit expense"
                                    fromScreenshot -> "Review receipt"
                                    else -> "Add expense"
                                }
                            )
                        },
                        navigationIcon = { IconButton(onDismiss) { Icon(Icons.Default.Close, "Cancel") } },
                        actions = {
                            TextButton(
                                { onSave(draft, duplicateOf != null) },
                                enabled = draft.amount.isNotBlank()
                            ) { Text(if (duplicateOf != null) "Save anyway" else "Save") }
                        }
                    )
                }
            ) { padding ->
                Column(
                    Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    if (fromScreenshot && draft.id == 0L) Text(
                        "Values were read from your screenshot. Please verify before saving.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    duplicateOf?.let { DuplicateWarning(it) }
                    error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }

                    OutlinedTextField(
                        draft.amount,
                        { draft = draft.copy(amount = it) },
                        label = { Text("Amount (₹)") },
                        singleLine = true,
                        isError = error != null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        draft.merchant,
                        { draft = draft.copy(merchant = it) },
                        label = { Text("Merchant / payee") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        draft.note,
                        { draft = draft.copy(note = it) },
                        label = { Text("Your note") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Section("Category")
                    FlowChips(categories.map { it.name }, draft.category) { draft = draft.copy(category = it) }
                    if (categories.isEmpty()) Text(
                        "No categories yet. Add some under Settings.",
                        style = MaterialTheme.typography.bodySmall
                    )

                    Section("Attachments")
                    if (previews.isEmpty()) Text(
                        "No attachment yet. A shared receipt is attached automatically.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (previews.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(previews.size) { index ->
                            val preview = previews[index]
                            AttachmentThumb(
                                preview = preview,
                                onOpen = { viewing = preview },
                                onRemove = {
                                    draft = when (preview) {
                                        is AttachmentPreview.Stored ->
                                            draft.copy(removedAttachmentIds = draft.removedAttachmentIds + preview.id)
                                        is AttachmentPreview.Picked ->
                                            draft.copy(newAttachments = draft.newAttachments - preview.uri)
                                    }
                                }
                            )
                        }
                    }
                    OutlinedButton({
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) {
                        Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Add attachment")
                    }

                    Section("Split with people")
                    if (people.isEmpty()) Text(
                        "Add people under Settings to split an expense.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    draft.shares.forEach { (personId, share) ->
                        val person = people.firstOrNull { it.id == personId } ?: return@forEach
                        ShareRow(
                            name = person.name,
                            value = share,
                            onChange = { draft = draft.copy(shares = draft.shares + (personId to it)) },
                            onRemove = { draft = draft.copy(shares = draft.shares - personId) }
                        )
                    }
                    val available = people.filter { it.id !in draft.shares.keys }
                    if (available.isNotEmpty()) Box {
                        OutlinedButton({ showPersonMenu = true }) {
                            Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Add person")
                        }
                        DropdownMenu(showPersonMenu, { showPersonMenu = false }) {
                            available.forEach { person ->
                                DropdownMenuItem(
                                    text = { Text(person.name) },
                                    onClick = {
                                        draft = draft.copy(shares = draft.shares + (person.id to ""))
                                        showPersonMenu = false
                                    }
                                )
                            }
                        }
                    }
                    if (draft.shares.isNotEmpty()) SplitSummary(totalPaise, assignedPaise, myShare)
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }

    viewing?.let { preview ->
        Dialog({ viewing = null }, DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().clickable { viewing = null }, contentAlignment = Alignment.Center) {
                    rememberThumbnail(preview, maxPx = 2048)?.let {
                        Image(it, "Attachment", Modifier.fillMaxWidth(), contentScale = ContentScale.Fit)
                    } ?: CircularProgressIndicator()
                }
            }
        }
    }
}

@Composable private fun DuplicateWarning(existing: Expense) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Looks like a duplicate", fontWeight = FontWeight.Bold)
            Text(
                "${money(existing.amountPaise)} to ${existing.merchant.ifBlank { existing.category }} " +
                    "is already recorded on ${shortDate(existing.paidAt)}.",
                style = MaterialTheme.typography.bodySmall
            )
            Text("Save anyway if this really is a second payment.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable private fun SplitSummary(totalPaise: Long, assignedPaise: Long, myShare: Long) {
    val over = myShare < 0
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (over) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row {
                Text("Total", Modifier.weight(1f)); Text(money(totalPaise))
            }
            Row {
                Text("Others owe", Modifier.weight(1f)); Text(money(assignedPaise))
            }
            Row {
                Text("Your share", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                Text(if (over) "—" else money(myShare), fontWeight = FontWeight.Bold)
            }
            if (over) Text(
                "Shares add up to more than the total.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable private fun ShareRow(name: String, value: String, onChange: (String) -> Unit, onRemove: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(name, Modifier.weight(1f))
        OutlinedTextField(
            value,
            onChange,
            label = { Text("Owes ₹") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.width(140.dp)
        )
        IconButton(onRemove) { Icon(Icons.Default.Close, "Remove $name") }
    }
}

@Composable private fun AttachmentThumb(preview: AttachmentPreview, onOpen: () -> Unit, onRemove: () -> Unit) {
    Box {
        val bitmap = rememberThumbnail(preview)
        Box(
            Modifier.size(96.dp).clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = onOpen),
            contentAlignment = Alignment.Center
        ) {
            bitmap?.let { Image(it, "Attachment", Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        }
        FilledIconButton(
            onRemove,
            Modifier.align(Alignment.TopEnd).size(28.dp),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        ) { Icon(Icons.Default.Close, "Remove attachment", Modifier.size(16.dp)) }
    }
}

@Composable fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}

@Composable fun FlowChips(options: List<String>, selected: String, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        options.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach {
                    FilterChip(selected == it, { onSelect(it) }, { Text(it) })
                }
            }
        }
    }
}
