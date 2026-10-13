package org.lvl3.hibi.ui

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.lvl3.hibi.HibiApplication
import org.lvl3.hibi.backup.ArchiveProgress
import org.lvl3.hibi.backup.PreparedImport
import org.lvl3.hibi.backup.ZipBackup
import org.lvl3.hibi.model.*
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

data class UiState(
    val entries: List<Entry> = emptyList(), val preferences: Preferences = Preferences(),
    val draft: Entry? = null, val editorOpen: Boolean = false, val requestedEntry: Entry? = null,
    val loaded: Boolean = false, val busy: Boolean = false,
    val operation: String? = null, val progress: ArchiveProgress? = null, val cancellable: Boolean = false,
    val message: String? = null, val fatal: Boolean = false,
    val export: File? = null, val prepared: PreparedImport? = null,
    val importPlan: MergePlan? = null, val importWarnings: List<String> = emptyList(),
    val importError: String? = null,
)

class DiaryViewModel(application: Application, private val saved: SavedStateHandle = SavedStateHandle()) : AndroidViewModel(application) {
    private val diary = (application as HibiApplication).diary
    fun mediaFile(photo: Photo): File = diary.mediaFile(photo)
    private val mutable = MutableStateFlow(UiState(editorOpen = saved["editorOpen"] ?: false))
    val state = mutable.asStateFlow()
    private val mutex = (application as HibiApplication).diaryMutex
    private val backup = ZipBackup()
    private var draftJob: Job? = null
    private var activeWork: Job? = null
    private var pendingSavePassword: CharArray? = null
    private val exports = File(application.cacheDir, "exports").apply { mkdirs() }

