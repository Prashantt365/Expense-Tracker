package com.example.expensetracker

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.expensetracker.data.Attachment
import com.example.expensetracker.data.Expense
import com.example.expensetracker.data.ExpenseDetails
import com.example.expensetracker.data.ExpenseRepository
import com.example.expensetracker.data.ExpenseSplit
import com.example.expensetracker.data.PdfTextReader
import com.example.expensetracker.data.Person
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Everything the editor collects, in the raw text form the fields hold it. */
data class ExpenseInput(
    val id: Long = 0,
    val amount: String = "",
    val category: String = "Other",
    val note: String = "",
    val merchant: String = "",
    val paidAt: Long = System.currentTimeMillis(),
    val sourceUri: String? = null,
    val splitMode: SplitMode = SplitMode.CUSTOM,
    /**
     * personId to whatever they typed, read according to [splitMode] - rupees, a percentage, or
     * ignored entirely for an equal split. Blank is allowed: it just leaves that person out.
     * My own share is the remainder and is never typed.
     */
    val shares: Map<Long, String> = emptyMap(),
    val newAttachments: List<Uri> = emptyList(),
    val existingAttachments: List<Attachment> = emptyList(),
    val removedAttachmentIds: List<Long> = emptyList()
)

/** Where a statement import has got to. */
sealed interface ImportState {
    data object Idle : ImportState
    data class Reading(val page: Int, val total: Int) : ImportState
    data class Review(
        val rows: List<StatementRow>,
        val selected: Set<Int>,
        val category: String = "Other"
    ) : ImportState
    data class Failed(val message: String) : ImportState
}

sealed interface SaveOutcome {
    data object Saved : SaveOutcome
    data class Invalid(val message: String) : SaveOutcome
    /** The user is asked to confirm rather than blocked: a genuine repeat payment is legitimate. */
    data class Duplicate(val existing: Expense) : SaveOutcome
}

class ExpenseViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = ExpenseRepository(application)

    val expenses = repository.expenses.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val categories = repository.categories.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val people = repository.people.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val balances = repository.balances.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun outstandingFor(personId: Long) = repository.outstandingFor(personId)

    fun save(input: ExpenseInput, force: Boolean = false, onResult: (SaveOutcome) -> Unit) {
        val totalPaise = SplitCalculator.parsePaise(input.amount)
            ?: return onResult(SaveOutcome.Invalid("Enter an amount like 250 or 250.50"))

        val split = SplitCalculator.compute(totalPaise, input.splitMode, input.shares)
        if (split is SplitResult.Invalid) return onResult(SaveOutcome.Invalid(split.message))
        val shares = (split as SplitResult.Valid).shares.map {
            ExpenseSplit(expenseId = input.id, personId = it.personId, amountPaise = it.amountPaise)
        }

        val expense = Expense(
            id = input.id,
            amountPaise = totalPaise,
            category = input.category,
            note = input.note.trim(),
            merchant = input.merchant.trim(),
            paidAt = input.paidAt,
            sourceUri = input.sourceUri
        )

        viewModelScope.launch {
            if (!force) {
                repository.findDuplicate(expense)?.let { return@launch onResult(SaveOutcome.Duplicate(it)) }
            }
            repository.save(expense, shares, input.newAttachments, input.removedAttachmentIds)
            onResult(SaveOutcome.Saved)
        }
    }

    fun delete(details: ExpenseDetails) = viewModelScope.launch { repository.delete(details.expense) }

    fun settleShare(splitId: Long) = viewModelScope.launch { repository.settleShare(splitId) }
    fun settleEverything(personId: Long) = viewModelScope.launch { repository.settleEverything(personId) }
    fun reopenEverything(personId: Long) = viewModelScope.launch { repository.reopenEverything(personId) }

    fun addCategory(name: String) = viewModelScope.launch { if (name.isNotBlank()) repository.addCategory(name) }
    fun renameCategory(category: com.example.expensetracker.data.Category, newName: String) =
        viewModelScope.launch { repository.renameCategory(category, newName) }

    fun deleteCategory(category: com.example.expensetracker.data.Category, onBlocked: (Int) -> Unit) =
        viewModelScope.launch {
            val used = repository.categoryUsage(category.name)
            // Deleting would leave those expenses pointing at a category the picker no longer offers.
            if (used > 0) onBlocked(used) else repository.deleteCategory(category)
        }

    fun addPerson(name: String) = viewModelScope.launch { if (name.isNotBlank()) repository.addPerson(name) }
    fun renamePerson(person: Person, newName: String) = viewModelScope.launch { repository.renamePerson(person, newName) }

    fun deletePerson(person: Person, onBlocked: (Int) -> Unit) = viewModelScope.launch {
        val owing = repository.personOutstandingCount(person.id)
        if (owing > 0) onBlocked(owing) else repository.deletePerson(person)
    }

    fun addPeople(names: List<String>, onDone: (Int) -> Unit) = viewModelScope.launch {
        onDone(repository.addPeople(names))
    }

    // --- Statement import -------------------------------------------------------------------

    private val _import = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = _import

    fun importFrom(uri: Uri) = viewModelScope.launch {
        _import.value = ImportState.Reading(0, 0)
        val reader = PdfTextReader(getApplication())
        val text = reader.readText(uri) { page, total -> _import.value = ImportState.Reading(page, total) }
        text.onFailure {
            _import.value = ImportState.Failed("Couldn't read that PDF. It may be password protected.")
        }.onSuccess { content ->
            val rows = StatementParser.parse(content)
            _import.value = if (rows.isEmpty()) {
                ImportState.Failed("No transactions were recognised in that PDF.")
            } else {
                // Credits are money in, so they start unticked; the user can still take them.
                ImportState.Review(rows, rows.indices.filterNot { rows[it].isCredit }.toSet())
            }
        }
    }

    fun toggleImportRow(index: Int) {
        val current = _import.value as? ImportState.Review ?: return
        val selected = current.selected.toMutableSet()
        if (!selected.add(index)) selected.remove(index)
        _import.value = current.copy(selected = selected)
    }

    fun setImportCategory(category: String) {
        val current = _import.value as? ImportState.Review ?: return
        _import.value = current.copy(category = category)
    }

    fun confirmImport(onDone: (Int) -> Unit) = viewModelScope.launch {
        val current = _import.value as? ImportState.Review ?: return@launch
        val now = System.currentTimeMillis()
        val expenses = current.selected.sorted().map { index ->
            val row = current.rows[index]
            Expense(
                amountPaise = row.amountPaise,
                category = current.category,
                note = "",
                merchant = row.description.take(80),
                // A row whose date could not be read falls back to now rather than being dropped.
                paidAt = row.date ?: now
            )
        }
        val written = repository.importExpenses(expenses)
        _import.value = ImportState.Idle
        onDone(written)
    }

    fun cancelImport() { _import.value = ImportState.Idle }

}
