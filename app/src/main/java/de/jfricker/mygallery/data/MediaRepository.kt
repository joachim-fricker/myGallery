package de.jfricker.mygallery.data

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore

object MediaRepository {

    /** Nur Bilder aus diesen Ordnern (inkl. Unterordner) werden angezeigt. */
    val FOLDERS = listOf("DCIM/Camera/", "Data/images/")

    fun loadImages(context: Context): List<MediaImage> {
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.VOLUME_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.MIME_TYPE,
        )
        val selection = FOLDERS.joinToString(" OR ") { "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?" }
        val args = FOLDERS.map { "$it%" }.toTypedArray()

        val result = ArrayList<MediaImage>()
        context.contentResolver.query(collection, projection, selection, args, null)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val pathCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
            val takenCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val addedCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val modCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            val wCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
            val hCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
            val volCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.VOLUME_NAME)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val mimeCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val volume = c.getString(volCol) ?: MediaStore.VOLUME_EXTERNAL_PRIMARY
                val taken = if (c.isNull(takenCol)) 0L else c.getLong(takenCol)
                val added = c.getLong(addedCol) * 1000L
                result += MediaImage(
                    id = id,
                    uri = ContentUris.withAppendedId(MediaStore.Images.Media.getContentUri(volume), id),
                    name = c.getString(nameCol) ?: "",
                    relativePath = c.getString(pathCol) ?: "",
                    date = if (taken > 0) taken else added,
                    dateModified = c.getLong(modCol),
                    width = c.getInt(wCol),
                    height = c.getInt(hCol),
                    dateTaken = taken,
                    dateAdded = added,
                    size = c.getLong(sizeCol),
                    mimeType = c.getString(mimeCol) ?: "",
                    volume = volume,
                    dateSource = if (taken > 0) DateSource.MEDIASTORE else DateSource.ADDED,
                )
            }
        }
        // Neueste zuoberst
        result.sortByDescending { it.date }
        return result
    }
}
