<div align="center">

<img src="branding/png/wordmark/ambient-tv-wordmark-readme.png" alt="Ambient TV" width="420">

**Android-TV- und Handy-Client für lokale Medien – Plex, Jellyfin, Emby, TVHeadend, SmartTube, Spotify, DLNA, Dateien/NAS und Fotos in einer App, abgespielt mit MPV, VLC oder Media3. Optional mit Monogram (Telegram-Medien).**

[![Android 10+](https://img.shields.io/badge/Android-API%2029%2B-3DDC84?logo=android&logoColor=white)](#voraussetzungen)
[![Android TV](https://img.shields.io/badge/Android%20TV-ready-3DDC84?logo=androidtv&logoColor=white)](#funktionen)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-UI-4285F4?logo=jetpackcompose&logoColor=white)](https://developer.android.com/compose)

<!-- version-badge:start -->

[![Version](https://img.shields.io/badge/Version-1.0%20%28380%29-E5A00D)](#)

<!-- version-badge:end -->

[![GitHub Sponsors](https://img.shields.io/badge/GitHub%20Sponsors-Sponsor-EA4AAA?logo=githubsponsors&logoColor=white)](https://github.com/sponsors/seinist)
[![Ko-fi](https://img.shields.io/badge/Ko--fi-Support-FF5E5B?logo=kofi&logoColor=white)](https://ko-fi.com/seinist)
[![License: CC0](https://img.shields.io/badge/License-CC0%201.0-lightgrey)](LICENSE)

[Screenshots](#screenshots) · [Funktionen](#funktionen) · [Warum?](#warum) · [Unterstützen](#unterstützen) · [Haftungsausschluss](#haftungsausschluss) · [Bauen](#bauen) · [Doku](#dokumentation) · [English](README.md)

</div>

## Screenshots

<table>
<tr>
<td width="33%" valign="top"><a href="docs/screenshots/01-home.png"><img src="docs/screenshots/01-home.png" alt="Startseite" width="320"></a><br><b>Startseite</b></td>
<td width="33%" valign="top"><a href="docs/screenshots/04-player-engine-selection.png"><img src="docs/screenshots/04-player-engine-selection.png" alt="Player-Auswahl" width="320"></a><br><b>Player-Auswahl</b></td>
<td width="33%" valign="top"><a href="docs/screenshots/07-tv-programme-guide.png"><img src="docs/screenshots/07-tv-programme-guide.png" alt="TV-Programmführer" width="320"></a><br><b>TV-Programmführer</b></td>
</tr>
</table>

[Alle Screenshots auf der Website](https://seinist.github.io/ambient-tv/?lang=de#screens)

<p align="center">

**Package** `com.ambient.tv` · **Version (Repo)** <!-- version:start -->`1.0` (380)<!-- version:end --> · **Min. Android** API 29 · **Lizenz** [CC0 1.0](LICENSE)

</p>

## Warum?

Angefangen hat alles mit Plex. Jedem Client fehlte etwas – etwa Wake-on-LAN für den Server oder eine echte Bildbearbeitung mit Scalern, Upscalern und Filtern, wie sie bisher kein anderer Player auf Android TV bietet. Hier ist all das drin.

Daraus wurde eine All-in-one-Lösung: Jedes Video, egal aus welcher Quelle, läuft durch den Renderer meiner Wahl. WireGuard ist eingebaut, damit die Mediatheken auch unterwegs nur einen Klick entfernt sind. Und die guten Ideen, die sonst über ein halbes Dutzend Player verstreut sind, finden endlich in einer App zusammen.

## Unterstützen

Ambient TV entsteht in der Freizeit, aus Liebe zu einem guten Fernsehabend. Wenn es deinen Fernseher ein bisschen schöner macht, hilft ein Kaffee oder ein Sponsoring, dass es weitergeht – danke! ☕

[![Ko-fi](https://img.shields.io/badge/Ko--fi-Support-FF5E5B?logo=kofi&logoColor=white)](https://ko-fi.com/seinist) [![GitHub Sponsors](https://img.shields.io/badge/GitHub%20Sponsors-Sponsor-EA4AAA?logo=githubsponsors&logoColor=white)](https://github.com/sponsors/seinist)

## Haftungsausschluss

> [!WARNING]
> Ambient TV ist ein Hobbyprojekt und wird so bereitgestellt, wie es ist. **Nutzung auf eigene Gefahr.** Für geschmolzene Fernseher, explodierte TV-Boxen, überhitzte Wohnzimmer oder plötzliche Lust auf neue Hardware wird keine Haftung übernommen.
>
> Bildverarbeitung ist hungrig: Wie gut Filter, Upscaler/Shader, Zwischenbildberechnung (SVP), Deinterlacing, HDR-Umwandlung und Co. laufen, hängt **stark von deiner Hardware** ab. Was auf der einen Box butterweich läuft, macht die andere zur Diashow (und zum Heizlüfter). Ruckelt es, die Einstellungen zurückdrehen oder den Leistungsmodus auf „Sparsam“ stellen.

## Funktionen

<table>
<tr>
<td width="50%" valign="top">

### 📚 Quellen
- **Plex · Jellyfin · Emby** – Mediatheken, Home-Reihen, Suche, Titelmusik
- **TVHeadend** – Live-TV, EPG, Aufnahmen, Timer; Aufnahmen und Timer bearbeiten (Vor-/Nachlauf, Priorität) und löschen, Bilder aus EPG, TMDb oder Senderlogo
- **IPTV** – M3U-Wiedergabelisten mit XMLTV-Programmführer
- **SmartTube** – YouTube-Feeds, **SponsorBlock**, Anmeldung per QR-Code
- **Spotify** – Connect-Empfänger + native Wiedergabe (librespot)
- **Monogram** *(optionaler GPL-/MTProto-Build)* – Telegram-Kanalmedien, vollständige Beitragstexte, Vorschaubilder und YouTube-Links, sechs Home-Reihen, QR-/Telefon-/2FA-Anmeldung; Media3/VLC/MPV-Anbindung (Gerätetests stehen noch aus)
- **Trailer** (TMDB) · **DLNA-Server** · **Fotos**
- **Dateien** – Lokal/USB, **SMB 1/2/3**, **NFSv3/v4**, **WebDAV** (HTTP/HTTPS), **SFTP** (Passwort oder SSH-Schlüssel)
- **Musik in Dateien/NAS** — Titel-Tags, Albumreihenfolge und Cover aus dem Ordner

</td>
<td width="50%" valign="top">

### ▶️ Wiedergabe
- **Media3, VLC und MPV** in einem gemeinsamen Player
- **Live-TV-Timeshift** — Pause, Spulen und zurück zu Live; EPG-Erinnerungen und optionale TVHeadend-Direktaufnahme
- **Untertitel** — externe Dateien bei Dateien/NAS und DLNA, Online-Suche über OpenSubtitles.com (eigener API-Schlüssel), **automatisch synchronisieren** am Server-Untertitel, eigenes **Aussehen** für Text-Untertitel (ASS/SSA behalten ihren Stil)
- **Player-Werkzeuge** — A-B-Wiederholung, Einzelbild, **Lesezeichen**, Bildschirmfotos
- **Musik** — **lückenlose** Wiedergabe (Media3), **Songtexte** (Plex, Jellyfin, optional LRCLIB)
- **Miniplayer** & Hintergrundwiedergabe
- Bildeinstellungen **pro Titel gemerkt**
- **Anzeigemodus-Anpassung**, optional **SDR → HDR10** (Android 14+)
- **Deinterlacing** – BWDIF, YADIF, NNEDI, IVTC, …
- **Zwischenbildberechnung (SVP)**, experimentell

</td>
</tr>
<tr>
<td valign="top">

### 🎨 Bild
- MPV-**Scaler** + GPU-Shader-**Upscaler**: NNEDI3, RAVU, FSRCNNX, ArtCNN, Anime4K, NVIDIA NIS, AMD FSR
- **Debanding, Schärfen, Entrauschen**, HDR-Tone-Mapping
- **Media3-GPU-Filter** – MPVs Kernel (EWA Lanczos, Robidoux, Splines, …), RAVU, NVIDIA NIS, FSR, CAS/Adaptive Sharpen, Debanding, Entrauschen, Filmkorn, auf 4K-Panels in 4K gerendert
- Bildoptionen für **VLC**

</td>
<td valign="top">

### 🔊 Ton
- **Audioprofile** je Ausgang (TV / HDMI-AVR / Kopfhörer)
- **Passthrough** – AC3, E-AC3, DTS, DTS-HD, TrueHD
- **Upmix, Nachtmodus, Dialoganhebung**, Lautstärkeausgleich, 10-Band-EQ
- **Nachtmodus nach Uhrzeit** — jeder Player schaltet ihn im Zeitfenster ein
- Visualizer / **projectM**

</td>
</tr>
<tr>
<td valign="top">

### 🌐 Netzwerk
- **Wake-on-LAN** für Server, NAS-Quellen und DLNA-Server, nach Rechner gruppiert
- Eingebautes **WireGuard**, je System schaltbar
- **DLNA-Renderer** – Empfang von BubbleUPnP, VLC, …

</td>
<td valign="top">

### 📱 Geräte & Extras
- **TV- (D-Pad/Sidebar)** und **Handy-Oberfläche (Touch)** in einer APK
- **Android-TV-System- und Sprachsuche** findet Titel aus Plex, Jellyfin und Emby
- Offline-Server bleiben im Menü, mit einer Seite zum **Aufwecken / Neu verbinden**
- **Handy ↔ TV koppeln** – Fernbedienung, Wiedergabe senden, Konten übertragen
- **Uhr** in den Kopfzeilen, auf der Startseite und im Player (Format, Datum, Aussehen)
- **Lichtstimmungen** über der Startseite, **Filterleiste** über Mediatheken, Programmführer, Trailern und YouTube
- **Leistungsmodus** Auto / Ausgewogen / Sparsam, Bildschirmschoner, Startbildschirm-Kanäle
- **Einstellungs-Backup** (Schema 76; API-Daten nur im passwortverschlüsselten Export mit Zugangsdaten) · DE/EN UI

</td>
</tr>
</table>

📖 Jede Einstellung und Player-Option: **[Funktionskompendium](docs/DOCUMENTATION.de.md)** · [English](docs/DOCUMENTATION.md) · [Docs-Index](docs/README.md)

## Voraussetzungen

| Voraussetzung | Details |
| --- | --- |
| **Geräte** | Android TV / Google TV, Handys und Tablets mit **Android API 29+** |
| **ABIs** | arm64-v8a · armeabi-v7a · x86 · x86_64 |
| **Geräteabhängig** | Vulkan-Ausgabe/-Dekodierung, SDR → HDR (Android 14+, HDR10-Display), Passthrough-Formate, SVP |
| **Netzwerk** | LAN zu Medienserver/NAS; Internet für Plex-Link, SmartTube, Spotify, Trailer und optional Monogram |
| **Build** | **JDK 17**, Android SDK/NDK (`ANDROID_NDK_HOME` oder `ANDROID_NDK_ROOT`); optional Keystore |

## Bauen

<details>
<summary><b>Bauen & starten</b></summary>

```bash
# Debug-APK (baut Native-Engines bei Bedarf mit)
./build.sh debug

# Signiertes Release (wenn keystore.properties vorhanden)
./build.sh release

# Einzelne ABI (ARM TV / Geräte)
./build.sh arm64
./build.sh armv7

# Emulator-ABIs
./build.sh x86
./build.sh x86_64

# Gepinnte Engines prüfen / Pins aktualisieren
./build.sh libs-check
./build.sh libs-update
```

Artefakte landen unter `dist/` (z. B. `ambient-tv-arm64-<version>.apk`, `ambient-tv-x86_64-<version>.apk`). Vorab-Suffixe wie `beta`, `alpha` oder `rc` bleiben am Ende, die Build-Nummer steht davor (`ambient-tv-arm64-1.0.270-beta.apk`).

Gradle direkt (mit automatischer MPV/FFmpeg-Vorbereitung bei fehlenden Local-Audio-Headern oder -Bibliotheken): `./gradlew :app:assembleDebug` – für produktnahe Builds `./build.sh` bevorzugen. `SKIP_MPV_NATIVE=1` überspringt den nativen MPV-Build (schnelle Builds/CI), z. B. `SKIP_MPV_NATIVE=1 ./gradlew :app:assembleDebug :app:testDebugUnitTest`. Es ist keine Spotify-Client-ID eingebaut; siehe [Spotify einrichten](#spotify-einrichten).

Details und Umgebungsvariablen: [docs/DOCUMENTATION.de.md §14](docs/DOCUMENTATION.de.md#14-entwicklung-und-build) · [EXTERNAL_LIBRARIES.md](EXTERNAL_LIBRARIES.md)

</details>

<details>
<summary><b>Release-Signierung</b></summary>

Der Release-Keystore und `keystore.properties` liegen nicht im Repository (beide stehen in `.gitignore`). Zum Signieren entweder eine lokale `keystore.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`) neben `build.sh` ablegen oder setzen:

```bash
export AMBIENT_TV_KEYSTORE_FILE=/pfad/zu/release.jks
export AMBIENT_TV_KEYSTORE_PASSWORD=…
export AMBIENT_TV_KEY_ALIAS=…
export AMBIENT_TV_KEY_PASSWORD=…
```

Ohne beides funktionieren Debug-Builds, Release-APKs bleiben unsigniert. `./build.sh gen-keystore` erzeugt einen neuen Schlüssel.

</details>

<details>
<summary><b>Einstellungen (Kurz)</b></summary>

| Bereich | Inhalt |
| --- | --- |
| Start & Bedienung | Home-Reihen, Medienauswahl, Sprache, Bibliothek |
| Darstellung | Theme, Akzent, Skalierung, **Leistung**, Hintergrundvideo, Bildschirmschoner |
| Systeme & Konten | Plex / Jellyfin / Emby / TVH / IPTV / SmartTube / Spotify / optional Monogram / Trailer / DLNA / Dateien |
| Geräte & Netzwerk | Handy & Geräte, UPnP-Receiver, WireGuard / VPN, Server & Wake-on-LAN |
| Wiedergabe | Player-Backend, Untertitel, „Als Nächstes“, Sprungweite |
| Bild & Ton | Bild-/Audioprofile, Visualizer, Fotos |
| App & Daten | Backup, Diagnose, Erweitert, Über |


</details>

### Spotify einrichten

<details>
<summary>Ambient TV enthält keine Spotify-Client-ID – erstelle deine eigene (ca. 5 Minuten)</summary>

1. Im [Spotify Developer Dashboard](https://developer.spotify.com/dashboard) anmelden und **Create app** wählen.
2. Beliebigen Namen und Beschreibung eintragen. Bei **Which API/SDKs are you planning to use?** **Web API** auswählen.
3. Beide **Redirect URIs** hinzufügen und speichern:
   - `ambienttv://spotify-auth` (Anmeldung auf dem Gerät)
   - `https://seinist.github.io/ambient-tv-spotify-callback/spotify-callback.html` (QR-Anmeldung am TV; die Seite leitet den Code nur an den TV in deinem Netzwerk weiter)
4. In den **Settings** der App die **Client ID** kopieren.
5. In Ambient TV: **Einstellungen › Medienquellen & Konten › Spotify** → im Abschnitt **Spotify-Konto** bei **Spotify-Client-ID** einfügen, dann **Anmelden**.
6. Neue Apps laufen im **Development mode**: im Dashboard unter **User Management** die Spotify-Konten (Name + E-Mail) eintragen, die sich anmelden dürfen.

</details>

### Monogram einrichten (optionale Telegram-Medien)

> **Inoffizieller Client, nutzt die Telegram-API.** Monogram in Ambient TV ist keine App von Telegram.

**Lizenz:** Der Monogram-Build steht unter **GPL-3.0-only**. Sein vollständiger Quellcode ist dieses Repository plus das festgelegte Upstream-Monogram-Release in `gradle/external-versions.properties` (`MONOGRAM_VERSION`, derzeit Release 0.4.3, mit geprüftem Tag-Commit `MONOGRAM_REF` und mono-rs-Submodul-Commit `MONOGRAM_MONO_RS_REF`), mit den Änderungen aus `scripts/patch_monogram_leave.py`; jede von diesem Skript geänderte Upstream-Datei trägt einen datierten Änderungshinweis.

<details>
<summary>Eigene Telegram-API-ID und API-Hash hinterlegen und per TV-QR-Code oder Telefoncode anmelden</summary>

**Voraussetzung:** `./build.sh release` packt die gepinnte Monogram-Laufzeit unter GPL-3.0-only in alle vier ABI-APKs. Debug und Einzel-ABI-Builds (`./build.sh arm64` und die übrigen) lassen sie weg, außer mit `MONOGRAM_NATIVE=1`. `MONOGRAM_NATIVE=0 ./build.sh release` baut den Release ohne Monogram. Siehe [Monogram-Build und Integration](docs/monogram-integration.md). Die native Bibliothek und das ursprüngliche Logo stehen unter GPL-3.0-only; vor einer Verteilung müssen GPL-Lizenzpflichten und Telegram-API-Bedingungen geklärt werden. Native Release-Builds und die drei Renderer benötigen noch Gerätetests.

1. Mit deinem **Telegram-Konto** unter [my.telegram.org/apps](https://my.telegram.org/apps) anmelden und eine Telegram-API-Anwendung erstellen. **API-ID** (Zahl) und **API-Hash** (32 Hex-Zeichen) kopieren.
2. In der Monogram-fähigen Ambient-TV-App **Einstellungen › Medienquellen & Konten › Monogram** öffnen. Die API-Felder sind auch bei noch deaktiviertem System verfügbar.
3. **API-ID** und **API-Hash** eintragen und **API-Zugangsdaten speichern** wählen. Der Hash wird verdeckt eingegeben; die Werte landen verschlüsselt auf dem Gerät, **nicht** fest in der APK.
4. **Monogram aktivieren**. Im Kontoabschnitt erscheint der Anmeldestatus mit den passenden Wegen:
   - **Android TV:** **Mit QR-Code anmelden** wählen. Telegram auf einem **bereits angemeldeten Handy** öffnen, unter **Einstellungen › Geräte** den QR-Code scannen und die Anmeldung bestätigen. Dies ist ein Telegram-`tg://`-Anmeldetoken und **kein** Spotify-Web-Callback.
   - **Handy oder Alternative am TV:** Telefonnummer mit Ländervorwahl eintragen, **Anmeldecode senden** wählen und den von Telegram erhaltenen Code bestätigen. Bei Bedarf das Passwort für die **Zwei-Schritt-Verifizierung** eingeben. Am Handy wird der QR-Login nicht angezeigt, da es den eigenen Bildschirm nicht scannen kann.
5. Sobald **Verbundenes Konto** erscheint, die Monogram-Tabs bzw. sechs Home-Reihen einrichten: **Neue Medien, Kanäle, Videos, YouTube-Links, Musik und Audio, Fotos**. YouTube-Videos öffnen sich über die vorhandene SmartTube-Auswahl; vollständige Telegram-Beitragstexte stehen auf der Detailseite.

**Backup:** Das normale Einstellungsbackup sichert Monogram, Menü/TV-Chips und Home-Reihen. **Export mit Zugangsdaten** enthält außerdem API-ID und API-Hash in einer passwortverschlüsselten Sicherung, **nicht** aber die Telegram-Anmeldung oder privat heruntergeladene Medien. Nach einem Gerätewechsel ist eine erneute Anmeldung erforderlich. Zum Ändern der API-Zugangsdaten zuvor abmelden.

</details>

### Releases

Ein `v*`-Tag baut die signierten Release- und Debug-APKs und veröffentlicht ein GitHub-Release – Einrichtung, Secrets und Versionierung: [Build-Doku §14.5](docs/DOCUMENTATION.de.md#145-releases-und-versionierung).


## Ausprobieren & mitgestalten

Ambient TV entsteht aus Leidenschaft – für alle, die mehr aus ihrem Mediensetup holen wollen. **Probier es aus**, kitzle das Letzte aus den Filtern heraus (mit Gefühl – siehe Haftungsausschluss) und sag mir, wie es dir gefällt: Fehler, Abstürze, Ideen und „Wäre schön, wenn …“ sind in den [Issues](../../issues) jederzeit willkommen. Jede Rückmeldung hilft, Ambient TV zum besten Medien-Client auf Android TV zu machen.

## Bekannte Grenzen

- Die NNEDI-Deinterlace-Gewichte (`app/src/main/res/raw/nnedi3_deinterlace_weights.bin`, GPL-2.0-or-later) bleiben im Baum; andere native Bibliotheken (MPV/FFmpeg, …) baut oder lädt `./build.sh` beim ersten Mal.
- Keine Garantie für jede Codec-/Filter-Kombination auf jedem Gerät; Bildfilter brauchen eine GPU-Videoausgabe
- Dateien: kein NFS-Kerberos, kein NFSv4.1+, keine NetBIOS-Suche, kein FTP; WebDAV mit Basic- oder Digest-Anmeldung
- DLNA-Renderer: kein Google Cast, keine DRM-Inhalte, keine Bilder

## Dokumentation

| Dokument | Sprache |
| --- | --- |
| [docs/DOCUMENTATION.md](docs/DOCUMENTATION.md) | English (Standard, vollständig) |
| [docs/DOCUMENTATION.de.md](docs/DOCUMENTATION.de.md) | Deutsch (vollständig) |
| [README.md](README.md) | English – Kurzüberblick |
| [docs/README.md](docs/README.md) | Docs-Index (EN) |
| [EXTERNAL_LIBRARIES.md](EXTERNAL_LIBRARIES.md) | EN – Engine-Pins |

## Lizenz

Dieses Projekt steht unter **[Creative Commons Zero v1.0 Universal (CC0-1.0)](LICENSE)** – Verzicht auf Urheberrechte soweit rechtlich möglich; ohne Garantie.

Drittanbieter-Engines und Marken (Plex, Jellyfin, Emby, YouTube/SmartTube, Spotify, …) unterliegen eigenen Lizenzen und Kennzeichnungen – siehe „Über“ in der App und `third_party_licenses/`.

**Gebaute APKs:** Die App enthält GPL-lizenzierte Bestandteile (mpv: GPL-2.0-or-later; FFmpeg, von mpv-android mit `--enable-gpl --enable-version3` gebaut: GPL-3.0-or-later; NNEDI-Gewichte: GPL-2.0-or-later; TVHeadend-Logo: GPL-3.0-or-later) und LGPL-Bestandteile (VLC, SVP-Adapter). Die enthaltenen GLSL-Shader stehen unter LGPL-3.0-or-later, MIT, BSD-2-Clause oder CC0-1.0 (siehe `SHADER_SOURCES_027.json`). Aus diesem Repository gebaute APKs werden daher unter den Bedingungen der **GNU GPL v3.0** verteilt; der zugehörige Quelltext ist dieses Repository plus die gepinnten Upstream-Quellen in `gradle/external-versions.properties`.

---

<div align="center"><sub>Ambient TV · gemacht für den großen Bildschirm · <a href="#warum">warum es das gibt</a></sub></div>
