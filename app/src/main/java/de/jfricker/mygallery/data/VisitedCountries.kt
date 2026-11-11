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
    )

    /** Länder, die trotz Bildern nicht als besucht gelten (Bilder stammen nicht von einer eigenen Reise). */
    val EXCLUDED = setOf(
        "IN", // Indien
    )
}
