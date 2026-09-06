package com.example.expensetracker.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.expensetracker.ExpenseViewModel
import com.example.expensetracker.data.Category
import com.example.expensetracker.data.ExpenseDetails
import com.example.expensetracker.data.Person
import com.example.expensetracker.data.PersonBalance

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
