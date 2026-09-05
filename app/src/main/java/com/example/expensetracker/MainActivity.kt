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
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        incomingImage = sharedImage(intent)
        setContent { MaterialTheme { SpendwiseApp(incomingImage) } }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); incomingImage = sharedImage(intent) }
    private fun sharedImage(intent: Intent): Uri? = if (intent.action == Intent.ACTION_SEND && intent.type?.startsWith("image/") == true) {
        @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
    } else null
}

private enum class Screen { DASHBOARD, TRANSACTIONS }
private val categories = listOf("Food", "Transport", "Bills", "Shopping", "Health", "Other")

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun SpendwiseApp(sharedImage: Uri?, vm: ExpenseViewModel = viewModel()) {
    val expenses by vm.expenses.collectAsStateWithLifecycle(); var screen by remember { mutableStateOf(Screen.DASHBOARD) }
    var showEditor by remember { mutableStateOf(sharedImage != null) }; var draft by remember { mutableStateOf(ReceiptDraft()) }; var imageForDraft by remember { mutableStateOf(sharedImage) }
    LaunchedEffect(sharedImage) { if (sharedImage != null) TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(InputImage.fromFilePath(vm.getApplication(), sharedImage)).addOnSuccessListener { draft = OcrReceiptParser.parse(it.text) } }
    Scaffold(topBar = { CenterAlignedTopAppBar(title = { Text("Spendwise", fontWeight = FontWeight.Bold) }) }, bottomBar = { NavigationBar { NavigationBarItem(screen == Screen.DASHBOARD, { screen = Screen.DASHBOARD }, { Icon(Icons.Default.Home, null) }, { Text("Overview") }); NavigationBarItem(screen == Screen.TRANSACTIONS, { screen = Screen.TRANSACTIONS }, { Icon(Icons.Default.ReceiptLong, null) }, { Text("Expenses") }) } }, floatingActionButton = { FloatingActionButton(onClick = { draft = ReceiptDraft(); imageForDraft = null; showEditor = true }) { Icon(Icons.Default.Add, "Add expense") } }) { padding -> when (screen) { Screen.DASHBOARD -> Dashboard(expenses, Modifier.padding(padding)); Screen.TRANSACTIONS -> Transactions(expenses, vm::delete, Modifier.padding(padding)) } }
    if (showEditor) ExpenseEditor(draft, imageForDraft != null, { showEditor = false }) { amount, category, note, merchant -> vm.save(amount, category, note, merchant, imageForDraft?.toString()); showEditor = false }
}

@Composable private fun Dashboard(expenses: List<Expense>, modifier: Modifier = Modifier) {
    val now = Calendar.getInstance(); val monthly = expenses.filter { Calendar.getInstance().run { timeInMillis = it.paidAt; get(Calendar.MONTH) == now.get(Calendar.MONTH) && get(Calendar.YEAR) == now.get(Calendar.YEAR) } }; val total = monthly.sumOf { it.amountPaise }; val grouped = monthly.groupBy { it.category }.mapValues { it.value.sumOf { e -> e.amountPaise } }.toList().sortedByDescending { it.second }
    Column(modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) { Text(SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date()), style = MaterialTheme.typography.titleMedium); Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp)) { Text("Total spent"); Text(money(total), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold) } }; Text("By category", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); if (grouped.isEmpty()) Text("No expenses yet. Tap + or share a payment screenshot."); grouped.forEach { (category, value) -> Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(16.dp)) { Text(category, Modifier.weight(1f)); Text(money(value), fontWeight = FontWeight.SemiBold) } } } }
}

@Composable private fun Transactions(expenses: List<Expense>, onDelete: (Expense) -> Unit, modifier: Modifier = Modifier) {
    if (expenses.isEmpty()) Column(modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) { Text("Your expense history is empty.", style = MaterialTheme.typography.titleLarge); Text("Add one manually or share a receipt screenshot from Google Pay.") }
    else LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { items(expenses, key = { it.id }) { e -> Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(e.merchant.ifBlank { e.category }, fontWeight = FontWeight.Bold); Text(listOf(e.category, e.note).filter { it.isNotBlank() }.joinToString(" • ")); Text(SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(e.paidAt)), style = MaterialTheme.typography.labelSmall) }; Text(money(e.amountPaise), fontWeight = FontWeight.Bold); IconButton({ onDelete(e) }) { Icon(Icons.Default.Delete, "Delete expense") } } } } }
}

@Composable private fun ExpenseEditor(initial: ReceiptDraft, fromScreenshot: Boolean, onDismiss: () -> Unit, onSave: (String, String, String, String) -> Unit) {
    var amount by remember(initial) { mutableStateOf(initial.amount) }; var merchant by remember(initial) { mutableStateOf(initial.merchant) }; var note by remember(initial) { mutableStateOf(initial.note) }; var category by remember(initial) { mutableStateOf(initial.category) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (fromScreenshot) "Review receipt" else "Add expense") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { if (fromScreenshot) Text("Values were read from your screenshot. Please verify before saving.", style = MaterialTheme.typography.bodySmall); OutlinedTextField(amount, { amount = it }, label = { Text("Amount (₹)") }, singleLine = true); OutlinedTextField(merchant, { merchant = it }, label = { Text("Merchant / payee") }, singleLine = true); OutlinedTextField(note, { note = it }, label = { Text("Your note") }); Text("Category"); Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { categories.take(3).forEach { AssistChip({ category = it }, { Text(it) }) } }; Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { categories.drop(3).forEach { AssistChip({ category = it }, { Text(it) }) } } } }, confirmButton = { Button({ onSave(amount, category, note, merchant) }, enabled = amount.toBigDecimalOrNull()?.signum() == 1) { Text("Save expense") } }, dismissButton = { Button(onDismiss) { Text("Cancel") } })
}
private fun money(paise: Long): String = NumberFormat.getCurrencyInstance(Locale("en", "IN")).format(paise / 100.0)
