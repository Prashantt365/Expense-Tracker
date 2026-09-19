package com.example.expensetracker

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.expensetracker.data.Attachment
import com.example.expensetracker.data.BackupFiles
import com.example.expensetracker.data.Expense
import com.example.expensetracker.data.ExpenseDetails
import com.example.expensetracker.data.ExpenseRepository
import com.example.expensetracker.data.ExpenseSplit
import com.example.expensetracker.data.LocalBackup
import com.example.expensetracker.data.PdfTextReader
import com.example.expensetracker.data.Person
import com.example.expensetracker.sync.BackupSettings
import com.example.expensetracker.sync.SyncEngine
import com.example.expensetracker.sync.SyncOutcome
import com.example.expensetracker.widget.PeyoWidgets
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Everything the editor collects, in the raw text form the fields hold it. */
data class ExpenseInput(
    val id: Long = 0,
    val amount: String = "",
    val category: String = "Other",
    val note: String = "",
    val merchant: String = "",
    val paidAt: Long = System.currentTimeMillis(),
    val sourceUri: String? = null,
    val splitMode: SplitMode = SplitMode.CUSTOM,
    /**
     * personId to whatever they typed, read according to [splitMode] - rupees, a percentage, or
     * ignored entirely for an equal split. Blank is allowed: it just leaves that person out.
     * My own share is the remainder and is never typed.
     */
    val shares: Map<Long, String> = emptyMap(),
    val newAttachments: List<Uri> = emptyList(),
    val existingAttachments: List<Attachment> = emptyList(),
    val removedAttachmentIds: List<Long> = emptyList()
)

/** Where a statement import has got to. */
sealed interface ImportState {
    data object Idle : ImportState
    data class Reading(val page: Int, val total: Int) : ImportState
    data class Review(
        val rows: List<StatementRow>,
        val selected: Set<Int>,
        val category: String = "Other"
    ) : ImportState
    data class Failed(val message: String) : ImportState
}

sealed interface SaveOutcome {
    data object Saved : SaveOutcome
    data class Invalid(val message: String) : SaveOutcome
    /** The user is asked to confirm rather than blocked: a genuine repeat payment is legitimate. */
    data class Duplicate(val existing: Expense) : SaveOutcome
}

class ExpenseViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = ExpenseRepository(application)

    val expenses = repository.expenses.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val categories = repository.categories.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val people = repository.people.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val balances = repository.balances.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val syncEngine = SyncEngine(application, repository.syncDao)

    val conflicts = repository.conflicts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Null until a sync has been run in this session, so nothing is claimed before it is true. */
    private val _lastSync = MutableStateFlow<SyncOutcome?>(null)
    val lastSync: StateFlow<SyncOutcome?> = _lastSync

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing

    /** When the last run finished, remembered across launches so the card can say so on open. */
    private val _lastSyncAt = MutableStateFlow(syncEngine.lastSyncAt)
    val lastSyncAt: StateFlow<Long> = _lastSyncAt

    private val backupSettings = BackupSettings(application)

    private val _autoBackup = MutableStateFlow(backupSettings.autoBackup)
    val autoBackup: StateFlow<Boolean> = _autoBackup

    /** One run at a time, whatever asked for it, so two of them cannot interleave their writes. */
    private val syncLock = Mutex()
    private var autoBackupJob: Job? = null

    init {
        // Automatic backup, keyed on what is actually waiting to go up rather than on a timer or
        // on each individual write. Every local write clears syncedAt on the row it touched, so
        // this one flow sees all of them -- including the ones a future feature adds.
        viewModelScope.launch {
            repository.pendingUpload.distinctUntilChanged().collect { waiting ->
                if (waiting > 0) scheduleAutoBackup()
            }
        }
        // And once on open, which is what brings a second phone up to date and what finishes a
        // restore that a lost connection interrupted partway through.
        scheduleAutoBackup(OPEN_DELAY_MILLIS)

        // Any home screen widget redraws whenever the expense list changes. Keyed on the list
        // rather than on each write site, so a figure added by a statement import, a restore or a
        // settle-up reaches the home screen the same way a typed expense does -- and a widget
        // cannot be left stale by a future write path that forgets to say so.
        viewModelScope.launch {
            repository.expenses.collect { PeyoWidgets.refresh(getApplication()) }
        }
    }

    /**
     * Sends what is waiting, shortly.
     *
     * Debounced rather than immediate: saving one expense writes the row, its splits and sometimes
     * a category, so a sync per write would turn one saved expense into three uploads and a flaky
     * connection into three retries. The delay is short enough that nothing sits unsent for long
     * and long enough that a burst of edits travels as a single batch.
     */
    private fun scheduleAutoBackup(delayMillis: Long = DEBOUNCE_MILLIS) {
        if (!_autoBackup.value) return
        autoBackupJob?.cancel()
        autoBackupJob = viewModelScope.launch {
            delay(delayMillis)
            runSync(automatic = true) { syncEngine.sync() }
        }
    }

    fun setAutoBackup(enabled: Boolean) {
        backupSettings.autoBackup = enabled
        _autoBackup.value = enabled
        if (enabled) scheduleAutoBackup(IMMEDIATE_MILLIS)
    }

    /** The explicit "back up now", for anybody who has turned the automatic one off. */
    fun sync() = runSync(automatic = false) { syncEngine.sync() }

    /**
     * Reads the account's entire history back down, rather than only what has changed.
     *
     * The ordinary sync is incremental and remembers how far it got, which is exactly what a user
     * standing in front of a freshly reinstalled app cannot rely on -- so this drops those marks
     * and starts again from the beginning.
     */
    fun restore() = runSync(automatic = false) { syncEngine.restore() }

    private fun runSync(automatic: Boolean, block: suspend () -> SyncOutcome) {
        viewModelScope.launch {
            // An automatic run gives up when one is already going, because that run is carrying
            // the same rows anyway. A run the user asked for waits its turn and then happens.
            if (automatic && syncLock.isLocked) return@launch
            syncLock.withLock {
                _syncing.value = true
                try {
                    _lastSync.value = block()
                    _lastSyncAt.value = syncEngine.lastSyncAt
                } finally {
                    _syncing.value = false
                }
            }
        }
    }

    fun resolveConflict(conflictId: Long, keepLocal: Boolean) = viewModelScope.launch {
        syncEngine.resolve(conflictId, keepLocal)
    }

    // --- Backup to a file on this phone -----------------------------------------------------

    /** What a backup or an import came to, shown once and then dismissed. */
    sealed interface FileOutcome {
        data class Exported(val expenses: Int) : FileOutcome
        data class Imported(val result: LocalBackup.Imported) : FileOutcome
        data class Failed(val message: String) : FileOutcome
    }

    private val _fileOutcome = MutableStateFlow<FileOutcome?>(null)
    val fileOutcome: StateFlow<FileOutcome?> = _fileOutcome

    fun clearFileOutcome() { _fileOutcome.value = null }

    private val files = BackupFiles(application)

    /**
     * Writes everything to the file the user picked.
     *
     * Failures are reported rather than thrown: the picker can hand back a Uri whose grant has
     * already lapsed, or a location that has since gone away, and neither is worth a crash on
     * what is meant to be the safe option.
     */
    fun backupToFile(uri: Uri) = viewModelScope.launch {
        _fileOutcome.value = runCatching {
            val stream = files.writeTo(uri) ?: error("That location could not be written to.")
            FileOutcome.Exported(LocalBackup.export(repository.syncDao, stream))
        }.getOrElse { FileOutcome.Failed(it.message ?: "The backup could not be written.") }
    }

    /**
     * Merges a backup file back in. Anything it brings is left unsynced, so an account that is
     * signed in picks it up on the next run and the file reaches the server too.
     */
    fun importBackupFile(uri: Uri) = viewModelScope.launch {
        _fileOutcome.value = runCatching {
            val stream = files.readFrom(uri) ?: error("That file could not be opened.")
            FileOutcome.Imported(LocalBackup.merge(repository.syncDao, stream))
        }.getOrElse { FileOutcome.Failed(it.message ?: "That file could not be imported.") }
    }

    fun outstandingFor(personId: Long) = repository.outstandingFor(personId)

    fun save(input: ExpenseInput, force: Boolean = false, onResult: (SaveOutcome) -> Unit) {
        val totalPaise = SplitCalculator.parsePaise(input.amount)
            ?: return onResult(SaveOutcome.Invalid("Enter an amount like 250 or 250.50"))

        val split = SplitCalculator.compute(totalPaise, input.splitMode, input.shares)
        if (split is SplitResult.Invalid) return onResult(SaveOutcome.Invalid(split.message))
        val shares = (split as SplitResult.Valid).shares.map {
            ExpenseSplit(expenseId = input.id, personId = it.personId, amountPaise = it.amountPaise)
        }

        val expense = Expense(
            id = input.id,
            amountPaise = totalPaise,
            category = input.category,
            note = input.note.trim(),
            merchant = input.merchant.trim(),
            paidAt = input.paidAt,
            sourceUri = input.sourceUri
        )

        viewModelScope.launch {
            if (!force) {
                repository.findDuplicate(expense)?.let { return@launch onResult(SaveOutcome.Duplicate(it)) }
            }
            repository.save(expense, shares, input.newAttachments, input.removedAttachmentIds)
            onResult(SaveOutcome.Saved)
        }
    }

    fun delete(details: ExpenseDetails) = viewModelScope.launch { repository.delete(details.expense) }

    fun settleShare(splitId: Long) = viewModelScope.launch { repository.settleShare(splitId) }
    fun settleEverything(personId: Long) = viewModelScope.launch { repository.settleEverything(personId) }
    fun reopenEverything(personId: Long) = viewModelScope.launch { repository.reopenEverything(personId) }

    fun addCategory(name: String) = viewModelScope.launch { if (name.isNotBlank()) repository.addCategory(name) }
    suspend fun addCategoryInline(name: String) { if (name.isNotBlank()) repository.addCategory(name) }
    fun renameCategory(category: com.example.expensetracker.data.Category, newName: String) =
        viewModelScope.launch { repository.renameCategory(category, newName) }

    fun deleteCategory(category: com.example.expensetracker.data.Category, onBlocked: (Int) -> Unit) =
        viewModelScope.launch {
            val used = repository.categoryUsage(category.name)
            // Deleting would leave those expenses pointing at a category the picker no longer offers.
            if (used > 0) onBlocked(used) else repository.deleteCategory(category)
        }

    fun addPerson(name: String) = viewModelScope.launch { if (name.isNotBlank()) repository.addPerson(name) }
    suspend fun addPersonInline(name: String): Long = repository.addPerson(name)
    fun renamePerson(person: Person, newName: String) = viewModelScope.launch { repository.renamePerson(person, newName) }

    fun deletePerson(person: Person, onBlocked: (Int) -> Unit) = viewModelScope.launch {
        val owing = repository.personOutstandingCount(person.id)
        if (owing > 0) onBlocked(owing) else repository.deletePerson(person)
    }

    fun addPeople(names: List<String>, onDone: (Int) -> Unit) = viewModelScope.launch {
        onDone(repository.addPeople(names))
    }

    // --- Statement import -------------------------------------------------------------------

    private val _import = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = _import

    fun importFrom(uri: Uri) = viewModelScope.launch {
        _import.value = ImportState.Reading(0, 0)
        val reader = PdfTextReader(getApplication())
        val text = reader.readText(uri) { page, total -> _import.value = ImportState.Reading(page, total) }
        text.onFailure {
            _import.value = ImportState.Failed("Couldn't read that PDF. It may be password protected.")
        }.onSuccess { content ->
            val rows = StatementParser.parse(content)
            _import.value = if (rows.isEmpty()) {
                ImportState.Failed("No transactions were recognised in that PDF.")
            } else {
                // Credits are money in, so they start unticked; the user can still take them.
                ImportState.Review(rows, rows.indices.filterNot { rows[it].isCredit }.toSet())
            }
        }
    }

    fun toggleImportRow(index: Int) {
        val current = _import.value as? ImportState.Review ?: return
        val selected = current.selected.toMutableSet()
        if (!selected.add(index)) selected.remove(index)
        _import.value = current.copy(selected = selected)
    }

    /**
     * Ticks or unticks every row at once.
     *
     * A bank statement runs to dozens of rows and the common cases are "all of these" and "none of
     * these, let me pick" -- both of which were previously several dozen taps.
     */
    fun toggleAllImportRows(select: Boolean) {
        val current = _import.value as? ImportState.Review ?: return
        _import.value = current.copy(
            selected = if (select) current.rows.indices.toSet() else emptySet()
        )
    }

    fun setImportCategory(category: String) {
        val current = _import.value as? ImportState.Review ?: return
        _import.value = current.copy(category = category)
    }

    fun confirmImport(onDone: (Int) -> Unit) = viewModelScope.launch {
        val current = _import.value as? ImportState.Review ?: return@launch
        val now = System.currentTimeMillis()
        val expenses = current.selected.sorted().map { index ->
            val row = current.rows[index]
            Expense(
                amountPaise = row.amountPaise,
                category = current.category,
                note = "",
                merchant = row.description.take(80),
                // A row whose date could not be read falls back to now rather than being dropped.
                paidAt = row.date ?: now
            )
        }
        val written = repository.importExpenses(expenses)
        _import.value = ImportState.Idle
        onDone(written)
    }

    fun cancelImport() { _import.value = ImportState.Idle }

    private companion object {
        /** Long enough for a burst of edits to settle, short enough not to feel like a delay. */
        const val DEBOUNCE_MILLIS = 3_000L

        /** A moment after opening, so the first frame is never waiting on a network call. */
        const val OPEN_DELAY_MILLIS = 1_500L

        const val IMMEDIATE_MILLIS = 250L
    }
}
