package org.lvl3.hibi

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.lvl3.hibi.backup.*
import org.lvl3.hibi.model.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupTest {
    @get:Rule val temporary = TemporaryFolder()
    private val backup = ZipBackup()
    private val preferences = Preferences(initialized = true, pictureFormat = PictureFormat.ORIGINAL, reminderPeriod = ReminderPeriod.MONTH)
    private fun file(name: String = "${UUID.randomUUID()}.zip") = File(temporary.root, name)
    private fun photo(bytes: ByteArray): Photo {
        val photo = Photo(UUID.randomUUID().toString(), "jpg", "image/jpeg", 1600, 900, sha256(bytes))
        file(photo.filename).writeBytes(bytes); return photo
    }
    private fun export(entries: List<Entry>, password: CharArray? = null): File = file().also {
        backup.export(it, entries, preferences, { p -> file(p.filename) }, password, 1234567890)
    }
    private fun replace(zip: File, name: String, bytes: ByteArray) = ZipFile(zip).use {
        it.addStream(ByteArrayInputStream(bytes), ZipParameters().apply { fileNameInZip = name })
    }
    private fun manifest(file: File): BackupManifest = ZipFile(file).use { zip ->
        zip.getInputStream(zip.getFileHeader("manifest.json")).use { diaryJson.decodeFromString(it.reader().readText()) }
    }
    private fun reject(zip: File, password: CharArray? = null) {
        val parent = file("staging").apply { mkdirs() }
        try { backup.prepare(zip, parent, password).use { fail("Invalid archive accepted") } }
        catch (_: Exception) { /* Required rejection. */ }
        assertEquals("Failed validation must remove every staged file", 0, parent.listFiles()!!.size)
    }

    @Test fun plainArchiveRoundTripsUnicodeTextImagesAndDisplaySettings() {
        val a = photo(byteArrayOf(1, 2, 3, 4)); val b = photo(byteArrayOf(5, 6, 7))
        val entries = listOf(Entry(day = "2026-10-12", markdown = "## Schöner Tag 🌿\n\n**Kaffee** und _Ruhe_.", photos = listOf(a, b)),
            Entry(day = "2026-10-11", markdown = "Nur Text"), Entry(day = "2026-10-10", photos = listOf(photo(byteArrayOf(8, 9)))))
        val zip = export(entries)
        ZipFile(zip).use { assertFalse(it.isEncrypted) }
        backup.prepare(zip, file("stage")).use { restored ->
            assertEquals(entries, restored.entries)
            assertEquals(PictureFormat.ORIGINAL, restored.manifest.pictureFormat)
            assertEquals(ReminderPeriod.MONTH, restored.manifest.reminderPeriod)
            entries.flatMap { it.photos }.forEach { assertArrayEquals(file(it.filename).readBytes(), restored.media.getValue(it.id).readBytes()) }
            assertTrue(restored.manifest.entries[0].markdownFile.startsWith("days/2026-10-12/"))
        }
    }

    @Test fun aesArchiveRequiresCorrectPasswordAndPreservesOriginalBytes() {
        val entry = Entry(markdown = "Privat", photos = listOf(photo("Unverändertes Original".toByteArray())))
        val zip = export(listOf(entry), "sicheres-passwort".toCharArray())
        ZipFile(zip).use { assertTrue(it.isEncrypted) }
        reject(zip); reject(zip, "falsch".toCharArray())
        backup.prepare(zip, file("stage"), "sicheres-passwort".toCharArray()).use { assertEquals(entry, it.entries.single()) }
    }

    @Test fun changedMarkdownFailsDigestBeforeImport() {
        val zip = export(listOf(Entry(markdown = "Original")))
        replace(zip, manifest(zip).entries.single().markdownFile, "Geändert".toByteArray())
        reject(zip)
    }

    @Test fun correctlyHashedInvalidUtf8MarkdownIsRejectedBeforeImport() {
        val zip = export(listOf(Entry(markdown = "Gültiger Text")))
        val invalid = byteArrayOf(0xff.toByte())
        val value = manifest(zip); val record = value.entries.single()
        replace(zip, record.markdownFile, invalid)
        replace(zip, "manifest.json", diaryJson.encodeToString(value.copy(entries = listOf(record.copy(markdownSha256 = sha256(invalid))))).toByteArray())
        reject(zip)
    }

    @Test fun invalidUtf8InIgnoredManifestFieldIsRejectedRatherThanReplaced() {
        val zip = export(listOf(Entry(markdown = "Gültig 🌿")))
        val bytes = diaryJson.encodeToString(manifest(zip)).toByteArray()
        val invalid = "{\"ignored\":\"".toByteArray() + byteArrayOf(0xff.toByte()) + "\",".toByteArray() + bytes.copyOfRange(1, bytes.size)
        replace(zip, "manifest.json", invalid)
        reject(zip)
    }

    @Test fun changedImageFailsDigestBeforeImport() {
        val zip = export(listOf(Entry(photos = listOf(photo(byteArrayOf(1, 2, 3))))))
        replace(zip, manifest(zip).entries.single().photos.single().file, byteArrayOf(9, 9, 9))
        reject(zip)
    }

    @Test fun missingOriginalRejectsEntireArchive() {
        val zip = export(listOf(Entry(markdown = "Mit Bild", photos = listOf(photo(byteArrayOf(1))))))
        ZipFile(zip).use { it.removeFile(manifest(zip).entries.single().photos.single().file) }
        reject(zip)
    }

    @Test fun traversalPathsNeverWriteOutsideStagingDirectory() {
        val zip = file()
        ZipOutputStream(zip.outputStream()).use { it.putNextEntry(ZipEntry("../outside.txt")); it.write(byteArrayOf(1)); it.closeEntry() }
        reject(zip)
        assertFalse(file("outside.txt").exists())
    }

    @Test fun unexpectedPayloadAndDuplicateEntryIdsAreRejected() {
        val zip = export(listOf(Entry(markdown = "Test")))
        replace(zip, "unexpected.txt", "Extra".toByteArray()); reject(zip)
        val second = export(listOf(Entry(markdown = "Test")))
        val value = manifest(second)
        replace(second, "manifest.json", diaryJson.encodeToString(value.copy(entries = value.entries + value.entries)).toByteArray())
        reject(second)
    }

    @Test fun unsupportedVersionNeverAppliesPartialData() {
        val zip = export(listOf(Entry(markdown = "Test")))
        replace(zip, "manifest.json", diaryJson.encodeToString(manifest(zip).copy(version = 2)).toByteArray()); reject(zip)
    }

    @Test fun exportRejectsMissingOrChangedLocalOriginalAndRemovesPartialZip() {
        val image = photo(byteArrayOf(1, 2, 3)); val target = file()
        file(image.filename).writeBytes(byteArrayOf(9))
        try { backup.export(target, listOf(Entry(photos = listOf(image))), preferences, { file(it.filename) }); fail("Changed image accepted") }
        catch (_: IllegalArgumentException) { assertFalse(target.exists()) }
    }

    @Test fun stableIdsSkipRepeatedImportsAndKeepDivergentLocalEntries() {
        val a = Entry(markdown = "Original"); val local = a.copy(markdown = "Lokale Änderung", updatedAt = a.updatedAt + 1)
        val extra = Entry(markdown = "Neu")
        assertEquals(MergePlan(emptyList(), 1, 0), planMerge(listOf(a), listOf(a)))
        assertEquals(MergePlan(listOf(extra), 0, 1), planMerge(listOf(local), listOf(a, extra)))
        assertEquals("Lokale Änderung", local.markdown)
    }

    @Test fun malformedAndOutOfRangeDatesAreRejectedWithoutParserDetails() {
        for (day in listOf("not-a-date", "2026-02-30", "0000-01-01", "+10000-01-01")) {
            try { validate(Entry(day = day, markdown = "Test")); fail("Invalid date accepted: $day") }
            catch (error: IllegalArgumentException) { assertEquals("Ungültiges Datum", error.message) }
        }
        validate(Entry(day = "0001-01-01", markdown = "Historischer Eintrag"))
        validate(Entry(day = "9999-12-31", markdown = "Zukünftiger Eintrag"))
    }

    @Test fun reminderUsesCalendarMonthsAndWeeksAcrossDstAndDismissal() {
        val utc = ZoneId.of("UTC")
        val january = Instant.parse("2026-01-31T12:00:00Z").toEpochMilli()
        assertEquals(Instant.parse("2026-02-28T12:00:00Z").toEpochMilli(), nextReminder(january, ReminderPeriod.MONTH, utc))
        val berlin = ZoneId.of("Europe/Berlin")
        val march = Instant.parse("2026-03-22T11:00:00Z").toEpochMilli()
        assertEquals(Instant.parse("2026-03-29T10:00:00Z").toEpochMilli(), nextReminder(march, ReminderPeriod.WEEK, berlin))
        val due = preferences.copy(nextReminderAt = january)
        assertTrue(reminderDue(due, january))
        assertFalse(reminderDue(due.copy(nextReminderAt = nextReminder(january, ReminderPeriod.MONTH, utc)), january + 1))
        assertFalse(reminderDue(due.copy(reminderPeriod = ReminderPeriod.OFF), Long.MAX_VALUE))
        assertFalse(reminderDue(due.copy(initialized = false), january))
    }

    @Test fun directOutputStreamRoundTripsAesWithoutSeekingOrLocalArchiveStaging() {
        // Multiple AES blocks expose in-place encryption of the supplied write buffer.
        val entry = Entry(markdown = "**Streamend gespeichert**", photos = listOf(photo(ByteArray(100000) { it.toByte() })))
        val stream = ByteArrayOutputStream()
        backup.export(stream, listOf(entry), preferences, { file(it.filename) }, "direktes-ziel".toCharArray())
        val zip = file().apply { writeBytes(stream.toByteArray()) }
        backup.prepare(zip, file("stage"), "direktes-ziel".toCharArray()).use { assertEquals(entry, it.entries.single()) }
        reject(zip, "falsch".toCharArray())
    }

    @Test fun callerOwnsFinalOutputCloseAndImagePayloadIsWrittenInBlocks() {
        var closes = 0; var singleByteWrites = 0; var blockWrites = 0
        val stream = object : ByteArrayOutputStream() {
            override fun close() { closes++; super.close() }
            override fun write(value: Int) { singleByteWrites++; super.write(value) }
            override fun write(bytes: ByteArray, offset: Int, length: Int) { blockWrites++; super.write(bytes, offset, length) }
        }
        val entry = Entry(photos = listOf(photo(ByteArray(100000) { 7 })))
        stream.use {
            backup.export(it, listOf(entry), preferences, { image -> file(image.filename) })
            assertEquals("Only the caller may close/commit the destination", 0, closes)
            assertTrue("Image bytes should not become individual destination writes", singleByteWrites < 1000)
            assertTrue(blockWrites > 0)
        }
        assertEquals(1, closes)
        backup.prepare(file().apply { writeBytes(stream.toByteArray()) }, file("stage")).use { assertEquals(entry, it.entries.single()) }
    }

    @Test fun failedExportNamesEveryAffectedDayAndLeavesNoArchive() {
        val first = photo(byteArrayOf(1)); val second = photo(byteArrayOf(2))
        file(first.filename).delete(); file(second.filename).writeBytes(byteArrayOf(3))
        val target = file()
        try {
            backup.export(target, listOf(Entry(day = "2026-10-10", photos = listOf(first)), Entry(day = "2026-10-11", photos = listOf(second))), preferences, { file(it.filename) })
            fail("Bad originals accepted")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message!!.contains("2026-10-10")); assertTrue(error.message!!.contains("2026-10-11"))
            assertFalse(target.exists())
        }
    }

    @Test fun cancellationRemovesPartialArchiveAndRetainsOriginals() {
        val original = photo(ByteArray(100000) { 42 }); val target = file()
        try {
            backup.export(target, listOf(Entry(photos = listOf(original))), preferences, { file(it.filename) }, progress = {
                if (it.completedFiles == 1) throw java.util.concurrent.CancellationException("cancel")
            })
            fail("Cancelled export completed")
        } catch (_: java.util.concurrent.CancellationException) {
            assertFalse(target.exists()); assertEquals(original.sha256, sha256(file(original.filename)))
        }
    }
}
