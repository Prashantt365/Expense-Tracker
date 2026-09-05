package com.example.expensetracker.data

import android.content.Context
import androidx.room.Room

class ExpenseRepository(context: Context) {
    private val dao = Room.databaseBuilder(context, AppDatabase::class.java, "spendwise.db")
        // No migrations are written yet, so bump AppDatabase.version and add one before shipping a
        // schema change -- without this the first schema change crashes on every existing install.
        .fallbackToDestructiveMigration()
        .build()
        .expenseDao()

    val expenses = dao.observeAll()
    suspend fun save(expense: Expense) = dao.insert(expense)
    suspend fun delete(expense: Expense) = dao.delete(expense)
}
