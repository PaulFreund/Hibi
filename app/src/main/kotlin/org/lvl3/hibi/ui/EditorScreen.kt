@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package org.lvl3.hibi.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import org.lvl3.hibi.model.Entry
import org.lvl3.hibi.model.PictureFormat
import org.lvl3.hibi.ui.theme.HibiDesign
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@Composable
fun EditorScreen(draft: Entry, vm: DiaryViewModel, modifier: Modifier) {
    val controller = remember(draft.id) { EditorController() }
    var datePicker by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    val images = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50), vm::addPhotos)
    fun close() { controller.read { text -> vm.updateDraft { it.copy(markdown = text) }; vm.closeEditor() } }
    BackHandler { close() }
    Column(modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { close() }) { Icon(Icons.Outlined.ArrowBack, "Entwurf schließen") }
            Text("Dein Moment", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Entwurf-Optionen") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Entwurf verwerfen") }, onClick = { menu = false; discard = true })
                }
            }
            Button(onClick = { controller.read(vm::saveDraft) }, enabled = draft.markdown.isNotBlank() || draft.photos.isNotEmpty()) { Text("Speichern") }
        }
        HorizontalDivider()
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(HibiDesign.pagePadding), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Datum", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { datePicker = true }, contentPadding = PaddingValues(0.dp)) { Text(prettyDate(draft.day)) }
                }
                IconButton(onClick = { datePicker = true }) { Icon(Icons.Outlined.DateRange, "Datum wählen") }
            }
            if (draft.photos.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                itemsIndexed(draft.photos, key = { _, photo -> photo.id }) { index, photo ->
                    var photoMenu by remember { mutableStateOf(false) }
                    Column(Modifier.width(112.dp)) {
                        PhotoImage(vm.mediaFile(photo), photo, Modifier.size(112.dp), description = "Foto ${index + 1}")
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("${index + 1}", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
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
            }
            OutlinedButton(onClick = { images.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text("Fotos hinzufügen")
            }
            Text("Deine Worte", style = MaterialTheme.typography.titleMedium)
            Surface(shape = MaterialTheme.shapes.medium) {
                MarkdownEditor(draft.id, draft.markdown, controller, Modifier.fillMaxWidth().height(440.dp)) { text -> vm.updateDraft { it.copy(markdown = text) } }
            }
            Text("Entwürfe werden lokal gespeichert. Mit Speichern erscheint dein Eintrag im Tagebuch.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
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
                    Text("Erinnerung", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
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
