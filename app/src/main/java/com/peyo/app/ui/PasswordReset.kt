package com.peyo.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.peyo.app.sync.Account
import com.peyo.app.sync.AuthLink
import com.peyo.app.sync.Response
import com.peyo.app.sync.Session
import kotlinx.coroutines.launch

private const val OFFLINE = "No connection. Try again when you are online."

/** Supabase's email codes are six digits by default, and a project can lengthen them to ten. */
private val CODE_LENGTHS = 6..10

/**
 * Forgotten password, from the sign-in screen: an email address, then the code that arrives at
 * it and the new password, in one form.
 *
 * The code is there for an email read on another device. On this phone the link in the same email
 * does the job without any typing, and arrives at [AuthLinkScreen] instead.
 */
@Composable
internal fun PasswordResetForm(
    account: Account,
    initialEmail: String,
    onSignedIn: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf(initialEmail.trim()) }
    var sentTo by remember { mutableStateOf<String?>(null) }
    var code by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // A code is spent once it is verified, so the session it bought is held on to: a new password
    // the server turns down (too weak, the same as the old one) is retried without it.
    var verified by remember { mutableStateOf<Session?>(null) }

    val emailLooksReal = email.trim().let { it.contains('@') && it.substringAfterLast('@').contains('.') }

    fun sendCode() {
        error = null
        busy = true
        scope.launch {
            when (val result = account.sendPasswordReset(email)) {
                is Response.Ok -> {
                    sentTo = email.trim()
                    code = ""
                    verified = null
                }
                is Response.Rejected -> error = result.message
                is Response.Offline -> error = OFFLINE
            }
            busy = false
        }
    }

    fun reset(to: String) {
        error = null
        busy = true
        scope.launch {
            val session = verified ?: when (val result = account.verifyResetCode(to, code)) {
                is Response.Ok -> result.body.also { verified = it }
                is Response.Rejected -> {
                    // GoTrue says only "Token has expired or is invalid", which reads as jargon.
                    error = if (result.status == 403) {
                        "That code is wrong or has expired. Check the latest email, or send a new code."
                    } else result.message
                    busy = false
                    return@launch
                }
                is Response.Offline -> {
                    error = OFFLINE
                    busy = false
                    return@launch
                }
            }
            when (val result = account.setPassword(session, password)) {
                is Response.Ok -> onSignedIn()
                is Response.Rejected -> error = result.message
                is Response.Offline -> error = OFFLINE
            }
            busy = false
        }
    }

    ResetSurface(modifier) {
        val to = sentTo
        if (to == null) {
            PeyoMark("Forgot your password? We will email you a code to set a new one.")
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                email,
                { email = it; error = null },
                label = { Text("Email") },
                singleLine = true,
                enabled = !busy,
                shape = MaterialTheme.shapes.medium,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Done
                ),
                modifier = Modifier.fillMaxWidth()
            )
            error?.let { AlertCard(it, icon = Icons.Default.Warning) }
            Button({ sendCode() }, Modifier.fillMaxWidth(), enabled = !busy && emailLooksReal) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text("Send code")
            }
        } else {
            PeyoMark("Check your email")
            Text(
                "If $to has a Peyo account, we sent it a code and a link. Tap the link on this " +
                    "phone, or type the code here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            // Once verified the code has done its job, and changing it would mean nothing.
            if (verified == null) OutlinedTextField(
                code,
                { typed -> code = typed.filter(Char::isDigit).take(CODE_LENGTHS.last); error = null },
                label = { Text("Code") },
                singleLine = true,
                enabled = !busy,
                shape = MaterialTheme.shapes.medium,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.NumberPassword,
                    imeAction = ImeAction.Next
                ),
                modifier = Modifier.fillMaxWidth()
            )
            NewPasswordField(password, { password = it; error = null }, enabled = !busy)
            error?.let { AlertCard(it, icon = Icons.Default.Warning) }
            Button(
                { reset(to) },
                Modifier.fillMaxWidth(),
                enabled = !busy && password.length >= MIN_PASSWORD &&
                    (verified != null || code.length in CODE_LENGTHS)
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text("Set new password")
            }
            TextButton({ sendCode() }, Modifier.fillMaxWidth(), enabled = !busy) {
                Text("Send a new code")
            }
        }
        TextButton(onBack, Modifier.fillMaxWidth(), enabled = !busy) { Text("Back to sign in") }
    }
}

