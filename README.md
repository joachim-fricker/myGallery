# myGallery

Eigene Android-Galerie (Kotlin, Jetpack Compose, minSdk 36).

## Bildquellen
Nur Bilder aus `DCIM/Camera/` und `Data/images/` (inkl. Unterordner) – anpassbar in
`data/MediaRepository.kt` (`FOLDERS`).

## Ansichten
| Tab | Inhalt |
|---|---|
| **Alle** | Alle Bilder, neueste zuoberst, mit Monats-Überschriften. Datum = EXIF-Aufnahmedatum, sonst Datum aus Ordnername (z.B. 2015_Zinnen, 2015-07_Nepal, 20150712_Ausflug), sonst MediaStore DATE_TAKEN, sonst Hinzugefügt-Datum |
| **Favoriten** | Herz im Vollbild antippen. Album „Favoriten“ ganz oben im Tab Alben; dort Mehrfachauswahl zum Entfernen. Kleines Herz auf Vorschaubildern. Gespeichert nur in myGallery (`files/favorites.txt`, Schlüssel = Pfad + Dateiname) |
| **Alben** | Pro Jahr (aufklappbar) alle Alben/Ordner dieses Jahres. Ordner-Jahr = Jahr am Anfang des Ordnernamens/Pfads (z.B. 2015_Zinnen), sonst Jahr der meisten Aufnahmen; Kamera-Ordner wird pro Jahr als Album „Kamera“ eingeordnet |
| **Personen** | Gesichter werden auf dem Gerät erkannt (ML Kit) und per Gesichtsmodell (TFLite) zu Personen gruppiert. Namen vergeben, Personen zusammenführen; in einer Person: langer Druck startet Mehrfachauswahl -> Titelbild (1 Bild), anderer/neuer Person zuordnen, entfernen. Ansicht nach Gruppen: „Haupt“ immer sichtbar, weitere Gruppen (Alcina Familie, Ulli, VB, andere, eigene) aufklappbar, dann „Ohne Namen“ (aufklappbar). Reihenfolge innerhalb einer Gruppe per lange drücken + ziehen; Gruppe einer Person im Menü ⋮; „Gruppen bearbeiten“ (Knopf oben oder langer Druck auf eine Gruppen-Überschrift): Reihenfolge per Drag & Drop am Griff ≡, umbenennen, löschen, neu anlegen |
| **Personen suchen** | Lupe im Tab Personen oder in einer Person ⋮ → „Mit weiteren Personen …“. Personen antippen (gruppiert, Zahl = Treffer mit dieser Person dazu, ausgegraut = nie zusammen auf einem Bild) -> Bilder, auf denen alle ausgewählten Personen sind. Schalter „Nur diese Personen“: keine weiteren benannten Personen auf dem Bild. Zusätzlich einschränkbar auf **Land** und **Jahr** (Auswahlmenüs zeigen nur Länder/Jahre mit Treffern, mit Anzahl). Es zählen bestätigte + automatische Zuordnungen |
| **Länder** | Umschalter **Liste / Karte**. Liste: Flagge, Name, Anzahl Bilder, Zeitraum (erstes – letztes Bild), Suchfeld, Sortierung A–Z oder nach Bildern. Karte: Weltkarte, besuchte Länder rot; Tipp auf ein Land zeigt Infos + „Bilder“. Besucht = Land aus GPS-EXIF oder manuell eingetragen; Regeln in `data/VisitedCountries.kt` (MANUAL = ohne Bilder besucht, EXCLUDED = Bilder zählen nicht als besucht, OVERRIDES = Gebiete mit fest vorgegebenem Land, z.B. Bhadrapur → Nepal) |
| **Karte** | OpenStreetMap (osmdroid), Bilder als Cluster mit Vorschaubild + Anzahl. Tipp auf Cluster oder „Bilder im Ausschnitt“ öffnet die Auswahl |

Vollbild: Wischen, Pinch-Zoom, Doppeltipp, Teilen, Details (ⓘ: kleine Karte ganz oben -> Tipp öffnet die grosse Karte am Aufnahmeort, Speicherort, alle Datumsquellen, EXIF, GPS, Land, erkannte Personen mit „Zuordnen …“ inkl. neue Person erstellen).

App-Icon: Polaroid mit Bergen/Sonne und „JF“ (res/drawable/ic_launcher_*.xml, inkl. monochrome für Themed Icons).

## Länder (offline)
GPS-Daten und Aufnahmedatum werden einmal pro Bild aus EXIF gelesen und in `files/locations.tsv` gecacht.
Ländergrenzen: Natural Earth 1:10 Mio. (Public Domain) für die Zuordnung; zum Zeichnen der Länderkarte
`app/src/main/assets/countries_map.geojson` (Natural Earth 1:50 Mio., id = ISO-Alpha-2, deutscher Name; aus dem activityViewer übernommen). Die App lädt sie beim ersten Bedarf
einmalig herunter (~25 MB) und arbeitet danach offline. Für eine komplett offline APK die Datei
hier herunterladen und als `app/src/main/assets/countries_10m.geojson` ablegen:
https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/ne_10m_admin_0_countries.geojson

## Personen-Erkennung
Komplett offline auf dem Handy:
1. ML Kit findet Gesichter + 5 Merkmalspunkte; zu kleine (< 56 px), zu seitliche oder unvollständige Gesichter werden ignoriert
2. 5-Punkt-Ausrichtung auf die ArcFace-Vorlage (112x112)
3. Gesichtsmodell: `app/src/main/assets/face_model.onnx` (InsightFace w600k_mbf, empfohlen) oder `face_model.tflite` (MobileFaceNet)
4. Zuordnung über **bestätigte Beispiele** jeder benannten Person (k-nächste-Nachbarn):
   sicher -> automatisch, unsicher -> **Vorschlag** („Ist das …?“ zum Prüfen), sonst -> Gruppe „Ohne Namen“.
   Unscharfe Gesichter werden nie automatisch zugeordnet. Schwellen je Modell in `ModelKind` (data/Faces.kt).
5. Jede Bestätigung/Ablehnung verbessert die Vorschläge (Neuberechnung im Hintergrund).

Prüfen: in einer Person Banner „X Vorschläge prüfen“ bzw. Menü ⋮ → „Automatische prüfen“ (unsicherste zuerst).
Raster mit Gesichtsausschnitten: Falsche antippen, dann „bestätigen“ – 40 pro Runde. Lange drücken zeigt das Foto.

Modellwechsel = Neu-Scan; Namen und bisherige Zuordnungen werden übernommen (gleiches Bild, gleiche Stelle).
Daten in `files/faces/` (faces.bin, faces_done.tsv, persons.tsv, faces_blocked.tsv, persons_cover.tsv, groups.tsv, migration.tsv, model.txt).

## Berechtigungen
`READ_MEDIA_IMAGES`, `ACCESS_MEDIA_LOCATION` (sonst entfernt Android die GPS-Daten), `INTERNET` (Kartenkacheln).
Bei „Nur ausgewählte Fotos erlauben“ startet die App nicht – bitte „Alle erlauben“ wählen.

## Code-Struktur
```
data/   MediaRepository (MediaStore-Abfrage), LocationStore (EXIF-GPS + Cache),
        CountryLookup (GeoJSON-Parser + Punkt-in-Polygon), Grouping (Alben, Länder, Monate)
ui/     GalleryApp (Tabs + Navigation), ImageGrid, AlbumsScreen, CountriesScreen,
        MapScreen (osmdroid + Cluster), ViewerScreen, Images (Thumbnail-Cache)
GalleryViewModel  lädt Bilder, scannt GPS/Länder im Hintergrund
```
