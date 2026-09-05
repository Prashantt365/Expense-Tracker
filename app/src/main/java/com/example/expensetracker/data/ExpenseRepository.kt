package com.example.expensetracker.data

import android.content.Context
import androidx.room.Room

class ExpenseRepository(context: Context) {
    private val dao = Room.databaseBuilder(context, AppDatabase::class.java, "spendwise.db").build().expenseDao()
    val expenses = dao.observeAll()
    suspend fun save(expense: Expense) = dao.insert(expense)
    suspend fun delete(expense: Expense) = dao.delete(expense)
}
