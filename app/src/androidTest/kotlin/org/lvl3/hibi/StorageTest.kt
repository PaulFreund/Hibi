package org.lvl3.hibi

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.lvl3.hibi.backup.PreparedImport
import org.lvl3.hibi.backup.ZipBackup
import org.lvl3.hibi.data.LocalDiary
import org.lvl3.hibi.model.*
import java.io.File
import java.util.UUID

/** Each test owns a separate database and media directory, including on a physical device. */
private class StorageContext(base: Context) : ContextWrapper(base) {
    private val root = File(base.cacheDir, "hibi-storage-test-${UUID.randomUUID()}").apply { mkdirs() }
    override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
    override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
    override fun getDatabasePath(name: String): File = File(File(root, "db").apply { mkdirs() }, name)
    override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).absolutePath, factory)
    override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).absolutePath, factory, errorHandler)
}

@RunWith(AndroidJUnit4::class)
class StorageTest {
    private fun context() = StorageContext(InstrumentationRegistry.getInstrumentation().targetContext)
    private fun picture(context: Context, rotate: Boolean = false): File {
        val file = File(context.cacheDir, "${UUID.randomUUID()}.jpg")
        val bitmap = Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF557C70.toInt()) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }; bitmap.recycle()
        if (rotate) ExifInterface(file).apply { setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString()); saveAttributes() }
        return file
    }

    @Test fun sqliteAndDraftSurviveRepositoryRecreationAndEditsKeepStableIds() {
        val context = context(); val diary = LocalDiary(context)
        val photo = diary.copyPhoto(Uri.fromFile(picture(context)))
        val entry = Entry(markdown = "**Bleibt gespeichert**", photos = listOf(photo))
        diary.setDraft(entry)
        assertEquals(entry, LocalDiary(context).draft())
        diary.save(entry)
        assertNull(diary.draft())
        assertEquals(listOf(entry), LocalDiary(context).entries())
        val updated = entry.copy(markdown = "Neue Worte", updatedAt = entry.updatedAt + 1)
        diary.save(updated)
        assertEquals(listOf(updated), LocalDiary(context).entries())
        assertEquals(photo.sha256, sha256(diary.mediaFile(photo)))
    }

    @Test fun encryptedBackupRestoresOriginalExifAndIsIdempotentWithoutOverwritingLocalChanges() {
        val sourceContext = context(); val source = LocalDiary(sourceContext)
        val original = picture(sourceContext, rotate = true)
        val photo = source.copyPhoto(Uri.fromFile(original))
        assertEquals(90, photo.width); assertEquals(160, photo.height)
        assertArrayEquals(original.readBytes(), source.mediaFile(photo).readBytes())
        val a = Entry(markdown = "## Café\n\n**Ein guter Tag.**", photos = listOf(photo))
        val b = Entry(markdown = "Nur Text")
        source.save(a); source.save(b)
        val zip = File(sourceContext.cacheDir, "backup.zip")
        ZipBackup().export(zip, source.entries(), Preferences(initialized = true, pictureFormat = PictureFormat.ORIGINAL), source::mediaFile, "geheim".toCharArray())
        val targetContext = context(); val target = LocalDiary(targetContext)
        ZipBackup().prepare(zip, File(targetContext.cacheDir, "stage"), "geheim".toCharArray()).use { prepared ->
            assertEquals(2, target.import(prepared).additions.size)
            assertEquals(source.entries(), target.entries())
            assertArrayEquals(original.readBytes(), target.mediaFile(photo).readBytes())
            assertEquals(PictureFormat.ORIGINAL, target.preferences().pictureFormat)
            assertEquals(2, target.import(prepared).identical)
            val local = a.copy(markdown = "Lokale Worte", updatedAt = a.updatedAt + 10)
            target.save(local)
            assertEquals(1, target.import(prepared).conflicts)
            assertEquals("Lokale Worte", target.entries().single { it.id == a.id }.markdown)
            assertEquals(2, target.entries().size)
        }
    }

    @Test fun platformMetadataDifferencesWarnAndRestoreExactOriginalBytes() {
        val context = context(); val diary = LocalDiary(context)
        val local = Entry(markdown = "Bestehender Eintrag"); diary.save(local)
        val original = picture(context)
        val photo = Photo(UUID.randomUUID().toString(), "jpg", "image/jpeg", 161, 90, sha256(original))
        val folder = File(context.cacheDir, "prepared").apply { mkdirs() }
        val staged = original.copyTo(File(folder, photo.filename))
        val a = Entry(markdown = "Neue Worte"); val b = Entry(photos = listOf(photo))
        val manifest = org.lvl3.hibi.backup.BackupManifest(exportedAt = 1, newestFirst = false, pictureFormat = PictureFormat.SQUARE, reminderPeriod = ReminderPeriod.WEEK, entries = emptyList())
        PreparedImport(folder, manifest, listOf(a, b), mapOf(photo.id to staged)).use { prepared ->
            assertEquals(1, diary.validateImport(prepared).size)
            assertEquals(2, diary.import(prepared).additions.size)
            assertArrayEquals(original.readBytes(), diary.mediaFile(photo).readBytes())
        }
        assertEquals(3, diary.entries().size)
        assertEquals(local, diary.entries().single { it.id == local.id })
    }

    @Test fun changedStagedOriginalRejectsAllEntriesEvenWithValidImageMetadata() {
        val context = context(); val diary = LocalDiary(context)
        val local = Entry(markdown = "Bleibt erhalten"); diary.save(local)
        val original = picture(context); val photo = Photo(UUID.randomUUID().toString(), "jpg", "image/jpeg", 160, 90, sha256(original))
        val folder = File(context.cacheDir, "prepared").apply { mkdirs() }
        val staged = original.copyTo(File(folder, photo.filename)).apply { appendBytes(byteArrayOf(1)) }
        val manifest = org.lvl3.hibi.backup.BackupManifest(exportedAt = 1, newestFirst = false, pictureFormat = PictureFormat.SQUARE, reminderPeriod = ReminderPeriod.WEEK, entries = emptyList())
        PreparedImport(folder, manifest, listOf(Entry(markdown = "Neu"), Entry(photos = listOf(photo))), mapOf(photo.id to staged)).use {
            try { diary.import(it); fail("Changed original accepted") } catch (_: IllegalArgumentException) { }
        }
        assertEquals(listOf(local), diary.entries())
    }

    @Test fun sqliteConstraintFailureRollsBackAllNewEntriesAndRetainsExistingOriginal() {
        val context = context(); val diary = LocalDiary(context)
        val original = picture(context); val photo = diary.copyPhoto(Uri.fromFile(original))
        val local = Entry(markdown = "Bestehend", photos = listOf(photo)); diary.save(local)
        val a = Entry(markdown = "Darf nicht teilweise importiert werden")
        val b = Entry(markdown = "Bild-ID-Kollision", photos = listOf(photo))
        val archive = File(context.cacheDir, "collision.zip")
        ZipBackup().export(archive, listOf(a, b), Preferences(), { diary.mediaFile(it) })
        ZipBackup().prepare(archive, File(context.cacheDir, "stage")).use { prepared ->
            try { diary.import(prepared); fail("Photo ownership collision accepted") } catch (_: android.database.sqlite.SQLiteConstraintException) { }
        }
        assertEquals(listOf(local), diary.entries())
        assertArrayEquals(original.readBytes(), diary.mediaFile(photo).readBytes())
    }
}
