package org.lvl3.hibi.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.io.outputstream.ZipOutputStream
import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.lvl3.hibi.model.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files

@Serializable
data class BackupManifest(
    val format: String = "hibi",
    val version: Int = 1,
    val exportedAt: Long,
    val newestFirst: Boolean,
    val pictureFormat: PictureFormat,
    val reminderPeriod: ReminderPeriod,
    val entries: List<BackupEntry>,
)

@Serializable
data class BackupEntry(
    val id: String, val day: String, val createdAt: Long, val updatedAt: Long,
    val markdownFile: String, val markdownSha256: String, val photos: List<BackupPhoto>,
)
@Serializable data class BackupPhoto(val photo: Photo, val file: String)

class PreparedImport(
    val directory: File, val manifest: BackupManifest, val entries: List<Entry>,
    val media: Map<String, File>,
) : AutoCloseable {
    override fun close() { directory.deleteRecursively() }
}

data class ArchiveProgress(val completedFiles: Int, val totalFiles: Int)

/** Portable, versioned ZIP format. No Android or UI dependencies. */
class ZipBackup {
    fun export(
        output: File, entries: List<Entry>, preferences: Preferences,
        mediaFile: (Photo) -> File, password: CharArray? = null,
        now: Long = System.currentTimeMillis(),
        progress: (ArchiveProgress) -> Unit = {},
    ) {
        require(!output.exists()) { "Zieldatei existiert bereits" }
        try {
            output.outputStream().use { export(it, entries, preferences, mediaFile, password, now, progress) }
        } catch (error: Exception) { output.delete(); throw error }
    }

