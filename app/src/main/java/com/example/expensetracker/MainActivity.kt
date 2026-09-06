package com.example.expensetracker

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.IntentCompat
import com.example.expensetracker.ui.SpendwiseApp

/** What the app was asked to do on launch, whether by a share or a launcher shortcut. */
sealed interface LaunchAction {
    data object None : LaunchAction
    data class ReceiptShared(val image: Uri) : LaunchAction
    data class StatementShared(val pdf: Uri) : LaunchAction
    data object AddExpense : LaunchAction
    data object SplitExpense : LaunchAction
    data object ImportPdf : LaunchAction
    data object OpenBalances : LaunchAction
}

class MainActivity : ComponentActivity() {

    private var action by mutableStateOf<LaunchAction>(LaunchAction.None)

    /**
     * Incremented on every incoming intent. The same screenshot shared twice leaves the Uri
     * unchanged, and tapping the same shortcut twice leaves the action unchanged, so neither can
     * be keyed on its value alone.
     */
    private var actionToken by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        consume(intent)
        setContent { MaterialTheme { SpendwiseApp(action, actionToken) } }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); consume(intent) }

    private fun consume(intent: Intent) {
        val next = when (intent.action) {
            Intent.ACTION_SEND, Intent.ACTION_VIEW -> sharedDocument(intent)
            ACTION_ADD_EXPENSE -> LaunchAction.AddExpense
            ACTION_SPLIT_EXPENSE -> LaunchAction.SplitExpense
            ACTION_IMPORT_PDF -> LaunchAction.ImportPdf
            ACTION_OPEN_BALANCES -> LaunchAction.OpenBalances
            else -> null
        } ?: return
        action = next
        actionToken++
    }

    private fun sharedDocument(intent: Intent): LaunchAction? {
        val type = intent.type.orEmpty()
        val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            ?: intent.data
            ?: return null
        return when {
            type.startsWith("image/") -> LaunchAction.ReceiptShared(uri)
            type == "application/pdf" -> LaunchAction.StatementShared(uri)
            else -> null
        }
    }

    private companion object {
        const val ACTION_ADD_EXPENSE = "com.example.expensetracker.ADD_EXPENSE"
        const val ACTION_SPLIT_EXPENSE = "com.example.expensetracker.SPLIT_EXPENSE"
        const val ACTION_IMPORT_PDF = "com.example.expensetracker.IMPORT_PDF"
        const val ACTION_OPEN_BALANCES = "com.example.expensetracker.OPEN_BALANCES"
    }
}
