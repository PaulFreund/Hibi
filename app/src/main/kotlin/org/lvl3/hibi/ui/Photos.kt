package org.lvl3.hibi.ui

import android.graphics.BitmapFactory
import android.graphics.Matrix
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
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.lvl3.hibi.model.Photo
import org.lvl3.hibi.model.PictureFormat
import java.io.File
import kotlin.math.max

@Composable
fun PhotoImage(file: File, photo: Photo, modifier: Modifier, scale: ContentScale = ContentScale.Crop, description: String) {
    val bitmap by produceState<ImageBitmap?>(null, photo.id, photo.sha256) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                var sample = 1
                while (max(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
                val original = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return@runCatching null
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
                if (matrix.isIdentity) original.asImageBitmap()
                else android.graphics.Bitmap.createBitmap(original, 0, 0, original.width, original.height, matrix, true).also { if (it != original) original.recycle() }.asImageBitmap()
            }.getOrNull()
        }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap!!, description, Modifier.fillMaxSize(), contentScale = scale)
        else Text("Bild wird geladen", style = MaterialTheme.typography.bodyMedium)
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