    init { load(cleanTemporaryFiles = true) }
    fun load(cleanTemporaryFiles: Boolean = false) = work(fatalOnError = true) {
        if (cleanTemporaryFiles && getApplication<HibiApplication>().claimTemporaryCleanup()) {
            val app = getApplication<Application>()
            exports.listFiles()?.forEach { it.delete() }
            File(app.filesDir, "exports").listFiles()?.forEach { it.delete() }
            File(app.cacheDir, "imports").listFiles()?.forEach { it.deleteRecursively() }
            app.cacheDir.listFiles()?.filter { it.name.startsWith("import-") && it.extension == "zip" }?.forEach { it.delete() }
            diary.cleanTemporaryMedia()
        }
        val entries = diary.entries(); val preferences = diary.preferences(); val draft = diary.draft()
        mutable.update { it.copy(entries = entries, preferences = preferences, draft = draft,
            editorOpen = it.editorOpen && draft != null, loaded = true, fatal = false) }
    }
    fun startDiary() = work { diary.setPreferences(diary.preferences().copy(initialized = true)); refresh() }
    private fun setEditorOpen(open: Boolean) {
        mutable.update { it.copy(editorOpen = open) }
        viewModelScope.launch(Dispatchers.Main.immediate) { saved["editorOpen"] = open }
    }
    fun newEntry(day: String? = null) {
        val entry = Entry(day = day ?: LocalDate.now().toString())
        if (mutable.value.draft != null) mutable.update { it.copy(requestedEntry = entry) }
        else openEditor(entry)
    }
    fun openEditor(entry: Entry? = null) {
        val current = mutable.value.draft
        if (entry != null && current != null && current.id != entry.id) {
            mutable.update { it.copy(requestedEntry = entry) }; return
        }
        val draft = current ?: entry ?: Entry()
        mutable.update { it.copy(draft = draft, requestedEntry = null) }
        setEditorOpen(true); flushDraft(draft.id)
    }
    fun resumeDraft() { openEditor() }
    fun cancelEditorRequest() { mutable.update { it.copy(requestedEntry = null) } }
    fun replaceDraftAndOpen() {
        val requested = mutable.value.requestedEntry ?: return
        draftJob?.cancel()
        work {
            diary.discardDraft(); diary.setDraft(requested)
            mutable.update { it.copy(draft = requested, requestedEntry = null) }; setEditorOpen(true)
        }
    }
    fun updateDraft(transform: (Entry) -> Entry) {
        val draft = mutable.value.draft?.let(transform) ?: return
        mutable.update { it.copy(draft = draft) }; persistDraft(draft)
    }
    fun updateDraftText(id: String, text: String) {
        if (mutable.value.editorOpen && mutable.value.draft?.id == id) updateDraft { it.copy(markdown = text) }
    }
    fun persistEditorSnapshot(id: String, base: String, text: String) {
        if (mutable.value.draft?.id != id) return
        // A late outgoing-renderer response must not overwrite newer words or another draft.
        mutable.update { state ->
            val draft = state.draft
            if (draft?.id == id && draft.markdown == base) state.copy(draft = draft.copy(markdown = text)) else state
        }
        flushDraft(id)
    }
    fun flushDraft(id: String, after: (() -> Unit)? = null) {
        if (mutable.value.draft?.id != id) return
        draftJob?.cancel()
        work {
            val draft = mutable.value.draft?.takeIf { it.id == id } ?: return@work
            diary.setDraft(draft)
            if (after != null) withContext(Dispatchers.Main.immediate) {
                if (mutable.value.editorOpen && mutable.value.draft?.id == id) after()
            }
        }
    }
    private fun persistDraft(draft: Entry) {
        draftJob?.cancel()
        draftJob = viewModelScope.launch {
            delay(250)
            try { withContext(Dispatchers.IO) { mutex.withLock {
                mutable.value.draft?.takeIf { it.id == draft.id }?.let { diary.setDraft(it) }
            } } }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { message("Entwurf konnte nicht gespeichert werden: ${error.message}") }
        }
    }
    fun closeEditor() {
        draftJob?.cancel()
        work {
            val draft = mutable.value.draft ?: return@work
            if (keepDraft(draft, mutable.value.entries.firstOrNull { it.id == draft.id })) diary.setDraft(draft)
            else { diary.discardDraft(); mutable.update { it.copy(draft = null) } }
            setEditorOpen(false)
        }
    }
    fun discardDraft() {
        draftJob?.cancel()
        work { diary.discardDraft(); mutable.update { it.copy(draft = null) }; setEditorOpen(false) }
    }
    fun saveDraft(markdown: String) {
        val id = mutable.value.draft?.id ?: return
        updateDraft { it.copy(markdown = markdown) }; draftJob?.cancel()
        work {
            val draft = mutable.value.draft?.takeIf { it.id == id } ?: error("Der Entwurf wurde inzwischen gewechselt")
            // Publishing may fail independently of draft persistence; retain the latest words first.
            diary.setDraft(draft)
            val entry = draft.copy(updatedAt = System.currentTimeMillis().coerceAtLeast(draft.createdAt))
            diary.save(entry); mutable.update { it.copy(draft = null) }; setEditorOpen(false)
            refresh(); message("Eintrag gespeichert")
        }
    }
    fun addPhotos(uris: List<Uri>) {
        if (uris.isEmpty()) return
        draftJob?.cancel()
        work(operation = "Originalbilder übernehmen") {
            val current = mutable.value.draft ?: diary.draft() ?: error("Der Entwurf ist nicht mehr verfügbar")
            val photos = mutableListOf<Photo>()
            try {
                uris.forEachIndexed { index, uri ->
                    photos += diary.copyPhoto(uri)
                    mutable.update { it.copy(progress = ArchiveProgress(index + 1, uris.size)) }
                }
            } finally {
                val latest = mutable.value.draft?.takeIf { it.id == current.id } ?: current
                val updated = latest.copy(photos = latest.photos + photos)
                diary.setDraft(updated); mutable.update { it.copy(draft = updated) }; setEditorOpen(true)
            }
        }
    }
    fun deleteEntry(entry: Entry) = work {
        diary.delete(entry.id)
        if (mutable.value.draft?.id == entry.id) mutable.update { it.copy(draft = null) }
        refresh(); message("Eintrag gelöscht")
    }
    fun changePreferences(transform: (Preferences) -> Preferences) = work {
        diary.setPreferences(transform(diary.preferences())); refresh()
    }
    fun setReminder(period: ReminderPeriod) = changePreferences {
        it.copy(reminderPeriod = period, nextReminderAt = nextReminder(System.currentTimeMillis(), period))
    }
    fun dismissReminder() = changePreferences { it.copy(nextReminderAt = nextReminder(System.currentTimeMillis(), it.reminderPeriod)) }
    private fun exportName() = "Hibi-${LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)}-${UUID.randomUUID().toString().take(8)}.zip"
    private suspend fun progressReporter(): (ArchiveProgress) -> Unit {
        val context = currentCoroutineContext()
        return { value -> context.ensureActive(); mutable.update { it.copy(progress = value) } }
    }
    fun createExport(passwordText: String) {
        val password = passwordText.takeIf { it.isNotEmpty() }?.toCharArray()
        work(operation = "ZIP zum Teilen erstellen", cancellable = true) {
            try {
                exports.listFiles()?.forEach { it.delete() }
                val file = File(exports, exportName())
                backup.export(file, diary.entries(), diary.preferences(), diary::mediaFile, password, progress = progressReporter())
                mutable.update { it.copy(export = file) }
            } finally { password?.fill('\u0000') }
        }
    }
    fun requestSaveExport(passwordText: String): String {
        pendingSavePassword?.fill('\u0000'); pendingSavePassword = passwordText.toCharArray()
        return exportName()
    }
    fun saveExport(uri: Uri?) {
        val password = pendingSavePassword; pendingSavePassword = null
        if (uri == null) { password?.fill('\u0000'); return }
        if (password == null) { message("Export nach dem Neustart bitte erneut starten."); return }
        work(operation = "ZIP-Datei speichern", cancellable = true) {
            val resolver = getApplication<Application>().contentResolver
            try {
                resolver.openOutputStream(uri, "w")?.use { output ->
                    backup.export(output, diary.entries(), diary.preferences(), diary::mediaFile, password.takeIf { it.isNotEmpty() }, progress = progressReporter())
                } ?: error("Zieldatei kann nicht geschrieben werden")
                recordExport(); message("ZIP gespeichert. Die Ziel-App übernimmt einen möglichen Upload.")
            } catch (error: Exception) {
                runCatching { DocumentsContract.deleteDocument(resolver, uri) }; throw error
            } finally { password.fill('\u0000') }
        }
    }
    private fun recordExport() {
        val now = System.currentTimeMillis(); val preferences = diary.preferences()
        diary.setPreferences(preferences.copy(lastExportAt = now, nextReminderAt = nextReminder(now, preferences.reminderPeriod)))
        refresh()
    }
    fun sharedExport(file: File) = work {
        if (file.isFile) { recordExport(); mutable.update { it.copy(export = null) } }
    }
    fun closeExport() = work { mutable.value.export?.delete(); mutable.update { it.copy(export = null) } }
    fun prepareImport(uri: Uri, passwordText: String) {
        val password = passwordText.takeIf { it.isNotEmpty() }?.toCharArray()
        mutable.update { it.copy(importError = null) }
        work(operation = "Backup prüfen", cancellable = true) {
            val app = getApplication<Application>()
            val archive = File(app.cacheDir, "import-${UUID.randomUUID()}.zip")
            var prepared: PreparedImport? = null
            try {
                val report = progressReporter()
                app.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(archive).use { output ->
                        val buffer = ByteArray(64 * 1024); var count = 0L
                        while (true) {
                            val size = input.read(buffer); if (size < 0) break
                            count += size; require(count <= ZipBackup.MAX_TOTAL_BYTES) { "Archiv ist zu groß" }
                            output.write(buffer, 0, size); report(ArchiveProgress(0, 0))
                        }
                    }
                } ?: error("ZIP kann nicht geöffnet werden")
                prepared = backup.prepare(archive, File(app.cacheDir, "imports"), password, report)
                val warnings = diary.validateImport(prepared)
                val plan = planMerge(diary.entries(), prepared.entries)
                mutable.value.prepared?.close()
                mutable.update { it.copy(prepared = prepared, importPlan = plan, importWarnings = warnings) }
                prepared = null
            } catch (error: Exception) {
                mutable.update { it.copy(importError = if (error is CancellationException) "Prüfung abgebrochen" else error.message ?: "Prüfung fehlgeschlagen") }
                throw error
            } finally { archive.delete(); password?.fill('\u0000'); prepared?.close() }
        }
    }
    fun clearImportError() { mutable.update { it.copy(importError = null) } }
    fun cancelImport() = work {
        mutable.value.prepared?.close(); mutable.update { it.copy(prepared = null, importPlan = null, importWarnings = emptyList()) }
    }
    fun applyImport() {
        val prepared = mutable.value.prepared ?: return
        work(operation = "Einträge importieren") {
            val plan = diary.import(prepared)
            prepared.close(); mutable.update { it.copy(prepared = null, importPlan = null, importWarnings = emptyList()) }; refresh()
            message("${plan.additions.size} Einträge importiert · ${plan.identical} bereits vorhanden · ${plan.conflicts} lokale Fassungen behalten")
        }
    }
    fun cancelOperation() { if (mutable.value.cancellable) activeWork?.cancel() }
    fun message(text: String) { mutable.update { it.copy(message = text) } }
    fun clearMessage() { mutable.update { it.copy(message = null) } }
    private fun refresh() {
        val entries = diary.entries(); val preferences = diary.preferences()
        mutable.update { it.copy(entries = entries, preferences = preferences) }
    }
    private fun work(fatalOnError: Boolean = false, operation: String? = null, cancellable: Boolean = false, action: suspend () -> Unit) {
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { mutex.withLock {
                activeWork = currentCoroutineContext()[Job]
                mutable.update { it.copy(busy = true, operation = operation, progress = null, cancellable = cancellable) }
                try { action() }
                finally {
                    activeWork = null
                    mutable.update { it.copy(busy = false, operation = null, progress = null, cancellable = false) }
                }
            } } }
            catch (error: CancellationException) { if (operation != null) message("Vorgang abgebrochen"); throw error }
            catch (error: Exception) { mutable.update { it.copy(message = error.message ?: "Aktion fehlgeschlagen", fatal = it.fatal || fatalOnError) } }
        }
    }
    override fun onCleared() {
        // Active IO owns its staging directory. Abandoned previews are reclaimed on next startup.
        pendingSavePassword?.fill('\u0000')
        super.onCleared()
    }
}
