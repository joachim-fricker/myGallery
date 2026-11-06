package de.jfricker.mygallery.data

import android.net.Uri

/**
 * Ein Bild aus dem MediaStore.
 * [date] = verwendetes Datum in ms: EXIF-Aufnahmedatum, sonst Datum aus Ordnername,
 * sonst MediaStore DATE_TAKEN, sonst Hinzugefügt-Datum. [dateSource] sagt, welches.
 */
data class MediaImage(
    val id: Long,
    val uri: Uri,
    val name: String,
    val relativePath: String,
    val date: Long,
    val dateModified: Long,
    val width: Int,
    val height: Int,
    /** Roh-Werte für die Detailansicht */
    val dateTaken: Long = 0L,
    val dateAdded: Long = 0L,
    val size: Long = 0L,
    val mimeType: String = "",
    val volume: String = "",
    /** EXIF-Aufnahmedatum (0 = keins / noch nicht gelesen) */
    val exifDate: Long = 0L,
    val dateSource: DateSource = DateSource.ADDED,
) {
    /** Ordnername = Albumname */
    val folderName: String get() = relativePath.trimEnd('/').substringAfterLast('/')
    val isCamera: Boolean get() = relativePath.startsWith("DCIM/Camera", ignoreCase = true)
}

enum class DateSource(val label: String) {
    EXIF("EXIF-Aufnahmedatum"),
    FOLDER("Ordnername (kein EXIF-Datum)"),
    MEDIASTORE("MediaStore DATE_TAKEN (kein EXIF-Datum)"),
    ADDED("Hinzugefügt-Datum (kein Aufnahmedatum vorhanden)"),
}
