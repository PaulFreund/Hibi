package org.lvl3.hibi.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

val diaryJson = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }

@Serializable
data class Photo(
    val id: String,
    val extension: String,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val sha256: String,
) {
    val filename: String get() = "$id.$extension"
}

@Serializable
data class Entry(
    val id: String = UUID.randomUUID().toString(),
    val day: String = LocalDate.now().toString(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val markdown: String = "",
    val photos: List<Photo> = emptyList(),
)

@Serializable enum class PictureFormat { SQUARE, ORIGINAL }
@Serializable enum class ReminderPeriod { OFF, WEEK, MONTH }

@Serializable
data class Preferences(
    val initialized: Boolean = false,
    val newestFirst: Boolean = false,
    val pictureFormat: PictureFormat = PictureFormat.SQUARE,
    val reminderPeriod: ReminderPeriod = ReminderPeriod.WEEK,
    val nextReminderAt: Long = nextReminder(System.currentTimeMillis(), ReminderPeriod.WEEK),
    val lastExportAt: Long? = null,
)

fun nextReminder(from: Long, period: ReminderPeriod, zone: ZoneId = ZoneId.systemDefault()): Long {
    val date = Instant.ofEpochMilli(from).atZone(zone)
    return when (period) {
        ReminderPeriod.OFF -> Long.MAX_VALUE
        ReminderPeriod.WEEK -> date.plusWeeks(1).toInstant().toEpochMilli()
        ReminderPeriod.MONTH -> date.plusMonths(1).toInstant().toEpochMilli()
    }
}

fun reminderDue(preferences: Preferences, now: Long): Boolean =
    preferences.initialized && preferences.reminderPeriod != ReminderPeriod.OFF && now >= preferences.nextReminderAt

fun validate(entry: Entry) {
    require(isId(entry.id)) { "Ungültige Eintrags-ID" }
    val date = runCatching { LocalDate.parse(entry.day) }.getOrNull()
    require(entry.day.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")) && date != null && date.year in 1..9999 && date.toString() == entry.day) { "Ungültiges Datum" }
    require(entry.createdAt >= 0 && entry.updatedAt >= entry.createdAt) { "Ungültige Zeitangabe" }
    require(entry.markdown.toByteArray(Charsets.UTF_8).size <= 2 * 1024 * 1024) { "Text ist zu groß" }
    require(entry.markdown.isNotBlank() || entry.photos.isNotEmpty()) { "Eintrag ist leer" }
    require(entry.photos.map { it.id }.distinct().size == entry.photos.size) { "Doppelte Bild-ID" }
    entry.photos.forEach {
        require(isId(it.id) && it.extension in imageExtensions) { "Ungültige Bilddatei" }
        require(it.mimeType in imageExtensions.getValue(it.extension)) { "Ungültiger Bildtyp" }
        require(it.width in 1..100000 && it.height in 1..100000) { "Ungültige Bildgröße" }
        require(it.sha256.matches(Regex("[0-9a-f]{64}"))) { "Ungültige Prüfsumme" }
    }
}

val imageExtensions = mapOf(
    "jpg" to setOf("image/jpeg"), "png" to setOf("image/png"),
    "webp" to setOf("image/webp"), "gif" to setOf("image/gif"),
    "heic" to setOf("image/heic", "image/heif"), "avif" to setOf("image/avif"),
)

fun isId(value: String): Boolean = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { stream ->
        val buffer = ByteArray(64 * 1024)
        while (true) { val size = stream.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/** Stable IDs make repeated imports idempotent. Divergent local entries always win. */
data class MergePlan(val additions: List<Entry>, val identical: Int, val conflicts: Int)
fun planMerge(local: List<Entry>, incoming: List<Entry>): MergePlan {
    val byId = local.associateBy { it.id }
    return MergePlan(
        incoming.filter { it.id !in byId },
        incoming.count { byId[it.id] == it },
        incoming.count { it.id in byId && byId[it.id] != it },
    )
}

interface DiaryRepository {
    fun entries(): List<Entry>
    fun preferences(): Preferences
    fun save(entry: Entry)
    fun delete(id: String)
    fun setPreferences(value: Preferences)
    fun mediaFile(photo: Photo): File
}
