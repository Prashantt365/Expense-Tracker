package com.peyo.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.IntentCompat
import com.peyo.app.sync.Account
import com.peyo.app.sync.BackupSettings
import com.peyo.app.ui.AuthMode
import com.peyo.app.ui.AuthScreen
import com.peyo.app.ui.CurrencyDialog
import com.peyo.app.ui.PeyoApp
import com.peyo.app.ui.theme.PeyoTheme
import com.peyo.app.ui.theme.ThemeSettings
import com.peyo.app.ui.WelcomeScreen

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

/**
 * Where a launch has got to, before the app itself is on screen.
 *
 * Only ever moves forward, and only on something the user did: choosing a route from the welcome
 * screen, or finishing with the one they chose. A phone that is already signed in starts at
 * [Opening] and never sees any of it.
 */
private enum class Launch { Welcome, SigningIn, Registering, Opening }

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
        // Before any composition, so the first frame already shows the right currency and the
        // theme the user picked rather than a light flash followed by the dark one.
        AppCurrency.load(this)
        ThemeSettings.load(this)
        // Only on a genuine launch. A rotation recreates the activity with the very intent it
        // was launched by, and consuming that again replayed it: the PDF picker reopened, a shared
        // screenshot was read a second time, and a statement already being reviewed was parsed
        // afresh with the user's ticks thrown away.
        if (savedInstanceState == null) consume(intent)
        val account = Account(this)
        val settings = BackupSettings(this)
        setContent {
            PeyoTheme {
                // An account is offered on a cold start and can be declined. The app is usable
                // offline by design, so the account gates the backup, not the app; a shared
                // receipt waiting in [action] is applied once this clears either way, because
                // PeyoApp reads it when it first composes.
                // Signed in, or previously said no: either way the question has been answered and
                // the app opens on itself. Only a genuinely fresh install sees the welcome screen.
                var launch by rememberSaveable {
                    mutableStateOf(
                        if (account.stored() != null || settings.accountDeclined) Launch.Opening
                        else Launch.Welcome
                    )
                }
                // Asked once, and only once the user has settled the account question, so the two
                // decisions are not stacked on top of each other on a first launch. The stored
                // answer is what makes it once: until then the app is running on a guess taken
                // from the phone's region, which is right often enough to open with and wrong
                // often enough -- a phone bought abroad, a tablet with no region at all -- to be
                // worth confirming.
                var askCurrency by rememberSaveable { mutableStateOf(!AppCurrency.hasChosen(this)) }

                when (launch) {
                    Launch.Welcome -> WelcomeScreen(
                        onSignIn = { launch = Launch.SigningIn },
                        onCreateAccount = { launch = Launch.Registering },
                        onSkip = { settings.accountDeclined = true; launch = Launch.Opening }
                    )

                    Launch.SigningIn, Launch.Registering -> AuthScreen(
                        account = account,
                        initialMode = if (launch == Launch.SigningIn) AuthMode.SIGN_IN else AuthMode.SIGN_UP,
                        onSignedIn = { launch = Launch.Opening },
                        onSkip = { settings.accountDeclined = true; launch = Launch.Opening },
                        onBack = { launch = Launch.Welcome }
                    )

                    Launch.Opening -> {
                        PeyoApp(action, actionToken)
                        if (askCurrency) {
                            CurrencyDialog(
                                // Dismissing is a real answer -- it keeps the currency the app
                                // guessed -- so it counts as having chosen and is not asked again.
                                onDismiss = { AppCurrency.set(this, AppCurrency.code); askCurrency = false },
                                subtitle = "Amounts are shown in this currency everywhere. " +
                                    "You can change it later in Settings.",
                                dismissLabel = "Keep " + AppCurrency.code,
                                onPick = { picked ->
                                    AppCurrency.set(this, picked)
                                    askCurrency = false
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Kept as the activity's intent too, so that anything reading it later sees the latest
        // request rather than the one the activity happened to be created with.
        setIntent(intent)
        consume(intent)
    }

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
        const val ACTION_ADD_EXPENSE = "com.peyo.app.ADD_EXPENSE"
        const val ACTION_SPLIT_EXPENSE = "com.peyo.app.SPLIT_EXPENSE"
        const val ACTION_IMPORT_PDF = "com.peyo.app.IMPORT_PDF"
        const val ACTION_OPEN_BALANCES = "com.peyo.app.OPEN_BALANCES"
    }
}
