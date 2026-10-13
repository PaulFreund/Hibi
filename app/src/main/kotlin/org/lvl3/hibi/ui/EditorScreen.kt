@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package org.lvl3.hibi.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.lvl3.hibi.model.Entry
import org.lvl3.hibi.model.PictureFormat
import org.lvl3.hibi.ui.theme.HibiDesign
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@Composable
fun EditorScreen(draft: Entry, vm: DiaryViewModel, modifier: Modifier, pickPhotos: () -> Unit) {
    val state by vm.state.collectAsState()
    val editing = state.entries.any { it.id == draft.id }
    val controller = remember(draft.id) { EditorController().apply { latest = draft.markdown } }
    var reading by remember(draft.id) { mutableStateOf(false) }
    var datePicker by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(controller, lifecycle, draft.id) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                val base = vm.state.value.draft?.takeIf { it.id == draft.id }?.markdown ?: return@LifecycleEventObserver
                controller.read { result -> vm.persistEditorSnapshot(draft.id, base, result.markdown) }
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    fun read(action: (String) -> Unit) {
        if (reading || state.busy) return
        reading = true
        controller.read { result ->
            reading = false
            if (vm.state.value.draft?.id == draft.id) {
                if (result.confirmed) action(result.markdown)
                else { vm.flushDraft(draft.id); vm.message("Der aktuelle Text ist noch nicht bestätigt. Bitte wiederholen.") }
            }
        }
    }
    fun close() { read { text -> vm.updateDraft { it.copy(markdown = text) }; vm.closeEditor() } }
    BackHandler { if (!state.busy) close() }
    Column(modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { close() }, enabled = !state.busy && !reading) { Icon(Icons.Outlined.ArrowBack, "Entwurf schließen") }
            Text(if (editing) "Eintrag bearbeiten" else "Neuer Eintrag", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Entwurf-Optionen") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Entwurf verwerfen") }, onClick = { menu = false; discard = true })
                }
            }
            Button(onClick = { read(vm::saveDraft) }, enabled = controller.ready && !reading && !state.busy && (draft.markdown.isNotBlank() || draft.photos.isNotEmpty())) { Text("Speichern") }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(horizontal = HibiDesign.pagePadding), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.DateRange, null, Modifier.size(18.dp))
            TextButton(onClick = { datePicker = true }, enabled = !state.busy) { Text(prettyDate(draft.day), style = MaterialTheme.typography.bodyMedium) }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = HibiDesign.pagePadding, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(draft.photos, key = { _, photo -> photo.id }) { index, photo ->
                    var photoMenu by remember { mutableStateOf(false) }
                    Column(Modifier.width(80.dp)) {
                        PhotoImage(vm.mediaFile(photo), photo, Modifier.size(80.dp), description = "Foto ${index + 1}${if (index == 0) ", Titelbild" else ""}")
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (index == 0) "Titelbild" else "${index + 1}", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                            Box {
                                IconButton(onClick = { photoMenu = true }) { Icon(Icons.Outlined.MoreVert, "Foto ${index + 1} bearbeiten") }
                                DropdownMenu(photoMenu, { photoMenu = false }) {
                                    fun move(delta: Int) {
                                        vm.updateDraft { entry ->
                                            val ordered = entry.photos.toMutableList()
                                            java.util.Collections.swap(ordered, index, index + delta)
                                            entry.copy(photos = ordered)
                                        }; photoMenu = false
                                    }
                                    DropdownMenuItem(text = { Text("Nach links") }, enabled = index > 0, onClick = { move(-1) })
                                    DropdownMenuItem(text = { Text("Nach rechts") }, enabled = index < draft.photos.lastIndex, onClick = { move(1) })
                                    DropdownMenuItem(text = { Text("Bild entfernen") }, onClick = { photoMenu = false; vm.updateDraft { it.copy(photos = it.photos.filterNot { p -> p.id == photo.id }) } })
                                }
                            }
                        }
                    }
                }
            item(key = "add-photo") {
                OutlinedButton(onClick = { read { text -> vm.updateDraftText(draft.id, text); vm.flushDraft(draft.id, pickPhotos) } }, enabled = !state.busy && !reading, modifier = Modifier.height(if (draft.photos.isEmpty()) 44.dp else 80.dp)) {
                    Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(6.dp)); Text("Fotos hinzufügen")
                }
            }
        }
        if (!controller.ready && controller.failure == null) Text("Schreibbereich wird geladen · Entwurf bleibt erhalten", Modifier.padding(horizontal = HibiDesign.pagePadding), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        controller.readIssue?.let { Text(it, Modifier.padding(horizontal = HibiDesign.pagePadding), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        MarkdownEditor(draft.id, draft.markdown, controller, Modifier.fillMaxWidth().weight(1f), checkpoint = { base, text -> vm.persistEditorSnapshot(draft.id, base, text) }) { text -> vm.updateDraftText(draft.id, text) }
    }
    if (datePicker) {
        val picker = rememberDatePickerState(initialSelectedDateMillis = LocalDate.parse(draft.day).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), yearRange = 1..9999)
        DatePickerDialog(onDismissRequest = { datePicker = false }, confirmButton = {
            TextButton(onClick = {
                picker.selectedDateMillis?.let { time -> vm.updateDraft { it.copy(day = Instant.ofEpochMilli(time).atZone(ZoneOffset.UTC).toLocalDate().toString()) } }
                datePicker = false
            }) { Text("Übernehmen") }
        }, dismissButton = { TextButton(onClick = { datePicker = false }) { Text("Abbrechen") } }) { DatePicker(picker) }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("Entwurf verwerfen?") }, text = { Text("Ungespeicherte Änderungen werden entfernt. Ein bereits veröffentlichter Eintrag bleibt erhalten.") },
        confirmButton = { TextButton(onClick = { discard = false; vm.discardDraft() }) { Text("Verwerfen") } }, dismissButton = { TextButton(onClick = { discard = false }) { Text("Behalten") } })
}

@Composable
fun DetailDialog(entry: Entry, vm: DiaryViewModel, close: () -> Unit, edit: () -> Unit) {
    var delete by remember { mutableStateOf(false) }
    Dialog(close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = close) { Icon(Icons.Outlined.Close, "Eintrag schließen") }
                    Text("Eintrag", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = edit) { Icon(Icons.Outlined.Edit, "Eintrag bearbeiten") }
                    IconButton(onClick = { delete = true }) { Icon(Icons.Outlined.Delete, "Eintrag löschen") }
                }
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    PhotoCarousel(entry.photos, vm::mediaFile, PictureFormat.ORIGINAL)
                    Column(Modifier.padding(horizontal = HibiDesign.pagePadding, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(prettyDate(entry.day), style = MaterialTheme.typography.titleLarge)
                        if (entry.markdown.isNotBlank()) MarkdownText(entry.markdown, Modifier.fillMaxWidth())
                        Spacer(Modifier.height(18.dp))
                    }
                }
            }
        }
    }
    if (delete) AlertDialog(onDismissRequest = { delete = false }, title = { Text("Eintrag löschen?") }, text = { Text("Dieser Eintrag und seine lokal gespeicherten Bilder werden entfernt. Bereits erstellte ZIP-Dateien bleiben erhalten.") },
        confirmButton = { TextButton(onClick = { delete = false; vm.deleteEntry(entry); close() }) { Text("Löschen") } }, dismissButton = { TextButton(onClick = { delete = false }) { Text("Behalten") } })
}
