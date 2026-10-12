@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package org.lvl3.hibi.ui

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.delay
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.awaitCancellation
import org.lvl3.hibi.model.*
import org.lvl3.hibi.ui.theme.HibiDesign
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val fullDateFormat = DateTimeFormatter.ofPattern("EEEE, d. MMMM yyyy", Locale.GERMAN)
private val shortDateFormat = DateTimeFormatter.ofPattern("d. MMMM", Locale.GERMAN)
private val exportDateFormat = DateTimeFormatter.ofPattern("d. MMM yyyy, HH:mm", Locale.GERMAN)
fun prettyDate(day: String): String = LocalDate.parse(day).format(fullDateFormat)
fun feedDate(day: String): String {
    val date = LocalDate.parse(day); val today = LocalDate.now()
    val relative = when (date) { today -> "Heute · "; today.minusDays(1) -> "Gestern · "; else -> "" }
    return relative + date.format(shortDateFormat) + if (date.year != today.year) " ${date.year}" else ""
}
fun exportDate(time: Long?): String = time?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(exportDateFormat) } ?: "Noch kein Export"

@Composable
fun HibiApp(vm: DiaryViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val now by produceState(System.currentTimeMillis(), lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            value = System.currentTimeMillis()
            awaitCancellation()
        }
    }
    val snackbar = remember { SnackbarHostState() }
    var settings by rememberSaveable { mutableStateOf(false) }
    var calendar by rememberSaveable { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf(false) }
    var filterDay by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    var exportDialog by rememberSaveable { mutableStateOf(false) }
    var importUri by rememberSaveable { mutableStateOf<String?>(null) }
    val feedScroll = rememberLazyListState()
    var scrollSignature by rememberSaveable { mutableStateOf<String?>(null) }
    var showOperation by remember { mutableStateOf(false) }
    val images = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50), vm::addPhotos)
    val openZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importUri = uri.toString()
            vm.clearImportError()
        }
    }
    val saveZip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip"), vm::saveExport)
    LaunchedEffect(state.operation) { showOperation = false; if (state.operation != null) { delay(300); showOperation = true } }
    LaunchedEffect(state.prepared) { if (state.prepared != null) importUri = null }
    val display = remember(state.entries, state.preferences.newestFirst, filterDay, query) {
        state.entries.filter { (filterDay == null || it.day == filterDay) && (query.isBlank() || it.markdown.contains(query, true) || it.day.contains(query, true) || prettyDate(it.day).contains(query, true)) }.let { if (state.preferences.newestFirst) it.reversed() else it }
    }
    val signature = "${state.preferences.newestFirst}|$filterDay|$query|${display.size}|${display.firstOrNull()?.id}|${display.lastOrNull()?.id}"
    LaunchedEffect(signature, state.loaded) {
        if (state.loaded && scrollSignature != signature) {
            if (display.isNotEmpty()) feedScroll.scrollToItem(if (state.preferences.newestFirst) 0 else display.lastIndex)
            scrollSignature = signature
        }
    }
    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); vm.clearMessage() }
    }
    BackHandler(enabled = settings && !state.editorOpen) { settings = false }

    Scaffold(containerColor = MaterialTheme.colorScheme.background, snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (state.loaded && !state.editorOpen) Column(Modifier.statusBarsPadding().padding(horizontal = HibiDesign.pagePadding, vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (settings) IconButton(onClick = { settings = false }) { Icon(Icons.Outlined.ArrowBack, "Zum Tagebuch") }
                    Text(if (settings) "Einstellungen" else "Hibi", Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
                }
            }
        },
        bottomBar = {
            if (state.loaded && state.preferences.initialized && !state.editorOpen && !state.fatal) {
                Column {
                    HorizontalDivider()
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        NavigationBarItem(selected = calendar, enabled = !state.busy, onClick = { calendar = true; settings = false }, icon = { Icon(Icons.Outlined.DateRange, null) }, label = { Text("Kalender") })
                        NavigationBarItem(selected = search, enabled = !state.busy, onClick = { search = true; settings = false }, icon = { Icon(Icons.Outlined.Search, null) }, label = { Text("Suche") })
                        NavigationBarItem(selected = false, enabled = !state.busy, onClick = { vm.newEntry(filterDay) }, icon = { Icon(Icons.Outlined.AddCircle, null, tint = MaterialTheme.colorScheme.primary) }, label = { Text("Eintrag") })
                        NavigationBarItem(selected = settings, enabled = !state.busy, onClick = { settings = !settings }, icon = { Icon(Icons.Outlined.Settings, null) }, label = { Text("Optionen") })
                    }
                }
            }
        }) { padding ->
        when {
            state.fatal -> Column(Modifier.padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Tagebuch konnte nicht geladen werden", style = MaterialTheme.typography.titleLarge)
                Text("Deine lokalen Daten werden nicht ersetzt. Bitte versuche es erneut.")
                Button(onClick = { vm.load() }) { Text("Erneut versuchen") }
            }
            !state.loaded -> Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.editorOpen && state.draft != null -> EditorScreen(state.draft!!, vm, Modifier.padding(padding).consumeWindowInsets(padding)) { images.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
            !state.preferences.initialized -> WelcomeScreen(Modifier.padding(padding), vm::startDiary, { openZip.launch(arrayOf("*/*")) })
            settings -> SettingsScreen(state, vm, Modifier.padding(padding), { exportDialog = true }, { openZip.launch(arrayOf("*/*")) })
            else -> {
                Feed(display, state, vm, filterDay, query, { filterDay = null; query = "" }, { detailId = it.id }, { exportDialog = true }, Modifier.padding(padding), now, feedScroll)
            }
        }
    }
    if (calendar) CalendarDialog(state.entries, filterDay, { calendar = false }) { filterDay = it; calendar = false; settings = false }
    if (search) SearchDialog(query, { search = false }) { query = it; search = false; settings = false }
    state.entries.firstOrNull { it.id == detailId }?.let { entry ->
        DetailDialog(entry, vm, { detailId = null }, { detailId = null; vm.openEditor(entry) })
    }
    if (exportDialog) ExportPasswordDialog({ exportDialog = false }, create = { password -> exportDialog = false; vm.createExport(password) }, save = { password -> exportDialog = false; saveZip.launch(vm.requestSaveExport(password)) })
    importUri?.let { value -> ImportPasswordDialog({ if (!state.busy) { importUri = null; vm.clearImportError() } }, state.busy, state.importError) { password -> vm.prepareImport(Uri.parse(value), password) } }
    state.export?.let { file ->
        ExportReadyDialog(file, vm::closeExport, share = {
            runCatching {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"; putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newRawUri("Hibi-Backup", uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, "Hibi-Backup teilen"))
                vm.sharedExport(file)
            }.onFailure { vm.message("Keine passende App zum Teilen gefunden") }
        })
    }
    state.requestedEntry?.let { requested ->
        val draft = state.draft
        val editing = state.entries.any { it.id == draft?.id }
        val targetEditing = state.entries.any { it.id == requested.id }
        AlertDialog(onDismissRequest = vm::cancelEditorRequest, title = { Text("Ein Entwurf ist vorhanden") }, text = {
            Text("${if (editing) "Bearbeitung" else "Entwurf"} vom ${draft?.day?.let(::prettyDate).orEmpty()}. Möchtest du ihn fortsetzen oder verwerfen?")
        }, confirmButton = { TextButton(onClick = vm::resumeDraft) { Text("Entwurf fortsetzen") } }, dismissButton = {
            TextButton(onClick = vm::replaceDraftAndOpen) { Text(if (targetEditing) "Verwerfen und bearbeiten" else "Verwerfen und neu") }
        })
    }
    if (state.prepared != null && state.importPlan != null) ImportPreviewDialog(state.prepared!!, state.importPlan!!, state.importWarnings, vm::cancelImport, vm::applyImport)
    if (showOperation && state.operation != null) Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
        Surface(shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(state.operation!!, style = MaterialTheme.typography.titleMedium)
                val progress = state.progress
                if (progress != null && progress.totalFiles > 0) {
                    LinearProgressIndicator(progress = { progress.completedFiles.toFloat() / progress.totalFiles }, modifier = Modifier.fillMaxWidth())
                    Text("${progress.completedFiles} von ${progress.totalFiles} Dateien", style = MaterialTheme.typography.bodyMedium)
                } else CircularProgressIndicator(Modifier.size(30.dp))
                if (state.cancellable) TextButton(onClick = vm::cancelOperation) { Text("Abbrechen") }
            }
        }
    }
}

