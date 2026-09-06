package com.example.expensetracker.ui

import android.net.Uri
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
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.expensetracker.ExpenseInput
import com.example.expensetracker.ExpenseViewModel
import com.example.expensetracker.OcrReceiptParser
import com.example.expensetracker.SaveOutcome
import com.example.expensetracker.data.Expense
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

private enum class Screen(val label: String, val icon: ImageVector) {
    DASHBOARD("Overview", Icons.Default.Home),
    TRANSACTIONS("Expenses", Icons.AutoMirrored.Filled.ReceiptLong),
    PEOPLE("People", Icons.Default.Group),
    SETTINGS("Settings", Icons.Default.Settings)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpendwiseApp(sharedImage: Uri?, shareToken: Int, vm: ExpenseViewModel = viewModel()) {
    val expenses by vm.expenses.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val people by vm.people.collectAsStateWithLifecycle()
    val balances by vm.balances.collectAsStateWithLifecycle()

    var screen by remember { mutableStateOf(Screen.DASHBOARD) }
    var editing by remember { mutableStateOf<ExpenseInput?>(null) }
    var fromScreenshot by remember { mutableStateOf(false) }
    var editorError by remember { mutableStateOf<String?>(null) }
    var duplicateOf by remember { mutableStateOf<Expense?>(null) }

    val defaultCategory = categories.firstOrNull()?.name ?: "Other"

    LaunchedEffect(shareToken) {
        val uri = sharedImage ?: return@LaunchedEffect
        // The screenshot is attached to the expense, and its Uri is remembered so that sharing the
        // very same receipt again is recognised as a duplicate.
        editing = ExpenseInput(
            category = defaultCategory,
            sourceUri = uri.toString(),
            newAttachments = listOf(uri)
        )
        fromScreenshot = true
        editorError = null
        duplicateOf = null
        // fromFilePath throws when the share grant has lapsed or the file is unreadable, and it runs
        // before any listener is attached, so it needs its own guard.
        runCatching { InputImage.fromFilePath(vm.getApplication(), uri) }
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
                            category = categories.firstOrNull { it.name == draft.category }?.name ?: defaultCategory
                        )
                    }
                    .addOnFailureListener {
                        editorError = "Couldn't read that screenshot. Enter the details below."
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
            if (screen == Screen.DASHBOARD || screen == Screen.TRANSACTIONS) FloatingActionButton(onClick = {
                editing = ExpenseInput(category = defaultCategory)
                fromScreenshot = false
                editorError = null
                duplicateOf = null
            }) { Icon(Icons.Default.Add, "Add expense") }
        }
    ) { padding ->
        val content = Modifier.padding(padding)
        when (screen) {
            Screen.DASHBOARD -> DashboardScreen(expenses, content)
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
            Screen.SETTINGS -> SettingsScreen(vm, categories, people, content)
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
}
