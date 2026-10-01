package com.peyo.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings as SystemSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.peyo.app.Analytics
import com.peyo.app.ContactCandidate
import com.peyo.app.ContactImportPlanner
import com.peyo.app.ExpenseInput
import com.peyo.app.ExpenseViewModel
import com.peyo.app.LaunchAction
import com.peyo.app.OcrReceiptParser
import com.peyo.app.Period
import com.peyo.app.KnownPerson
import com.peyo.app.pickableMonths
import com.peyo.app.sync.Account
import com.peyo.app.SaveOutcome
import com.peyo.app.SplitMode
import com.peyo.app.data.ContactsReader
import com.peyo.app.data.Expense
import com.peyo.app.data.PersonBalance
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Screen(val label: String, val title: String, val icon: ImageVector) {
    DASHBOARD("Insights", "Insights", Icons.Default.Insights),
    TRANSACTIONS("Expenses", "Expenses", Icons.AutoMirrored.Filled.ReceiptLong),
    PEOPLE("People", "Balances", Icons.Default.Group),
    SETTINGS("Settings", "Settings", Icons.Default.Settings)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeyoApp(action: LaunchAction, actionToken: Int, vm: ExpenseViewModel = viewModel()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val expenses by vm.expenses.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val people by vm.people.collectAsStateWithLifecycle()
    val balances by vm.balances.collectAsStateWithLifecycle()
    val shared by vm.shared.collectAsStateWithLifecycle()

    // Notifications only mean something once there is a number for friends' expenses to find, so
    // the question waits until then, and is asked once: a refusal is an answer.
    val notifyPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(Unit) {
        vm.refreshShared()
        if (android.os.Build.VERSION.SDK_INT < 33) return@LaunchedEffect
        val prefs = context.getSharedPreferences("peyo.settings", android.content.Context.MODE_PRIVATE)
        val signedInWithNumber = Account(context).stored()?.phone?.isNotBlank() == true
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (signedInWithNumber && !granted && !prefs.getBoolean("askedNotifications", false)) {
            prefs.edit().putBoolean("askedNotifications", true).apply()
            notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val importState by vm.importState.collectAsStateWithLifecycle()

    // The tab and the period are saved with the activity; what is open on top of them lives in the
    // ViewModel. Either way a rotation leaves the user where they were.
    var screen by rememberSaveable { mutableStateOf(Screen.DASHBOARD) }
    var period by rememberSaveable { mutableStateOf(Period.thisMonth()) }
    // Read again on every tab change, so signing in or out from Settings shows on return.
    val firstName = remember(screen) {
        Account(context).stored()?.name.orEmpty().substringBefore(' ')
    }
    var addingPerson by rememberSaveable { mutableStateOf(false) }
    var editing by vm.editing
    var fromScreenshot by vm.fromScreenshot
    var editorError by vm.editorError
    var duplicateOf by vm.duplicateOf
    var settling by vm.settling
    var contactCandidates by vm.contactCandidates
    var contactSelection by vm.contactSelection

    // Notices are a snackbar rather than a modal now. Every one of them reports something that has
    // already happened successfully, and an alert the user has to dismiss to carry on is the wrong
    // weight for "imported 12 transactions".
    val snackbars = remember { SnackbarHostState() }
    fun notify(message: String) = scope.launch { snackbars.showSnackbar(message) }

    val defaultCategory = categories.firstOrNull()?.name ?: "Other"

    fun openEditor(mode: SplitMode = SplitMode.CUSTOM) {
        editing = ExpenseInput(category = defaultCategory, splitMode = mode)
        fromScreenshot = false
        editorError = null
        duplicateOf = null
    }

    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::importFrom)
    }

    var loadingContacts by remember { mutableStateOf(false) }

    fun loadContacts() {
        // A second tap while the phone book is still being read would only start the same work
        // again behind the first.
        if (loadingContacts) return
        loadingContacts = true
        scope.launch {
            val contacts = ContactsReader.readContacts(context)
            val existing = people.map { KnownPerson(it.name, it.phone) }
            // Checking every contact against every other for duplicates is real work on a phone
            // book of a thousand names. On the main thread it froze the app for seconds -- long
            // enough to look as though the import had done nothing, or to be killed as not
            // responding -- so it runs in the background and the picker opens when it is done.
            val candidates = withContext(Dispatchers.Default) {
                ContactImportPlanner.planContacts(contacts, existing)
            }
            contactCandidates = candidates
            // Anything flagged as a likely duplicate starts unticked.
            contactSelection = candidates.filterNot { it.isFlagged }.map { it.name }.toSet()
            loadingContacts = false
        }
    }

    val contactsPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) loadContacts()
        else scope.launch {
            // After a second refusal Android stops showing the prompt at all and answers "denied"
            // at once, so a tap on From contacts did nothing visible. The only way back from
            // there is the app's own page in system settings, so the notice offers it.
            val choice = snackbars.showSnackbar(
                "Peyo needs access to your contacts to import names.",
                actionLabel = "Settings",
                withDismissAction = true
            )
            if (choice == SnackbarResult.ActionPerformed) {
                context.startActivity(
                    Intent(SystemSettings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.fromParts("package", context.packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    fun importContacts() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) loadContacts() else contactsPermission.launch(Manifest.permission.READ_CONTACTS)
    }

    LaunchedEffect(actionToken) {
        when (val current = action) {
            is LaunchAction.None -> Unit
            is LaunchAction.AddExpense -> openEditor()
            is LaunchAction.SplitExpense -> openEditor(SplitMode.EQUAL)
            is LaunchAction.ImportPdf -> pdfPicker.launch(arrayOf("application/pdf"))
            is LaunchAction.OpenBalances -> screen = Screen.PEOPLE
            is LaunchAction.StatementShared -> vm.importFrom(current.pdf)
            is LaunchAction.ReceiptShared -> {
                // The screenshot is attached to the expense, and its Uri is remembered so that
                // sharing the very same receipt again is recognised as a duplicate.
                editing = ExpenseInput(
                    category = defaultCategory,
                    sourceUri = current.image.toString(),
                    newAttachments = listOf(current.image)
                )
                fromScreenshot = true
                editorError = null
                duplicateOf = null
                // fromFilePath throws when the share grant has lapsed or the file is unreadable,
                // and it runs before any listener is attached, so it needs its own guard.
                runCatching { InputImage.fromFilePath(vm.getApplication(), current.image) }
                    .onFailure { editorError = "Couldn't open that screenshot. Enter the details below." }
                    .onSuccess { image ->
                        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                            .process(image)
                            .addOnSuccessListener { text ->
                                val draft = OcrReceiptParser.parse(text.text)
                                editing = editing?.copy(
                                    amount = draft.amount,
                                    merchant = draft.merchant,
                                    note = draft.note,
                                    category = categories.firstOrNull { it.name == draft.category }?.name
                                        ?: defaultCategory
                                )
                            }
                            .addOnFailureListener {
                                editorError = "Couldn't read that screenshot. Enter the details below."
                            }
                    }
            }
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(screen.title) },
                actions = {
                    // Adding somebody is the thing you have come to the People tab to do when it
                    // is empty, and the one action there is no other route to from that tab.
                    if (screen == Screen.PEOPLE) IconButton({ addingPerson = true }) {
                        Icon(Icons.Default.PersonAdd, "Add a person")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                Screen.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = screen == destination,
                        onClick = { screen = destination },
                        icon = { Icon(destination.icon, null) },
                        label = { Text(destination.label) },
                        alwaysShowLabel = false
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbars) },
        floatingActionButton = {
            AnimatedVisibility(
                screen == Screen.DASHBOARD || screen == Screen.TRANSACTIONS,
                enter = scaleIn(tween(180)) + fadeIn(),
                exit = scaleOut(tween(180)) + fadeOut()
            ) {
                ExtendedFloatingActionButton(
                    onClick = { openEditor() },
                    icon = { Icon(Icons.Default.Add, null) },
                    text = { Text("Add") }
                )
            }
        }
    ) { padding ->
        val content = Modifier.padding(padding)
        // Crossfade rather than an instant swap: four tabs of dense, similarly coloured cards are
        // hard to tell apart at the moment of switching, and a short fade is what makes it read as
        // one screen replacing another.
        AnimatedContent(
            targetState = screen,
            transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(180)) },
            label = "screen"
        ) { current ->
            when (current) {
                Screen.DASHBOARD -> AnalyticsScreen(
                    report = remember(expenses, people, period) {
                        Analytics.build(expenses, people, period)
                    },
                    period = period,
                    months = remember(expenses) { pickableMonths(expenses) },
                    hasAnyExpenses = expenses.isNotEmpty(),
                    firstName = firstName,
                    onPeriodChange = { period = it },
                    onSettle = { personId, name ->
                        settling = balances.firstOrNull { it.personId == personId }
                            ?: PersonBalance(personId, name, 0)
                    },
                    onAdd = { openEditor() },
                    modifier = content
                )

                Screen.TRANSACTIONS -> TransactionsScreen(
                    expenses = expenses,
                    categories = categories.map { it.name },
                    peopleNames = remember(people) { people.associate { it.id to it.name } },
                    onEdit = { details ->
                        editing = ExpenseInput(
                            id = details.expense.id,
                            amount = (details.expense.amountPaise / 100.0).toString(),
                            category = details.expense.category,
                            note = details.expense.note,
                            merchant = details.expense.merchant,
                            paidAt = details.expense.paidAt,
                            sourceUri = details.expense.sourceUri,
                            // A bill somebody else paid keeps its splits as my share and theirs,
                            // which the editor shows as one figure rather than as people tagged.
                            shares = if (!details.expense.paidByMe) emptyMap()
                            else details.splits
                                .filter { it.personId != null && it.deletedAt == null }
                                .associate { it.personId!! to (it.amountPaise / 100.0).toString() },
                            paidBy = details.expense.paidByPersonId,
                            myShare = if (details.expense.paidByMe) ""
                            else details.splits
                                .filter { it.personId == null && it.deletedAt == null }
                                .sumOf { it.amountPaise }
                                .let { (it / 100.0).toString() },
                            existingAttachments = details.attachments
                        )
                        fromScreenshot = false
                        editorError = null
                        duplicateOf = null
                    },
                    onDelete = vm::delete,
                    onAdd = { openEditor() },
                    modifier = content
                )

                Screen.PEOPLE -> PeopleScreen(
                    vm = vm,
                    balances = balances,
                    shared = shared,
                    onMarkShared = { split ->
                        vm.markSharedSettled(split) { problem ->
                            notify(problem ?: "Marked as settled. They'll see it on their phone.")
                        }
                    },
                    onAddPeople = { addingPerson = true },
                    modifier = content
                )

                Screen.SETTINGS -> SettingsScreen(
                    vm = vm,
                    categories = categories,
                    people = people,
                    onImportContacts = ::importContacts,
                    onImportPdf = { pdfPicker.launch(arrayOf("application/pdf")) },
                    onNotice = { notify(it) },
                    onSettleUp = { person ->
                        settling = balances.firstOrNull { it.personId == person.id }
                            ?: PersonBalance(person.id, person.name, 0)
                    },
                    modifier = content
                )
            }
        }
    }

    editing?.let { input ->
        ExpenseEditor(
            input = input,
            initialDraft = vm.typedDraft?.takeIf { it.first == input }?.second ?: input,
            onDraftChange = { vm.typedDraft = input to it },
            categories = categories,
            people = people,
            fromScreenshot = fromScreenshot,
            error = editorError,
            duplicateOf = duplicateOf,
            onAddCategoryInline = vm::addCategoryInline,
            onAddPersonInline = vm::addPersonInline,
            onDismiss = { editing = null; editorError = null; duplicateOf = null },
            onSave = { draft, force ->
                vm.save(draft, force) { outcome ->
                    when (outcome) {
                        is SaveOutcome.Saved -> {
                            editing = null; editorError = null; duplicateOf = null
                            notify(if (draft.id == 0L) "Expense saved." else "Changes saved.")
                        }
                        is SaveOutcome.Invalid -> {
                            editing = draft; editorError = outcome.message; duplicateOf = null
                        }
                        is SaveOutcome.Duplicate -> {
                            // Keep the values on screen and let the user insist.
                            editing = draft; editorError = null; duplicateOf = outcome.existing
                        }
                    }
                }
            }
        )
    }

    settling?.let { balance -> SettleDialog(vm, balance) { settling = null } }

    if (addingPerson) PersonDialog("New person", "", "", { addingPerson = false }) { name, phone ->
        vm.addPerson(name, phone)
        addingPerson = false
        notify("Added $name.")
    }

    ImportReviewScreen(
        state = importState,
        categories = categories,
        onToggle = vm::toggleImportRow,
        onToggleAll = vm::toggleAllImportRows,
        onCategory = vm::setImportCategory,
        onConfirm = {
            vm.confirmImport { written ->
                notify(
                    if (written == 0) "Nothing imported: those rows are already recorded."
                    else "Imported $written transaction${if (written == 1) "" else "s"}."
                )
            }
        },
        onDismiss = vm::cancelImport
    )

    contactCandidates?.let { candidates ->
        ContactPickerScreen(
            candidates = candidates,
            selected = contactSelection,
            onToggle = { name ->
                contactSelection = if (name in contactSelection) contactSelection - name
                else contactSelection + name
            },
            onConfirm = {
                val phones = candidates.associate { it.name to it.phone.orEmpty() }
                vm.addPeople(contactSelection.map { it to phones[it].orEmpty() }) { added ->
                    notify("Added $added ${if (added == 1) "person" else "people"}.")
                }
                contactCandidates = null
            },
            onDismiss = { contactCandidates = null }
        )
    }
}
