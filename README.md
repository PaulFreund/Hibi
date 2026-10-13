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

Release-Builds verwenden eine eigene PKCS12-Datei außerhalb von Git. Das Zertifikat der ersten APK wurde beibehalten: Updates mit `adb install -r` erhalten damit die Daten. Der Build liest `HIBI_SIGNING_PROPERTIES` oder standardmäßig `~/.ivy/secrets/Hibi/signing.properties`; diese Datei enthält `storeFile`, `storePassword`, `keyAlias` und `keyPassword`. Ein Release ohne konfigurierte Signierung schlägt fehl. Schlüsseldatei und Zugangsdaten unabhängig vom Build-Rechner sichern und vor jedem Rechnerwechsel prüfen. Private Schlüssel und Passwörter niemals ins Repository aufnehmen. **Vor einer Deinstallation oder Neuinstallation zuerst eine ZIP extern sichern.** Debug-Builds verwenden `org.lvl3.hibi.debug`, Release-Builds `org.lvl3.hibi`.

```powershell
.\gradlew.bat :app:assembleDebugAndroidTest
.\gradlew.bat :app:connectedDebugAndroidTest
```

Speichertests verwenden eigene Datenbanken. Die UI-Tests verlangen einen Emulator und verwenden Testdaten im Debug-Paket. Sie prüfen Editor/Speichern, Feed, Kalender, Suche, Bildformat, Erinnerung und Export. Mit dem Instrumentierungsargument `hibi.screenshots=true` werden zusätzlich Screenshots im externen App-Verzeichnis unter `verification/` abgelegt, sofern Android die Bildaufnahme unterstützt. Das Foto in den Testassets stammt aus dem freigegebenen TASK-0030-Mockup; es wird nicht in der Release-App mitgeliefert.

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

Große Texte und Entwurf-JSON werden in begrenzten Byteabschnitten aus SQLite gelesen, damit die dokumentierten 2 MiB Markdown auch nach einem Neustart lesbar bleiben. Die bisherigen Tabellen und Daten bleiben verwendbar. Markdown wird beim Veröffentlichen unverändert übernommen; Einrückung und abschließende Leerzeichen bzw. Zeilenumbrüche bleiben erhalten.

Speichern und Schließen benötigen einen bestätigten Text aus dem Schreibbereich. Bei einem Lesefehler bleibt der Editor offen und bietet Wiederholung an. Entwurf-Checkpoints sichern Änderungen bei App-Wechseln und vor der Fotoauswahl; die Auswahl öffnet erst nach abgeschlossener Entwurfspersistierung. Nach Rotation bleibt die gewählte ZIP-Verschlüsselung erhalten, Passwörter müssen erneut eingegeben werden.

Der Editor-Controller bleibt im ViewModel über Activity-Neuerstellungen erhalten. Beim Rendererwechsel wird zuerst der Abschluss-Snapshot der alten WebView übernommen; erst danach lädt und initialisiert die neue WebView den aktuellen Text. Fehlt die Bestätigung, bleibt Speichern gesperrt und die alte WebView für Wiederholung oder eine verspätete Textübernahme erhalten. Erst nach einem ausdrücklichen Hinweis mit Bestätigung kann stattdessen der bisher gesicherte Entwurf weiterbearbeitet werden. Antworten eines abgelösten Renderers oder einer ersetzten Wiederholungsabfrage werden ignoriert. Ein expliziter Text-Read sendet zusätzlich einen unabhängigen Entwurf-Checkpoint, damit eine verworfene Read-Antwort keine ausstehende Textsicherung unterdrückt. Bei einem Initialisierungsstillstand bietet der Schreibbereich eine Wiederholung an; die erste Fehlerdiagnose bleibt im Controller auch nach Entfernen der WebView erhalten. Die untere Formatleiste öffnet ihr Überlaufmenü nach oben und verwendet größere, beschriftete Trefferflächen.

## Backup-Verhalten

In den Einstellungen den ZIP-Export öffnen und optional ein Passwort vergeben. **ZIP-Datei direkt speichern** schreibt ohne lokale ZIP-Kopie in das gewählte Dateiziel. **ZIP erstellen** erzeugt eine einzelne temporäre ZIP zum anschließenden Teilen. OneDrive, Google Drive oder eine andere Ziel-App übernimmt den Upload und ihre WLAN-/Mobilfunkeinstellungen. Hibi zeigt **Letzter Export** und bestätigt keinen Cloud-Upload. Nach einer Neuinstallation **ZIP-Backup importieren** wählen.

Ein Backup enthält veröffentlichte Einträge, Originalbilder und die Anzeige-/Erinnerungseinstellungen. Entwürfe sind nicht enthalten. Dateinamen und Ordnernamen bleiben auch bei AES-ZIPs lesbar; alle Dateiinhalte einschließlich Manifest werden verschlüsselt. Das Passwort wird nicht gespeichert und lässt sich nicht zurücksetzen. Desktop-Tools müssen AES-ZIPs unterstützen.

Beim Import werden alle Inhalte und SHA-256-Prüfsummen geprüft und erst nach der Vorschau neue Einträge in einer Datenbanktransaktion übernommen. Unterschiede zwischen Bilddecodern bzw. EXIF-Informationen werden als Hinweise angezeigt; auch auf diesem Gerät nicht lesbare Originale bleiben erhalten und können wieder exportiert werden. Die Bildanzeige verwendet dann einen Platzhalter. Bereits vorhandene IDs mit anderem Inhalt bleiben lokal erhalten; eine automatische Zusammenführung von Textfassungen gibt es nicht. Der ausführliche Dateivertrag steht in [docs/backup-format.md](docs/backup-format.md).

Erinnerungen werden nach erfolgreichem Dateispeichern, Öffnen des Teilen-Dialogs, × oder einer geänderten Häufigkeit auf den nächsten Zeitraum gesetzt. Eine nur erzeugte und anschließend geschlossene ZIP verschiebt die Erinnerung nicht. Der Teilen-Dialog kann abgebrochen werden; **Letzter Export** belegt dabei nur die Übergabe, keinen abgeschlossenen Upload. Ein Monat bedeutet einen Kalendermonat. Es gibt keine Hintergrund-Uploads oder Push-Erinnerungen. Änderungen seit der letzten extern gespeicherten ZIP sind bei Geräteverlust nicht wiederherstellbar.

Hibi hält höchstens eine temporäre ZIP zum Teilen im Cache. Sie wird beim nächsten Teilen-Export oder Kaltstart entfernt und kann auch vom System bereinigt werden. Den Upload in der Ziel-App abschließen, bevor ein weiteres Teilen-Backup erzeugt wird. Extern gespeicherte Kopien verwaltet die Ziel-App. Verwaiste Importdateien und unvollständige Fotokopien werden beim Kaltstart aufgeräumt. Androids automatisches Cloud-Backup und Geräteübertragung sind ausdrücklich deaktiviert; Wiederherstellung erfolgt über die portable ZIP.

## Bibliotheken

Kotlin/Compose und kotlinx.serialization, Zip4j 2.11.6, Markwon 4.6.2 und TOAST UI Editor 3.2.2. Alle Editor-Assets sind mitgeliefert; Statistikübermittlung ist deaktiviert. Der Editor lädt nur eigene Assets, blockiert Netzwerkzugriffe und eingebettete Skripte. Siehe [docs/third-party.md](docs/third-party.md).
