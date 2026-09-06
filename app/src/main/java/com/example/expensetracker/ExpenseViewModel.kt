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
import com.example.expensetracker.data.Person
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode

/** Everything the editor collects, in the raw text form the fields hold it. */
data class ExpenseInput(
    val id: Long = 0,
    val amount: String = "",
    val category: String = "Other",
    val note: String = "",
    val merchant: String = "",
    val paidAt: Long = System.currentTimeMillis(),
    val sourceUri: String? = null,
    /** personId to their typed share. My own share is the remainder, never typed. */
    val shares: Map<Long, String> = emptyMap(),
    val newAttachments: List<Uri> = emptyList(),
    val existingAttachments: List<Attachment> = emptyList(),
    val removedAttachmentIds: List<Long> = emptyList()
)

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

        val split = SplitCalculator.compute(totalPaise, input.shares)
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

}
