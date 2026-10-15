# Entwicklung

## Build

JDK 21, Android SDK Platform 36 und Build Tools 36 installieren. Den SDK-Pfad über `ANDROID_HOME` oder eine lokale `local.properties` setzen. Letztere gehört nicht ins Repository. Der Gradle-Wrapper verwendet Version 8.13.

```sh
./gradlew :core:test :app:assembleDebug :app:lintDebug
```

Unter Windows `gradlew.bat` verwenden. Der Debug-Paketname ist `org.lvl3.hibi.debug`, der Release-Paketname `org.lvl3.hibi`. APKs liegen unter `app/build/outputs/apk/`.

## Release signieren

Ein eigenes PKCS12-Keystore außerhalb des Repositories erzeugen, beispielsweise mit `keytool` aus dem JDK. Die Passwörter interaktiv vergeben und den Schlüssel unabhängig vom Entwicklungsrechner sichern.

```sh
keytool -genkeypair -storetype PKCS12 -keystore /safe/path/hibi-release.p12 \
  -alias hibi -keyalg RSA -keysize 3072 -validity 10000
```

Die externe Datei `~/.config/hibi/signing.properties` enthält:

```properties
storeFile=/safe/path/hibi-release.p12
storePassword=YOUR_KEYSTORE_PASSWORD
keyAlias=hibi
keyPassword=YOUR_KEY_PASSWORD
```

Die Beispielwerte durch die eigenen Werte ersetzen. Alternativ kann `HIBI_SIGNING_PROPERTIES` auf eine andere externe Properties-Datei zeigen. Passwörter und Schlüsseldateien bleiben außerhalb von Git.

```sh
./gradlew :app:assembleRelease :app:lintRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

Ein Release ohne vollständig konfigurierte Signierung schlägt fehl. Updates einer vorhandenen Installation benötigen denselben Schlüssel. Vor Deinstallation, Gerätewechsel oder Schlüsselwechsel eine externe ZIP sichern. App-Daten werden nicht durch Androids automatisches Cloud-Backup oder Geräteübertragung gesichert.

## Tests

```sh
./gradlew :core:test :app:lintDebug
./gradlew :app:assembleDebugAndroidTest
./gradlew :app:connectedDebugAndroidTest
```

Die Speichertests verwenden eigene Datenbanken. UI-Fixtures verlangen einen Emulator und verwenden ein eigenes Debug-Tagebuch mit fiktiven Einträgen und einem Demonstrationsfoto. Sie dürfen nicht auf einem echten Tagebuch ausgeführt werden. Die Emulatorprüfung in `UiTest.requireEmulator` bleibt aktiviert.

Das Instrumentierungsargument `hibi.screenshots=true` legt optionale Bilder unter `verification/` im externen App-Verzeichnis ab, wenn Android die Aufnahme unterstützt. Die README enthält native Aufnahmen der unveränderten App auf einem Pixel-8-Emulator mit API 36, fiktiven Einträgen und zwei generierten Fotomotiven. Die Aufnahmeprüfung bestätigt das sichtbare App-Fenster sowie die Editor-Bereitschaft und den geladenen Markdown-Inhalt; sie ersetzt keinen vollständigen Funktionstest.

## Aufbau und Datenverträge

- `core/model`: Eintrags-/Bildmodelle, Validierung, Importvergleich, Entwürfe und Erinnerungszeitpunkte.
- `core/backup`: ZIP-Format, AES-Export, Prüfsummen, Pfadvalidierung und Import-Staging.
- `app/data`: SQLite-Schema, Transaktionen, Entwürfe und unveränderte Originaldateien.
- `app/ui`: Compose-Oberfläche, ViewModel und Editor-Controller.
- `app/src/main/res/values/colors.xml`: zentrale Palette; `ui/theme/Theme.kt`: Typografie, Abstände und Formen.

Änderungen am SQLite-Schema erfordern eine explizite Migration in `LocalDiary.Database`. Änderungen am Backup-Vertrag benötigen eine neue unterstützte Formatversion. Bestehende Datenbanken werden nicht automatisch durch leere ersetzt.

Große Texte und Entwurf-JSON werden in begrenzten Byteabschnitten aus SQLite gelesen. Markdown bleibt beim Veröffentlichen einschließlich Einrückung und abschließender Leerzeichen erhalten.

Speichern und Schließen erfordern bestätigten Text aus dem Schreibbereich. Entwurf-Checkpoints sichern Änderungen beim App-Wechsel und vor der Fotoauswahl. Der Editor-Controller bleibt bei einer Activity-Neuerstellung im ViewModel erhalten. Beim Rendererwechsel wird der Abschluss-Snapshot übernommen, bevor der neue Renderer initialisiert wird. Fehler, verspätete Antworten und Wiederholungen dürfen weder neueren Text überschreiben noch einen unbestätigten leeren Text veröffentlichen.

Die WebView-Schreibfläche verwendet die tatsächlich gemessene Fensterhöhe. Die Editorprüfung verlangt positive Flächenhöhen und eine im Fenster liegende Werkzeugleiste. Das Überlaufmenü öffnet oberhalb der unteren Formatleiste.

Die ZIP-Spezifikation und die Grenzen der Importprüfung stehen in [backup-format.md](backup-format.md).
