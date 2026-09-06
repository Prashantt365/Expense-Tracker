package com.example.expensetracker.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.expensetracker.Analytics
import com.example.expensetracker.ContactCandidate
import com.example.expensetracker.ContactImportPlanner
import com.example.expensetracker.ExpenseInput
import com.example.expensetracker.ExpenseViewModel
import com.example.expensetracker.ImportState
import com.example.expensetracker.LaunchAction
import com.example.expensetracker.OcrReceiptParser
import com.example.expensetracker.Period
import com.example.expensetracker.SaveOutcome
import com.example.expensetracker.SplitMode
import com.example.expensetracker.data.ContactsReader
import com.example.expensetracker.data.Expense
import com.example.expensetracker.data.PersonBalance
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.launch

private enum class Screen(val label: String, val icon: ImageVector) {
    DASHBOARD("Insights", Icons.Default.Home),
    TRANSACTIONS("Expenses", Icons.AutoMirrored.Filled.ReceiptLong),
    PEOPLE("People", Icons.Default.Group),
    SETTINGS("Settings", Icons.Default.Settings)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpendwiseApp(action: LaunchAction, actionToken: Int, vm: ExpenseViewModel = viewModel()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val expenses by vm.expenses.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val people by vm.people.collectAsStateWithLifecycle()
    val balances by vm.balances.collectAsStateWithLifecycle()
    val importState by vm.importState.collectAsStateWithLifecycle()

    var screen by remember { mutableStateOf(Screen.DASHBOARD) }
    var editing by remember { mutableStateOf<ExpenseInput?>(null) }
    var fromScreenshot by remember { mutableStateOf(false) }
    var editorError by remember { mutableStateOf<String?>(null) }
    var duplicateOf by remember { mutableStateOf<Expense?>(null) }
    var period by remember { mutableStateOf(Period.MONTH) }
    var settling by remember { mutableStateOf<PersonBalance?>(null) }
    var contactCandidates by remember { mutableStateOf<List<ContactCandidate>?>(null) }
    var contactSelection by remember { mutableStateOf(emptySet<String>()) }
    var notice by remember { mutableStateOf<String?>(null) }

    val defaultCategory = categories.firstOrNull()?.name ?: "Other"

    fun openEditor(mode: SplitMode = SplitMode.CUSTOM) {
        editing = ExpenseInput(category = defaultCategory, splitMode = mode)
        fromScreenshot = false
        editorError = null
        duplicateOf = null
    }

    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::importFrom)
    }

    fun loadContacts() {
        scope.launch {
            val names = ContactsReader.readNames(context)
            contactCandidates = ContactImportPlanner.plan(names, people.map { it.name })
            // Anything flagged as a likely duplicate starts unticked.
            contactSelection = contactCandidates.orEmpty().filterNot { it.isFlagged }.map { it.name }.toSet()
        }
    }

    val contactsPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) loadContacts()
        else notice = "Contacts permission is needed to import names."
    }

    fun importContacts() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) loadContacts() else contactsPermission.launch(Manifest.permission.READ_CONTACTS)
    }

    LaunchedEffect(actionToken) {
        when (val current = action) {
            is LaunchAction.None -> Unit
            is LaunchAction.AddExpense -> openEditor()
            is LaunchAction.SplitExpense -> openEditor(SplitMode.EQUAL)
            is LaunchAction.ImportPdf -> pdfPicker.launch(arrayOf("application/pdf"))
            is LaunchAction.OpenBalances -> screen = Screen.PEOPLE
            is LaunchAction.StatementShared -> vm.importFrom(current.pdf)
            is LaunchAction.ReceiptShared -> {
                // The screenshot is attached to the expense, and its Uri is remembered so that
                // sharing the very same receipt again is recognised as a duplicate.
                editing = ExpenseInput(
                    category = defaultCategory,
                    sourceUri = current.image.toString(),
                    newAttachments = listOf(current.image)
                )
                fromScreenshot = true
                editorError = null
                duplicateOf = null
                // fromFilePath throws when the share grant has lapsed or the file is unreadable,
                // and it runs before any listener is attached, so it needs its own guard.
                runCatching { InputImage.fromFilePath(vm.getApplication(), current.image) }
                    .onFailure { editorError = "Couldn't open that screenshot. Enter the details below." }
                    .onSuccess { image ->
                        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                            .process(image)
                            .addOnSuccessListener { text ->
                                val draft = OcrReceiptParser.parse(text.text)
                                editing = editing?.copy(
                                    amount = draft.amount,
                                    merchant = draft.merchant,
                                    note = draft.note,
                                    category = categories.firstOrNull { it.name == draft.category }?.name
                                        ?: defaultCategory
                                )
                            }
                            .addOnFailureListener {
                                editorError = "Couldn't read that screenshot. Enter the details below."
                            }
                    }
            }
        }
    }

    Scaffold(
        topBar = { CenterAlignedTopAppBar(title = { Text("Spendwise", fontWeight = FontWeight.Bold) }) },
        bottomBar = {
            NavigationBar {
                Screen.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = screen == destination,
                        onClick = { screen = destination },
                        icon = { Icon(destination.icon, null) },
                        label = { Text(destination.label) }
                    )
                }
            }
        },
        floatingActionButton = {
            if (screen == Screen.DASHBOARD || screen == Screen.TRANSACTIONS) {
                FloatingActionButton(onClick = { openEditor() }) { Icon(Icons.Default.Add, "Add expense") }
            }
        }
    ) { padding ->
        val content = Modifier.padding(padding)
        when (screen) {
            Screen.DASHBOARD -> AnalyticsScreen(
                report = remember(expenses, people, period) { Analytics.build(expenses, people, period) },
                period = period,
                onPeriodChange = { period = it },
                onSettle = { personId, name ->
                    settling = balances.firstOrNull { it.personId == personId }
                        ?: PersonBalance(personId, name, 0)
                },
                modifier = content
            )
            Screen.TRANSACTIONS -> TransactionsScreen(
                expenses = expenses,
                onEdit = { details ->
                    editing = ExpenseInput(
                        id = details.expense.id,
                        amount = (details.expense.amountPaise / 100.0).toString(),
                        category = details.expense.category,
                        note = details.expense.note,
                        merchant = details.expense.merchant,
                        paidAt = details.expense.paidAt,
                        sourceUri = details.expense.sourceUri,
                        shares = details.splits
                            .filter { it.personId != null }
                            .associate { it.personId!! to (it.amountPaise / 100.0).toString() },
                        existingAttachments = details.attachments
                    )
                    fromScreenshot = false
                    editorError = null
                    duplicateOf = null
                },
                onDelete = vm::delete,
                modifier = content
            )
            Screen.PEOPLE -> PeopleScreen(vm, balances, content)
            Screen.SETTINGS -> SettingsScreen(
                vm = vm,
                categories = categories,
                people = people,
                onImportContacts = ::importContacts,
                onImportPdf = { pdfPicker.launch(arrayOf("application/pdf")) },
                modifier = content
            )
        }
    }

    editing?.let { input ->
        ExpenseEditor(
            input = input,
            categories = categories,
            people = people,
            fromScreenshot = fromScreenshot,
            error = editorError,
            duplicateOf = duplicateOf,
            onDismiss = { editing = null; editorError = null; duplicateOf = null },
            onSave = { draft, force ->
                vm.save(draft, force) { outcome ->
                    when (outcome) {
                        is SaveOutcome.Saved -> {
                            editing = null; editorError = null; duplicateOf = null
                        }
                        is SaveOutcome.Invalid -> {
                            editing = draft; editorError = outcome.message; duplicateOf = null
                        }
                        is SaveOutcome.Duplicate -> {
                            // Keep the values on screen and let the user insist.
                            editing = draft; editorError = null; duplicateOf = outcome.existing
                        }
                    }
                }
            }
        )
    }

    settling?.let { balance -> SettleDialog(vm, balance) { settling = null } }

    ImportReviewDialog(
        state = importState,
        categories = categories,
        onToggle = vm::toggleImportRow,
        onCategory = vm::setImportCategory,
        onConfirm = {
            vm.confirmImport { written ->
                notice = if (written == 0) "Nothing imported: those rows are already recorded."
                else "Imported $written transaction${if (written == 1) "" else "s"}."
            }
        },
        onDismiss = vm::cancelImport
    )

    contactCandidates?.let { candidates ->
        ContactPickerDialog(
            candidates = candidates,
            selected = contactSelection,
            onToggle = { name ->
                contactSelection = if (name in contactSelection) contactSelection - name
                else contactSelection + name
            },
            onConfirm = {
                vm.addPeople(contactSelection.toList()) { added ->
                    notice = "Added $added ${if (added == 1) "person" else "people"}."
                }
                contactCandidates = null
            },
            onDismiss = { contactCandidates = null }
        )
    }

    notice?.let { message ->
        AlertDialog(
            onDismissRequest = { notice = null },
            text = { Text(message) },
            confirmButton = { Button({ notice = null }) { Text("OK") } }
        )
    }
}