    /** One central directory, including AES; suitable for a SAF stream without a local ZIP copy. */
    fun export(
        output: OutputStream, entries: List<Entry>, preferences: Preferences,
        mediaFile: (Photo) -> File, password: CharArray? = null,
        now: Long = System.currentTimeMillis(),
        progress: (ArchiveProgress) -> Unit = {},
    ) {
        entries.forEach(::validate)
        require(entries.size <= 20000) { "Zu viele Einträge für ein Backup" }
        require(entries.map { it.id }.distinct().size == entries.size) { "Doppelte Einträge" }
        val totalFiles = 1 + entries.sumOf { 1 + it.photos.size }
        val photoIds = entries.flatMap { it.photos }.map { it.id }
        require(photoIds.distinct().size == photoIds.size && totalFiles <= 100000) { "Zu viele oder doppelte Bilddateien" }
        progress(ArchiveProgress(0, totalFiles))
        val problems = mutableListOf<String>()
        val buffer = ByteArray(64 * 1024)
        // Fail before creating archive entries; never silently omit diary originals.
        entries.forEach { entry -> entry.photos.forEachIndexed { index, photo ->
            val source = mediaFile(photo)
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val readable = runCatching {
                require(source.isFile && source.length() <= MAX_FILE_BYTES)
                source.inputStream().use { input ->
                    while (true) {
                        val size = input.read(buffer); if (size < 0) break
                        digest.update(buffer, 0, size); progress(ArchiveProgress(0, totalFiles))
                    }
                }
                digest.digest().joinToString("") { "%02x".format(it) } == photo.sha256
            }.getOrElse { error ->
                if (error is java.util.concurrent.CancellationException) throw error
                false
            }
            if (!readable) problems += "${entry.day}, Bild ${index + 1} (${photo.id.take(8)})"
        } }
        require(problems.isEmpty()) {
            "Originalbilder fehlen oder sind beschädigt: ${problems.take(8).joinToString("; ")}" +
                if (problems.size > 8) " · und ${problems.size - 8} weitere" else ""
        }
        val records = entries.map { entry ->
            val base = "days/${entry.day}/${entry.id}"
            BackupEntry(entry.id, entry.day, entry.createdAt, entry.updatedAt,
                "$base/entry.md", sha256(entry.markdown.toByteArray()),
                entry.photos.map { BackupPhoto(it, "$base/images/${it.filename}") })
        }
        val manifest = BackupManifest(exportedAt = now, newestFirst = preferences.newestFirst,
            pictureFormat = preferences.pictureFormat, reminderPeriod = preferences.reminderPeriod, entries = records)
        val manifestBytes = diaryJson.encodeToString(manifest).toByteArray()
        require(manifestBytes.size <= 8 * 1024 * 1024) { "Backup-Verzeichnis ist zu groß" }
        require(manifestBytes.size.toLong() + entries.sumOf { it.markdown.toByteArray().size.toLong() + it.photos.sumOf { photo -> mediaFile(photo).length() } } <= MAX_TOTAL_BYTES) { "Backup ist zu groß" }
        val encrypted = password != null && password.isNotEmpty()
        var completed = 0
        // The caller owns the SAF/file stream and observes its final close exactly once.
        val nonClosing = object : OutputStream() {
            override fun write(value: Int) { output.write(value) }
            override fun write(bytes: ByteArray, offset: Int, length: Int) { output.write(bytes, offset, length) }
            override fun flush() { output.flush() }
            override fun close() { flush() }
        }
        ZipOutputStream(nonClosing, password ?: charArrayOf()).use { zip ->
                fun add(name: String, stream: InputStream, image: Boolean = false, size: Long = 0, expectedHash: String? = null) {
                    val parameters = ZipParameters().apply {
                        fileNameInZip = name
                        compressionMethod = if (image) CompressionMethod.STORE else CompressionMethod.DEFLATE
                        if (image) entrySize = size
                        isEncryptFiles = encrypted
                        if (encrypted) { encryptionMethod = EncryptionMethod.AES; aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256 }
                    }
                    zip.putNextEntry(parameters)
                    val digest = expectedHash?.let { java.security.MessageDigest.getInstance("SHA-256") }
                    stream.use { input ->
                        while (true) {
                            val size = input.read(buffer); if (size < 0) break
                            // Zip4j AES may encrypt the caller's buffer in place.
                            digest?.update(buffer, 0, size); zip.write(buffer, 0, size)
                            progress(ArchiveProgress(completed, totalFiles))
                        }
                    }
                    zip.closeEntry()
                    require(digest == null || digest.digest().joinToString("") { "%02x".format(it) } == expectedHash) { "Ein Originalbild hat sich während des Exports geändert: $name" }
                    progress(ArchiveProgress(++completed, totalFiles))
                }
                add("manifest.json", ByteArrayInputStream(manifestBytes))
                entries.zip(records).forEach { (entry, record) ->
                    add(record.markdownFile, ByteArrayInputStream(entry.markdown.toByteArray()))
                    record.photos.forEach { item ->
                        val source = mediaFile(item.photo)
                        add(item.file, source.inputStream(), true, source.length(), item.photo.sha256)
                    }
                }
        }
    }

