# Name und App-Grafiken

Der aktuelle Name ist **Ambient TV**. Das freigegebene SVG-/PNG-Branding nutzt das
Monitor-Icon mit stilisiertem A, ein größeres gelbes A in der weißen Wortmarke und
ein gelbes TV-Badge. Übersicht und Exporte: [`branding/`](branding/).

Die Komponenten in der App verwenden lokale SVGs aus `assets/branding/`.
Nur die gelben Elemente folgen der Akzentfarbe; die weiße Wortmarke und die dunkle
TV-Schrift behalten ihre Farben. PNGs dienen dem Export.
Der spätere Markenwechsel hat folgende zentrale Einstiegspunkte:

| Bestandteil | Stelle | Verwendung |
| --- | --- | --- |
| Anzeigename | `branding.properties` → `APP_DISPLAY_NAME` (UTF-8) | Launcher, Einrichtung, Über-Seite und Standard-Gerätename |
| Laufzeitname | `branding/AppBrand.kt` | Produktname für Medienserver und neue Spotify-Gerätenamen |
| Zeichen in der App | `assets/branding/ambient-tv-icon.svg` → `BrandMark` | Einrichtung, Über-Seite, leere Zustände, Drawer |
| Wortmarke | `assets/branding/ambient-tv-wordmark.svg` → `BrandWordmark` | Über-Seite, Einrichtung, Drawer |
| Kombilogo | `assets/branding/ambient-tv-combo.svg` → `BrandCombo` | Zusammenhängendes Icon und Wortmarke |
| Android-TV-Banner | `res/values/branding.xml` → `brand_banner` | TV-Launcher |
| Launcher-/Splash-Icon | gleiche Datei → `brand_launcher` | Launcher und Android-Splash |

Den Namen als einzelne Zeile ohne doppelte Anführungszeichen oder Backslashes eintragen.
Gradle generiert `BuildConfig.APP_DISPLAY_NAME` und `R.string.brand_app_name` aus demselben Wert.
Die drei Sprachdateien referenzieren diesen String über `app_name`. Kein getrenntes Umbenennen je Sprache.

Die Grafik-Aliase können auf neue Ressourcen zeigen. Ein neues Launcher-Icon muss adaptive
und Legacy-Varianten enthalten; das vorhandene TV-Banner muss beim Markenwechsel ebenfalls ersetzt werden.
In Compose `BrandMark`, `BrandWordmark` oder `BrandCombo` verwenden. Plattformressourcen
werden über `R.drawable.brand_banner` bzw. `R.mipmap.brand_launcher` angesprochen.

**Technische Identität:** Application-ID und Namespace sind `com.ambient.tv`.
Die Application heißt `AmbientTvApplication`, das Compose-Theme `AmbientTvTheme`.
App-eigene JNI-Bibliotheken verwenden `ambientmpv`, `ambientprojectm`, `ambientsvp`
und `ambientvulkan`; JNI-Exporte und R8-Regeln müssen dazu passen.
Die Application-ID bezeichnet eine neue Android-Installation. Einstellungen und
Anmeldungen einer Installation unter einer anderen ID werden nicht automatisch übernommen.
Portable JSON-Backups behalten ihr Schema und ihre Provider-Schlüssel.
Plex bleibt als System einschließlich APIs, Logos, Tokens und Einstellungen erhalten.

Der mobile Spotify-Callback lautet `ambienttv://spotify-auth` und muss für den
verwendeten Spotify-Client registriert sein. Die bestehende HTTPS-Relay-Adresse
bleibt erhalten, bis die gehostete Seite und die Spotify-Registrierung gemeinsam
umgezogen werden.
Bereits vom Nutzer gesetzte Gerätenamen bleiben bestehen; nur neue Standardwerte folgen dem neuen Namen.
Ein neuer Name mit Sonderzeichen ist vor Veröffentlichung auch in Plex-/Jellyfin-HTTP-Headern und Spotify Connect zu testen.

## Systemzeichen

Die Logos von Plex, Jellyfin, YouTube und Spotify sind lokale Vektorressourcen.
Das TVheadend-Zeichen ist die originale lokale Grafik; Dateien verwendet das gemeinsame Ordnersymbol.
Es werden keine Logos aus dem Netz geladen, keine zusätzlichen Fokusziele angelegt.
Quellen und SHA256: `SYSTEM_LOGO_SOURCES.json`; Lizenztexte liegen daneben.
Die Systemnamen und -zeichen gehören weiterhin den jeweiligen Anbietern.

## SVG- und PNG-Master

Die gemeinsame Quelle ist `branding/svg/ambient-tv-combo.svg`. Mit
`rtk proxy /usr/bin/python3 scripts/export_branding.py` werden zuerst Icon,
Wortmarke, Banner und Launcher als eigenständige SVGs abgeleitet und danach alle
PNGs unter `branding/png/` exportiert. Die bestehenden Dateinamen bleiben erhalten.
Der gleiche Lauf erzeugt die Android-Plattformvektoren für Launcher und Banner
sowie die verbleibenden Ressourcen-Aliase aus diesen SVGs.

Die neue Wortmarke enthält mehrere Farben und darf nicht vollständig mit
`ColorFilter.tint` eingefärbt werden. Schriftzeichen sind in Pfade umgewandelt.
Farben, Größen und Vorschau stehen in [`branding/`](branding/).

## SVGs in der App

Die Varianten unter `branding/svg/app/` setzen ausschließlich die gelben Füllungen
und Linien auf `currentColor`. Das Exportskript kopiert sie nach
`app/src/main/assets/branding/`. `BrandSvgPainter` ersetzt diese Farbwerte durch
`MaterialTheme.colorScheme.primary` beziehungsweise die explizite `tint`-Farbe.
Weißes „mbient“ und dunkle TV-Schrift werden dabei nicht verändert.

AndroidSVG zeichnet die lokalen Dateien als Vektorbefehle in ein `Picture`.
Dateizugriff und Parsen laufen auf `Dispatchers.IO`; ein begrenzter Cache hält die
Zeichnungen pro Datei und Akzentfarbe. Im Drawer bleibt das Icon an seiner Position,
während rechts daneben die Wortmarke eingeblendet wird.

Launcher und TV-Banner sind Android-Plattformressourcen mit festen Markenfarben.
Das TV-Banner verwendet die breite `ambient-tv-wordmark.svg` zentriert im
320-×-180-Format. Das Telefon-Launcher-Foreground ist dasselbe Monitorzeichen
(A1) als 432-px-Ebene in `mipmap-xxxhdpi`; der Splash nutzt den Vector.
