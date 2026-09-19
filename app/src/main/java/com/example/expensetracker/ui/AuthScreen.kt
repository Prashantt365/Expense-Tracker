package com.example.expensetracker.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.expensetracker.R
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

/** The mark, as it opens both the welcome screen and the sign-in form. */
@Composable
private fun PeyoMark(subtitle: String? = null) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(76.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            // The monochrome layer rather than the launcher icon itself: ic_launcher is an
            // adaptive icon, which is an XML the loader cannot rasterise, and the full artwork
            // carries the wordmark that is already set in type immediately below this.
            Image(
                painterResource(R.mipmap.ic_launcher_monochrome),
                null,
                Modifier.size(52.dp),
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onPrimaryContainer)
            )
        }
        Spacer(Modifier.height(14.dp))
        Text("Peyo", style = MaterialTheme.typography.headlineLarge)
        subtitle?.let {
            Spacer(Modifier.height(6.dp))
            Text(
                it,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

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
    Surface(modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Spacer(Modifier.height(48.dp))
            PeyoMark("Track what you spend, split what you share, and settle up.")
            Spacer(Modifier.height(16.dp))

            PeyoCard(container = MaterialTheme.colorScheme.surfaceContainer) {
                Column(
                    Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Promise(
                        Icons.Default.PhoneAndroid,
                        "Yours, on this phone",
                        "Every expense is written here first, so the app works with no connection at all."
                    )
                    Promise(
                        Icons.Default.CloudDone,
                        "Backed up with an account",
                        "Your transactions come back after a reinstall, and reach a second phone."
                    )
                    Promise(
                        Icons.Default.Groups,
                        "Split and settle",
                        "Share a bill three ways, and Peyo keeps the running balance for you."
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
            Button(onCreateAccount, Modifier.fillMaxWidth(), enabled = Supabase.isConfigured) {
                Text("Create an account")
            }
            OutlinedButton(onSignIn, Modifier.fillMaxWidth(), enabled = Supabase.isConfigured) {
                Text("Sign in")
            }

            if (!Supabase.isConfigured) NotConfiguredCard()

            TextButton(onSkip, Modifier.fillMaxWidth()) { Text("Continue without an account") }
            Text(
                "Everything stays on this phone. You can sign in later from Settings, and back up " +
                    "to a file at any time.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Promise(icon: ImageVector, title: String, body: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun NotConfiguredCard() = AlertCard(
    text = "This build has no Supabase project configured, so accounts are unavailable. Set " +
        "supabase.url and supabase.anonKey in local.properties.",
    title = "Accounts are switched off",
    icon = Icons.Default.Warning
)

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
    var revealed by remember { mutableStateOf(false) }
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

    Surface(modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Spacer(Modifier.height(40.dp))

            val sentTo = confirmationSentTo
            if (sentTo != null) {
                // The project requires a confirmed address, so the account exists but cannot sign
                // in yet. Saying so is the whole job here: a spinner that never resolves is the
                // failure mode this screen exists to avoid.
                Box(
                    Modifier.size(72.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.MarkEmailUnread,
                        null,
                        Modifier.size(34.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Text("Check your email", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "We sent a confirmation link to $sentTo. Open it, then sign in.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Button({
                    confirmationSentTo = null
                    mode = AuthMode.SIGN_IN
                    password = ""
                }, Modifier.fillMaxWidth()) { Text("Back to sign in") }
                TextButton(onSkip, Modifier.fillMaxWidth()) { Text("Continue without an account") }
                return@Column
            }

            PeyoMark(
                if (mode == AuthMode.SIGN_IN) "Sign in and your transactions come back on any phone."
                else "Create an account and your transactions survive a reinstall."
            )
            Text(
                "Receipts and attachments stay on this phone either way.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))

            if (!Supabase.isConfigured) NotConfiguredCard()

            OutlinedTextField(
                email,
                { email = it; error = null },
                label = { Text("Email") },
                singleLine = true,
                enabled = !busy,
                isError = error != null,
                shape = MaterialTheme.shapes.medium,
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
                shape = MaterialTheme.shapes.medium,
                visualTransformation = if (revealed) VisualTransformation.None
                else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton({ revealed = !revealed }) {
                        Icon(
                            if (revealed) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            if (revealed) "Hide password" else "Show password"
                        )
                    }
                },
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

            error?.let { AlertCard(it, icon = Icons.Default.Warning) }

            Button(
                { submit() },
                Modifier.fillMaxWidth(),
                enabled = canSubmit && Supabase.isConfigured
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(if (mode == AuthMode.SIGN_IN) "Sign in" else "Create account")
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
                    if (mode == AuthMode.SIGN_IN) "No account yet? Create one"
                    else "Already registered? Sign in"
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
}
