package com.example.expensetracker

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.expensetracker.data.Expense
import com.example.expensetracker.data.ExpenseRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode

class ExpenseViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = ExpenseRepository(application)
    val expenses = repository.expenses.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Validates first and only then writes, returning null on success or a message to show the
     * user. The caller must keep the editor open on a non-null result, otherwise a rejected
     * amount closes the dialog and the expense is silently lost.
     */
    fun save(
        amount: String,
        category: String,
        note: String,
        merchant: String,
        receiptUri: String? = null
    ): String? {
        val paise = parsePaise(amount) ?: return "Enter an amount like 250 or 250.50"
        if (paise <= 0) return "Amount must be more than zero"
        viewModelScope.launch {
            repository.save(
                Expense(
                    amountPaise = paise,
                    category = category,
                    note = note.trim(),
                    merchant = merchant.trim(),
                    paidAt = System.currentTimeMillis(),
                    receiptUri = receiptUri
                )
            )
        }
        return null
    }

    /** OCR can hand back more precision than paise can hold, so round rather than reject. */
    private fun parsePaise(amount: String): Long? = runCatching {
        BigDecimal(amount.trim().replace(",", ""))
            .movePointRight(2)
            .setScale(0, RoundingMode.HALF_UP)
            .longValueExact()
    }.getOrNull()

    fun delete(expense: Expense) = viewModelScope.launch { repository.delete(expense) }
}
