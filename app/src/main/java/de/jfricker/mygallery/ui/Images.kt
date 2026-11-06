package de.jfricker.mygallery.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import de.jfricker.mygallery.data.MediaImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.math.max

/** Thumbnails über den MediaStore-Thumbnail-Cache (schnell), im Speicher per LRU gehalten. */
object ThumbCache {
    private const val SIZE = 320
    private val semaphore = Semaphore(4)
    private val cache = object : LruCache<Long, Bitmap>((Runtime.getRuntime().maxMemory() / 8 / 1024).toInt()) {
        override fun sizeOf(key: Long, value: Bitmap): Int = value.allocationByteCount / 1024
    }

    fun peek(id: Long): Bitmap? = cache.get(id)

    suspend fun load(context: Context, uri: Uri, id: Long): Bitmap? {
        cache.get(id)?.let { return it }
        return semaphore.withPermit {
            withContext(Dispatchers.IO) {
                cache.get(id) ?: try {
                    context.contentResolver.loadThumbnail(uri, android.util.Size(SIZE, SIZE), null)
                        .also { cache.put(id, it) }
                } catch (_: Exception) {
                    null
                }
            }
        }
    }
}

object FullImageLoader {
    /** Dekodiert das Bild in max. [maxDim] px Kantenlänge (EXIF-Drehung wird berücksichtigt). */
    suspend fun load(context: Context, uri: Uri, maxDim: Int = 2560): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val w = info.size.width
                val h = info.size.height
                val m = max(w, h)
                if (m > maxDim) {
                    val f = maxDim.toFloat() / m
                    decoder.setTargetSize(max(1, (w * f).toInt()), max(1, (h * f).toInt()))
                }
            }
        } catch (_: Exception) {
            null
        }
    }
}

@Composable
fun Thumbnail(image: MediaImage, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap by produceState(ThumbCache.peek(image.id), image.id) {
        if (value == null) value = ThumbCache.load(context, image.uri, image.id)
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        bitmap?.let { bmp ->
            val img = remember(bmp) { bmp.asImageBitmap() }
            Image(img, contentDescription = image.name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    }
}
