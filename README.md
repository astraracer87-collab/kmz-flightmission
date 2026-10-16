# Mission Kitzrettung – Feldauswahl für die DJI RC

Android-App für die DJI RC Pro: zeigt alle KMZ-Flugplanungen (z. B. aus dem UAV Editor) eines Ordners als Felder auf einer Satellitenkarte. Felder antippen → **Download** → die Dateien landen im DJI-Missionsordner und können in DJI Pilot 2 importiert werden.

## Bedienung
- **Karte (Vollbild):** Feld oder Feldname antippen = markieren (grün) / abwählen (rot). Ein Finger verschiebt, zwei Finger zoomen.
- **Rechts:** Plus, Minus, alle Felder anzeigen, aktueller Standort.
- **Leiste unten:** Anzahl markiert · Alle abwählen · Feldsuche · Download · Einstellungen.
- **Einstellungen:** Quell- und Zielordner (Standard: `Download` → `DJI/Mission/Download`), Verschieben/Kopieren, Zielordner vorher leeren, Feldnamen, Satellit/Karte.

Beim ersten Start „Zugriff auf alle Dateien“ für *Mission Kitzrettung* erlauben.

## APK bekommen
Jeder Push auf `main` baut die App automatisch (GitHub Actions). Die fertige `Mission-Kitzrettung.apk` liegt danach unter **Releases → latest**.

Installation auf der RC: APK herunterladen (oder per USB/SD kopieren), im Dateimanager antippen, „Unbekannte Quellen“ erlauben. Updates lassen sich direkt drüber installieren.

## Aufbau
- `app/src/main/assets/index.html` – Oberfläche und Karte (Kacheln: Esri World Imagery / OpenStreetMap, Internet nötig)
- `app/src/main/java/.../MainActivity.java` – WebView + Dateizugriff (Lesen der KMZ, Kopieren/Verschieben, Ordnerwahl, GPS)
