package de.jfricker.mygallery.data

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Einmaliger Export der eigenen Daten für myGallery unter Windows.
 * Schreibt Download/myGallery_export_<datum>.zip mit:
 *  - images.tsv      MediaStore-ID -> Pfad (damit Windows faces.bin den Bildern zuordnen kann)
 *  - favorites.txt   Favoriten (Schlüssel = relativer Pfad + Dateiname)
 *  - faces/           Gesichter, Personen, Gruppen, Titelbilder, Ablehnungen, Modell
 *  - export.txt      Format-Version, Datum, Anzahl Bilder
 */
object WindowsExport {
    const val FORMAT = 1

    /** Liefert den Dateinamen im Download-Ordner. Aufrufer muss faceDb vorher gespeichert haben. */
    fun export(context: Context, images: List<MediaImage>): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.ROOT).format(Date())
        val fileName = "myGallery_export_$stamp.zip"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Download-Datei konnte nicht angelegt werden")
        try {
            resolver.openOutputStream(uri)!!.use { os ->
                ZipOutputStream(os.buffered()).use { zip ->
                    fun text(name: String, content: String) {
                        zip.putNextEntry(ZipEntry(name))
                        zip.write(content.toByteArray(Charsets.UTF_8))
                        zip.closeEntry()
                    }
                    fun file(name: String, f: File) {
                        if (!f.isFile) return
                        zip.putNextEntry(ZipEntry(name))
                        f.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                    text(
                        "export.txt",
                        "format\t$FORMAT\ncreated\t${System.currentTimeMillis()}\nimages\t${images.size}\n",
                    )
                    // id, relativePath, name, date(ms), exifDate(ms), dateTaken(ms), width, height, size
                    val sb = StringBuilder("id\trelativePath\tname\tdate\texifDate\tdateTaken\twidth\theight\tsize\n")
                    for (i in images) {
                        sb.append(i.id).append('\t').append(i.relativePath).append('\t').append(i.name).append('\t')
                            .append(i.date).append('\t').append(i.exifDate).append('\t').append(i.dateTaken).append('\t')
                            .append(i.width).append('\t').append(i.height).append('\t').append(i.size).append('\n')
                    }
                    text("images.tsv", sb.toString())
                    file("favorites.txt", File(context.filesDir, "favorites.txt"))
                    File(context.filesDir, "faces").listFiles()
                        ?.filter { it.isFile && !it.name.endsWith(".tmp") }
                        ?.forEach { file("faces/${it.name}", it) }
                }
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return fileName
    }
}
