package com.peyo.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.peyo.app.AppCurrency
import com.peyo.app.ExpenseViewModel
import com.peyo.app.data.Category
import com.peyo.app.data.LocalBackup
import com.peyo.app.data.Person
import com.peyo.app.sync.Account
import com.peyo.app.sync.Response
import com.peyo.app.sync.SyncOutcome
import com.peyo.app.ui.theme.ThemeMode
import com.peyo.app.ui.theme.ThemeSettings
import com.peyo.app.widget.PeyoWidgets
import kotlinx.coroutines.launch

/** A delete the app would not do, why, and the thing to do instead. */
private data class Refusal(
    val title: String,
    val body: String,
    val actionLabel: String,
    val action: () -> Unit
)

@Composable
fun SettingsScreen(
    vm: ExpenseViewModel,
    categories: List<Category>,
    people: List<Person>,
    onImportContacts: () -> Unit,
    onImportPdf: () -> Unit,
    onNotice: (String) -> Unit,
    onSettleUp: (Person) -> Unit,
    modifier: Modifier = Modifier
) {
    // Why a delete was refused, if one was. A banner at the top of the screen was the wrong place
    // for it: categories and people live near the foot of a long scroll, so the explanation
    // appeared somewhere the user could not see and the delete looked like it had simply done
    // nothing. This is a dialog, in front of whatever they were looking at.
    var refused by remember { mutableStateOf<Refusal?>(null) }
    var editingCategory by remember { mutableStateOf<Category?>(null) }
    var editingPerson by remember { mutableStateOf<Person?>(null) }
    var addingCategory by remember { mutableStateOf(false) }
    var addingPerson by remember { mutableStateOf(false) }
    var choosingCurrency by remember { mutableStateOf(false) }
    var showCategories by remember { mutableStateOf(false) }
    var showPeople by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val account = remember(context) { Account(context) }
    var session by remember { mutableStateOf(account.stored()) }
    var signingIn by remember { mutableStateOf(false) }
    var showingConflicts by remember { mutableStateOf(false) }
    var restoreConfirm by remember { mutableStateOf(false) }
    var deleteAccountConfirm by remember { mutableStateOf(false) }
    var deletingAccount by remember { mutableStateOf(false) }
    var deleteAccountError by remember { mutableStateOf<String?>(null) }
    val conflicts by vm.conflicts.collectAsState()
    val syncing by vm.syncing.collectAsState()
    val lastSync by vm.lastSync.collectAsState()
    val lastSyncAt by vm.lastSyncAt.collectAsState()
    // A sync can find the session revoked and sign the phone out underneath this screen, which
    // otherwise went on showing the account as signed in with every backup quietly refused.
    LaunchedEffect(lastSync) {
        if (lastSync is SyncOutcome.NotSignedIn) session = account.stored()
    }
    val autoBackup by vm.autoBackup.collectAsState()
    val fileOutcome by vm.fileOutcome.collectAsState()

    // CreateDocument picks where the file goes; OpenDocument picks which one comes back. Both hand
    // back a Uri rather than a path, which is why the reading and writing live behind BackupFiles
    // rather than in java.io.
    val backupFilePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(LocalBackup.MIME)
    ) { uri -> uri?.let(vm::backupToFile) }

    // The wildcard sits beside the JSON type because plenty of file providers hand a .json file
    // back as application/octet-stream, and a picker that greys out the user's own backup is
    // worse than one that lets them pick something we then refuse by name.
    val importFilePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(vm::importBackupFile) }

    val fileNotice = when (val outcome = fileOutcome) {
        null -> null
        is ExpenseViewModel.FileOutcome.Exported ->
            "Backed up ${outcome.expenses} transaction${if (outcome.expenses == 1) "" else "s"} to the file."
        is ExpenseViewModel.FileOutcome.Imported -> with(outcome.result) {
            if (total == 0) "Nothing new in that file: it is all already here."
            else "Imported $expenses transaction${if (expenses == 1) "" else "s"}, " +
                "$people people and $categories categories."
        }
        is ExpenseViewModel.FileOutcome.Failed -> outcome.message
    }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Section("Account")
        AccountCard(
            email = session?.email,
            onSignIn = { signingIn = true },
            onSignOut = {
                scope.launch {
                    account.signOut()
                    session = null
                }
            },
            onDeleteAccount = { deleteAccountConfirm = true }
        )

        if (session != null) {
            Section("Backup to your account")
            PeyoCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("Back up automatically", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Sends new and changed transactions a few seconds after you " +
                                    "record them, and when the app opens.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(autoBackup, vm::setAutoBackup)
                    }

                    SyncStatusLine(syncing, lastSync, lastSyncAt)

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({ vm.sync() }, Modifier.weight(1f), enabled = !syncing) {
                            if (syncing) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                Gap()
                                Text("Working")
                            } else {
                                Icon(Icons.Default.CloudUpload, null, Modifier.size(18.dp))
                                Gap()
                                Text("Back up now")
                            }
                        }
                        OutlinedButton(
                            { restoreConfirm = true },
                            Modifier.weight(1f),
                            enabled = !syncing
                        ) {
                            Icon(Icons.Default.CloudDownload, null, Modifier.size(18.dp))
                            Gap()
                            Text("Restore")
                        }
                    }
                    Text(
                        "Restore reads your whole history back down from the server. Use it after " +
                            "reinstalling, or on a new phone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (conflicts.isNotEmpty()) AlertCard(
                text = "Nothing changes until you choose. Tap to review.",
                title = conflictSummary(conflicts.size),
                icon = Icons.Default.Warning,
                onClick = { showingConflicts = true }
            )
        }

        Section("Home screen")
        WidgetsCard(onNotice)

        Section("Appearance")
        AppearanceCard()

        Section("Backup to a file")
        PeyoCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Writes every transaction, person and category to a file you choose. It needs " +
                        "no account and no connection, and it can be imported back here or onto " +
                        "another phone. Receipts and attachments are not included.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                fileNotice?.let {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            it,
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold
                        )
                        TextButton(vm::clearFileOutcome) { Text("OK") }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        { backupFilePicker.launch(LocalBackup.suggestedFileName()) },
                        Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Save, null, Modifier.size(18.dp))
                        Gap(); Text("Back up")
                    }
                    OutlinedButton(
                        { importFilePicker.launch(arrayOf(LocalBackup.MIME, "*/*")) },
                        Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.FolderOpen, null, Modifier.size(18.dp))
                        Gap(); Text("Import file")
                    }
                }
            }
        }

        Section("Currency")
        PeyoCard(onClick = { choosingCurrency = true }) {
            SettingsRow(
                icon = Icons.Default.Payments,
                title = AppCurrency.currency.displayName,
                subtitle = "Changing it relabels amounts already recorded rather than " +
                    "converting them.",
                trailing = {
                    Text(
                        AppCurrency.currency.symbol,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            )
        }

        Section("Categories")
        ManagedList(
            expanded = showCategories,
            onExpand = { showCategories = !showCategories },
            summary = "${categories.size} categor${if (categories.size == 1) "y" else "ies"}",
            names = categories.map { it.name },
            iconFor = { categoryIcon(it) },
            onEdit = { name -> editingCategory = categories.firstOrNull { it.name == name } },
            onDelete = { name ->
                categories.firstOrNull { it.name == name }?.let { category ->
                    vm.deleteCategory(category) { used ->
                        refused = Refusal(
                            title = "${category.name} is still in use",
                            body = "$used expense${if (used == 1) "" else "s"} " +
                                "${if (used == 1) "is" else "are"} filed under it. Deleting it " +
                                "would leave ${if (used == 1) "that one" else "them"} pointing at " +
                                "a category the picker no longer offers.\n\nRenaming it instead " +
                                "carries those expenses across with it.",
                            actionLabel = "Rename instead",
                            action = { editingCategory = category }
                        )
                    }
                }
            },
            actions = {
                OutlinedButton({ addingCategory = true }) {
                    Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                    Gap(); Text("Add category")
                }
            }
        )

        Section("People")
        ManagedList(
            expanded = showPeople,
            onExpand = { showPeople = !showPeople },
            summary = if (people.isEmpty()) "Nobody yet"
            else "${people.size} ${if (people.size == 1) "person" else "people"}",
            names = people.map { it.name },
            iconFor = null,
            onEdit = { name -> editingPerson = people.firstOrNull { it.name == name } },
            onDelete = { name ->
                people.firstOrNull { it.name == name }?.let { person ->
                    vm.deletePerson(person) { owing ->
                        refused = Refusal(
                            title = "${person.name} still owes you",
                            body = "$owing unsettled " +
                                "share${if (owing == 1) "" else "s"} " +
                                "${if (owing == 1) "is" else "are"} outstanding. Removing them now " +
                                "would take that off your balances as though it had been paid.",
                            actionLabel = "Settle up",
                            action = { onSettleUp(person) }
                        )
                    }
                }
            },
            actions = {
                OutlinedButton({ addingPerson = true }) {
                    Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                    Gap(); Text("Add person")
                }
                OutlinedButton(onImportContacts) {
                    Icon(Icons.Default.Contacts, null, Modifier.size(18.dp))
                    Gap(); Text("From contacts")
                }
            }
        )

        Section("Import")
        PeyoCard(onClick = onImportPdf) {
            SettingsRow(
                icon = Icons.Default.PictureAsPdf,
                title = "Import from a statement",
                subtitle = "Read a bank or UPI statement PDF and pick which rows to record. " +
                    "Pages are read on this device."
            )
        }

        Spacer(Modifier.height(32.dp))
    }

    refused?.let { reason ->
        AlertDialog(
            onDismissRequest = { refused = null },
            icon = { Icon(Icons.Default.Warning, null) },
            title = { Text(reason.title) },
            text = { Text(reason.body) },
            confirmButton = {
                Button({ reason.action(); refused = null }) { Text(reason.actionLabel) }
            },
            dismissButton = { TextButton({ refused = null }) { Text("Cancel") } }
        )
    }

    if (choosingCurrency) CurrencyDialog({ choosingCurrency = false }) { picked ->
        AppCurrency.set(context, picked)
        choosingCurrency = false
    }
    if (restoreConfirm) {
        AlertDialog(
            onDismissRequest = { restoreConfirm = false },
            icon = { Icon(Icons.Default.CloudDownload, null) },
            title = { Text("Restore from your account?") },
            text = {
                Text(
                    "Everything recorded under " + (session?.email ?: "this account") +
                        " is read back down onto this phone. Anything here that is not on the " +
                        "server is kept and sent up, so nothing you have typed is lost. " +
                        "Receipts and attachments are not part of the backup."
                )
            },
            confirmButton = { Button({ vm.restore(); restoreConfirm = false }) { Text("Restore") } },
            dismissButton = { TextButton({ restoreConfirm = false }) { Text("Cancel") } }
        )
    }
    if (deleteAccountConfirm) {
        AlertDialog(
            onDismissRequest = { if (!deletingAccount) deleteAccountConfirm = false },
            icon = { Icon(Icons.Default.DeleteForever, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Delete your account?") },
            text = {
                Text(
                    "This removes " + (session?.email ?: "your account") + " and everything " +
                        "backed up under it from the server -- permanently, for anyone else who " +
                        "signs into it too. What is on this phone is not touched and is yours to " +
                        "keep; you will just be signed out."
                )
            },
            confirmButton = {
                Button(
                    {
                        deletingAccount = true
                        scope.launch {
                            when (val result = account.deleteAccount()) {
                                is Response.Ok -> {
                                    session = null
                                    deleteAccountConfirm = false
                                    onNotice("Your account has been deleted.")
                                }
                                is Response.Rejected -> deleteAccountError = result.message
                                is Response.Offline ->
                                    deleteAccountError = "No connection. Try again when you are online."
                            }
                            deletingAccount = false
                        }
                    },
                    enabled = !deletingAccount,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    if (deletingAccount) {
                        CircularProgressIndicator(
                            Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onError
                        )
                    } else Text("Delete account")
                }
            },
            dismissButton = {
                TextButton({ deleteAccountConfirm = false }, enabled = !deletingAccount) { Text("Cancel") }
            }
        )
    }
    deleteAccountError?.let { message ->
        AlertDialog(
            onDismissRequest = { deleteAccountError = null },
            icon = { Icon(Icons.Default.Warning, null) },
            title = { Text("Couldn't delete your account") },
            text = { Text(message) },
            confirmButton = { Button({ deleteAccountError = null }) { Text("OK") } }
        )
    }

    if (showingConflicts && conflicts.isNotEmpty()) {
        ConflictDialog(
            conflicts = conflicts,
            onResolve = { id, keepLocal -> vm.resolveConflict(id, keepLocal) },
            onDismiss = { showingConflicts = false }
        )
    }
    if (signingIn) {
        // Full screen rather than a dialog: the same surface the app opens with, so signing in
        // later looks like signing in at the start.
        AuthScreen(
            account = account,
            onSignedIn = { session = account.stored(); signingIn = false },
            onSkip = { signingIn = false }
        )
    }
    if (addingCategory) NameDialog("New category", "", { addingCategory = false }) {
        vm.addCategory(it); addingCategory = false
    }
    if (addingPerson) NameDialog("New person", "", { addingPerson = false }) {
        vm.addPerson(it); addingPerson = false
    }
    editingCategory?.let { category ->
        NameDialog("Rename category", category.name, { editingCategory = null }) {
            val wanted = it.trim()
            vm.renameCategory(category, wanted) {
                refused = Refusal(
                    title = "There's already a category called $wanted",
                    body = "Two categories with one name couldn't be told apart in the picker " +
                        "or in Insights. Pick a different name for ${category.name}.",
                    actionLabel = "Choose another name",
                    action = { editingCategory = category }
                )
            }
            editingCategory = null
        }
    }
    editingPerson?.let { person ->
        NameDialog("Rename person", person.name, { editingPerson = null }) {
            val wanted = it.trim()
            vm.renamePerson(person, wanted) {
                refused = Refusal(
                    title = "You already have $wanted in your people",
                    body = "Two people with one name couldn't be told apart when splitting a " +
                        "bill or settling up. Pick a different name for ${person.name}.",
                    actionLabel = "Choose another name",
                    action = { editingPerson = person }
                )
            }
            editingPerson = null
        }
    }
}

/** Where the last backup got to, worded as a state rather than as a log line. */
@Composable
private fun SyncStatusLine(syncing: Boolean, lastSync: SyncOutcome?, lastSyncAt: Long) {
    val palette = chartPalette
    val (text, colour) = when {
        syncing -> "Backing up now…" to MaterialTheme.colorScheme.onSurfaceVariant
        lastSync == null ->
            (if (lastSyncAt > 0) "Last backed up " + shortDate(lastSyncAt) + "."
            else "Not backed up yet.") to MaterialTheme.colorScheme.onSurfaceVariant
        lastSync is SyncOutcome.Done ->
            ("Sent ${lastSync.pushed}, received ${lastSync.pulled}." +
                if (lastSync.conflicts > 0) " ${lastSync.conflicts} need a decision."
                else "") to palette.settled
        lastSync is SyncOutcome.Offline ->
            "No connection. Your data is safe on this phone." to MaterialTheme.colorScheme.onSurfaceVariant
        lastSync is SyncOutcome.Failed -> lastSync.message to MaterialTheme.colorScheme.error
        else -> "Sign in to back up." to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(text, style = MaterialTheme.typography.bodySmall, color = colour)
}

/** The widgets on offer, each placeable without leaving the app where the launcher allows it. */
@Composable
private fun WidgetsCard(onNotice: (String) -> Unit) {
    val context = LocalContext.current
    val canPin = remember(context) { PeyoWidgets.canPin(context) }
    PeyoCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(Icons.Default.Widgets, null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Text("Widgets", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (canPin) "Put Peyo on your home screen."
                        else "Long-press your home screen, choose Widgets, and find Peyo.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            PeyoWidgets.Kind.entries.forEach { kind ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(kind.title, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            kind.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (canPin) TextButton({
                        // The launcher owns the outcome: it may show its own confirmation, place
                        // it silently, or refuse. Only a refusal is worth saying anything about.
                        if (!PeyoWidgets.requestPin(context, kind)) {
                            onNotice("Your launcher would not add it. Long-press the home screen and pick Peyo from Widgets.")
                        }
                    }) { Text("Add") }
                }
            }
        }
    }
}

