package org.lvl3.hibi.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import org.lvl3.hibi.BuildConfig
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import org.lvl3.hibi.backup.PreparedImport
import org.lvl3.hibi.model.*
import org.lvl3.hibi.ui.theme.HibiDesign
import java.io.File
import java.util.Locale

@Composable
fun SettingsScreen(state: UiState, vm: DiaryViewModel, modifier: Modifier, export: () -> Unit, restore: () -> Unit) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(HibiDesign.pagePadding), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        SettingsCard("Dein Feed") {
            Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.toggleable(state.preferences.newestFirst, role = Role.Switch,
                onValueChange = { enabled -> vm.changePreferences { it.copy(newestFirst = enabled) } }), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("Neueste Einträge oben", style = MaterialTheme.typography.titleMedium); Text("Standard: neueste unten", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Switch(state.preferences.newestFirst, null)
            }
            Text("Bildansicht", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice("Quadratisch", state.preferences.pictureFormat == PictureFormat.SQUARE, Modifier.weight(1f)) { vm.changePreferences { it.copy(pictureFormat = PictureFormat.SQUARE) } }
                Choice("Quellformat", state.preferences.pictureFormat == PictureFormat.ORIGINAL, Modifier.weight(1f)) { vm.changePreferences { it.copy(pictureFormat = PictureFormat.ORIGINAL) } }
            }
            Text("Originalbilder bleiben unverändert.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SettingsCard("Backup") {
            Text("Deine Erinnerungen mitnehmen", style = MaterialTheme.typography.titleMedium)
            Text("Eine ZIP enthält alle gespeicherten Einträge, Markdown und Originalbilder. Teile sie mit OneDrive, Google Drive oder einer anderen App.", style = MaterialTheme.typography.bodyMedium)
            Button(export, Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Share, null); Spacer(Modifier.width(8.dp)); Text("ZIP-Backup erstellen") }
            OutlinedButton(restore, Modifier.fillMaxWidth()) { Icon(Icons.Outlined.ArrowForward, null); Spacer(Modifier.width(8.dp)); Text("ZIP-Backup importieren") }
            Text("Letzter Export", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(exportDate(state.preferences.lastExportAt), style = MaterialTheme.typography.bodyMedium)
            Text("Upload und Netzverwendung übernimmt die Ziel-App. Hibi bestätigt den Export, keinen Cloud-Upload.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SettingsCard("Backup-Erinnerung") {
            Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(ReminderPeriod.OFF to "Aus", ReminderPeriod.WEEK to "Woche", ReminderPeriod.MONTH to "Monat").forEach { (period, label) ->
                    Choice(label, state.preferences.reminderPeriod == period, Modifier.weight(1f)) { vm.setReminder(period) }
                }
            }
            Text("Wenn sie fällig ist, erscheint die Erinnerung beim Öffnen im Feed. × schließt sie bis zum nächsten Termin.", style = MaterialTheme.typography.bodyMedium)
        }
        Text("${state.entries.size} Einträge · ${state.entries.sumOf { it.photos.size }} Fotos\nHibi ${BuildConfig.VERSION_NAME} · lokal auf deinem Gerät", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            content()
        }
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, modifier: Modifier, action: () -> Unit) {
    Surface(modifier.selectable(selected = selected, role = Role.RadioButton, onClick = action), shape = MaterialTheme.shapes.small,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)) {
        Box(Modifier.heightIn(min = 48.dp).padding(horizontal = 6.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
            Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun PasswordField(value: String, set: (String) -> Unit, label: String) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(value, set, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = { TextButton(onClick = { visible = !visible }) { Text(if (visible) "Verbergen" else "Zeigen") } })
}

@Composable
fun ExportPasswordDialog(close: () -> Unit, create: (String) -> Unit, save: (String) -> Unit) {
    var encrypted by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = close, title = { Text("ZIP-Backup erstellen") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.toggleable(encrypted, role = Role.Checkbox, onValueChange = { encrypted = it }), verticalAlignment = Alignment.CenterVertically) { Checkbox(encrypted, null); Text("Mit Passwort verschlüsseln") }
            if (encrypted) {
                PasswordField(password, { password = it }, "Passwort")
                PasswordField(repeat, { repeat = it }, "Passwort wiederholen")
                Text("AES-256. Ohne dieses Passwort ist ein Import nicht möglich. Es wird nicht gespeichert; Dateinamen bleiben lesbar.", style = MaterialTheme.typography.bodySmall)
            } else Text("Ohne Passwort ist die ZIP unverschlüsselt.")
            Text("Entwürfe sind nicht enthalten. Sichere die fertige ZIP anschließend außerhalb dieses Geräts.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = { save(if (encrypted) password else "") }, enabled = !encrypted || (password.isNotEmpty() && password == repeat), modifier = Modifier.fillMaxWidth()) { Text("ZIP-Datei direkt speichern") }
        }
    }, confirmButton = { TextButton(onClick = { create(if (encrypted) password else "") }, enabled = !encrypted || (password.isNotEmpty() && password == repeat)) { Text("ZIP erstellen") } },
        dismissButton = { TextButton(onClick = close) { Text("Abbrechen") } })
}