    /** Read every byte and verify every digest BEFORE the repository may apply anything. */
    fun prepare(archive: File, stagingParent: File, password: CharArray? = null, progress: (ArchiveProgress) -> Unit = {}): PreparedImport {
        stagingParent.mkdirs()
        val stage = Files.createTempDirectory(stagingParent.toPath(), "hibi-import-").toFile()
        try {
            ZipFile(archive, password ?: charArrayOf()).use { zip ->
                require(zip.isValidZipFile) { "Kein gültiges ZIP-Archiv" }
                require(!zip.isEncrypted || (password != null && password.isNotEmpty())) { "Dieses Backup benötigt ein Passwort" }
                val headers = zip.fileHeaders
                require(headers.size <= 100000) { "Backup enthält zu viele Dateien" }
                headers.forEach { header ->
                    val name = header.fileName
                    require(name.isNotEmpty() && !name.startsWith('/') && !name.contains('\\') && !name.contains(':') && !name.contains('\u0000') && name.split('/').none { it == ".." || it == "." }) { "Unsicherer Dateipfad im ZIP" }
                    val attributes = header.externalFileAttributes
                    val mode = if (attributes != null && attributes.size >= 4) ((attributes[3].toInt() and 255) shl 8) or (attributes[2].toInt() and 255) else 0
                    require(mode and 0xF000 != 0xA000) { "Verknüpfungen sind im Backup nicht erlaubt" }
                    require(header.uncompressedSize in 0..MAX_FILE_BYTES) { "Eine Datei ist zu groß" }
                }
                val files = headers.filterNot { it.isDirectory }.associateBy { it.fileName }
                var completed = 0
                progress(ArchiveProgress(completed, files.size))
                require(files.size == headers.count { !it.isDirectory }) { "Doppelte Dateinamen im ZIP" }
                require(headers.sumOf { it.uncompressedSize } <= MAX_TOTAL_BYTES) { "Backup ist zu groß" }
                val manifestHeader = files["manifest.json"] ?: error("Hibi-Manifest fehlt")
                val manifestBytes = zip.getInputStream(manifestHeader).use { readLimited(it, 8 * 1024 * 1024) }
                progress(ArchiveProgress(++completed, files.size))
                val manifest = diaryJson.decodeFromString<BackupManifest>(manifestBytes.toString(Charsets.UTF_8))
                require(manifest.format == "hibi" && manifest.version == 1) { "Nicht unterstütztes Backup-Format" }
                require(manifest.entries.size <= 20000 && manifest.exportedAt >= 0) { "Ungültiges Backup" }
                require(manifest.entries.map { it.id }.distinct().size == manifest.entries.size) { "Doppelte Eintrags-IDs" }
                val expected = mutableSetOf("manifest.json")
                val photoIds = mutableSetOf<String>()
                val media = mutableMapOf<String, File>()
                var actualTotal = manifestBytes.size.toLong()
                val entries = manifest.entries.map { record ->
                    require(isId(record.id)) { "Ungültige Eintrags-ID" }
                    val base = "days/${record.day}/${record.id}"
                    require(record.markdownFile == "$base/entry.md") { "Ungültiger Textpfad" }
                    expected += record.markdownFile
                    val textHeader = files[record.markdownFile] ?: error("Textdatei fehlt")
                    val text = zip.getInputStream(textHeader).use { readLimited(it, 2 * 1024 * 1024) }
                    progress(ArchiveProgress(++completed, files.size))
                    actualTotal += text.size
                    require(sha256(text) == record.markdownSha256) { "Textdatei ist beschädigt" }
                    val entry = Entry(record.id, record.day, record.createdAt, record.updatedAt,
                        text.toString(Charsets.UTF_8), record.photos.map { it.photo })
                    validate(entry)
                    record.photos.forEach { item ->
                        require(photoIds.add(item.photo.id)) { "Doppelte Bild-ID im Backup" }
                        require(item.file == "$base/images/${item.photo.filename}") { "Ungültiger Bildpfad" }
                        expected += item.file
                        val header = files[item.file] ?: error("Bilddatei fehlt")
                        val destination = File(stage, item.photo.filename)
                        zip.getInputStream(header).use { input ->
                            destination.outputStream().use { output ->
                                val buffer = ByteArray(64 * 1024)
                                var count = 0L
                                while (true) {
                                    val size = input.read(buffer); if (size < 0) break
                                    count += size; actualTotal += size
                                    require(count <= MAX_FILE_BYTES && actualTotal <= MAX_TOTAL_BYTES) { "Backup ist zu groß" }
                                     output.write(buffer, 0, size)
                                     progress(ArchiveProgress(completed, files.size))
                                }
                            }
                        }
                        require(sha256(destination) == item.photo.sha256) { "Bilddatei ist beschädigt" }
                        media[item.photo.id] = destination
                        progress(ArchiveProgress(++completed, files.size))
                    }
                    entry
                }
                require(files.keys == expected) { "ZIP enthält unerwartete oder fehlende Dateien" }
                return PreparedImport(stage, manifest, entries, media)
            }
        } catch (error: Exception) {
            stage.deleteRecursively()
            if (error is ZipException) throw IllegalArgumentException("ZIP lässt sich nicht öffnen. Prüfe Archiv und Passwort.", error)
            throw error
        }
    }

    private fun readLimited(input: InputStream, maximum: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val size = input.read(buffer); if (size < 0) break
            require(output.size() + size <= maximum) { "Datei ist zu groß" }
            output.write(buffer, 0, size)
        }
        return output.toByteArray()
    }

    companion object {
        const val MAX_FILE_BYTES = 512L * 1024 * 1024
        const val MAX_TOTAL_BYTES = 20L * 1024 * 1024 * 1024
    }
}