/** Theme mode, and whether the phone's wallpaper colours win over Peyo's own. */
@Composable
private fun AppearanceCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // The widgets take their colours from these two settings, and a placed widget only redraws
    // when something asks it to, so changing the theme has to ask.
    fun repaintWidgets() = scope.launch { PeyoWidgets.refresh(context) }
    PeyoCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Theme", style = MaterialTheme.typography.titleSmall)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ThemeMode.entries.forEachIndexed { index, entry ->
                    SegmentedButton(
                        selected = ThemeSettings.mode == entry,
                        onClick = { ThemeSettings.setMode(context, entry); repaintWidgets() },
                        shape = SegmentedButtonDefaults.itemShape(index, ThemeMode.entries.size),
                        label = { Text(entry.label) }
                    )
                }
            }
            if (ThemeSettings.supportsDynamic) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(Icons.Default.Colorize, null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Use my wallpaper colours", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Takes the palette from your wallpaper instead of Peyo's green. The " +
                                "widgets follow this too.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        ThemeSettings.dynamicColor,
                        { ThemeSettings.setDynamicColor(context, it); repaintWidgets() }
                    )
                }
            }
        }
    }
}

/**
 * A collapsed count that opens into the editable list behind it.
 *
 * Categories and people were both a run of one-line cards straight down the settings screen, which
 * on a phone book import ran to a hundred rows between the user and everything below them.
 */
