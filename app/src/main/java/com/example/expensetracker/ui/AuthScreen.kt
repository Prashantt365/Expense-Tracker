package com.example.expensetracker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.expensetracker.sync.Account
import com.example.expensetracker.sync.Response
import com.example.expensetracker.sync.SignUpResult
import com.example.expensetracker.sync.Supabase
import kotlinx.coroutines.launch

/**
 * The shortest password Supabase will accept by default. Checking it here rather than letting the
 * server refuse turns a round trip and a jargon error into an answer the user gets while typing.
 */
private const val MIN_PASSWORD = 6

enum class AuthMode { SIGN_IN, SIGN_UP }

/**
 * The first thing a new install shows: three choices, stated plainly, before any form.
 *
 * Opening straight onto a password field asks somebody who has never seen the app to commit to an
 * account before they know what it is for, and buries "no thanks" under a form they have to read
 * past. The three routes are equally weighted here because they genuinely are alternatives --
 * everything is written to this phone first, so an account is a backup rather than a licence to
 * use the app.
 */
@Composable
fun WelcomeScreen(
    onSignIn: () -> Unit,
    onCreateAccount: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Spacer(Modifier.height(48.dp))
        Text("Peyo", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text(
            "Track what you spend, split what you share, and settle up.",
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(Modifier.height(12.dp))

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("With an account", fontWeight = FontWeight.Bold)
                Text(
                    "Your transactions are backed up and come back on any phone, including this " +
                        "one after a reinstall.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Button(onCreateAccount, Modifier.fillMaxWidth(), enabled = Supabase.isConfigured) {
            Text("Create an account")
        }
        OutlinedButton(onSignIn, Modifier.fillMaxWidth(), enabled = Supabase.isConfigured) {
            Text("Sign in")
        }

        if (!Supabase.isConfigured) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Text(
                    "This build has no Supabase project configured, so accounts are unavailable. " +
                        "Set supabase.url and supabase.anonKey in local.properties.",
                    Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Spacer(Modifier.height(4.dp))
        TextButton(onSkip, Modifier.fillMaxWidth()) { Text("Continue without an account") }
        Text(
            "Everything stays on this phone. You can sign in later from Settings, and back up to " +
                "a file at any time.",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * Signing in, so that a reinstall or a second phone finds the same transactions.
 *
 * Skipping is deliberately offered. Everything is written to the local database first and syncing
 * is what happens afterwards, so an account is a backup rather than a licence to use the app --
 * and refusing to open without a connection would be a regression for anybody already using it.
 * Make [onSkip] unreachable here if registration should be compulsory.
 */
@Composable
fun AuthScreen(
    account: Account,
    onSignedIn: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
    initialMode: AuthMode = AuthMode.SIGN_IN,
    onBack: (() -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(initialMode) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmationSentTo by remember { mutableStateOf<String?>(null) }

    val emailLooksReal = email.trim().let { it.contains('@') && it.substringAfterLast('@').contains('.') }
    val canSubmit = !busy && emailLooksReal && password.length >= MIN_PASSWORD

    fun submit() {
        error = null
        busy = true
        scope.launch {
            when (mode) {
                AuthMode.SIGN_IN -> when (val result = account.signIn(email, password)) {
                    is Response.Ok -> onSignedIn()
                    is Response.Rejected -> error = result.message
                    is Response.Offline -> error = "No connection. You can keep using the app offline."
                }

                AuthMode.SIGN_UP -> when (val result = account.signUp(email, password)) {
                    is SignUpResult.SignedIn -> onSignedIn()
                    is SignUpResult.NeedsEmailConfirmation -> confirmationSentTo = result.email
                    is SignUpResult.Failed -> error = result.message
                }
            }
            busy = false
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Spacer(Modifier.height(36.dp))
        Text("Peyo", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

        val sentTo = confirmationSentTo
        if (sentTo != null) {
            // The project requires a confirmed address, so the account exists but cannot sign in
            // yet. Saying so is the whole job here: a spinner that never resolves is the failure
            // mode this screen exists to avoid.
            Text("Check your email", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("We sent a confirmation link to $sentTo. Open it, then sign in.")
            Button({
                confirmationSentTo = null
                mode = AuthMode.SIGN_IN
                password = ""
            }, Modifier.fillMaxWidth()) { Text("Back to sign in") }
            TextButton(onSkip, Modifier.fillMaxWidth()) { Text("Continue without an account") }
            return@Column
        }

        Text(
            if (mode == AuthMode.SIGN_IN) {
                "Sign in and your transactions come back on any phone."
            } else {
                "Create an account and your transactions survive a reinstall."
            },
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            "Receipts and attachments stay on this phone either way.",
            style = MaterialTheme.typography.bodySmall
        )

        if (!Supabase.isConfigured) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Text(
                    "This build has no Supabase project configured, so accounts are unavailable. " +
                        "Set supabase.url and supabase.anonKey in local.properties.",
                    Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        OutlinedTextField(
            email,
            { email = it; error = null },
            label = { Text("Email") },
            singleLine = true,
            enabled = !busy,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Next
            ),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            password,
            { password = it; error = null },
            label = { Text("Password") },
            singleLine = true,
            enabled = !busy,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done
            ),
            supportingText = {
                if (mode == AuthMode.SIGN_UP) Text("At least $MIN_PASSWORD characters")
            },
            isError = password.isNotEmpty() && password.length < MIN_PASSWORD,
            modifier = Modifier.fillMaxWidth()
        )

        error?.let {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Text(it, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
            }
        }

        Button({ submit() }, Modifier.fillMaxWidth(), enabled = canSubmit && Supabase.isConfigured) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Text(if (mode == AuthMode.SIGN_IN) "Sign in" else "Create account")
            }
        }

        TextButton(
            {
                mode = if (mode == AuthMode.SIGN_IN) AuthMode.SIGN_UP else AuthMode.SIGN_IN
                error = null
            },
            Modifier.fillMaxWidth(),
            enabled = !busy
        ) {
            Text(
                if (mode == AuthMode.SIGN_IN) "No account yet? Create one" else "Already registered? Sign in"
            )
        }

        onBack?.let {
            TextButton(it, Modifier.fillMaxWidth(), enabled = !busy) { Text("Back") }
        }
        TextButton(onSkip, Modifier.fillMaxWidth(), enabled = !busy) {
            Text("Continue without an account")
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** The account row in Settings, once there is somewhere to show it. */
@Composable
fun AccountCard(
    email: String?,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (email.isNullOrBlank()) {
                Text("Not signed in", fontWeight = FontWeight.Bold)
                Text(
                    "Transactions are on this phone only. Signing in backs them up and brings " +
                        "them to any other phone.",
                    style = MaterialTheme.typography.bodySmall
                )
                TextButton(onSignIn) { Text("Sign in or create an account") }
            } else {
                Text(email, fontWeight = FontWeight.Bold)
                Text(
                    "Transactions are backed up. Screenshots and attachments stay on this phone.",
                    style = MaterialTheme.typography.bodySmall
                )
                TextButton(onSignOut) { Text("Sign out") }
            }
        }
    }
}
