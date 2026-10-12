# Hibi

Privates Android-Tagebuch für Bilder und Markdown. Android 8.0 (API 26) oder neuer; Installation als APK über ADB. Kein Konto und keine direkte Cloud-Anbindung erforderlich.

## Funktionen

- Feed mit Fotos zuerst, Datum und Textvorschau; mehrere Originalbilder als Carousel.
- Nur Text, nur Bilder oder beides; Beiträge bearbeiten, Datum ändern und löschen.
- Offline-WYSIWYG-Editor für Markdown mit Überschriften, Formatierung, Listen, Zitaten und Links.
- Lokale Entwürfe, Kalender mit markierten Tagen und Suche nach Text oder Datum.
- Neueste Einträge standardmäßig unten; umkehrbar. Fotos quadratisch oder im Quellformat.
- Vollständiger ZIP-Export mit optionaler AES-256-Verschlüsselung, Android-Teilen und Dateispeichern.
- Geprüfter ZIP-Import mit Vorschau. Stabile IDs verhindern Duplikate; abweichende lokale Fassungen bleiben erhalten.
- Wöchentliche oder monatliche Erinnerung beim Öffnen, bis zum nächsten Termin mit × schließbar; auch abschaltbar.

## Build und Installation

Voraussetzungen: JDK 21, Android-SDK mit Plattform 36 und Build Tools 36, Internet beim ersten Laden der Build-Abhängigkeiten. Gradle 8.13 wird durch den Wrapper geladen. `local.properties` enthält den lokalen SDK-Pfad und gehört nicht ins Git.

```powershell
.\gradlew.bat :core:test :app:assembleRelease :app:lintDebug
adb install -r .\app\build\outputs\apk\release\app-release.apk
```

Die erste APK ist mit dem **Entwicklungszertifikat dieses Hosts** signiert. Updates müssen mit demselben Schlüssel signiert werden, damit `adb install -r` die Daten erhält. Für einen eigenen Release-Schlüssel die Signing-Konfiguration anpassen; private Schlüssel niemals ins Repository aufnehmen. Debug-Builds verwenden `org.lvl3.hibi.debug`, Release-Builds `org.lvl3.hibi`.

```powershell
.\gradlew.bat :app:assembleDebugAndroidTest
.\gradlew.bat :app:connectedDebugAndroidTest
```

Speichertests verwenden eigene Datenbanken. Die UI-Tests verlangen einen Emulator und verwenden Testdaten im Debug-Paket. Sie prüfen Editor/Speichern, Feed, Kalender, Suche, Bildformat, Erinnerung und Export. Screenshots landen im externen App-Verzeichnis unter `verification/`. Das Foto in den Testassets stammt aus dem freigegebenen TASK-0030-Mockup; es wird nicht in der Release-App mitgeliefert.

## Code und Gestaltung

| Ort | Verantwortung |
| --- | --- |
| `core/model` | Stabile Eintrags-/Bildmodelle, Validierung, Importvergleich und Erinnerungszeitpunkte; unabhängig von Android und UI |
| `core/backup` | Versioniertes ZIP-Format, AES-Export, Prüfung und Staging des Imports |
| `app/data` | SQLite-Schema Version 1, Transaktionen, Entwürfe und unveränderte Originaldateien |
| `app/ui` | Compose-Oberfläche und ViewModel als Verbindung zur Datenhaltung |
| `app/src/main/res/values/colors.xml` | Zentrale Farbpalette, auch für Editor und App-Icon |
| `app/ui/theme/Theme.kt` | Schriftfamilien, Schriftgrößen, Abstände und Formen |

Gestaltungsänderungen benötigen keine Datenmigration. Änderungen am SQLite-Schema erfordern eine explizite Migration in `LocalDiary.Database`; Änderungen am Backup-Vertrag eine neue unterstützte Formatversion. Bestehende Daten werden nicht durch eine automatische Neuerstellung der Datenbank ersetzt.

## Backup-Verhalten

In den Einstellungen eine ZIP erstellen, optional ein Passwort vergeben und anschließend **Mit … teilen** oder **ZIP-Datei speichern** wählen. OneDrive, Google Drive oder eine andere Ziel-App übernimmt den Upload und ihre WLAN-/Mobilfunkeinstellungen. Hibi zeigt **Letzter Export** und bestätigt keinen Cloud-Upload. Nach einer Neuinstallation **ZIP-Backup importieren** wählen.

Ein Backup enthält veröffentlichte Einträge, Originalbilder und die Anzeige-/Erinnerungseinstellungen. Entwürfe sind nicht enthalten. Dateinamen und Ordnernamen bleiben auch bei AES-ZIPs lesbar; alle Dateiinhalte einschließlich Manifest werden verschlüsselt. Das Passwort wird nicht gespeichert und lässt sich nicht zurücksetzen. Desktop-Tools müssen AES-ZIPs unterstützen.

Beim Import werden alle Inhalte geprüft und erst nach der Vorschau neue Einträge in einer Datenbanktransaktion übernommen. Bereits vorhandene IDs mit anderem Inhalt bleiben lokal erhalten; eine automatische Zusammenführung von Textfassungen gibt es nicht. Der ausführliche Dateivertrag steht in [docs/backup-format.md](docs/backup-format.md).

Erinnerungen werden nach Export, × oder einer geänderten Häufigkeit auf den nächsten Zeitraum gesetzt. Ein Monat bedeutet einen Kalendermonat. Es gibt keine Hintergrund-Uploads oder Push-Erinnerungen. Änderungen seit der letzten extern gespeicherten ZIP sind bei Geräteverlust nicht wiederherstellbar.

Lokal erzeugte ZIPs bleiben zum Teilen verfügbar. Hibi behält mindestens die drei neuesten und entfernt weitere lokale Kopien erst nach sieben Tagen. Extern gespeicherte Kopien verwaltet die Ziel-App.

## Bibliotheken

Kotlin/Compose und kotlinx.serialization, Zip4j 2.11.6, Markwon 4.6.2 und TOAST UI Editor 3.2.2. Alle Editor-Assets sind mitgeliefert; Statistikübermittlung ist deaktiviert. Der Editor lädt nur eigene Assets, blockiert Netzwerkzugriffe und eingebettete Skripte. Siehe [docs/third-party.md](docs/third-party.md).
