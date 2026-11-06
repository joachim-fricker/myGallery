package de.jfricker.mygallery.data

import java.io.File

/** Schlüssel eines Bildes für die Favoriten: Pfad + Dateiname (bleibt auch bei neuer MediaStore-ID gleich). */
val MediaImage.favKey: String get() = relativePath + name

/** Favoriten nur in myGallery gespeichert (files/favorites.txt, eine Zeile pro Bild). */
class FavoritesStore(private val file: File) {
    fun load(): Set<String> = try {
        if (file.exists()) file.readLines().filter { it.isNotBlank() }.toSet() else emptySet()
    } catch (_: Exception) {
        emptySet()
    }

    fun save(keys: Set<String>) {
        try {
            val tmp = File(file.path + ".tmp")
            tmp.writeText(keys.joinToString("\n"))
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        } catch (_: Exception) {
        }
    }
}
