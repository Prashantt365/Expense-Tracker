package com.example.expensetracker

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.expensetracker.data.Expense
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    private var incomingImage by mutableStateOf<Uri?>(null)

    /**
     * Incremented on every share. Sharing the same screenshot twice leaves the Uri unchanged, so
     * keying the OCR effect on the Uri alone would not reopen the review dialog the second time.
     */
    private var shareToken by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        consumeShare(intent)
        setContent { MaterialTheme { SpendwiseApp(incomingImage, shareToken) } }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); consumeShare(intent) }

    private fun consumeShare(intent: Intent) {
        if (intent.action != Intent.ACTION_SEND || intent.type?.startsWith("image/") != true) return
        val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) ?: return
        incomingImage = uri
        shareToken++
    }
}

private enum class Screen { DASHBOARD, TRANSACTIONS }
private val categories = listOf("Food", "Transport", "Bills", "Shopping", "Health", "Other")

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun SpendwiseApp(sharedImage: Uri?, shareToken: Int, vm: ExpenseViewModel = viewModel()) {
    val expenses by vm.expenses.collectAsStateWithLifecycle()
    var screen by remember { mutableStateOf(Screen.DASHBOARD) }
    var showEditor by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(ReceiptDraft()) }
    var imageForDraft by remember { mutableStateOf<Uri?>(null) }
    var editorError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(shareToken) {
        val uri = sharedImage ?: return@LaunchedEffect
        imageForDraft = uri
        draft = ReceiptDraft()
        editorError = null
        showEditor = true
        // fromFilePath throws when the share grant has already lapsed or the file is unreadable,
        // and it runs before any listener is attached, so it needs its own guard.
        runCatching { InputImage.fromFilePath(vm.getApplication(), uri) }
            .onFailure { editorError = "Couldn't open that screenshot. Enter the details below." }
            .onSuccess { image ->
                TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                    .process(image)
                    .addOnSuccessListener { draft = OcrReceiptParser.parse(it.text) }
                    .addOnFailureListener { editorError = "Couldn't read that screenshot. Enter the details below." }
            }
    }

    Scaffold(
        topBar = { CenterAlignedTopAppBar(title = { Text("Spendwise", fontWeight = FontWeight.Bold) }) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = screen == Screen.DASHBOARD,
                    onClick = { screen = Screen.DASHBOARD },
                    icon = { Icon(Icons.Default.Home, null) },
                    label = { Text("Overview") }
                )
                NavigationBarItem(
                    selected = screen == Screen.TRANSACTIONS,
                    onClick = { screen = Screen.TRANSACTIONS },
                    icon = { Icon(Icons.AutoMirrored.Filled.ReceiptLong, null) },
                    label = { Text("Expenses") }
                )
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                draft = ReceiptDraft(); imageForDraft = null; editorError = null; showEditor = true
            }) {
                Icon(Icons.Default.Add, "Add expense")
            }
        }
    ) { padding ->
        when (screen) {
            Screen.DASHBOARD -> Dashboard(expenses, Modifier.padding(padding))
            Screen.TRANSACTIONS -> Transactions(expenses, vm::delete, Modifier.padding(padding))
        }
    }

    if (showEditor) ExpenseEditor(
        initial = draft,
        fromScreenshot = imageForDraft != null,
        error = editorError,
        onDismiss = { showEditor = false; editorError = null }
    ) { amount, category, note, merchant ->
        // Close only once the value has actually been accepted for writing.
        editorError = vm.save(amount, category, note, merchant, imageForDraft?.toString())
        if (editorError == null) showEditor = false
    }
}

@Composable private fun Dashboard(expenses: List<Expense>, modifier: Modifier = Modifier) {
    val now = Calendar.getInstance()
    val month = now.get(Calendar.MONTH)
    val year = now.get(Calendar.YEAR)
    val cursor = remember { Calendar.getInstance() }
    val monthly = expenses.filter {
        cursor.timeInMillis = it.paidAt
        cursor.get(Calendar.MONTH) == month && cursor.get(Calendar.YEAR) == year
    }
    val total = monthly.sumOf { it.amountPaise }
    val grouped = monthly.groupBy { it.category }
        .mapValues { entry -> entry.value.sumOf { it.amountPaise } }
        .toList()
        .sortedByDescending { it.second }

    Column(modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date()), style = MaterialTheme.typography.titleMedium)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp)) {
                Text("Total spent")
                Text(money(total), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            }
        }
        Text("By category", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        if (grouped.isEmpty()) Text("No expenses yet. Tap + or share a payment screenshot.")
        grouped.forEach { (category, value) ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp)) {
                    Text(category, Modifier.weight(1f))
                    Text(money(value), fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable private fun Transactions(expenses: List<Expense>, onDelete: (Expense) -> Unit, modifier: Modifier = Modifier) {
    if (expenses.isEmpty()) {
        Column(modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text("Your expense history is empty.", style = MaterialTheme.typography.titleLarge)
            Text("Add one manually or share a receipt screenshot from Google Pay.")
        }
        return
    }
    var pendingDelete by remember { mutableStateOf<Expense?>(null) }
    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(expenses, key = { it.id }) { e ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(e.merchant.ifBlank { e.category }, fontWeight = FontWeight.Bold)
                        Text(listOf(e.category, e.note).filter { it.isNotBlank() }.joinToString(" • "))
                        Text(SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(e.paidAt)), style = MaterialTheme.typography.labelSmall)
                    }
                    Text(money(e.amountPaise), fontWeight = FontWeight.Bold)
                    IconButton({ pendingDelete = e }) { Icon(Icons.Default.Delete, "Delete expense") }
                }
            }
        }
    }
    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete this expense?") },
            text = { Text("${money(target.amountPaise)} • ${target.merchant.ifBlank { target.category }}") },
            confirmButton = { Button({ onDelete(target); pendingDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton({ pendingDelete = null }) { Text("Cancel") } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ExpenseEditor(
    initial: ReceiptDraft,
    fromScreenshot: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSave: (String, String, String, String) -> Unit
) {
    var amount by remember(initial) { mutableStateOf(initial.amount) }
    var merchant by remember(initial) { mutableStateOf(initial.merchant) }
    var note by remember(initial) { mutableStateOf(initial.note) }
    var category by remember(initial) { mutableStateOf(initial.category) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (fromScreenshot) "Review receipt" else "Add expense") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (fromScreenshot) Text(
                    "Values were read from your screenshot. Please verify before saving.",
                    style = MaterialTheme.typography.bodySmall
                )
                if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    amount,
                    { amount = it },
                    label = { Text("Amount (₹)") },
                    singleLine = true,
                    isError = error != null,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                OutlinedTextField(merchant, { merchant = it }, label = { Text("Merchant / payee") }, singleLine = true)
                OutlinedTextField(note, { note = it }, label = { Text("Your note") })
                Text("Category")
                categories.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        row.forEach {
                            FilterChip(
                                selected = category == it,
                                onClick = { category = it },
                                label = { Text(it) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                { onSave(amount, category, note, merchant) },
                enabled = amount.isNotBlank()
            ) { Text("Save expense") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } }
    )
}

private fun money(paise: Long): String =
    NumberFormat.getCurrencyInstance(Locale("en", "IN")).format(paise / 100.0)
