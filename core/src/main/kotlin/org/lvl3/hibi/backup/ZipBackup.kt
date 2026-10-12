package org.lvl3.hibi.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import net.lingala.zip4j.ZipFile
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

/** Portable, versioned ZIP format. No Android or UI dependencies. */
class ZipBackup {
    fun export(
        output: File, entries: List<Entry>, preferences: Preferences,
        mediaFile: (Photo) -> File, password: CharArray? = null,
        now: Long = System.currentTimeMillis(),
    ) {
        require(!output.exists()) { "Zieldatei existiert bereits" }
        entries.forEach(::validate)
        require(entries.map { it.id }.distinct().size == entries.size) { "Doppelte Einträge" }
        val records = entries.map { entry ->
            val base = "days/${entry.day}/${entry.id}"
            BackupEntry(entry.id, entry.day, entry.createdAt, entry.updatedAt,
                "$base/entry.md", sha256(entry.markdown.toByteArray()),
                entry.photos.map { BackupPhoto(it, "$base/images/${it.filename}") })
        }
        val manifest = BackupManifest(exportedAt = now, newestFirst = preferences.newestFirst,
            pictureFormat = preferences.pictureFormat, reminderPeriod = preferences.reminderPeriod, entries = records)
        val encrypted = password != null && password.isNotEmpty()
        try {
            ZipFile(output, password ?: charArrayOf()).use { zip ->
                fun add(name: String, stream: InputStream, image: Boolean = false) {
                    val parameters = ZipParameters().apply {
                        fileNameInZip = name
                        compressionMethod = if (image) CompressionMethod.STORE else CompressionMethod.DEFLATE
                        isEncryptFiles = encrypted
                        if (encrypted) { encryptionMethod = EncryptionMethod.AES; aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256 }
                    }
                    stream.use { zip.addStream(it, parameters) }
                }
                add("manifest.json", ByteArrayInputStream(diaryJson.encodeToString(manifest).toByteArray()))
                entries.zip(records).forEach { (entry, record) ->
                    add(record.markdownFile, ByteArrayInputStream(entry.markdown.toByteArray()))
                    record.photos.forEach { item ->
                        val source = mediaFile(item.photo)
                        require(source.isFile && sha256(source) == item.photo.sha256) { "Ein Originalbild fehlt oder ist beschädigt" }
                        add(item.file, source.inputStream(), true)
                    }
                }
            }
        } catch (error: Exception) { output.delete(); throw error }
    }

    /** Read every byte and verify every digest BEFORE the repository may apply anything. */
    fun prepare(archive: File, stagingParent: File, password: CharArray? = null): PreparedImport {
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
                require(files.size == headers.count { !it.isDirectory }) { "Doppelte Dateinamen im ZIP" }
                require(headers.sumOf { it.uncompressedSize } <= MAX_TOTAL_BYTES) { "Backup ist zu groß" }
                val manifestHeader = files["manifest.json"] ?: error("Hibi-Manifest fehlt")
                val manifestBytes = zip.getInputStream(manifestHeader).use { readLimited(it, 8 * 1024 * 1024) }
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
                                }
                            }
                        }
                        require(sha256(destination) == item.photo.sha256) { "Bilddatei ist beschädigt" }
                        media[item.photo.id] = destination
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