/**
 * Where a link from a Supabase email lands: a password reset asks for the new password, and any
 * other link -- a confirmed signup -- offers to sign in.
 *
 * Neither happens without a tap, and both name the account first. Any app or web page can open a
 * peyo://auth address, so one carrying somebody else's tokens must not be able to sign this phone
 * into their account and start backing its transactions up there.
 */
@Composable
fun AuthLinkScreen(
    link: AuthLink,
    account: Account,
    onSignedIn: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var session by remember(link) { mutableStateOf<Session?>(null) }
    var failure by remember(link) { mutableStateOf((link as? AuthLink.Failed)?.message) }
    var password by remember(link) { mutableStateOf("") }
    var busy by remember(link) { mutableStateOf(false) }
    var error by remember(link) { mutableStateOf<String?>(null) }

    LaunchedEffect(link) {
        if (link !is AuthLink.Tokens) return@LaunchedEffect
        when (val result = account.sessionFromLink(link)) {
            is Response.Ok -> session = result.body
            // A link's token is short lived, so one that is refused has most likely expired.
            is Response.Rejected -> failure = "That link has expired. Ask for a new one from the sign-in screen."
            is Response.Offline -> failure = "No connection. Open the link again when you are online."
        }
    }

    ResetSurface(modifier) {
        val signed = session
        val refused = failure
        when {
            refused != null -> {
                PeyoMark("That link did not work")
                AlertCard(refused, icon = Icons.Default.Warning)
                Button(onDismiss, Modifier.fillMaxWidth()) { Text("OK") }
            }

            signed == null -> {
                PeyoMark("Checking your link")
                CircularProgressIndicator()
            }

            (link as? AuthLink.Tokens)?.type == "recovery" -> {
                PeyoMark("Choose a new password")
                AccountLine(signed)
                Spacer(Modifier.height(8.dp))
                NewPasswordField(password, { password = it; error = null }, enabled = !busy)
                error?.let { AlertCard(it, icon = Icons.Default.Warning) }
                Button(
                    {
                        error = null
                        busy = true
                        scope.launch {
                            when (val result = account.setPassword(signed, password)) {
                                is Response.Ok -> onSignedIn()
                                is Response.Rejected -> error = result.message
                                is Response.Offline -> error = OFFLINE
                            }
                            busy = false
                        }
                    },
                    Modifier.fillMaxWidth(),
                    enabled = !busy && password.length >= MIN_PASSWORD
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text("Save password and sign in")
                }
                TextButton(onDismiss, Modifier.fillMaxWidth(), enabled = !busy) { Text("Not my account") }
            }

            else -> {
                PeyoMark("Your email is confirmed")
                AccountLine(signed)
                Spacer(Modifier.height(8.dp))
                Button({ account.remember(signed); onSignedIn() }, Modifier.fillMaxWidth()) {
                    Text("Sign in")
                }
                TextButton(onDismiss, Modifier.fillMaxWidth()) { Text("Not now") }
            }
        }
    }
}

@Composable
private fun ResetSurface(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Spacer(Modifier.height(40.dp))
            content()
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** The account a link belongs to, so the user can see it is theirs before doing anything with it. */
@Composable
private fun AccountLine(session: Session) {
    Text(
        "For " + session.email.ifBlank { "your account" },
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.Medium,
        textAlign = TextAlign.Center
    )
}

@Composable
private fun NewPasswordField(password: String, onChange: (String) -> Unit, enabled: Boolean) {
    var revealed by remember { mutableStateOf(false) }
    OutlinedTextField(
        password,
        onChange,
        label = { Text("New password") },
        singleLine = true,
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
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
        supportingText = { Text("At least $MIN_PASSWORD characters") },
        isError = password.isNotEmpty() && password.length < MIN_PASSWORD,
        modifier = Modifier.fillMaxWidth()
    )
}
