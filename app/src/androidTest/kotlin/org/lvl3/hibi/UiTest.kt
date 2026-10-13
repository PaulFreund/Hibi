package org.lvl3.hibi

import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import org.lvl3.hibi.ui.DiaryViewModel
import org.lvl3.hibi.ui.EditorController
import org.lvl3.hibi.ui.EditorRead
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.lvl3.hibi.model.*
import java.io.File
import java.time.LocalDate
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** UI fixtures are restricted to a fresh emulator. Never run these against a real diary. */
class UiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as HibiApplication
    private val diary get() = app.diary

    @Before fun requireEmulator() {
        check(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish") { "UI tests require an emulator with disposable Hibi data" }
        rule.waitForIdle()
    }

    private fun reload() {
        val expectedEntries = diary.entries(); val expectedPreferences = diary.preferences()
        lateinit var viewModel: DiaryViewModel
        rule.activityRule.scenario.onActivity { viewModel = ViewModelProvider(it)[DiaryViewModel::class.java]; viewModel.load() }
        rule.waitUntil(20000) { val state = viewModel.state.value; state.loaded && !state.busy && state.entries == expectedEntries && state.preferences == expectedPreferences }
        rule.waitForIdle()
    }

    private fun fixture() {
        diary.entries().forEach { diary.delete(it.id) }; diary.discardDraft()
        diary.setPreferences(Preferences(initialized = true))
        val directory = File(app.cacheDir, "ui-fixtures").apply { mkdirs() }
        val source = File(directory, "lake.png")
        instrumentation.context.assets.open("demo/lake.png").use { input -> source.outputStream().use { input.copyTo(it) } }
        diary.save(Entry(day = LocalDate.now().minusDays(1).toString(), markdown = "Ein ruhiger Sonntag.\n\nZeit für **kleine Dinge**."))
        val a = diary.copyPhoto(Uri.fromFile(source)); val b = diary.copyPhoto(Uri.fromFile(source))
        diary.save(Entry(day = LocalDate.now().toString(), markdown = "Am See\n\nFrische Luft, warmes Licht und ein Moment nur für uns.", photos = listOf(a, b)))
        reload()
        rule.waitUntil(20000) { rule.onAllNodesWithText("Kalender").fetchSemanticsNodes().isNotEmpty() }
        rule.waitUntil(20000) { rule.onAllNodesWithText("Bild wird geladen").fetchSemanticsNodes().isEmpty() }
    }

    private fun screenshot(name: String) {
        // Rendering diagnostics are optional; screenshot service failures must not abort feature assertions.
        if (InstrumentationRegistry.getArguments().getString("hibi.screenshots") != "true") return
        rule.waitForIdle(); Thread.sleep(350)
        val file = File(app.getExternalFilesDir(null), "verification/$name.png").apply { parentFile!!.mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.useBitmap { bitmap -> file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }
    private fun Bitmap.useBitmap(block: (Bitmap) -> Unit) { try { block(this) } finally { recycle() } }
    private fun web(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) web(view.getChildAt(i))?.let { return it }
        return null
    }
    private fun javascript(expression: String, required: Boolean = true): String {
        var result = "null"; val completed = CountDownLatch(1)
        var controller: EditorController? = null
        rule.activityRule.scenario.onActivity { activity ->
            val view = web(activity.window.decorView)
            controller = view?.tag as? EditorController
            if (view == null) completed.countDown() else view.evaluateJavascript(expression) { value -> result = value; completed.countDown() }
        }
        val answered = completed.await(8, TimeUnit.SECONDS)
        if (required) assertTrue("JavaScript callback timed out: nativeReady=${controller?.ready}, latestLength=${controller?.latest?.length}, rendererFailure=${controller?.failure}, consoleError=${controller?.consoleError}, expression=$expression", answered)
        if (!answered) return "null" // Readiness polling tolerates a cold renderer; feature reads remain required.
        return result
    }
    private fun editorReady(): Boolean {
        var ready = false
        rule.activityRule.scenario.onActivity { activity -> ready = (web(activity.window.decorView)?.tag as? EditorController)?.ready == true }
        return ready
    }
    private fun diaryState(): String {
        var value = ""
        rule.activityRule.scenario.onActivity { activity ->
            val state = ViewModelProvider(activity)[DiaryViewModel::class.java].state.value
            val files = File(app.cacheDir, "exports").listFiles().orEmpty().joinToString { it.name + ":" + it.length() }
            value = "busy=${state.busy}, operation=${state.operation}, message=${state.message}, export=${state.export}, archiveFiles=$files"
        }
        return value
    }

    @Test fun feedCalendarSearchAndImageSettingWorkWithOriginalPhotos() {
        fixture()
        screenshot("01-feed-quadratisch")
        rule.onNodeWithText("Kalender").performClick()
        val day = LocalDate.now().toString()
        val label = LocalDate.parse(day).format(java.time.format.DateTimeFormatter.ofPattern("EEEE, d. MMMM yyyy", Locale.GERMAN)) + ", mit Eintrag"
        rule.onNodeWithContentDescription(label).assertExists()
        screenshot("02-kalender")
        rule.onNodeWithContentDescription(label).performClick()
        rule.onNodeWithContentDescription("Filter aufheben").performClick()
        rule.onNodeWithText("Suche").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("nicht-vorhanden")
        rule.onNodeWithText("Suchen").performClick()
        rule.onNodeWithText("Keine passenden Einträge").assertExists()
        rule.onNodeWithContentDescription("Filter aufheben").performClick()
        rule.onNodeWithText("Optionen").performClick()
        rule.onNodeWithText("Quellformat").performClick()
        rule.waitUntil(10000) { diary.preferences().pictureFormat == PictureFormat.ORIGINAL }
        rule.waitUntil(10000) { rule.onAllNodesWithText("Einen Moment …").fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithContentDescription("Zum Tagebuch").performClick()
        screenshot("03-feed-quellformat")
    }

    @Test fun wysiwygEditsSaveMarkdownAndCanBeOpenedAgain() {
        fixture()
        rule.onNodeWithText("Eintrag").performClick()
        // The first WebView renderer startup can exceed a minute on a constrained emulator host.
        rule.waitUntil(180000) { editorReady() && javascript("typeof editor !== 'undefined' && !!editor", required = false) == "true" }
        val markdown = "## Ein kleiner Moment\n\n**Gute Gedanken** bleiben.\n\n- Kaffee\n- frische Luft"
        javascript("editor.setMarkdown(${JSONObject.quote(markdown)})")
        rule.waitForIdle()
        assertEquals("true", javascript("!!document.querySelector('.toastui-editor-ww-container h2')"))
        assertEquals("true", javascript("!!document.querySelector('.toastui-editor-ww-container strong')"))
        rule.activityRule.scenario.onActivity { activity -> web(activity.window.decorView)!!.requestFocus() }
        javascript("(()=>{const area=document.querySelector('.toastui-editor-ww-container [contenteditable=\"true\"]'); area.focus(); const range=document.createRange();range.selectNodeContents(area);range.collapse(false);const selection=window.getSelection();selection.removeAllRanges();selection.addRange(range);return true;})()")
        instrumentation.sendStringSync(" Echter Tastaturtext.")
        rule.waitUntil(15000) { diary.draft()?.markdown?.contains("**Gute Gedanken**") == true }
        rule.waitUntil(15000) { diary.draft()?.markdown?.contains("Echter Tastaturtext") == true }
        screenshot("04-editor-wysiwyg")
        rule.onNodeWithText("Speichern").assertIsEnabled().performClick()
        rule.waitUntil(15000) { diary.entries().any { it.markdown.contains("**Gute Gedanken**") } }
        val stored = diary.entries().single { it.markdown.contains("**Gute Gedanken**") }
        assertTrue(stored.markdown.startsWith("## Ein kleiner Moment"))
        assertTrue(stored.markdown.contains("Echter Tastaturtext"))
        rule.activityRule.scenario.recreate(); rule.waitForIdle()
        assertTrue(diary.entries().any { it.id == stored.id })
    }

    @Test fun dueReminderClosesAndExportProducesEncryptedArchive() {
        fixture()
        diary.setPreferences(diary.preferences().copy(nextReminderAt = 1))
        reload()
        rule.onNodeWithText("Zeit für ein Backup").assertExists()
        screenshot("05-erinnerung")
        rule.onNodeWithContentDescription("Erinnerung bis zum nächsten Termin schließen").performClick()
        rule.waitUntil(10000) { diary.preferences().nextReminderAt > System.currentTimeMillis() }
        rule.waitUntil(10000) { rule.onAllNodesWithText("Einen Moment …").fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithText("Zeit für ein Backup").assertDoesNotExist()
        rule.onNodeWithText("Optionen").performClick()
        rule.onNodeWithText("ZIP-Backup erstellen").performScrollTo().performClick()
        rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox)).performClick()
        rule.onAllNodes(hasSetTextAction())[0].performTextInput("Hibi-Testpasswort")
        rule.onAllNodes(hasSetTextAction())[1].performTextInput("Hibi-Testpasswort")
        screenshot("06-zip-passwort")
        rule.onNodeWithText("ZIP erstellen").performClick()
        try { rule.waitUntil(20000) { rule.onAllNodesWithText("Deine ZIP ist bereit").fetchSemanticsNodes().isNotEmpty() } }
        catch (error: Throwable) { throw AssertionError("Export did not reach ready dialog: ${diaryState()}", error) }
        rule.onNodeWithText("Deine ZIP ist bereit").assertExists()
        screenshot("07-zip-bereit")
        assertNull("Creating an unshared ZIP must not count as external export", diary.preferences().lastExportAt)
        val zip = File(app.cacheDir, "exports").listFiles()!!.filter { it.extension == "zip" }.maxBy { it.lastModified() }
        org.lvl3.hibi.backup.ZipBackup().prepare(zip, File(app.cacheDir, "ui-restore"), "Hibi-Testpasswort".toCharArray()).use { assertEquals(2, it.entries.size) }
        rule.onNodeWithText("Fertig").performClick()
    }

    @Test fun pickerResultDuringReloadKeepsRecoveredDraft() {
        fixture()
        val draft = Entry(markdown = "Ein wiederhergestellter Entwurf")
        diary.setDraft(draft)
        val store = ViewModelStore()
        rule.activityRule.scenario.onActivity {
            val fresh = DiaryViewModel(app)
            store.put("picker-recovery", fresh)
            fresh.addPhotos(listOf(Uri.fromFile(File(app.cacheDir, "ui-fixtures/lake.png"))))
        }
        rule.waitUntil(20000) { diary.draft()?.photos?.size == 1 }
        assertEquals(draft.id, diary.draft()!!.id)
        assertEquals(draft.markdown, diary.draft()!!.markdown)
        assertTrue(diary.mediaFile(diary.draft()!!.photos.single()).isFile)
        store.clear()
    }

    @Test fun unreadyEditorReadRetainsRestoredTextInsteadOfReturningEmptyMarkdown() {
        var result: EditorRead? = null
        rule.activityRule.scenario.onActivity {
            EditorController().apply { latest = "**Wiederhergestellte Worte**" }.read { value -> result = value }
        }
        assertEquals("**Wiederhergestellte Worte**", result!!.markdown)
        assertFalse("Cached text is not a confirmed renderer read", result!!.confirmed)
    }

    @Test fun timedOutReadNeverConfirmsCacheOrAcceptsALateAnswer() {
        var answer: ((String) -> Unit)? = null; var result: EditorRead? = null; var callbacks = 0
        lateinit var controller: EditorController
        rule.activityRule.scenario.onActivity {
            controller = EditorController { _, callback -> answer = callback }.apply { latest = "Letzter bestätigter Text"; ready = true }
            controller.read { result = it; callbacks++ }
        }
        rule.waitUntil(5000) { result != null }
        assertFalse(result!!.confirmed); assertEquals("Letzter bestätigter Text", result!!.markdown)
        rule.activityRule.scenario.onActivity { answer!!.invoke(JSONObject.quote("Verspätete Antwort")) }
        assertEquals(1, callbacks); assertEquals("Letzter bestätigter Text", controller.latest)
        assertNotNull(controller.readIssue)
        for (invalid in listOf("null", "42", "{}")) {
            rule.activityRule.scenario.onActivity {
                EditorController { _, callback -> callback(invalid) }.apply { ready = true; latest = "Bleibt" }.read { assertFalse(it.confirmed); assertEquals("Bleibt", it.markdown) }
            }
        }
    }

    @Test fun snapshotsAndPickerCheckpointPreserveRawWordsAndRejectOldDraftResponses() {
        fixture()
        val store = ViewModelStore(); lateinit var fresh: DiaryViewModel
        rule.activityRule.scenario.onActivity { fresh = DiaryViewModel(app); store.put("snapshot-review", fresh) }
        rule.waitUntil(20000) { fresh.state.value.loaded && !fresh.state.value.busy }
        val draft = Entry(); val raw = "    erste Zeile\n    zweite Zeile  \n"
        var pickerCheckpoint = false
        rule.activityRule.scenario.onActivity {
            fresh.openEditor(draft)
            fresh.persistEditorSnapshot(draft.id, "", raw)
            fresh.flushDraft(draft.id) { pickerCheckpoint = diary.draft()?.markdown == raw }
        }
        rule.waitUntil(20000) { pickerCheckpoint }
        rule.activityRule.scenario.onActivity {
            fresh.updateDraftText(draft.id, raw + "Neuere Worte")
            fresh.persistEditorSnapshot(draft.id, raw, "Veraltete Antwort")
            fresh.persistEditorSnapshot(Entry().id, "", "Falscher Entwurf")
        }
        rule.waitUntil(20000) { diary.draft()?.markdown == raw + "Neuere Worte" }
        rule.activityRule.scenario.onActivity { fresh.saveDraft(raw) }
        rule.waitUntil(20000) { diary.entries().any { it.id == draft.id } }
        assertEquals(raw, diary.entries().single { it.id == draft.id }.markdown)
        store.clear()
    }

    @Test fun rotationPreservesEncryptionIntentAndRequiresPasswordsAgain() {
        fixture()
        rule.onNodeWithText("Optionen").performClick()
        rule.onNodeWithText("ZIP-Backup erstellen").performScrollTo().performClick()
        val checkbox = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox)
        rule.onNode(checkbox).performClick()
        rule.onAllNodes(hasSetTextAction())[0].performTextInput("Verschlüsselt")
        rule.onAllNodes(hasSetTextAction())[1].performTextInput("Verschlüsselt")
        rule.onNodeWithText("ZIP erstellen").assertIsEnabled()
        rule.activityRule.scenario.recreate(); rule.waitForIdle()
        rule.onNode(checkbox).assertIsOn()
        rule.onNodeWithText("ZIP erstellen").assertIsNotEnabled()
        rule.onNodeWithText("ZIP-Datei direkt speichern").assertIsNotEnabled()
    }

    @Test fun unchangedEditingIsDiscardedAndNewEntryNeverSilentlyOverwritesEditingDraft() {
        fixture()
        val original = diary.entries().first()
        val store = ViewModelStore(); lateinit var fresh: DiaryViewModel
        rule.activityRule.scenario.onActivity { fresh = DiaryViewModel(app); store.put("draft-review", fresh) }
        rule.waitUntil(20000) { fresh.state.value.loaded && !fresh.state.value.busy }
        rule.activityRule.scenario.onActivity { fresh.openEditor(original); fresh.closeEditor() }
        rule.waitUntil(20000) { !fresh.state.value.editorOpen && !fresh.state.value.busy }
        assertNull(diary.draft()); assertEquals(original, diary.entries().single { it.id == original.id })
        rule.activityRule.scenario.onActivity {
            fresh.openEditor(original); fresh.updateDraft { it.copy(markdown = "Bearbeitung behalten") }; fresh.closeEditor()
        }
        rule.waitUntil(20000) { diary.draft()?.markdown == "Bearbeitung behalten" && !fresh.state.value.editorOpen }
        rule.activityRule.scenario.onActivity { fresh.newEntry("2026-10-15") }
        assertEquals(original.id, fresh.state.value.draft!!.id)
        assertEquals("2026-10-15", fresh.state.value.requestedEntry!!.day)
        assertFalse(fresh.state.value.editorOpen)
        rule.activityRule.scenario.onActivity { fresh.replaceDraftAndOpen() }
        rule.waitUntil(20000) { fresh.state.value.editorOpen && fresh.state.value.draft?.id != original.id }
        assertEquals("2026-10-15", fresh.state.value.draft!!.day)
        assertEquals(original, diary.entries().single { it.id == original.id })
        rule.activityRule.scenario.onActivity { fresh.discardDraft() }
        rule.waitUntil(20000) { diary.draft() == null && !fresh.state.value.editorOpen }
        store.clear()
    }

    @Test fun failedPublishRetainsLatestDraftWordsAndExistingEntries() {
        fixture()
        val before = diary.entries(); val draft = Entry()
        val database = app.openOrCreateDatabase("hibi.db", 0, null)
        database.execSQL("CREATE TRIGGER review_save_failure BEFORE INSERT ON entries WHEN NEW.id='${draft.id}' BEGIN SELECT RAISE(ABORT, 'review save failure'); END")
        val store = ViewModelStore(); lateinit var fresh: DiaryViewModel
        try {
            rule.activityRule.scenario.onActivity { fresh = DiaryViewModel(app); store.put("failed-save", fresh) }
            rule.waitUntil(20000) { fresh.state.value.loaded && !fresh.state.value.busy }
            rule.activityRule.scenario.onActivity { fresh.openEditor(draft); fresh.saveDraft("Diese Worte müssen auch nach einem Speicherfehler bleiben") }
            rule.waitUntil(20000) { fresh.state.value.message?.contains("review save failure") == true }
            assertEquals(draft.id, diary.draft()!!.id)
            assertEquals("Diese Worte müssen auch nach einem Speicherfehler bleiben", diary.draft()!!.markdown)
            assertEquals(before, diary.entries())
        } finally {
            database.execSQL("DROP TRIGGER IF EXISTS review_save_failure"); database.close()
            store.clear(); diary.discardDraft()
        }
    }
}
