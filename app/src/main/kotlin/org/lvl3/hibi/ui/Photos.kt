package org.lvl3.hibi.ui

import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.lvl3.hibi.model.Photo
import org.lvl3.hibi.model.PictureFormat
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

private object PhotoBitmaps {
    val decodes = Semaphore(2)
    private val maximum = (Runtime.getRuntime().maxMemory() / 16).coerceIn(8L * 1024 * 1024, 32L * 1024 * 1024).toInt()
    val cache = object : LruCache<String, Bitmap>(maximum) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
        // Eviction drops the cache reference; a visible Compose image may still own the bitmap.
    }
}

private data class PhotoLoad(val bitmap: ImageBitmap? = null, val failed: Boolean = false)

@Composable
fun PhotoImage(file: File, photo: Photo, modifier: Modifier, scale: ContentScale = ContentScale.Crop, description: String) {
    BoxWithConstraints(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        val width = maxWidth.value.takeIf { it.isFinite() } ?: 256f
        val height = maxHeight.value.takeIf { it.isFinite() } ?: width
        val pixels = (max(width, height) * LocalDensity.current.density).roundToInt().coerceIn(64, 2048)
        var bucket = 64; while (bucket < pixels) bucket *= 2
        val cacheKey = "${file.absolutePath}|${photo.sha256}|$bucket"
        val image by produceState(PhotoLoad(), cacheKey) {
          value = withContext(Dispatchers.IO) { PhotoBitmaps.decodes.withPermit {
            val cached = PhotoBitmaps.cache.get(cacheKey)
            if (cached != null) return@withPermit PhotoLoad(cached.asImageBitmap())
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                var sample = 1
                while (max(bounds.outWidth, bounds.outHeight) / sample > bucket) sample *= 2
                val original = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }) ?: error("Bild nicht lesbar")
                val orientation = runCatching { ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
                val matrix = Matrix().apply {
                    when (orientation) {
                        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                        ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                        ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                        ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                        ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                        ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
                        ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
                    }
                }
                val bitmap = if (matrix.isIdentity) original
                else Bitmap.createBitmap(original, 0, 0, original.width, original.height, matrix, true).also { if (it != original) original.recycle() }
                PhotoBitmaps.cache.put(cacheKey, bitmap)
                PhotoLoad(bitmap.asImageBitmap())
            }.getOrElse { PhotoLoad(failed = true) }
          } }
        }
        if (image.bitmap != null) Image(image.bitmap!!, description, Modifier.fillMaxSize(), contentScale = scale)
        else if (image.failed) Text("Bild auf diesem Gerät nicht lesbar", Modifier.padding(12.dp).semantics { contentDescription = "$description, hier nicht lesbar. Originaldatei bleibt erhalten." }, style = MaterialTheme.typography.bodySmall)
        else Box(Modifier.fillMaxSize().semantics { contentDescription = "$description wird geladen" })
    }
}

@Composable
fun PhotoCarousel(photos: List<Photo>, mediaFile: (Photo) -> File, format: PictureFormat, open: (() -> Unit)? = null) {
    if (photos.isEmpty()) return
    val pager = rememberPagerState(pageCount = { photos.size })
    val active = photos[pager.currentPage.coerceIn(0, photos.lastIndex)]
    // Extreme panoramas retain their entire image using Fit within practical viewport limits.
    val ratio = if (format == PictureFormat.SQUARE) 1f else (active.width.toFloat() / active.height).coerceIn(.25f, 4f)
    Box(Modifier.fillMaxWidth().aspectRatio(ratio)) {
        HorizontalPager(pager, Modifier.fillMaxSize(), key = { photos[it].id }) { index ->
            val photo = photos[index]
            PhotoImage(mediaFile(photo), photo,
                Modifier.fillMaxSize().then(if (open != null) Modifier.clickable(onClick = open) else Modifier),
                if (format == PictureFormat.SQUARE) ContentScale.Crop else ContentScale.Fit,
                "Foto ${index + 1} von ${photos.size}")
        }
        if (photos.size > 1) {
            Row(Modifier.align(Alignment.BottomCenter).padding(12.dp).background(MaterialTheme.colorScheme.surface.copy(alpha = .9f), CircleShape).padding(horizontal = 10.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                photos.forEachIndexed { index, _ ->
                    Box(Modifier.size(6.dp).background(if (index == pager.currentPage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape))
                }
            }
            Text("${pager.currentPage + 1}/${photos.size}", Modifier.align(Alignment.TopEnd).padding(12.dp).background(MaterialTheme.colorScheme.surface.copy(alpha = .9f), CircleShape).padding(horizontal = 9.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
        }
    }
}