@Composable
fun ExportReadyDialog(file: File, close: () -> Unit, share: () -> Unit) {
    AlertDialog(onDismissRequest = close, title = { Text("Deine ZIP ist bereit") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(file.name, style = MaterialTheme.typography.bodyMedium)
            Text(String.format(Locale.GERMAN, "%.1f MB", file.length() / (1024.0 * 1024.0)))
            Button(share, Modifier.fillMaxWidth()) { Text("Mit … teilen") }
            Text("Wähle die Ziel-App und schließe dort den Upload ab.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }, confirmButton = { TextButton(close) { Text("Fertig") } })
}

@Composable
fun ImportPasswordDialog(close: () -> Unit, busy: Boolean = false, error: String? = null, inspect: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = close, title = { Text("ZIP-Backup prüfen") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PasswordField(password, { password = it }, "Passwort (falls verschlüsselt)")
            if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
            Text("Hibi prüft erst alle Dateien. Vor der Übernahme siehst du eine Zusammenfassung.", style = MaterialTheme.typography.bodyMedium)
        }
    }, confirmButton = { TextButton(onClick = { inspect(password) }, enabled = !busy) { Text("Backup prüfen") } }, dismissButton = { TextButton(close, enabled = !busy) { Text("Abbrechen") } })
}

@Composable
fun ImportPreviewDialog(prepared: PreparedImport, plan: MergePlan, warnings: List<String>, cancel: () -> Unit, apply: () -> Unit) {
    AlertDialog(onDismissRequest = cancel, title = { Text("Backup ist geprüft") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Stand des Backups: ${exportDate(prepared.manifest.exportedAt)}")
            Text("${prepared.entries.size} Einträge · ${prepared.entries.sumOf { it.photos.size }} Originalbilder")
            Text("${plan.additions.size} neue Einträge werden übernommen.")
            if (plan.identical > 0) Text("${plan.identical} identische Einträge sind bereits vorhanden.")
            if (plan.conflicts > 0) Text("${plan.conflicts} Einträge unterscheiden sich. Deine lokalen Fassungen bleiben erhalten.")
            Text("Bestehende Einträge werden nicht überschrieben.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (warnings.isNotEmpty()) {
                Text("${warnings.size} Hinweise zu Bildern", style = MaterialTheme.typography.titleMedium)
                warnings.take(5).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (warnings.size > 5) Text("${warnings.size - 5} weitere. Alle Originaldateien bleiben erhalten.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }, confirmButton = { TextButton(apply) { Text("Importieren") } }, dismissButton = { TextButton(cancel) { Text("Abbrechen") } })
}
