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

class ExpenseViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = ExpenseRepository(application)
    val expenses = repository.expenses.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun save(amount: String, category: String, note: String, merchant: String, receiptUri: String? = null) {
        val paise = runCatching { BigDecimal(amount).movePointRight(2).longValueExact() }.getOrNull() ?: return
        if (paise <= 0) return
        viewModelScope.launch { repository.save(Expense(amountPaise = paise, category = category, note = note, merchant = merchant, paidAt = System.currentTimeMillis(), receiptUri = receiptUri)) }
    }

    fun delete(expense: Expense) = viewModelScope.launch { repository.delete(expense) }
}
