# Hibi ZIP, Version 1

Dateien sind UTF-8, Bilder enthalten unveränderte Originalbytes. Pfade verwenden `/`.

```text
manifest.json
days/YYYY-MM-DD/<entry UUID>/entry.md
days/YYYY-MM-DD/<entry UUID>/images/<photo UUID>.<extension>
```

Mehrere Einträge an einem Tag besitzen jeweils einen eigenen Unterordner. UUIDs und Bildprüfsummen ändern sich durch UI-Änderungen nicht. `entry.md` enthält ausschließlich den Markdown-Inhalt; Datum, Reihenfolge und IDs stehen im Manifest.

## Manifest

`format: "hibi"`, `version: 1`, `exportedAt` als Unix-Millisekunden, `newestFirst`, `pictureFormat` (`SQUARE`/`ORIGINAL`), `reminderPeriod` (`OFF`/`WEEK`/`MONTH`) und `entries`.

Ein Manifest-Eintrag enthält `id`, `day`, `createdAt`, `updatedAt`, `markdownFile`, `markdownSha256` und `photos`. Jedes Foto enthält den Archivpfad `file` und `photo`: `id`, `extension`, `mimeType`, EXIF-korrigierte `width`/`height` und `sha256`. Prüfsummen beziehen sich auf die unveränderten Dateibytes und sind lowercase SHA-256. Die Reihenfolge von `photos` ist die Carousel-Reihenfolge.

## Grenzen und Prüfung

- Höchstens 20.000 Einträge, 100.000 Archivdateien und 20 GiB unkomprimierte Inhalte.
- Einzeldateien höchstens 512 MiB; Markdown höchstens 2 MiB und Manifest höchstens 8 MiB.
- Unterstützte Originalformate: JPEG, PNG, WebP, GIF, HEIC/HEIF und AVIF, soweit die Android-Version sie dekodieren kann. Animierte Bilder werden im Feed als Standbild dargestellt.
- Die Quellansicht zeigt Bilder vollständig. Extreme Seitenverhältnisse außerhalb 1:4 bis 4:1 nutzen einen begrenzten Rahmen mit Leerraum.
- Absolute Pfade, `..`, Windows-Trenner, Symlinks, doppelte Dateinamen/IDs, unerwartete Dateien, fehlende Originale und abweichende Prüfsummen werden verworfen.
- Android prüft zusätzlich den tatsächlichen Bildtyp und die EXIF-korrigierten Abmessungen.
- Datumswerte liegen im kanonischen ISO-Format zwischen `0001-01-01` und `9999-12-31`.
- Alle Dateien werden zunächst in ein privates Staging-Verzeichnis gelesen. Fehler verhindern die Übernahme. Neue Originale werden vor dem gemeinsamen SQLite-Commit gespeichert; bei einem fehlgeschlagenen Commit werden neu angelegte Bilder wieder entfernt.

## Import in ein bestehendes Tagebuch

Fehlende IDs werden ergänzt. Identische Einträge werden übersprungen. Bei gleicher ID und abweichendem Inhalt bleibt die lokale Fassung bestehen. Die Vorschau zeigt alle drei Zahlen. Bestehende Anzeigeeinstellungen bleiben erhalten; beim ersten Import werden Einstellungen aus der ZIP übernommen. Lokale Export-/Erinnerungszeitpunkte werden nicht als Cloud-Sicherungsbestätigung restauriert.

## Verschlüsselung

Optionales WinZip-kompatibles AES-256 durch Zip4j. Alle Dateien sind verschlüsselt; Namen und Archivstruktur bleiben sichtbar. Kein Passwort im Manifest, in den Einstellungen oder im Git. Ohne Passwort entsteht ein normales ZIP. Neuere Formatversionen werden ausdrücklich abgelehnt, statt still teilweise importiert zu werden.