@Composable
private fun WelcomeScreen(modifier: Modifier, start: () -> Unit, restore: () -> Unit) {
    Column(modifier.fillMaxSize().padding(30.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Outlined.FavoriteBorder, null, Modifier.size(68.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(24.dp))
        Text("Ein Ort für deine Tage", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        Text("Bilder, Worte und kleine Momente. Dein Tagebuch bleibt bei dir.", textAlign = TextAlign.Center)
        Spacer(Modifier.height(28.dp))
        Button(onClick = start, modifier = Modifier.fillMaxWidth()) { Text("Neues Tagebuch anlegen") }
        OutlinedButton(onClick = restore, modifier = Modifier.fillMaxWidth()) { Text("ZIP-Backup importieren") }
        Spacer(Modifier.height(16.dp))
        Text("Sichere später eine ZIP über deine bevorzugte App. Keine Anmeldung erforderlich.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Feed(entries: List<Entry>, state: UiState, vm: DiaryViewModel, day: String?, query: String, clear: () -> Unit, open: (Entry) -> Unit, export: () -> Unit, modifier: Modifier, now: Long, scroll: LazyListState) {
    Column(modifier.fillMaxSize()) {
        if (reminderDue(state.preferences, now) && state.entries.isNotEmpty()) {
            Card(Modifier.padding(horizontal = HibiDesign.pagePadding, vertical = 8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Zeit für ein Backup", style = MaterialTheme.typography.titleMedium)
                        Text("Letzter Export: ${exportDate(state.preferences.lastExportAt)}", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = export, contentPadding = PaddingValues(0.dp)) { Text("ZIP erstellen und extern sichern") }
                    }
                    IconButton(onClick = vm::dismissReminder) { Icon(Icons.Outlined.Close, "Erinnerung bis zum nächsten Termin schließen") }
                }
            }
        }
        state.draft?.let { draft ->
            TextButton(onClick = vm::resumeDraft, modifier = Modifier.padding(horizontal = HibiDesign.pagePadding)) {
                Text("${if (state.entries.any { it.id == draft.id }) "Bearbeitung" else "Entwurf"} vom ${draft.day} fortsetzen")
            }
        }
        if (day != null || query.isNotEmpty()) {
            Row(Modifier.padding(horizontal = HibiDesign.pagePadding).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(listOfNotNull(day?.let(::prettyDate), query.takeIf { it.isNotEmpty() }?.let { "„$it“" }).joinToString(" · "), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                IconButton(onClick = clear) { Icon(Icons.Outlined.Close, "Filter aufheben") }
            }
        }
        if (entries.isEmpty()) Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.Star, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp))
            Text(if (query.isNotEmpty()) "Keine passenden Einträge" else if (day != null) "Dieser Tag ist noch frei" else "Dein erster Moment", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(10.dp))
            Text(if (query.isNotEmpty()) "Versuche andere Worte oder ein Datum." else "Ein Bild, ein Gedanke – oder beides.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (query.isEmpty()) Button(onClick = { vm.newEntry(day) }, modifier = Modifier.padding(top = 18.dp)) { Text("Eintrag erstellen") }
        } else LazyColumn(state = scroll, contentPadding = PaddingValues(top = 10.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            items(entries, key = { it.id }) { entry ->
                Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).clickable(role = Role.Button) { open(entry) }) {
                    PhotoCarousel(entry.photos, vm::mediaFile, state.preferences.pictureFormat) { open(entry) }
                    Column(Modifier.padding(horizontal = HibiDesign.pagePadding, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(feedDate(entry.day), style = MaterialTheme.typography.titleMedium)
                        if (entry.markdown.isNotBlank()) MarkdownText(entry.markdown, Modifier.fillMaxWidth(), preview = true)
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarDialog(entries: List<Entry>, selected: String?, close: () -> Unit, choose: (String) -> Unit) {
    var monthString by rememberSaveable { mutableStateOf(YearMonth.from(selected?.let(LocalDate::parse) ?: LocalDate.now()).toString()) }
    val month = YearMonth.parse(monthString)
    val marked = remember(entries) { entries.map { it.day }.toSet() }
    val start = month.atDay(1).dayOfWeek.value - 1
    Dialog(close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.padding(18.dp).widthIn(max = 480.dp).fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Deine Tage", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = close) { Icon(Icons.Outlined.Close, "Kalender schließen") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { monthString = month.minusMonths(1).toString() }, enabled = month.year > 1 || month.monthValue > 1) { Icon(Icons.Outlined.KeyboardArrowLeft, "Vorheriger Monat") }
                    Text(month.format(DateTimeFormatter.ofPattern("LLLL yyyy", Locale.GERMAN)).replaceFirstChar { it.titlecase(Locale.GERMAN) }, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = { monthString = month.plusMonths(1).toString() }, enabled = month.year < 9999 || month.monthValue < 12) { Icon(Icons.Outlined.KeyboardArrowRight, "Nächster Monat") }
                }
                Row { listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So").forEach { Box(Modifier.weight(1f).height(34.dp), contentAlignment = Alignment.Center) { Text(it, style = MaterialTheme.typography.labelMedium) } } }
                repeat((start + month.lengthOfMonth() + 6) / 7) { row ->
                    Row {
                        repeat(7) { column ->
                            val day = row * 7 + column - start + 1
                            if (day !in 1..month.lengthOfMonth()) Spacer(Modifier.weight(1f).height(48.dp))
                            else {
                                val date = month.atDay(day).toString()
                                val current = date == selected || (selected == null && date == LocalDate.now().toString())
                                Box(Modifier.weight(1f).height(48.dp)
                                    .semantics { contentDescription = "${prettyDate(date)}${if (date in marked) ", mit Eintrag" else ", ohne Eintrag"}" }
                                    .clickable(role = Role.Button) { choose(date) }, contentAlignment = Alignment.Center) {
                                    Column(Modifier.size(40.dp).background(if (current) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface, CircleShape), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                    Text(day.toString(), style = MaterialTheme.typography.bodyMedium)
                                     Box(Modifier.padding(top = 3.dp).size(6.dp).background(if (date in marked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface, CircleShape))
                                    }
                                }
                            }
                        }
                    }
                }
                Text("● Tage mit Einträgen", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { choose(LocalDate.now().toString()) }) { Text("Heute") }
            }
        }
    }
}

@Composable
private fun SearchDialog(initial: String, close: () -> Unit, apply: (String) -> Unit) {
    var value by rememberSaveable { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(onDismissRequest = close, title = { Text("Einträge finden") }, text = {
        OutlinedTextField(value, { value = it }, label = { Text("Text oder Datum") }, singleLine = true, modifier = Modifier.fillMaxWidth().focusRequester(focus),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { apply(value.trim()) }))
    }, confirmButton = { TextButton(onClick = { apply(value.trim()) }) { Text("Suchen") } }, dismissButton = { TextButton(onClick = close) { Text("Abbrechen") } })
}