@Composable
private fun ManagedList(
    expanded: Boolean,
    onExpand: () -> Unit,
    summary: String,
    names: List<String>,
    iconFor: ((String) -> ImageVector)?,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit,
    actions: @Composable () -> Unit
) {
    PeyoCard {
        Column {
            Row(
                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large)
                    .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(summary, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                IconButton(onExpand) {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        if (expanded) "Hide the list" else "Show the list"
                    )
                }
            }
            AnimatedVisibility(expanded) {
                Column {
                    names.forEach { name ->
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Row(
                            Modifier.padding(start = 16.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            if (iconFor != null) CategoryBadge(name, size = 30.dp)
                            else PersonAvatar(name, size = 30.dp)
                            Text(name, Modifier.weight(1f), maxLines = 1)
                            IconButton({ onEdit(name) }) {
                                Icon(Icons.Default.Edit, "Rename $name", Modifier.size(20.dp))
                            }
                            IconButton({ onDelete(name) }) {
                                Icon(Icons.Default.Delete, "Delete $name", Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier.padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) { actions() }
        }
    }
}

/** A leading icon, a title, a subtitle, and whatever goes on the right. */
@Composable
fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    tint: Color = MaterialTheme.colorScheme.primary,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        Modifier.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Icon(icon, null, tint = tint)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        trailing?.invoke()
    }
}

@Composable
fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                name,
                { name = it },
                label = { Text("Name") },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
            )
        },
        confirmButton = { Button({ onConfirm(name) }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } }
    )
}

