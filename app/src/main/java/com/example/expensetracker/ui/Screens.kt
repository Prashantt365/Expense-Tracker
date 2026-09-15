package com.example.expensetracker.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.expensetracker.AppCurrency
import com.example.expensetracker.ExpenseViewModel
import com.example.expensetracker.data.Category
import com.example.expensetracker.data.ExpenseDetails
import com.example.expensetracker.data.LocalBackup
import com.example.expensetracker.data.Person
import com.example.expensetracker.data.PersonBalance
import com.example.expensetracker.sync.Account
import com.example.expensetracker.sync.SyncOutcome
import kotlinx.coroutines.launch

@Composable
fun TransactionsScreen(
    expenses: List<ExpenseDetails>,
    onEdit: (ExpenseDetails) -> Unit,
    onDelete: (ExpenseDetails) -> Unit,
    modifier: Modifier = Modifier
) {
    if (expenses.isEmpty()) {
        Column(modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text("Your expense history is empty.", style = MaterialTheme.typography.titleLarge)
            Text("Add one manually or share a receipt screenshot from Google Pay.")
        }
        return
    }
    var pendingDelete by remember { mutableStateOf<ExpenseDetails?>(null) }
    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(expenses, key = { it.expense.id }) { details ->
            val e = details.expense
            Card(Modifier.fillMaxWidth().clickable { onEdit(details) }) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(e.merchant.ifBlank { e.category }, fontWeight = FontWeight.Bold)
                        Text(listOf(e.category, e.note).filter { it.isNotBlank() }.joinToString(" • "))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(shortDate(e.paidAt), style = MaterialTheme.typography.labelSmall)
                            if (details.attachments.isNotEmpty()) {
                                Icon(Icons.Default.AttachFile, "Has attachment", Modifier.size(14.dp))
                                Text("${details.attachments.size}", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        if (details.outstandingPaise > 0) Text(
                            "${money(details.outstandingPaise)} owed to you",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(money(e.amountPaise), fontWeight = FontWeight.Bold)
                    IconButton({ onEdit(details) }) { Icon(Icons.Default.Edit, "Edit expense") }
                    IconButton({ pendingDelete = details }) { Icon(Icons.Default.Delete, "Delete expense") }
                }
            }
        }
    }
    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete this expense?") },
            text = {
                Text(
                    "${money(target.expense.amountPaise)} • " +
                        target.expense.merchant.ifBlank { target.expense.category } +
                        if (target.attachments.isNotEmpty()) "\nIts attachments will be deleted too." else ""
                )
            },
            confirmButton = { Button({ onDelete(target); pendingDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton({ pendingDelete = null }) { Text("Cancel") } }
        )
    }
}

