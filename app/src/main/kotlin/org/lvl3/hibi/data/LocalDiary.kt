package org.lvl3.hibi.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.lvl3.hibi.backup.PreparedImport
import org.lvl3.hibi.model.*
import java.io.File
import java.io.FileOutputStream
import java.io.ByteArrayOutputStream
import java.util.UUID

/** Versioned SQLite schema and immutable original media. UI only consumes domain objects. */
class LocalDiary(private val context: Context) : DiaryRepository {
    private val helper = Database(context)
    private val db get() = helper.writableDatabase
    private val media = File(context.filesDir, "media").apply { mkdirs() }

    override fun entries(): List<Entry> {
        val photos = mutableMapOf<String, MutableList<Photo>>()
        db.query("photos", null, null, null, null, null, "position ASC").use { cursor ->
            while (cursor.moveToNext()) {
                fun text(name: String) = cursor.getString(cursor.getColumnIndexOrThrow(name))
                fun number(name: String) = cursor.getInt(cursor.getColumnIndexOrThrow(name))
                photos.getOrPut(text("entry_id")) { mutableListOf() }.add(
                    Photo(text("id"), text("extension"), text("mime"), number("width"), number("height"), text("sha256")))
            }
        }
        // Keep CursorWindow rows small, including for existing 2-MiB texts.
        return db.query("entries", arrayOf("id", "day", "created_at", "updated_at"), null, null, null, null, "day ASC, created_at ASC, id ASC").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    fun text(name: String) = cursor.getString(cursor.getColumnIndexOrThrow(name))
                    fun number(name: String) = cursor.getLong(cursor.getColumnIndexOrThrow(name))
                    val id = text("id")
                    add(Entry(id, text("day"), number("created_at"), number("updated_at"), readText("entries", "markdown", "id", id).orEmpty(), photos[id].orEmpty()))
                }
            }
        }
    }

    override fun preferences(): Preferences {
        val stored = setting("preferences")
        if (stored != null) return diaryJson.decodeFromString(stored)
        return Preferences().also(::setPreferences)
    }

    override fun setPreferences(value: Preferences) = setSetting("preferences", diaryJson.encodeToString(value))
    fun draft(): Entry? = setting("draft")?.let { diaryJson.decodeFromString(it) }
    fun setDraft(value: Entry?) {
        if (value == null) db.delete("settings", "key=?", arrayOf("draft"))
        else setSetting("draft", diaryJson.encodeToString(value))
    }

    override fun mediaFile(photo: Photo): File = File(media, photo.filename)

    override fun save(entry: Entry) {
        validate(entry)
        entry.photos.forEach { require(mediaFile(it).isFile) { "Originalbild fehlt" } }
        transaction { writeEntry(entry); setDraft(null) }
        collectUnusedMedia()
    }

    override fun delete(id: String) {
        transaction {
            db.delete("entries", "id=?", arrayOf(id))
            if (draft()?.id == id) setDraft(null)
        }
        collectUnusedMedia()
    }

    fun discardDraft() { setDraft(null); collectUnusedMedia() }

    fun copyPhoto(uri: Uri): Photo {
        val temporary = File(media, "${UUID.randomUUID()}.part")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var size = 0L
                    while (true) {
                        val count = input.read(buffer); if (count < 0) break
                        size += count
                        require(size <= 512L * 1024 * 1024) { "Bild ist zu groß" }
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            } ?: error("Bild kann nicht geöffnet werden")
            val info = imageInfo(temporary)
            val extension = imageExtensions.entries.firstOrNull { info.mime in it.value }?.key
                ?: error("Dieser Bildtyp wird nicht unterstützt")
            val photo = Photo(UUID.randomUUID().toString(), extension, info.mime, info.width, info.height, sha256(temporary))
            require(temporary.renameTo(mediaFile(photo))) { "Originalbild kann nicht gespeichert werden" }
            return photo
        } finally { temporary.delete() }
    }

    /** Hashes are mandatory; platform decoder differences must not prevent portable restore. */
    fun validateImport(prepared: PreparedImport): List<String> {
        val warnings = mutableListOf<String>()
        prepared.entries.forEach { entry ->
            validate(entry)
            entry.photos.forEachIndexed { index, photo ->
                val source = prepared.media.getValue(photo.id).takeIf { it.isFile } ?: mediaFile(photo)
                require(source.isFile && sha256(source) == photo.sha256) { "Bilddatei ist beschädigt: ${entry.day}, Bild ${index + 1}" }
                val info = runCatching { imageInfo(source) }.getOrNull()
                if (info == null) warnings += "${entry.day}, Bild ${index + 1}: auf diesem Gerät nicht lesbar; Original wird erhalten."
                else if (info.width != photo.width || info.height != photo.height || info.mime != photo.mimeType)
                    warnings += "${entry.day}, Bild ${index + 1}: abweichende Bildinformationen; Original wird erhalten."
            }
        }
        return warnings
    }

    fun import(prepared: PreparedImport): MergePlan {
        validateImport(prepared)
        val plan = planMerge(entries(), prepared.entries)
        val created = mutableListOf<File>()
        try {
            plan.additions.flatMap { it.photos }.forEach { photo ->
                val target = mediaFile(photo)
                if (target.exists()) require(sha256(target) == photo.sha256) { "Lokale Bild-ID hat einen anderen Inhalt" }
                else {
                    val source = prepared.media.getValue(photo.id)
                    // Staging and media normally share the app filesystem. Rename avoids another full copy.
                    if (!source.renameTo(target)) {
                        val temporary = File(media, "${photo.id}.part")
                        try {
                            FileOutputStream(temporary).use { output -> source.inputStream().use { it.copyTo(output) }; output.fd.sync() }
                            require(temporary.renameTo(target)) { "Bild kann nicht übernommen werden" }
                        } finally { temporary.delete() }
                    }
                        created += target
                }
            }
            transaction {
                plan.additions.forEach(::writeEntry)
                val current = preferences()
                // Existing diaries keep their UI settings. Restore settings only on first setup.
                setPreferences(if (current.initialized) current else current.copy(initialized = true,
                    newestFirst = prepared.manifest.newestFirst, pictureFormat = prepared.manifest.pictureFormat,
                    reminderPeriod = prepared.manifest.reminderPeriod,
                    nextReminderAt = nextReminder(System.currentTimeMillis(), prepared.manifest.reminderPeriod)))
            }
            return plan
        } catch (error: Exception) {
            // Keep the verified preview usable after a failed DB transaction.
            created.forEach { target ->
                val staged = prepared.media.values.firstOrNull { it.name == target.name }
                if (staged == null || staged.exists() || !target.renameTo(staged)) target.delete()
            }
            throw error
        }
    }

    fun cleanTemporaryMedia() {
        media.listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.delete() }
        collectUnusedMedia()
    }

    private fun writeEntry(entry: Entry) {
        validate(entry)
        // REPLACE is avoided: a parent replacement would cascade-delete child media rows.
        val values = ContentValues().apply {
            put("id", entry.id); put("day", entry.day); put("created_at", entry.createdAt)
            put("updated_at", entry.updatedAt); put("markdown", entry.markdown)
        }
        if (db.update("entries", values, "id=?", arrayOf(entry.id)) == 0) db.insertOrThrow("entries", null, values)
        db.delete("photos", "entry_id=?", arrayOf(entry.id))
        entry.photos.forEachIndexed { position, photo ->
            db.insertOrThrow("photos", null, ContentValues().apply {
                put("id", photo.id); put("entry_id", entry.id); put("position", position)
                put("extension", photo.extension); put("mime", photo.mimeType); put("width", photo.width)
                put("height", photo.height); put("sha256", photo.sha256)
            })
        }
    }

    private fun collectUnusedMedia() {
        val used = (entries().flatMap { it.photos } + draft()?.photos.orEmpty()).map { it.filename }.toSet()
        media.listFiles()?.filter { it.name !in used && !it.name.endsWith(".part") }?.forEach { it.delete() }
    }

    private fun setting(key: String): String? = readText("settings", "value", "key", key)

    /** Read bounded byte slices, then decode together; this also preserves embedded NULs. */
    private fun readText(table: String, column: String, keyColumn: String, key: String): String? {
        val size = 64 * 1024
        val result = ByteArrayOutputStream()
        var start = 1
        while (true) {
            val part = db.rawQuery("SELECT substr(CAST($column AS BLOB), ?, ?) FROM $table WHERE $keyColumn=?", arrayOf(start.toString(), size.toString(), key)).use {
                if (it.moveToFirst()) it.getBlob(0) else null
            } ?: return null
            if (part.isEmpty()) return result.toString(Charsets.UTF_8.name())
            result.write(part)
            start += size
        }
    }
    private fun setSetting(key: String, value: String) {
        db.insertWithOnConflict("settings", null, ContentValues().apply { put("key", key); put("value", value) }, SQLiteDatabase.CONFLICT_REPLACE)
    }
    private inline fun <T> transaction(block: () -> T): T {
        db.beginTransaction()
        try { val result = block(); db.setTransactionSuccessful(); return result } finally { db.endTransaction() }
    }

    private class Database(context: Context) : SQLiteOpenHelper(context, "hibi.db", null, 1) {
        override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true); db.enableWriteAheadLogging() }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE entries(id TEXT PRIMARY KEY, day TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, markdown TEXT NOT NULL)")
            db.execSQL("CREATE INDEX entries_day ON entries(day,created_at)")
            db.execSQL("CREATE TABLE photos(id TEXT PRIMARY KEY, entry_id TEXT NOT NULL REFERENCES entries(id) ON DELETE CASCADE, position INTEGER NOT NULL, extension TEXT NOT NULL, mime TEXT NOT NULL, width INTEGER NOT NULL, height INTEGER NOT NULL, sha256 TEXT NOT NULL)")
            db.execSQL("CREATE INDEX photos_entry ON photos(entry_id,position)")
            db.execSQL("CREATE TABLE settings(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { error("Keine Migration von $oldVersion nach $newVersion verfügbar") }
    }
}

data class ImageInfo(val width: Int, val height: Int, val mime: String)
fun imageInfo(file: File): ImageInfo {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Bild ist beschädigt oder nicht lesbar" }
    val orientation = runCatching { ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val rotated = orientation in setOf(ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_TRANSPOSE, ExifInterface.ORIENTATION_TRANSVERSE)
    val mime = when (bounds.outMimeType) { "image/heif", "image/x-heif" -> "image/heic"; else -> bounds.outMimeType ?: error("Unbekannter Bildtyp") }
    return ImageInfo(if (rotated) bounds.outHeight else bounds.outWidth, if (rotated) bounds.outWidth else bounds.outHeight, mime)
}
