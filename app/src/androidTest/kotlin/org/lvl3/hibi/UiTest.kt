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
        rule.activityRule.scenario.onActivity { ViewModelProvider(it)[DiaryViewModel::class.java].load() }
        rule.waitUntil(20000) { rule.onAllNodesWithText("Einen Moment …").fetchSemanticsNodes().isEmpty() }
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
    private fun javascript(expression: String): String {
        var result = "null"; val completed = CountDownLatch(1)
        rule.activityRule.scenario.onActivity { activity ->
            val view = web(activity.window.decorView)
            if (view == null) completed.countDown() else view.evaluateJavascript(expression) { value -> result = value; completed.countDown() }
        }
        assertTrue("JavaScript callback timed out", completed.await(8, TimeUnit.SECONDS))
        return result
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
        rule.waitUntil(180000) { javascript("typeof editor !== 'undefined' && !!editor") == "true" }
        val markdown = "## Ein kleiner Moment\n\n**Gute Gedanken** bleiben.\n\n- Kaffee\n- frische Luft"
        javascript("editor.setMarkdown(${JSONObject.quote(markdown)})")
        rule.waitForIdle()
        assertEquals("true", javascript("!!document.querySelector('.toastui-editor-ww-container h2')"))
        assertEquals("true", javascript("!!document.querySelector('.toastui-editor-ww-container strong')"))
        screenshot("04-editor-wysiwyg")
        rule.onNodeWithText("Speichern").assertIsEnabled().performClick()
        rule.waitUntil(15000) { diary.entries().any { it.markdown.contains("**Gute Gedanken**") } }
        val stored = diary.entries().single { it.markdown.contains("**Gute Gedanken**") }
        assertTrue(stored.markdown.startsWith("## Ein kleiner Moment"))
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
        rule.waitUntil(20000) { diary.preferences().lastExportAt != null }
        rule.onNodeWithText("Deine ZIP ist bereit").assertExists()
        screenshot("07-zip-bereit")
        val zip = File(app.filesDir, "exports").listFiles()!!.filter { it.extension == "zip" }.maxBy { it.lastModified() }
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
}