@Composable
fun PeopleScreen(vm: ExpenseViewModel, balances: List<PersonBalance>, modifier: Modifier = Modifier) {
    var settling by remember { mutableStateOf<PersonBalance?>(null) }
    if (balances.isEmpty()) {
        Column(modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text("No people yet.", style = MaterialTheme.typography.titleLarge)
            Text("Add people under Settings, then split an expense with them.")
        }
        return
    }
    val totalOwed = balances.sumOf { it.owedPaise }
    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Text("Owed to you")
                    Text(money(totalOwed), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                }
            }
        }
        items(balances, key = { it.personId }) { balance ->
            Card(Modifier.fillMaxWidth().clickable { settling = balance }) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(balance.name, fontWeight = FontWeight.Bold)
                        Text(
                            if (balance.owedPaise > 0) "owes you" else "all settled up",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Text(
                        money(balance.owedPaise),
                        fontWeight = FontWeight.Bold,
                        color = if (balance.owedPaise > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
    settling?.let { balance -> SettleDialog(vm, balance) { settling = null } }
}

@Composable
fun SettleDialog(vm: ExpenseViewModel, balance: PersonBalance, onDismiss: () -> Unit) {
    val shares by vm.outstandingFor(balance.personId).collectAsState(initial = emptyList())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(balance.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (shares.isEmpty()) Text("Nothing outstanding.")
                else {
                    Text(
                        "${shares.size} unsettled share${if (shares.size == 1) "" else "s"}, " +
                            "${money(shares.sumOf { it.amountPaise })} in total",
                        style = MaterialTheme.typography.bodySmall
                    )
                    // The list is capped and scrolls within itself, which is what keeps "Settle
                    // all" on screen. An unbounded column grew the dialog past the window on
                    // anyone with more than a handful of shares, pushing the buttons out of reach
                    // on exactly the balances that most needed clearing in one go.
                    Column(
                        Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        shares.forEach { share ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(share.merchant.ifBlank { share.category })
                                    Text(shortDate(share.paidAt), style = MaterialTheme.typography.labelSmall)
                                }
                                Text(money(share.amountPaise), fontWeight = FontWeight.SemiBold)
                                TextButton({ vm.settleShare(share.splitId) }) { Text("Settle") }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (shares.isNotEmpty()) Button({ vm.settleEverything(balance.personId); onDismiss() }) {
                Text("Settle all")
            } else TextButton(onDismiss) { Text("Close") }
        },
        dismissButton = {
            if (shares.isNotEmpty()) TextButton(onDismiss) { Text("Close") }
            else TextButton({ vm.reopenEverything(balance.personId) }) { Text("Reopen settled") }
        }
    )
}

@Composable
fun SettingsScreen(
    vm: ExpenseViewModel,
    categories: List<Category>,
    people: List<Person>,
    onImportContacts: () -> Unit,
    onImportPdf: () -> Unit,
    modifier: Modifier = Modifier
) {
    var message by remember { mutableStateOf<String?>(null) }
    var editingCategory by remember { mutableStateOf<Category?>(null) }
    var editingPerson by remember { mutableStateOf<Person?>(null) }
    var addingCategory by remember { mutableStateOf(false) }
    var addingPerson by remember { mutableStateOf(false) }
    var choosingCurrency by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val account = remember(context) { Account(context) }
    var session by remember { mutableStateOf(account.stored()) }
    var signingIn by remember { mutableStateOf(false) }
    var showingConflicts by remember { mutableStateOf(false) }
    var restoreConfirm by remember { mutableStateOf(false) }
    val conflicts by vm.conflicts.collectAsState()
    val syncing by vm.syncing.collectAsState()
    val lastSync by vm.lastSync.collectAsState()
    val lastSyncAt by vm.lastSyncAt.collectAsState()
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
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        message?.let {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(it, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton({ message = null }) { Text("OK") }
                }
            }
        }

        Section("Account")
        AccountCard(
            email = session?.email,
            onSignIn = { signingIn = true },
            onSignOut = {
                scope.launch {
                    account.signOut()
                    session = null
                }
            }
        )

        if (session != null) {
            Section("Backup to your account")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Back up automatically", fontWeight = FontWeight.Bold)
                            Text(
                                "Sends new and changed transactions a few seconds after you " +
                                    "record them, and when the app opens.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Switch(autoBackup, vm::setAutoBackup)
                    }

                    Text(
                        when (val outcome = lastSync) {
                            null ->
                                if (lastSyncAt > 0) "Last backed up " + shortDate(lastSyncAt) + "."
                                else "Not backed up yet."
                            is SyncOutcome.Done ->
                                "Sent ${outcome.pushed}, received ${outcome.pulled}." +
                                    if (outcome.conflicts > 0) " ${outcome.conflicts} need a decision." else ""
                            is SyncOutcome.Offline -> "No connection. Your data is safe on this phone."
                            is SyncOutcome.Failed -> outcome.message
                            SyncOutcome.NotSignedIn -> "Sign in to back up."
                        },
                        style = MaterialTheme.typography.bodySmall
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({ vm.sync() }, enabled = !syncing) {
                            Text(if (syncing) "Working..." else "Back up now")
                        }
                        OutlinedButton({ restoreConfirm = true }, enabled = !syncing) {
                            Icon(Icons.Default.CloudDownload, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp)); Text("Restore")
                        }
                    }
                    Text(
                        "Restore reads your whole history back down from the server. Use it after " +
                            "reinstalling, or on a new phone.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            if (conflicts.isNotEmpty()) {
                Card(
                    Modifier.fillMaxWidth().clickable { showingConflicts = true },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(conflictSummary(conflicts.size), fontWeight = FontWeight.Bold)
                        Text(
                            "Nothing changes until you choose. Tap to review.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        Section("Backup to a file")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Writes every transaction, person and category to a file you choose. It needs " +
                        "no account and no connection, and it can be imported back here or onto " +
                        "another phone. Receipts and attachments are not included.",
                    style = MaterialTheme.typography.bodySmall
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
                    OutlinedButton({ backupFilePicker.launch(LocalBackup.suggestedFileName()) }) {
                        Icon(Icons.Default.Save, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp)); Text("Back up locally")
                    }
                    OutlinedButton({ importFilePicker.launch(arrayOf(LocalBackup.MIME, "*/*")) }) {
                        Icon(Icons.Default.FolderOpen, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp)); Text("Import file")
                    }
                }
            }
        }

        Section("Currency")
        Card(Modifier.fillMaxWidth().clickable { choosingCurrency = true }) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(AppCurrency.currency.displayName, fontWeight = FontWeight.Bold)
                    Text(
                        "Set from the region this phone is configured for. Changing it relabels " +
                            "amounts already recorded rather than converting them.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Text(AppCurrency.currency.symbol, style = MaterialTheme.typography.titleLarge)
            }
        }

        Section("Categories")
        categories.forEach { category ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(category.name, Modifier.weight(1f))
                    IconButton({ editingCategory = category }) { Icon(Icons.Default.Edit, "Rename ${category.name}") }
                    IconButton({
                        vm.deleteCategory(category) { used ->
                            message = "${category.name} is used by $used expense${if (used == 1) "" else "s"}. " +
                                "Rename it, or move those expenses first."
                        }
                    }) { Icon(Icons.Default.Delete, "Delete ${category.name}") }
                }
            }
        }
        OutlinedButton({ addingCategory = true }) {
            Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Add category")
        }

        Spacer(Modifier.height(8.dp))
        Section("People")
        people.forEach { person ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(person.name, Modifier.weight(1f))
                    IconButton({ editingPerson = person }) { Icon(Icons.Default.Edit, "Rename ${person.name}") }
                    IconButton({
                        vm.deletePerson(person) { owing ->
                            message = "${person.name} still has $owing unsettled share${if (owing == 1) "" else "s"}. " +
                                "Settle up before removing them."
                        }
                    }) { Icon(Icons.Default.Delete, "Delete ${person.name}") }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ addingPerson = true }) {
                Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Add person")
            }
            OutlinedButton(onImportContacts) {
                Icon(Icons.Default.Contacts, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp)); Text("From contacts")
            }
        }

        Spacer(Modifier.height(8.dp))
        Section("Import")
        Text(
            "Read a bank or UPI statement and pick which rows to record. Pages are read on device.",
            style = MaterialTheme.typography.bodySmall
        )
        OutlinedButton(onImportPdf) {
            Icon(Icons.Default.PictureAsPdf, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp)); Text("Import from PDF")
        }
        Spacer(Modifier.height(24.dp))
    }

    if (choosingCurrency) CurrencyDialog({ choosingCurrency = false }) { picked ->
        AppCurrency.set(context, picked)
        choosingCurrency = false
    }
    if (restoreConfirm) {
        AlertDialog(
            onDismissRequest = { restoreConfirm = false },
            title = { Text("Restore from your account?") },
            text = {
                Text(
                    "Everything recorded under " + (session?.email ?: "this account") +
                        " is read back down onto this phone. Anything here that is not on the " +
                        "server is kept and sent up, so nothing you have typed is lost. " +
                        "Receipts and attachments are not part of the backup."
                )
            },
            confirmButton = {
                Button({ vm.restore(); restoreConfirm = false }) { Text("Restore") }
            },
            dismissButton = { TextButton({ restoreConfirm = false }) { Text("Cancel") } }
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
            vm.renameCategory(category, it); editingCategory = null
        }
    }
    editingPerson?.let { person ->
        NameDialog("Rename person", person.name, { editingPerson = null }) {
            vm.renamePerson(person, it); editingPerson = null
        }
    }
}

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
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
        title = { Text("Currency") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                OutlinedTextField(
                    query,
                    { query = it },
                    label = { Text("Search") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (shown.isEmpty()) Text("No currency matches that.")
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(shown, key = { it.first }) { (code, name) ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onPick(code) }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(code, Modifier.width(48.dp), fontWeight = FontWeight.Bold)
                            Text(name, Modifier.weight(1f))
                            if (code == AppCurrency.code) Icon(Icons.Default.Check, "Selected")
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(dismissLabel) } }
    )
}