/**
 * The full ISO 4217 list is long enough that it has to be searchable and has to scroll inside a
 * bounded box, or the dialog grows past the window and takes its buttons with it.
 *
 * [subtitle] and [dismissLabel] are what turn this into the question the app asks once on first
 * run. The list, the search and the check mark against the current pick are the same either way,
 * so the first-run prompt is this dialog with a sentence above it rather than a second one to
 * keep in step.
 */
@Composable
fun CurrencyDialog(
    onDismiss: () -> Unit,
    subtitle: String? = null,
    dismissLabel: String = "Close",
    onPick: (String) -> Unit
) {
    val all = remember { AppCurrency.all() }
    var query by remember { mutableStateOf("") }
    val shown = remember(query, all) {
        if (query.isBlank()) all
        else all.filter { (code, name) -> code.contains(query, true) || name.contains(query, true) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Payments, null) },
        title = { Text("Currency") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                subtitle?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedTextField(
                    query,
                    { query = it },
                    label = { Text("Search") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                )
                if (shown.isEmpty()) Text("No currency matches that.")
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(shown, key = { it.first }) { (code, name) ->
                        val chosen = code == AppCurrency.code
                        Row(
                            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
                                .then(
                                    if (chosen) Modifier.background(
                                        MaterialTheme.colorScheme.primaryContainer
                                    ) else Modifier
                                )
                                .clickable { onPick(code) }
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(code, Modifier.width(48.dp), fontWeight = FontWeight.Bold)
                            Text(name, Modifier.weight(1f), maxLines = 1)
                            if (chosen) Icon(Icons.Default.Check, "Selected")
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(dismissLabel) } }
    )
}

/** The account row in Settings, once there is somewhere to show it. */
@Composable
fun AccountCard(
    email: String?,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onDeleteAccount: () -> Unit,
    modifier: Modifier = Modifier
) {
    PeyoCard(modifier) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                Modifier.size(44.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.AccountCircle,
                    null,
                    Modifier.size(26.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (email.isNullOrBlank()) {
                    Text("Not signed in", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Transactions are on this phone only. Signing in backs them up and brings " +
                            "them to any other phone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(email, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    Text(
                        "Backed up. Screenshots and attachments stay on this phone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (email.isNullOrBlank()) {
                Button(onSignIn) { Text("Sign in") }
            } else {
                IconButton(onSignOut) {
                    Icon(Icons.AutoMirrored.Filled.Logout, "Sign out")
                }
            }
        }
        if (!email.isNullOrBlank()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            TextButton(
                onDeleteAccount,
                Modifier.padding(horizontal = 4.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.Default.DeleteForever, null, Modifier.size(18.dp))
                Gap(6.dp)
                Text("Delete account")
            }
        }
    }
}
