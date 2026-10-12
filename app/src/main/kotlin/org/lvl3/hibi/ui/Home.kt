@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package org.lvl3.hibi.ui

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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

fun prettyDate(day: String): String = LocalDate.parse(day).format(DateTimeFormatter.ofPattern("EEEE, d. MMMM yyyy", Locale.GERMAN))
fun exportDate(time: Long?): String = time?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d. MMM yyyy, HH:mm", Locale.GERMAN)) } ?: "Noch kein Export"

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
    var saveExportPath by rememberSaveable { mutableStateOf<String?>(null) }
    val openZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            importUri = uri.toString()
        }
    }
    val saveZip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val file = saveExportPath?.let(::File)
        if (uri != null && file?.isFile == true) vm.saveExport(uri, file)
        saveExportPath = null
    }
    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); vm.clearMessage() }
    }
    BackHandler(enabled = settings && !state.editorOpen) { settings = false }

    Scaffold(containerColor = MaterialTheme.colorScheme.background, snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (!state.editorOpen) Column(Modifier.statusBarsPadding().padding(horizontal = HibiDesign.pagePadding, vertical = 10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (settings) IconButton(onClick = { settings = false }) { Icon(Icons.Outlined.ArrowBack, "Zum Tagebuch") }
                    Text(if (settings) "Einstellungen" else "Hibi", Modifier.weight(1f).clickable { settings = false; filterDay = null; query = "" }, style = MaterialTheme.typography.headlineLarge)
                    if (!settings) Icon(Icons.Outlined.FavoriteBorder, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
                }
                if (!settings) Text("Deine Tage, in Bildern und Worten.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        bottomBar = {
            if (state.preferences.initialized && !state.editorOpen && !state.fatal) {
                Column {
                    HorizontalDivider()
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        NavigationBarItem(selected = calendar, onClick = { calendar = true; settings = false }, icon = { Icon(Icons.Outlined.DateRange, "Kalender öffnen") }, label = { Text("Kalender") })
                        NavigationBarItem(selected = search, onClick = { search = true; settings = false }, icon = { Icon(Icons.Outlined.Search, "Suche öffnen") }, label = { Text("Suche") })
                        NavigationBarItem(selected = false, onClick = { vm.openEditor(filterDay?.let { Entry(day = it) }) }, icon = { Icon(Icons.Outlined.AddCircle, "Eintrag erstellen", tint = MaterialTheme.colorScheme.primary) }, label = { Text("Eintrag") })
                        NavigationBarItem(selected = settings, onClick = { settings = !settings }, icon = { Icon(Icons.Outlined.Settings, "Einstellungen öffnen") }, label = { Text("Optionen") })
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
            state.editorOpen && state.draft != null -> EditorScreen(state.draft!!, vm, Modifier.padding(padding))
            !state.preferences.initialized -> WelcomeScreen(Modifier.padding(padding), vm::startDiary, { openZip.launch(arrayOf("*/*")) })
            settings -> SettingsScreen(state, vm, Modifier.padding(padding), { exportDialog = true }, { openZip.launch(arrayOf("*/*")) })
            else -> {
                val display = state.entries.filter { (filterDay == null || it.day == filterDay) && (query.isBlank() || it.markdown.contains(query, true) || it.day.contains(query, true) || prettyDate(it.day).contains(query, true)) }.let { if (state.preferences.newestFirst) it.reversed() else it }
                Feed(display, state, vm, filterDay, query, { filterDay = null; query = "" }, { detailId = it.id }, { exportDialog = true }, Modifier.padding(padding), now)
            }
        }
    }
    if (calendar) CalendarDialog(state.entries, filterDay, { calendar = false }) { filterDay = it; calendar = false; settings = false }
    if (search) SearchDialog(query, { search = false }) { query = it; search = false; settings = false }
    state.entries.firstOrNull { it.id == detailId }?.let { entry ->
        DetailDialog(entry, vm, { detailId = null }, { detailId = null; vm.openEditor(entry) })
    }
    if (exportDialog) ExportPasswordDialog({ exportDialog = false }) { password -> exportDialog = false; vm.createExport(password) }
    importUri?.let { value -> ImportPasswordDialog({ importUri = null }) { password -> importUri = null; vm.prepareImport(Uri.parse(value), password) } }
    state.export?.let { file ->
        ExportReadyDialog(file, vm::closeExport, share = {
            runCatching {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"; putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newRawUri("Hibi-Backup", uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, "Hibi-Backup teilen"))
            }.onFailure { vm.message("Keine passende App zum Teilen gefunden") }
        }, save = { saveExportPath = file.absolutePath; saveZip.launch(file.name) })
    }
    if (state.prepared != null && state.importPlan != null) ImportPreviewDialog(state.prepared!!, state.importPlan!!, vm::cancelImport, vm::applyImport)
    if (state.busy) Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
        Surface(shape = RoundedCornerShape(24.dp)) {
            Row(Modifier.padding(24.dp), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(30.dp)); Text("Einen Moment …") }
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
        Text("Bilder, Worte und kleine Momente. Dein Tagebuch bleibt bei dir.")
        Spacer(Modifier.height(28.dp))
        Button(onClick = start, modifier = Modifier.fillMaxWidth()) { Text("Neues Tagebuch anlegen") }
        OutlinedButton(onClick = restore, modifier = Modifier.fillMaxWidth()) { Text("ZIP-Backup importieren") }
        Spacer(Modifier.height(16.dp))
        Text("Sichere später eine ZIP über deine bevorzugte App. Keine Anmeldung erforderlich.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Feed(entries: List<Entry>, state: UiState, vm: DiaryViewModel, day: String?, query: String, clear: () -> Unit, open: (Entry) -> Unit, export: () -> Unit, modifier: Modifier, now: Long) {
    val scroll = rememberLazyListState()
    LaunchedEffect(entries.lastOrNull()?.id, entries.size, state.preferences.newestFirst, day, query) {
        if (entries.isNotEmpty()) scroll.scrollToItem(if (state.preferences.newestFirst) 0 else entries.lastIndex)
    }
    Column(modifier.fillMaxSize()) {
        if (reminderDue(state.preferences, now) && state.entries.isNotEmpty()) {
            Card(Modifier.padding(horizontal = HibiDesign.pagePadding, vertical = 8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Zeit für ein Backup", style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = export, contentPadding = PaddingValues(0.dp)) { Text("ZIP erstellen und extern sichern") }
                    }
                    IconButton(onClick = vm::dismissReminder) { Icon(Icons.Outlined.Close, "Erinnerung bis zum nächsten Termin schließen") }
                }
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
            if (query.isEmpty()) Button(onClick = { vm.openEditor(day?.let { Entry(day = it) }) }, modifier = Modifier.padding(top = 18.dp)) { Text(if (state.draft != null) "Entwurf fortsetzen" else "Eintrag erstellen") }
        } else LazyColumn(state = scroll, contentPadding = PaddingValues(start = HibiDesign.pagePadding, end = HibiDesign.pagePadding, top = 10.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            items(entries, key = { it.id }) { entry ->
                Card(Modifier.fillMaxWidth().clickable { open(entry) }, shape = RoundedCornerShape(HibiDesign.cardRadius), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    PhotoCarousel(entry.photos, vm::mediaFile, state.preferences.pictureFormat) { open(entry) }
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(prettyDate(entry.day), style = MaterialTheme.typography.titleMedium)
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
                                Column(Modifier.weight(1f).height(48.dp).background(if (current) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface, CircleShape)
                                    .semantics { contentDescription = "${prettyDate(date)}${if (date in marked) ", mit Eintrag" else ", ohne Eintrag"}" }
                                    .clickable(role = Role.Button) { choose(date) }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                    Text(day.toString(), style = MaterialTheme.typography.bodyMedium)
                                    Box(Modifier.padding(top = 3.dp).size(4.dp).background(if (date in marked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface, CircleShape))
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
    AlertDialog(onDismissRequest = close, title = { Text("Erinnerungen finden") }, text = {
        OutlinedTextField(value, { value = it }, label = { Text("Text oder Datum") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    }, confirmButton = { TextButton(onClick = { apply(value.trim()) }) { Text("Suchen") } }, dismissButton = { TextButton(onClick = close) { Text("Abbrechen") } })
}
