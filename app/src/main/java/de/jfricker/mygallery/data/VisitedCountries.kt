package de.jfricker.mygallery.data

/**
 * Regeln für die Länderliste (übernommen aus dem activityViewer).
 * Codes = ISO-3166 Alpha-2, wie sie CountryLookup liefert (z.B. "PT").
 */
object VisitedCountries {
    /** Länder, die ohne (GPS-)Bilder als besucht gelten. GB = ganzes Vereinigtes Königreich (England). */
    val MANUAL = setOf(
        "CA", // Kanada
        "US", // USA
        "MX", // Mexiko
        "TN", // Tunesien
        "MA", // Marokko
        "GB", // England
        "IE", // Irland
        "NL", // Holland
        "BE", // Belgien
        "AD", // Andorra
        "HU", // Ungarn
        "DK", // Dänemark
        "SE", // Schweden
        "CU", // Kuba
        "EC", // Ecuador
        "BO", // Bolivien
        "PE", // Peru
        "FI", // Finnland
        "DM", // Dominica
        "MC", // Monaco
        "VA", // Vatikanstadt
        "LU", // Luxemburg
    )

    /**
     * Länder, die trotz Bildern nicht als besucht gelten (Bilder stammen nicht von einer eigenen Reise).
     * (Früher Indien: das waren Bilder aus Bhadrapur/Nepal, jetzt über OVERRIDES richtig zugeordnet.)
     */
    val EXCLUDED = emptySet<String>()

    /** Gebiet, in dem das Land fest vorgegeben ist (Grenzdaten dort zu ungenau). */
    class Override(val minLat: Double, val maxLat: Double, val minLon: Double, val maxLon: Double, val country: String) {
        fun contains(lat: Double, lon: Double) = lat in minLat..maxLat && lon in minLon..maxLon
    }

    val OVERRIDES = listOf(
        // Bhadrapur (Ostnepal, Grenzfluss Mechi): die Grenzdaten legen den Ort nach Indien
        Override(26.50, 26.60, 88.05, 88.115, "NP"),
    )
}
