package org.lvl3.hibi.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.lvl3.hibi.HibiApplication
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
    val draft: Entry? = null, val editorOpen: Boolean = false,
    val busy: Boolean = true, val message: String? = null, val fatal: Boolean = false,
    val export: File? = null, val prepared: PreparedImport? = null,
    val importPlan: MergePlan? = null,
)

class DiaryViewModel(application: Application) : AndroidViewModel(application) {
    private val diary = (application as HibiApplication).diary
    fun mediaFile(photo: Photo): File = diary.mediaFile(photo)
    private val mutable = MutableStateFlow(UiState())
    val state = mutable.asStateFlow()
    private val mutex = Mutex()
    private val backup = ZipBackup()
    private var draftJob: Job? = null
    private val exports = File(application.filesDir, "exports").apply { mkdirs() }

    init { load() }

    fun load() = work(fatalOnError = true) {
        val entries = diary.entries(); val preferences = diary.preferences(); val draft = diary.draft()
        mutable.update { it.copy(entries = entries, preferences = preferences, draft = draft, fatal = false) }
    }

    fun startDiary() = work { diary.setPreferences(diary.preferences().copy(initialized = true)); refresh() }

    fun openEditor(entry: Entry? = null) {
        val current = mutable.value.draft
        if (entry != null && current != null && current.id != entry.id) {
            message("Es gibt einen Entwurf. Öffne ihn über + oder verwerfe ihn dort."); return
        }
        val draft = current ?: entry ?: Entry()
        mutable.update { it.copy(draft = draft, editorOpen = true) }
        persistDraft(draft)
    }

    fun updateDraft(transform: (Entry) -> Entry) {
        val draft = mutable.value.draft?.let(transform) ?: return
        mutable.update { it.copy(draft = draft) }
        persistDraft(draft)
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
        val draft = mutable.value.draft ?: return
        draftJob?.cancel()
        work { diary.setDraft(draft); mutable.update { it.copy(editorOpen = false) } }
    }

    fun discardDraft() {
        draftJob?.cancel()
        work { diary.discardDraft(); mutable.update { it.copy(draft = null, editorOpen = false) } }
    }

    fun saveDraft(markdown: String) {
        val draft = mutable.value.draft ?: return
        draftJob?.cancel()
        val entry = draft.copy(markdown = markdown.trim(), updatedAt = System.currentTimeMillis().coerceAtLeast(draft.createdAt))
        work {
            diary.save(entry)
            mutable.update { it.copy(draft = null, editorOpen = false) }
            refresh(); message("Eintrag gespeichert")
        }
    }

    fun addPhotos(uris: List<Uri>) {
        if (uris.isEmpty()) return
        draftJob?.cancel()
        work {
            // Activity results can arrive before a restored ViewModel has finished loading.
            val current = mutable.value.draft ?: diary.draft() ?: error("Der Entwurf ist nicht mehr verfügbar")
            val photos = mutableListOf<Photo>()
            try {
                uris.forEach { photos += diary.copyPhoto(it) }
                val latest = mutable.value.draft?.takeIf { it.id == current.id } ?: current
                val updated = latest.copy(photos = latest.photos + photos)
                diary.setDraft(updated)
                mutable.update { it.copy(draft = updated, editorOpen = true) }
            } catch (error: Exception) {
                // Keep successfully selected originals in the recoverable draft even if a later image failed.
                val latest = mutable.value.draft?.takeIf { it.id == current.id } ?: current
                val updated = latest.copy(photos = latest.photos + photos)
                diary.setDraft(updated); mutable.update { it.copy(draft = updated, editorOpen = true) }; throw error
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

    fun createExport(passwordText: String) {
        val password = passwordText.takeIf { it.isNotEmpty() }?.toCharArray()
        work {
            try {
                val now = System.currentTimeMillis()
                val name = "Hibi-${LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)}-${UUID.randomUUID().toString().take(8)}.zip"
                val file = File(exports, name)
                val preferences = diary.preferences()
                backup.export(file, diary.entries(), preferences, diary::mediaFile, password, now)
                diary.setPreferences(preferences.copy(lastExportAt = now, nextReminderAt = nextReminder(now, preferences.reminderPeriod)))
                mutable.update { it.copy(export = file) }; refresh()
                // Retain at least three archives, and never expire a recently shared URI.
                exports.listFiles()?.filter { it.extension == "zip" }?.sortedByDescending { it.lastModified() }?.drop(3)
                    ?.filter { now - it.lastModified() > 7L * 24 * 60 * 60 * 1000 }?.forEach { it.delete() }
            } finally { password?.fill('\u0000') }
        }
    }
    fun closeExport() { mutable.update { it.copy(export = null) } }

    fun saveExport(uri: Uri, file: File) = work {
        getApplication<Application>().contentResolver.openOutputStream(uri, "w")?.use { output ->
            file.inputStream().use { it.copyTo(output) }
        } ?: error("Zieldatei kann nicht geschrieben werden")
        message("ZIP gespeichert. Die Ziel-App übernimmt einen möglichen Upload.")
    }

    fun prepareImport(uri: Uri, passwordText: String) {
        val password = passwordText.takeIf { it.isNotEmpty() }?.toCharArray()
        work {
            val app = getApplication<Application>()
            val archive = File(app.cacheDir, "import-${UUID.randomUUID()}.zip")
            var prepared: PreparedImport? = null
            try {
                app.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(archive).use { output ->
                        val buffer = ByteArray(64 * 1024); var count = 0L
                        while (true) {
                            val size = input.read(buffer); if (size < 0) break
                            count += size; require(count <= ZipBackup.MAX_TOTAL_BYTES) { "Archiv ist zu groß" }
                            output.write(buffer, 0, size)
                        }
                    }
                } ?: error("ZIP kann nicht geöffnet werden")
                prepared = backup.prepare(archive, File(app.cacheDir, "imports"), password)
                diary.validateImport(prepared)
                val plan = planMerge(diary.entries(), prepared.entries)
                mutable.value.prepared?.close()
                mutable.update { it.copy(prepared = prepared, importPlan = plan) }
                prepared = null // Ownership transferred to the preview.
            } finally { archive.delete(); password?.fill('\u0000'); prepared?.close() }
        }
    }

    fun cancelImport() = work { mutable.value.prepared?.close(); mutable.update { it.copy(prepared = null, importPlan = null) } }
    fun applyImport() {
        val prepared = mutable.value.prepared ?: return
        work {
            val plan = diary.import(prepared)
            prepared.close(); mutable.update { it.copy(prepared = null, importPlan = null) }; refresh()
            message("${plan.additions.size} Einträge importiert · ${plan.identical} bereits vorhanden · ${plan.conflicts} lokale Fassungen behalten")
        }
    }

    fun message(text: String) { mutable.update { it.copy(message = text) } }
    fun clearMessage() { mutable.update { it.copy(message = null) } }
    private fun refresh() { val entries = diary.entries(); val preferences = diary.preferences(); mutable.update { it.copy(entries = entries, preferences = preferences) } }

    private fun work(fatalOnError: Boolean = false, action: suspend () -> Unit) {
        mutable.update { it.copy(busy = true) }
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { mutex.withLock { action() } } }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                val detail = error.message ?: "Aktion fehlgeschlagen"
                mutable.update { it.copy(message = detail, fatal = it.fatal || fatalOnError) }
            } finally { mutable.update { it.copy(busy = false) } }
        }
    }
}
