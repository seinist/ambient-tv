# GitHub-Release-Builds

Der Workflow **Build release** ruft `bash build.sh release` auf und baut alle vier
Architekturen inklusive nativer Engines und Monogram. Er erstellt signierte APKs,
`update.json` und einen GitHub-Release-Entwurf. Die Dateinamen kommen unverändert
aus `build.sh`.

## Einmalige Einrichtung

Den Workflow nach `main` pushen. Unter **Settings → Secrets and variables → Actions**
folgende Repository-Secrets hinterlegen:

| Secret | Inhalt |
| --- | --- |
| `AMBIENT_TV_KEYSTORE_BASE64` | Base64-Inhalt des bestehenden Release-Keystores |
| `AMBIENT_TV_KEYSTORE_PASSWORD` | Keystore-Passwort |
| `AMBIENT_TV_KEY_ALIAS` | Alias des Release-Schlüssels |
| `AMBIENT_TV_KEY_PASSWORD` | Passwort des Schlüssels |

Für Updates bestehender Installationen denselben Keystore wie beim lokalen Build
verwenden. Base64 lokal mit `base64 -w0 keystore/release.jks` erzeugen und direkt
als Secret speichern. Ohne vollständige Signierung bricht der Workflow früh ab.

## Release im Browser bauen

1. **Actions → Build release → Run workflow** öffnen und den gewünschten Branch wählen.
2. `version_name` (z. B. `1.0-beta`) und `version_code` (z. B. `274`) eingeben.
   Leere Felder übernehmen die Werte aus `version.properties` des gewählten Commits.
   Für installierbare Updates muss der Code höher als bei der vorherigen Version sein.
3. Nach erfolgreichem Build unter **Releases** den Entwurf prüfen und veröffentlichen.
   Die Dateien stehen zusätzlich als Actions-Artefakt bereit.

Beispiel: `1.0-beta` und Code `274` ergeben den Tag `v1.0.274-beta` und:

- `ambient-tv-arm64-1.0.274-beta.apk`
- `ambient-tv-armv7-1.0.274-beta.apk`
- `ambient-tv-x86-1.0.274-beta.apk`
- `ambient-tv-x86_64-1.0.274-beta.apk`
- `update.json`

Auch stabile Versionen enthalten den Build-Code: `1.0` und Code `353` ergeben
`ambient-tv-arm64-1.0.353.apk` und beim manuellen Workflow den Tag `v1.0.353`.
Debug- und unsignierte APKs verwenden dieselbe Versionsbezeichnung, z. B.
`ambient-tv-debug-1.0.353.apk` und `ambient-tv-arm64-unsigned-1.0.353.apk`.
Ein bereits enthaltener Code wird nicht doppelt angehängt (`1.0.353` bleibt bei
Code `353` unverändert). Name und Code stammen direkt aus den AGP-Metadaten der
gebauten APK. Damit stimmen Dateiname, lokaler Build-Zähler und die tatsächlich
angewendeten Gradle-Overrides (z. B. `ORG_GRADLE_PROJECT_versionCode`) überein.
CI erhöht den Versionscode nicht automatisch und schreibt keine Versionsdateien zurück.
Bei erneutem Lauf für einen bereits bestehenden Release wird dieser nicht überschrieben;
die neu gebauten Dateien bleiben im Actions-Artefakt verfügbar.

Alternativ startet ein gepushter Tag wie `v1.0.274-beta` den Build. Dabei wird die
Version aus dem Tag und der Code aus der committed `version.properties` verwendet.
Vor dem Taggen den passenden, erhöhten Code committen. Der Entwurf verwendet den
gepushten Tag; auch hier bestimmt allein `build.sh` die APK-Dateinamen.

Der vollständige native Build kann lange dauern und viel Speicherplatz benötigen.
Der Workflow hat ein Zeitlimit von sechs Stunden. Ein erfolgreicher lokaler
Workflow-/Skripttest ersetzt keinen erfolgreichen Build auf dem GitHub-Runner.
