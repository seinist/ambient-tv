# Ambient TV – Funktionskompendium

**Sprachen:** [English](DOCUMENTATION.md) · **Deutsch**

**App:** Ambient TV (`com.ambient.tv`) · Version `1.0` (versionCode siehe `version.properties`) · minSdk 29, target/compileSdk 37 · Backup-Schema 72
**Lizenz:** [CC0 1.0 Universal](../LICENSE)

Dieses Kompendium beschreibt, was die App heute kann: jede Einstellungsseite, die Video- und Audio-Panels im Player und die Medienquellen. Die Bezeichnungen sind die echten Oberflächentexte aus `app/src/main/res/values*/`. Standardwerte stammen aus dem Code (`AppPreferences`, `AudioOptions`, `VideoImageOptions`, `SettingsUiState`). Geplante Funktionen stehen hier nicht.

**So liest du die Tabellen**

| Spalte | Bedeutung |
| --- | --- |
| Optionen / Werte | Auswahl der Einstellung. Bereiche einschließlich der Grenzen. |
| Standard | Wert nach der Neuinstallation. „—“ heißt: kein gespeicherter Wert (Aktion oder anfangs leeres Feld). |
| Engines | **M3** = Media3 (ExoPlayer), **MPV**, **VLC**. „Alle“ = alle drei. |
| Geräte | **TV** = Android-TV-/Leanback-Oberfläche, **Handy** = Handy und Tablet (Touch-Oberfläche). „Alle“ = beide. |
| Sichtbar wenn | Bedingung, unter der die Einstellung erscheint. Ohne Hinweis ist sie auf ihrer Seite immer sichtbar. |

---

## Inhaltsverzeichnis

1. [Überblick](#1-überblick)
2. [Aufbau der Einstellungen und Speichern](#2-aufbau-der-einstellungen-und-speichern)
3. [Oberfläche & Bedienung](#3-oberfläche--bedienung)
4. [Medienquellen & Konten](#4-medienquellen--konten)
5. [Geräte & Netzwerk](#5-geräte--netzwerk)
6. [Wiedergabe-Einstellungen](#6-wiedergabe-einstellungen)
7. [Player-Panel (während der Wiedergabe)](#7-player-panel-während-der-wiedergabe)
8. [Leistungsmodi](#8-leistungsmodi)
9. [App & Daten](#9-app--daten)
10. [Einrichtung](#10-einrichtung)
11. [Navigation und Bedienung](#11-navigation-und-bedienung)
12. [Handy, Tablet und TV zusammen](#12-handy-tablet-und-tv-zusammen)
13. [Sicherung und Wiederherstellung](#13-sicherung-und-wiederherstellung)
14. [Entwicklung und Build](#14-entwicklung-und-build)
15. [Grenzen und Hinweise](#15-grenzen-und-hinweise)

---

## 1. Überblick

Ambient TV ist eine APK mit zwei Oberflächen: eine D-Pad-Oberfläche mit Seitenleiste für Android TV und eine Touch-Oberfläche mit unterer Leiste für Handys und Tablets. Der UI-Modus von Android entscheidet, welche startet.

| System | Was es bringt | Geräte |
| --- | --- | --- |
| Plex | Bibliotheken, Startseiten-Reihen, Suche, Wiedergabe, Anmeldung über plex.tv/link, Titelmusik | Alle |
| Jellyfin | Bibliotheken, Startseiten-Reihen, Suche, Wiedergabe, Anmeldung mit Benutzer/Passwort, Titelmusik | Alle |
| Emby | Gleicher Funktionsumfang wie Jellyfin | Alle |
| TVHeadend | Live-TV, Programmführer, Aufnahmen, Timer, eigene Player-Wahl, Deinterlacing | Alle |
| IPTV | M3U-Wiedergabelisten, XMLTV-Programmführer, Sendergruppen, Live-Player | Alle |
| SmartTube | YouTube-Bereiche, Anmeldung per Gerätecode, SponsorBlock, „Auf dem Fernseher ansehen“ | Alle |
| Spotify | Native Wiedergabe (librespot) und Spotify-Connect-Empfänger; Wiedergabe braucht Premium | Alle |
| Trailer | TMDB-Trailer und Reihe „Jetzt im Kino“; braucht einen eigenen TMDB-Schlüssel | Alle |
| DLNA | UPnP-/DLNA-Medienserver im LAN durchsuchen | Alle |
| Dateien | Lokale/USB-Ordner, SMB-1/2/3- und NFSv3/v4-Freigaben, Favoriten, zuletzt geöffnet, Weiterschauen | Alle |
| Fotos | Alben, Zeitleiste, Ordner, Diashow, Foto-Bildschirmschoner | Alle |
| UPnP-Receiver | Andere Apps (BubbleUPnP, VLC, …) senden Medien an dieses Gerät | Alle |
| Companion | Handy mit TV koppeln: Fernbedienung, Wiedergabe senden, Konten übertragen | Handy ↔ TV |
| WireGuard | Eingebauter Tunnel, je Mediensystem schaltbar | Alle |

Gemeinsame Bausteine: globale Suche über alle aktiven Systeme, Miniplayer, Hintergrundwiedergabe, Bildschirmschoner, Benachrichtigungskarten mit Verlauf, Einstellungs-Backup, Oberfläche auf Deutsch oder Englisch.

---

## 2. Aufbau der Einstellungen und Speichern

Die Einstellungen haben fünf Bereiche. Jeder Bereich listet seine Seiten; die Suche findet Seiten und Optionen bereichsübergreifend.

| Bereich | Seiten |
| --- | --- |
| **Oberfläche & Bedienung** | Startseiten-Reihen · Beim Auswählen eines Mediums · Sprache · Mediathek · Aussehen · Hintergrundvideo · Bildschirmschoner · Menütöne · Uhr |
| **Medienquellen & Konten** | Plex · Jellyfin · Emby · TVHeadend · IPTV · SmartTube · Spotify · Trailer · DLNA · Dateien (eingerichtete Systeme zuerst, dann „hinzufügen“) |
| **Geräte & Netzwerk** | Handy & Geräte · UPnP-Receiver · WireGuard / VPN · Server & Wake-on-LAN |
| **Wiedergabe** | Standard-Player · Untertitel · Intros, Abspann und nächste Folge · Sprungweite & Wiedergabe · Bildprofile · Audioprofile · Audio-Visualisierung · Fotos |
| **App & Daten** | Benachrichtigungen · Accounts und Einstellungen übertragen · Einstellungen sichern · Diagnose · Über die App |

**Speichern.** Normale Werte sind ein Entwurf und gelten erst mit **Speichern**. Beim Verlassen mit offenen Änderungen fragt die App nach Speichern oder Verwerfen. Sofort wirken: Anmeldung, Abmeldung, Serverwechsel, Verbindungstests und Import („Anmeldung, Serverwechsel, Tests und Import gelten sofort; alles andere mit Speichern übernehmen.“). Kopplungen und TV-Erreichbarkeit auf *Handy & Geräte* wirken ebenfalls sofort. Der Export schreibt die gespeicherten Werte, nicht den Entwurf.

Jede Seite eines Mediensystems hat zusätzlich:

| Einstellung | Optionen / Werte | Standard | Beschreibung |
| --- | --- | --- | --- |
| Anzeigename | Freitext | Leer (Anbietername) | Ersetzt den Systemnamen im Menü, auf der Startseite, in der Suche und in den Einstellungen. |
| Untermenü-Einträge | Einträge je System | Alle an | Welche Einträge das Untermenü der Seitenleiste zeigt. Mit Verknüpfung „Einstellungen“ am Ende. |
| Startseite dieses Systems | Weiterschauen, Kürzlich hinzugefügt, Sammlungen, Bibliotheken, Zuletzt gesehen, Top-Auswahl ungesehen, Top nach Genre, Weil du … gesehen hast, Empfehlungen des Servers | Alle an außer Plex „Empfehlungen des Servers“ | Reihen auf der eigenen Seite des Systems. Die Startseite der App hat ihr eigenes Layout. |
| Netzwerkeinstellungen | Verknüpfung | — | Springt zu Server & Wake-on-LAN und WireGuard. |

---

## 3. Oberfläche & Bedienung

### 3.1 Startseiten-Reihen

| Einstellung | Optionen / Werte | Standard | Geräte | Beschreibung |
| --- | --- | --- | --- | --- |
| Startseite gestalten | Reihen anzeigen, verschieben, entfernen | Plex: Weiterschauen, Kürzlich hinzugefügt, Sammlungen; TVHeadend: Live-TV | Alle | Reihen-Editor für die Startseite der App. |
| Reihe hinzufügen | Weiterschauen · Alle Systeme; je System: Weiterschauen, Kürzlich hinzugefügt, Sammlungen, Bibliotheken; Zuletzt gesehene TV-Sender; TVHeadend Live-TV / Programm / Aufnahmen / Timer; SmartTube Startseite / Abos / Verlauf / Playlists / Shorts / Musik / News / Gaming / Live / Kids; Spotify Startseite / Lieblingssongs / Playlists / Alben / Künstler / Läuft gerade; Trailer / Jetzt im Kino; Zuletzt hinzugefügte Fotos; Top im Genre; Direktzugänge zu Systembereichen | — | Alle | Inhaltsreihen oder Direktzugänge zu einem Bereich eines Systems. |
| Systeme in „Weiterschauen · Alle Systeme“ | Plex, Jellyfin, Emby | Alle an | Alle | Welche Server die gemeinsame Reihe füllen. Sichtbar, wenn diese Reihe auf der Startseite liegt. |
| Auf dem Startbildschirm zeigen | An / Aus | An | TV | Watch Next sowie die Kanäle „Weiterschauen“ und „Kürzlich hinzugefügt“ im Android-TV-Launcher. Sichtbar, wenn der Launcher es unterstützt. |
| TV-Senderverlauf löschen | Aktion | — | Alle | Die Reihe der zuletzt gesehenen Sender speichert bis zu 12 Sender auf dem Gerät; nicht im Backup. |

**Ansicht am TV:** Der Hero füllt den oberen Bildschirmteil; darunter steht eine Reihe bündig am unteren Rand, die anderen sind ausgeblendet, bis man zu ihnen wechselt (wie bei Plex). Die Kacheln dieser Reihen wachsen mit dem Bildschirm, unabhängig von der UI-Skalierung, sodass auch in 4K eine Reihe den Platz unter dem Hero füllt. Ab aus der letzten Reihe geht es oben weiter; Hoch aus der ersten Reihe führt zur Kopfzeile oder zu dem, was darüber liegt (z. B. dem Update-Hinweis), sonst zur letzten Reihe. Dasselbe gilt für die Empfehlungsseiten der Mediatheken.

**Offline-Server auf der Startseite:** Ein Server, der nicht antwortet, behält auf der Startseite eine Reihe mit seinem Namen und **Aufwecken** (mit Wake-on-LAN) bzw. **Neu verbinden**; seine übrigen Reihen kommen von selbst zurück, sobald er antwortet. Standen nur seine Reihen auf der Startseite, zeigt sie stattdessen die Offline-Seite. Gemeldet wird ein Ausfall nur über die Verbindungsmeldung, ohne Dialog.

**Aktualisieren nach Neuverbindung:** Nach einer erfolgreichen Server-Neuverbindung oder der Rückkehr des Netzwerks holen offene Inhaltsansichten ihre Daten erneut. Das umfasst Startseiten, Mediatheken, Details und abgeschickte Suchen sowie Live-TV mit Sendern, Programm, Aufnahmen und Timern; auch YouTube, Spotify, Dateien/NAS, DLNA, Fotos und Trailer sind eingebunden. Ansichten im Hintergrund holen eine verpasste Aktualisierung nach, sobald sie wieder sichtbar werden. Quelle, Bereich, Suchanfrage und vorhandene Inhalte bleiben während des Nachladens erhalten; bereits geladene Seiten und der Fokus werden soweit möglich beibehalten. Erfolgreich geladene leere Listen entfernen veraltete Einträge, während eine fehlgeschlagene Teilabfrage ihre bisherigen Daten behält. Bei IPTV werden auch M3U und XMLTV frisch angefordert. Eine laufende Aufnahmeaktion oder Quellenänderung wird zuerst abgeschlossen und anschließend aktualisiert. Die erneute Spotify-Autorisierung aktualisiert ebenfalls die geöffneten Spotify-Ansichten.

### 3.2 Beim Auswählen eines Mediums

| Einstellung | Optionen / Werte | Standard | Beschreibung |
| --- | --- | --- | --- |
| Beim Auswählen eines Mediums | Detailseite anzeigen · Sofort abspielen · Wiedergabeoptionen anzeigen | Detailseite anzeigen | Für Plex, Jellyfin und Emby. SmartTube hat eine eigene Wahl im SmartTube-Modul (Standard: abspielen). |

### 3.3 Sprache

| Einstellung | Optionen / Werte | Standard | Beschreibung |
| --- | --- | --- | --- |
| App-Sprache | Automatisch · English · Deutsch | Automatisch | Automatisch folgt der Android-Sprache. Ein Wechsel lädt die Oberfläche neu. |
| Metadatensprache | Automatisch + Sprachliste | Automatisch | Titel und Beschreibungen von Plex, Jellyfin und SmartTube. |

Die **bevorzugte Audiosprache** liegt unter *Wiedergabe › Audioprofile*, die Untertitelsprachen unter *Wiedergabe › Untertitel*.

### 3.4 Mediathek

| Einstellung | Optionen / Werte | Standard | Beschreibung |
| --- | --- | --- | --- |
| Hochauflösende Bilder | An / Aus | Aus | Schärfere Cover und Hintergründe; mehr Netz und Speicher. |
| Poster pro Reihe | 4–12 | 8 | Rasterdichte. |
| Listenzeilen auf dem Bildschirm | 4–10 | 7 | Listendichte. |
| Filterleiste | An / Aus (Ansicht-Menü) | An | Filter und Sortierung als Leiste direkt über dem Inhalt in Mediathek, Live-TV-Programmführer, Trailern und SmartTube: Jeder Filter öffnet seine Werte am Platz, zweiwertige Filter sind Schalter, Zurücksetzen erscheint nur bei aktiven Filtern. Aus verschiebt Filter und Sortierung zurück ins Ansicht-Panel. |

### 3.5 Aussehen

| Einstellung | Optionen / Werte | Standard | Geräte | Sichtbar wenn / Beschreibung |
| --- | --- | --- | --- | --- |
| Design | Dunkel · OLED Schwarz · Gedimmt | Dunkel | Alle | Theme. Live-Vorschau oben. |
| Oberflächengröße | Regler 50–150 % | 100 %; am TV 90 % bei 1440p und 70 % bei 4K | Alle | Größe von Texten, Karten und Bedienelementen. Der TV-Standard wird einmal anhand der Display-Auflösung gesetzt. |
| Akzentfarbe | Gold, Orange, Blau, Türkis, Grün, Violett, Pink, Rot, Artwork, Benutzerdefiniert | Gold | Alle | Artwork färbt Detailseiten nach dem Cover, sonst Gold. Benutzerdefiniert öffnet einen Farbwähler (Standard `#E5A00D`). |
| Eigene Wiedergabefarbe | An / Aus | Aus | Alle | Zweite Farbe für Wiedergabestatus und Fortschritt. |
| Haupt- und Wiedergabefarbe tauschen | Aktion | — | Alle | Sichtbar mit eigener Wiedergabefarbe. |
| Wiedergabefarbe | Farbwähler | `#1A5FF2` | Alle | Sichtbar mit eigener Wiedergabefarbe. |
| HDR-Oberfläche | Automatisch (sicher) · Immer HDR · Immer SDR | Automatisch (sicher) | Alle | Sichtbar ab Android 15 (API 35) mit HDR-Display und erweitertem Farbraum. Automatisch hebt die Oberfläche nur während HDR-Wiedergabe an. |
| SDR-Video zu HDR | An / Aus | Aus | Alle | Siehe [3.5.1](#351-sdr-video-zu-hdr). Wirkt nur mit HDR-Oberfläche „Automatisch“. |
| Oberflächenleistung | Auto · Ausgewogen · Maximale Leistung · Sparsam | Auto | Alle | Animationen, Schimmer, schwere Flächen. Siehe [§8](#8-leistungsmodi). |
| Oberfläche prüfen | Aktion | — | Alle | Misst die Zeichenlast und schlägt einen Modus vor. |
| Start-Animation | Aus · Lichtfeld · Morgenlicht | Lichtfeld | Alle | Einmal beim Kaltstart, in den Akzentfarben. Während ein Server aufwacht, atmet das Licht weiter. Jede Taste fährt fort. Sparsam zeigt die stehende Wortmarke. |
| Wiedergabeleistung | Auto · Ausgewogen · Maximale Leistung · Sparsam | Auto | Alle | Bewegungsglättung, schwere Hochskalierer, Deinterlace mit doppelter Rate. |
| Prüfdauer für Bildaussetzer | Automatisch, 2, 5, 6, 10, 15, 30, 60 s | Automatisch (2–6 s) | Alle | Messfenster des Filtertests (MPV, VLC, Media3). |
| Wiedergabe prüfen | Aktion | — | Alle | Misst die Filterlast ohne Bild. |
| Lichtstimmung über der Startseite | Ruhig · Lichtstreifen · Bildfarbe · Tiefenbewegung · Aurora · Kinolicht · Prisma · Dämmerung · Glut · Gezeiten · Fokus · Mondlicht · Nebel · Kerzenschein · Sternstaub · Lichtspiel · Farbatem | Tiefenbewegung | Alle | Bewegter Look des Startseiten-Heros. Sparsam und reduzierte Effekte zeigen „Ruhig“. |
| Stärke der Lichtstimmung | 0–100 % | 60 % | Alle | Stärke des Effekts. |
| Ausbreitung des Lichtscheins | Sanft · Fokussiert · Breit | Sanft | Alle | Größe des Lichts. |
| Home-Hintergrund auf allen Ansichten | An / Aus | Aus | Alle | Startseiten-Effekt auch hinter anderen Ansichten. Im Modus Sparsam aus. |

#### 3.5.1 SDR-Video zu HDR

Standardmäßig aus. Zeigt geeignetes SDR-Video auf einem HDR-Display als HDR10. Erzeugt kein Dolby Vision.

| Voraussetzung | Wert |
| --- | --- |
| Android | ab 14 (API 34) |
| Display | HDR10 im aktuellen Modus, erweiterter Farbraum, HDR/SDR-Verhältnis ≥ 1, Spitzenhelligkeit ≥ 300 Nits |
| HDR-Oberfläche | Automatisch (sicher); „Immer SDR“ schaltet es ab |
| Leistung | Nicht bei reduzierten Effekten |
| MPV | Vulkan-Videoausgabe muss bereits gewählt sein |
| VLC / Media3 | VLC mit Android-Ausgabe oder Media3; bestätigtes BT.709-SDR bis 1920×1080 bei bis zu 60 fps, ohne zusätzliche GPU-Effektkette, SVP oder Software-Filter. VLC mit Vulkan wird nicht unterstützt. |
| Statuszeile | aus · nicht verfügbar · angefordert (wartet auf Renderer) · vom Renderer bestätigt · nach einem Fehler deaktiviert. „Vom Renderer bestätigt“ prüft nicht die TV-Ausgabe. |

### 3.6 Hintergrundvideo

| Einstellung | Optionen / Werte | Standard | Engines | Beschreibung |
| --- | --- | --- | --- | --- |
| Video hinter der Oberfläche anzeigen | An / Aus | Aus | Alle | Das laufende Video bleibt beim Stöbern sichtbar. Aus: nur der Ton läuft weiter. |
| Sichtbarkeit des Hintergrundvideos | 90 %, 75 %, 60 % sichtbar | 75 % | Alle | Sichtbar, wenn die Option darüber an ist. |

### 3.7 Bildschirmschoner

| Einstellung | Optionen / Werte | Standard | Sichtbar wenn / Beschreibung |
| --- | --- | --- | --- |
| Bildschirmschoner aktivieren | An / Aus | An | Startet nach Leerlauf; unterbricht nie die Wiedergabe. |
| Starten nach | Minuten (1–120) | 10 Min. | Bildschirmschoner an. |
| Darstellung | Ambient · Nordlicht · Fluid · Sterne · Seide · Medien · OLED · Fotos | Ambient | Bildschirmschoner an. |
| Animationsqualität | Sparsam · Ausgewogen · Hoch | Ausgewogen | Shader-Darstellungen (Nordlicht, Fluid, Sterne, Seide). |
| Foto-Quelle | Album / Bibliothek oder „Zuletzt hinzugefügte Fotos (alle Bibliotheken)“ | Zuletzt hinzugefügt | Darstellung „Fotos“. |
| Uhr anzeigen | An / Aus | An | OLED zeigt eine kleine, gedimmte, wandernde Uhr. |
| Datum anzeigen | An / Aus | An | Uhr an. Zeitformat und Datumsstil folgen der [Uhr](#39-uhr). |
| Medien-Artwork verwenden | An / Aus | An | Darstellung „Medien“. Nutzt in dieser Sitzung bereits angezeigte Bilder. |
| Artwork-Wechsel | Sekunden (10–300) | 30 s | Darstellung „Medien“ mit Artwork. |
| Helligkeit | Prozent (20–100) | 70 % | Dimmt nur den Bildschirmschoner. |
| Bildschirmschoner testen | Aktion | — | Vorschau bis zur nächsten Taste, ohne Speichern. |

### 3.8 Menütöne

| Einstellung | Optionen / Werte | Standard | Geräte | Beschreibung |
| --- | --- | --- | --- | --- |
| Menütöne | An / Aus | Aus | Alle | Leise Klicks bei Fokus und Aktivierung. Während der Wiedergabe stumm. Am TV bei Oberflächenleistung Sparsam stumm. Handys nutzen die Medienlautstärke. |
| Lautstärke Menütöne | Regler 0–100 % | 30 % | Alle | Lautstärke der Klicks. |
| Sound-Editor | Je Ereignis: Fokuswechsel, Bestätigen / Öffnen, Zurück / Schließen, Einschalten, Ausschalten, Wert ändern, Erfolgsmeldung, Fehlermeldung | Eingebautes Set | Alle | Vorlagen (Warm, Soft Glass, Deep Ambient, Weicher Sinus, Sanfte Glocke, Klangpolster, Minimal, Holzklang, Kristall, Sonar, Schwebend, Tape-Echo) oder Eigener Klang: Tonhöhe, Tonlänge, Anschwellen, Ausklingen, Wärme / Höhendämpfung, Verstimmung, Hall (Hallanteil, Raumgröße, Nachhallzeit), Echo (Echoanteil, Echoabstand, Echowiederholung), Ereignislautstärke. Export / Import als Datei. |

### 3.9 Uhr

Uhrzeit und Datum in den Seiten-Kopfzeilen (oben rechts, TV), schwebend über dem Startseiten-Hero und im Player. Ein gemeinsamer Takt springt zur vollen Minute bzw. Sekunde und folgt Zeit- und Zeitzonenwechseln sofort; die Player-Uhr läuft auch bei offenem OSD weiter. Die Seite hat eine Live-Vorschau.

| Einstellung | Optionen / Werte | Standard | Beschreibung |
| --- | --- | --- | --- |
| Anzeigen | Aus · Nur Startseite · Überall (Kopfzeilen) · Überall + Player | Überall + Player | Wo die Uhr erscheint. |
| Format | 24 Stunden · 12 Stunden (AM/PM) · Wie System | 24 Stunden | Gilt auch für den Bildschirmschoner. |
| Sekunden | An / Aus | Aus | Aktualisiert jede Sekunde; kostet auf langsamen Fernsehern etwas Leistung. |
| Datum | Aus · Kurz (Di., 27. Okt.) · Lang (Dienstag, 27. Oktober) · Numerisch (27.10.) | Kurz | Gilt auch für den Bildschirmschoner. |
| Datum neben der Uhr | An / Aus | Aus | Eine Zeile statt Datum unter der Uhrzeit. |
| Größe | Klein · Mittel · Groß | Mittel | |
| Schrift | Wie Oberfläche · Leicht · Kräftig · Monospace | Wie Oberfläche | |
| Farbe | Weiß · Akzentfarbe · Gedimmt | Weiß | |
| Deckkraft | 40–100 % | 92 % | |
| Schatten | Aus · Weich · Kräftig | Weich | |
| Endzeit im Player | An / Aus | An | Zeigt „Endet um …“ neben der Restzeit. |

Die Uhr-Einstellungen sind Teil der Einstellungssicherung; ältere Sicherungen übernehmen ihren bisherigen 24-Stunden-Schalter.

---

## 4. Medienquellen & Konten

### 4.1 Plex

| Einstellung | Optionen / Werte | Standard | Beschreibung |
| --- | --- | --- | --- |
| Plex aktivieren | An / Aus | An | Blendet Plex aus; Server und Zugangsdaten bleiben erhalten. |
| Anmelden / Abmelden | Code über plex.tv/link mit QR | — | Kontoverknüpfung. |
| Gespeicherte Server | Liste; entfernen | — | Aktiver Server mit ●; WOL-Kennzeichen bei gesetzter MAC. |
| Server suchen | Aktion | — | Suche im LAN („Im LAN gefunden“). |
| Server-IP · Port · Zugriffstoken (optional) | Text | Port 32400 | Manuelle Verbindung. |
| HTTPS verwenden | An / Aus | Aus | Für Reverse Proxy oder TLS. |
| Sichere Adresse verwenden | Aktion | — | Nach der Anmeldung angeboten, wenn der Server eine plex.direct-HTTPS-Adresse hat. |
| Verbindung testen | Aktion | — | Prüft Adresse und Token. |
| Server beim Start wählen | An / Aus | An | Nur bei mehreren gespeicherten Servern. |
| Nur lokales Netzwerk | An / Aus | An | Beschränkt Medienverbindungen auf das LAN. Aus zeigt eine Klartext-Warnung, wenn HTTPS aus ist. |
| Automatisch aktualisieren | Aus, 30, 60, 120 s | 60 s | Lädt Plex-Startseite und sichtbare Mediathek neu. |
| Titelmusik | An / Aus | Aus | Spielt die Titelmusik auf Detailseiten. |
| Titelmusik-Lautstärke | 20, 40, 60, 80 % | 40 % | Gilt für Plex, Jellyfin und Emby. Sichtbar bei eingeschalteter Titelmusik. |
| Bibliotheken | Je Bibliothek ein-/ausblenden | Alle sichtbar | Bibliotheken in Menü und Startseite. |

### 4.2 Jellyfin und Emby

Beide Seiten haben dieselben Einstellungen.

| Einstellung | Optionen / Werte | Standard | Beschreibung |
| --- | --- | --- | --- |
| Jellyfin aktivieren / Emby aktivieren | An / Aus | Aus | Bindet Bibliotheken, Suche, Startseite und Wiedergabe ein. |
| Gespeicherte Server / Server suchen | Liste, LAN-Suche | — | Server wechseln oder entfernen. |
| Server-IP · Port | Text | 8096 | Manuelle Verbindung. |
| Basispfad (optional) | Text, z. B. `/jellyfin`, `/emby` | Leer | Für Reverse Proxys. |
| HTTPS verwenden | An / Aus | Aus | Reverse Proxy oder TLS. |
| Benutzername · Passwort | Text | — | Anmelden / Abmelden. |
| Verbindung testen | Aktion | — | |
| Titelmusik | An / Aus | Aus | Wie bei Plex. |
| Bibliotheken | Ein-/ausblenden | Alle sichtbar | |

### 4.3 TVHeadend

| Einstellung | Optionen / Werte | Standard | Engines | Beschreibung |
| --- | --- | --- | --- | --- |
| TVHeadend aktivieren | An / Aus | Aus | — | Live-TV, Programmführer, Aufnahmen und Timer. |
| Serveradresse · Port | Text | Port 9981 | — | |
| Benutzername · Passwort | Text | — | — | Das Konto braucht Rechte für Programmführer, Live-TV und Aufnahmen. |
| Stream-Profil (optional) | Text | Leer | — | Name des TVHeadend-Streamprofils. |
| HTTPS verwenden | An / Aus | Aus | — | |
| Verbindung testen | Aktion | — | — | |
| Untermenü-Einträge | Live · Programm · Aufnahmen · Timer | Alle | — | |
| Live-TV auf der Startseite anzeigen | An / Aus | An | — | |
| TVHeadend-Player | Media3 · VLC · MPV | Wie der globale Player, bis geändert | — | Standard-Renderer für Live-TV und Aufnahmen; auch im Player wechselbar. |
| Deinterlacing | Aus · Automatisch · An | Aus | VLC, MPV | Media3 zeigt nur einen Hinweis. |
| Verfahren | Siehe [7.5](#75-deinterlacing) | Automatisch | VLC, MPV | Für VLC und MPV getrennt gespeichert. |
| Live-TV · Audio und Video nach Breite | Editor | — | Alle | Eigene Audio-/Video-Presets für Live-Sender je Quellbreite (geöffnet über *Standard-Player*). |

**Aufnahmen und Timer** (TVHeadend, Jellyfin, Emby): Jede Zeile hat daneben eigene Buttons **Info**, **Bearbeiten** und **Löschen**; der Detaildialog bietet dasselbe. Löschen einer Aufnahme und Abbrechen eines Timers fragen nach (Fokus zuerst auf Zurück). Fehlgeschlagene Aufnahmen werden gelöscht, nicht abgebrochen. **Bearbeiten** setzt Vor- und Nachlauf; bei TVHeadend außerdem Priorität und bei Serien-Timern Aktiv/Pausiert (TVHeadend: 0 Minuten heißt Vorlauf des DVR-Profils). Plex-Timer sind nicht bearbeitbar.

**Bilder:** Zeilen und Dialoge zeigen das eigene Bild der Aufnahme, sonst Fanart, die EPG-Sendung, aus der sie stammt, ein per exaktem Titel auf TMDb gefundenes Bild (nur mit TMDb-Schlüssel; ohne Server-Zugangsdaten geladen) und zuletzt das Senderlogo. Jellyfin/Emby nutzen zusätzlich Thumb- und Serienbilder.

### 4.4 SmartTube (YouTube)

| Einstellung | Optionen / Werte | Standard | Geräte | Beschreibung |
| --- | --- | --- | --- | --- |
| SmartTube aktivieren | An / Aus | An | Alle | Modul in Navigation und Suche. |
| SmartTube anmelden | Gerätecode über yt.be/activate, QR-Code | — | Alle | QR-Code und Browser-Link öffnen youtube.com/activate mit bereits eingetragenem Code; der Code bleibt zum Abtippen bei yt.be/activate sichtbar. Am TV erscheint der QR-Dialog hier und auf der YouTube-Seite. Am Handy kopiert der Link den Code zusätzlich. Ohne Anmeldung als Gast nutzbar. |
| SponsorBlock | An / Aus | An | Alle | Überspringt erkannte Sponsorabschnitte. |
| Nächstes Video automatisch abspielen | An / Aus | Aus | Alle | Spielt am Ende den ersten Vorschlag. |
| Angezeigte Bereiche | Start, Abos, Verlauf, Playlists, Shorts, Musik, News, Gaming, Live, Kids | Alle | Alle | Bereiche im Modul. |
| Maximale Videoauflösung (Media3) | Auto, 720p, 1080p, 1440p, 2160p | Auto | Alle | Qualität und Codec wählst du im Player aus den verfügbaren Spuren. |
| Verbindung (Media3) | Klassisch (OkHttp) · Play-Dienste (Cronet) · Android (eingebaut) | Klassisch | Alle | Netzwerk-Engine des Media3-Players. Auch im Player unter Video › Stream; ein Wechsel dort öffnet das Video an der aktuellen Stelle neu und bleibt gespeichert. |
| Puffergröße (Media3) | Klein (30 s) · Mittel (50 s) · Groß (3 min) | Mittel | Alle | Wie im Original-Client; auch im Player unter Video › Stream. |
| Verknüpfen mit der YouTube-App erlauben | An / Aus | Aus | TV | „Auf dem Fernseher ansehen“: Handys öffnen, pausieren, spulen und stoppen Videos hier. Danach „TV-Code anzeigen“ / „Alle Handys trennen“. |

### 4.5 Spotify

| Einstellung | Optionen / Werte | Standard | Beschreibung |
| --- | --- | --- | --- |
| Spotify aktivieren | An / Aus | Aus | System und Spotify-Connect-Empfänger. |
| Spotify-Konto | Anmelden / Abmelden | — | OAuth mit PKCE; eigene Client-ID erforderlich. Am TV mit QR-Anmeldung. |
| Untermenü-Einträge | Startseite, Lieblingssongs, Playlists, Alben, Künstler, Aktuelle Wiedergabe | Alle | |
| Connect-Gerätename | Text | App-Name | Name in der Spotify-Geräteliste. |
| Audioqualität | 96, 160, 320 kbps | 320 kbps | Bitrate des Musikstreams. |
| Visualisierung | Cover-Atmosphäre · Spektrum · projectM · Aus | Cover-Atmosphäre | Audio-Visualisierungen brauchen die lokale Spotify-Wiedergabe. |
| Spotify-Konto › Spotify-Client-ID | Text | Leer | Eigene Client-ID aus dem Spotify-Developer-Dashboard; für die Anmeldung nötig (siehe [README-Anleitung](../README.de.md#spotify-einrichten)). |

Media3, VLC und MPV werden für Spotify nicht genutzt; ihre Audioprofile gelten dort nicht.

### 4.6 Trailer

| Einstellung | Optionen / Werte | Standard | Beschreibung |
| --- | --- | --- | --- |
| TMDB-Schlüssel oder Lesetoken | Text; Prüfen und speichern / API-Key entfernen | — | Schlüssel von themoviedb.org; keine Anmeldung. |
| Trailer aktivieren | An / Aus | An | Trailer auf Detailseiten und in Trailer-Reihen. |
| Bevorzugte Trailer-Sprache | Gerätesprache · Deutsch · English | Gerätesprache | |

### 4.7 DLNA

| Einstellung | Optionen / Werte | Standard | Beschreibung |
| --- | --- | --- | --- |
| Server | Suchen und hinzufügen | — | UPnP-/DLNA-Medienserver im LAN. Optional Wake-on-LAN pro Server (MAC, automatisch vor dem Durchsuchen wecken, jetzt wecken). |

### 4.8 Dateien

| Einstellung | Optionen / Werte | Standard | Beschreibung |
| --- | --- | --- | --- |
| Dateien im Menü anzeigen | An / Aus | Aus | Lokale Ordner, SMB, NFS, WebDAV und SFTP ohne Medienserver. |
| Quellen und Dateien öffnen | Ordner hinzufügen · NAS hinzufügen | — | Lokal: Android-Ordnerauswahl mit dauerhaftem Lesezugriff. NAS: SMB (Freigabe, Domäne/Arbeitsgruppe, Benutzer, Passwort) (SMB 2/3 oder ausdrücklich das alte SMB1) oder NFSv3/NFSv4.0 über TCP (UID/GID). WebDAV über HTTP/HTTPS mit Basic- oder Digest-Anmeldung sowie SFTP mit Passwort oder SSH-Schlüssel sind ebenfalls verfügbar. Optional Wake-on-LAN je Quelle. |
| Untermenü-Einträge | Einträge von Dateien (Durchsuchen, Favoriten, Zuletzt geöffnet, Weiterschauen) | Alle | Sichtbar, wenn Dateien an ist. |

Dateien laufen über den gemeinsamen Player (Media3/VLC/MPV) mit einer lokalen HTTP-Brücke. Nicht unterstützt: NFS-Kerberos, NFSv4.1+-Sessions.

#### Musik in Dateien/NAS
In Ordnern mit Musik liest die App im Hintergrund die Tags (MP3/ID3v2, FLAC, M4A/ALAC/AAC; bis 300 Dateien je Ordner) und zeigt Titel mit Tracknummer und Interpret. Tragen die meisten Dateien eine Tracknummer, wird der Ordner als Album nach CD und Track sortiert – in dieser Reihenfolge läuft auch die Wiedergabe. Ein Cover (`cover.jpg`, `folder.jpg`, `front.jpg`, `AlbumArt…`) erscheint mit Album und Interpret über der Liste sowie im Player. Ogg/Opus, WMA und APE zeigen weiter den Dateinamen; eingebettete Cover werden nicht gelesen.

### 4.9 WireGuard / VPN

| Einstellung | Optionen / Werte | Standard | Beschreibung |
| --- | --- | --- | --- |
| wg-quick-Konfiguration einfügen / Konfiguration laden | Text oder Datei | — | Importiert einen Tunnel. Übliche Exporte (wg-quick, FRITZ!Box, Mullvad, ProtonVPN, WARP, …) werden erkannt. IPv4-Tunnel. |
| Konfiguration speichern · Verbindung testen · Verbinden · Trennen · Konfiguration entfernen | Aktionen | — | Status: inaktiv, verbindet, aktiv, fehlgeschlagen. Details zeigen Endpoint, Adresse, DNS, erlaubte IPs, Keepalive, MTU. |
| Plex / Jellyfin / Emby / TVHeadend über WireGuard | Je An / Aus | Aus | Sichtbar mit importiertem Tunnel (auf *Server & Wake-on-LAN* und den Systemseiten). Wake-on-LAN dieses Systems läuft ebenfalls durch den Tunnel. |

### 4.10 IPTV (M3U/XMLTV)

Einstellungen › Systeme & Konten › **IPTV**: beliebig viele M3U-Wiedergabelisten (http/https) mit optionalem XMLTV-Programmführer (`.xml` oder `.xml.gz`; leer = `url-tvg` aus der Liste) und optionalem User-Agent. Sender, Gruppen, Logos, `tvg-chno` und `#EXTVLCOPT`-User-Agent/Referer werden übernommen. Jede Liste erscheint im Menü unter **IPTV** mit Live und Programm und nutzt die Live-TV-Ansichten und den Live-Player. Die Liste wird 6 h, der Programmführer 12 h zwischengespeichert (offline gilt die letzte geladene Liste). Adressen werden verschlüsselt gespeichert und sind nur in Backups mit Zugangsdaten enthalten. Keine Aufnahmen/Timer.

---

## 5. Geräte & Netzwerk

### 5.1 Handy & Geräte

| Einstellung | Optionen / Werte | Standard | Geräte | Beschreibung |
| --- | --- | --- | --- | --- |
| Verbindungen auf diesem TV erlauben | An / Aus | Aus bis zur Kopplung | TV | Gekoppelte Geräte verbinden sich, solange die App offen ist. Erreichbarkeit im Hintergrund braucht „Über anderen Apps einblenden“. |
| Handy koppeln / Mit TV verbinden | Aktion | — | TV / Handy | QR-Code oder achtstelliger Code. Siehe [§12](#12-handy-tablet-und-tv-zusammen). |
| Fernbedienung · Wiedergabe öffnen · Fernsteuerung trennen | Aktionen | — | Handy | Sichtbar während der Verbindung. |
| Steuerung beenden | Aktion | — | TV | Beendet die laufende Fernsteuerung. |
| Letzten Stand dieses Geräts wiederherstellen | Aktion | — | TV | Sichtbar, wenn eine Übertragung ein Wiederherstellungs-Backup angelegt hat. |

### 5.2 UPnP-Receiver

| Einstellung | Optionen / Werte | Standard | Beschreibung |
| --- | --- | --- | --- |
| Im Netzwerk als Abspielgerät anbieten | An / Aus | Aus | Apps wie BubbleUPnP, Web Video Caster oder VLC senden Videos und Musik. Braucht den lokalen Netzwerkzugriff. |
| Sichtbarer Name im Netzwerk | Text | Gerätename | |
| Vor dem Abspielen fragen | An / Aus | An | Fragt am TV, wenn ein Gerät zum ersten Mal abspielt. Sichtbar bei eingeschaltetem Receiver. |
| Freigegebene Geräte vergessen | Aktion | — | Sichtbar bei eingeschaltetem Receiver. |

### 5.3 Server & Wake-on-LAN

Dienste auf demselben Rechner werden anhand von Host bzw. MAC-Adresse zusammengefasst: Plex, Jellyfin, Emby, TVHeadend, NAS und DLNA. Eine gemeinsame MAC-Adresse kann auf die Dienste der Gruppe übernommen werden; Verbindungsdaten bleiben je Dienst erhalten.

| Einstellung | Optionen / Werte | Standard | Beschreibung |
| --- | --- | --- | --- |
| MAC-Adresse für automatisches Wecken | Text; MAC-Adresse finden / MAC prüfen | Leer | |
| Beim Start automatisch wecken | An / Aus | An | TVHeadend: weckt bei Nichterreichbarkeit und wartet. |
| Verbindung testen · Jetzt wecken | Aktionen | — | |

Für alle Systeme gemeinsam, am Ende der Seite:

| Einstellung | Optionen / Werte | Standard | Beschreibung |
| --- | --- | --- | --- |
| Wake-on-LAN-Wiederholungen | Aus · 1× · 2× · 3× · 5× | 2× | Antwortet der Server nach Ablauf der Wartezeit nicht, werden neue Pakete gesendet und erneut gewartet, höchstens so oft. Gilt für Plex, Jellyfin, Emby, TVHeadend, NAS-Quellen (SMB, NFS, WebDAV, SFTP; jeder Durchgang nutzt die Wartezeit der Quelle) und DLNA; die kurze Prüfung beim Zurückkehren in die App wird nie wiederholt. |

---

## 6. Wiedergabe-Einstellungen

### 6.1 Standard-Player

| Einstellung | Optionen / Werte | Standard | Sichtbar wenn / Beschreibung |
| --- | --- | --- | --- |
| Standard-Player | Media3 · VLC · MPV | Media3 | Standard-Player für Plex, Jellyfin, Emby und SmartTube. Ein gespeichertes „Automatisch“ (war immer Media3) gilt als Media3. MPV ist gesperrt, wenn es auf dem Gerät fehlt (dann Media3). TVHeadend hat eine eigene Wahl. |
| Gespeicherte Wiedergabeeinstellungen | Übersicht | — | Zeigt gespeicherte Audio-/Video-Presets je Titel und je Breite. |
| Live-TV · Audio und Video nach Breite | Editor | — | Nur-Live-Presets je Quellbreite. |
| Automatische Displayumschaltung | Aus · Immer · Bei Start und Ende · Bei Start | Bei Start und Ende | Sichtbar, wenn das Display mehr als einen Modus meldet. Zwischen zwei Titeln (nächste Folge, Autoplay, Playlist, Senderwechsel) geht das Display nicht zurück in den Menü-Modus: Ein gleicher Modus bleibt, ebenso einer, der den nächsten Titel schon ruckelfrei zeigt (gleiche Auflösung, 1× oder erlaubtes 2× seiner Bildrate, z. B. 25 fps auf 50 Hz); sonst wird direkt umgeschaltet. |
| Displaywechsel anzeigen | An / Aus | Aus | Kurze Meldung bei Wechsel von Auflösung oder Bildrate. |
| Doppelte Bildraten erlauben (24→48, 30→60, 50→100, 60→120 Hz) | An / Aus | An | Nimmt 2×, wenn das Display den Modus anbietet, auch 100 und 120 Hz. 25 fps bleibt bei 50 Hz, wenn 50 und 100 Hz beide vorhanden sind. |
| Doppelte Bildrate bevorzugen (30→60 Hz auch bei nativem 30-Hz-Modus) | An / Aus | An | Flüssigere Menüs während der Wiedergabe; aus nimmt zuerst die native Rate. 100/120 Hz brauchen weiter „100/120 Hz erlauben“. |
| 3:2-Pulldown erlauben (24→60 Hz) | An / Aus | An | |
| Bildschirmmodus | Auto · Videoauflösung + Hz · Auto · volle Displayauflösung + Hz · Systemvorgabe · feste Modi | Systemvorgabe | Ein Auto-Modus schaltet die Umschaltung (Start und Ende) ein, wenn sie aus war. |
| Erlaubte Bildschirmmodi | Modusliste | Alle unterstützten Modi | Begrenzt die automatische Umschaltung. |

### 6.2 Untertitel

Gilt für alle Player.

| Einstellung | Optionen / Werte | Standard | Beschreibung |
| --- | --- | --- | --- |
| Untertitelmodus | Untertitel aus · Nur erzwungene · Vollständige Untertitel | Untertitel aus | |
| Untertitelsprache | Automatisch, de, en, fr, es, it, pt, nl, pl, tr, ru, … | Automatisch | Automatisch folgt der bevorzugten Audiosprache bzw. der App-Sprache. |
| Ersatzsprache | Keine – Untertitel aus, oder eine Sprache | Keine | |
| Vollständige Untertitel bei fremdsprachigem Audio | An / Aus | Aus | Volle Untertitel, wenn der Ton nicht deine bevorzugte Sprache ist. |
| Erzwungene Untertitel in App-Sprache | An / Aus | Aus | Forced-Untertitel in der App-Sprache, auch bei Modus Aus. |

Je Wiedergabe im Player: Untertitelspur und **Untertitel-Verzögerung** (Schritte ±100 / ±500 ms, je Titel gemerkt).

**Untertitel online (OpenSubtitles.com):** Mit eigenem API-Schlüssel (Einstellungen › Wiedergabe › Untertitel, optional mit Konto für mehr Downloads) bietet das Untertitel-Panel bei Filmen und Folgen in Direktwiedergabe **Untertitel online suchen**. Gesucht wird nach IMDb-ID, Titel/Staffel/Folge und bei Dateien zusätzlich per Datei-Hash (exakte Treffer zuerst), in den eigenen Untertitelsprachen oder allen. Der gewählte Untertitel wird geladen und sofort ausgewählt.

**Untertitel neben der Videodatei** (Dateien/NAS und DLNA): `.srt`, `.ass`, `.ssa` und `.vtt` mit gleichem Namensanfang wie das Video werden als Untertitelspuren angeboten – im selben Ordner, in einem Ordner `Subs`/`Subtitles` oder in `Subs/<Videoname>/`. Sprache, „forced“ und „SDH“ werden aus dem Namen gelesen (z. B. `Film.de.forced.srt`, `Film.German.srt`, `Film.pt-BR.vtt`). DLNA-Server liefern Untertitel über Untertitel-Ressourcen oder `sec:CaptionInfoEx`.

**Aussehen** (Einstellungen › Wiedergabe › Untertitel › Aussehen): Größe, Farbe, Rand (Kontur, Schatten, keiner), Hintergrund, Fett und Position, mit Vorschau. Gilt für Text-Untertitel (SRT, WebVTT, Textspuren) in jedem Player. ASS/SSA-Untertitel behalten immer ihre eigenen Schriften, Farben und Positionen.

**Automatisch synchronisieren** (Untertitel-Panel): Eine geladene Untertitel-Datei, z. B. von OpenSubtitles, wird an einen Text-Untertitel des Servers für denselben Titel angelegt. Die Zeitachsen werden abgeglichen (Versatz bis ±90 s und Bildraten 23,976/24/25), die Datei mit den gefundenen Zeiten neu geschrieben und neu geladen – damit auch in Media3. Ohne eindeutigen Treffer bleibt alles, wie es war.

### 6.3 Intros, Abspann und nächste Folge

| Einstellung | Optionen / Werte | Standard | Beschreibung |
| --- | --- | --- | --- |
| Serien-Intros überspringen | Aus · Schaltfläche anzeigen · Automatisch | Schaltfläche anzeigen | Nutzt die Intro-Markierung des Servers. |
| Abspann überspringen | Aus · Schaltfläche anzeigen · Automatisch | Schaltfläche anzeigen | Nutzt die Abspann-Markierung des Servers. |
| „Als Nächstes“ anzeigen | An / Aus | An | Folgen und SmartTube-Videos; nicht Filme, Live-TV, Livestreams oder Audio. |
| Anzeigen vor dem Ende | 10, 20, 30, 60 s | 30 s | Sichtbar bei „Als Nächstes“ an. |
| Serienfolgen automatisch abspielen | An / Aus | Aus | |
| Sammlungen automatisch fortsetzen | An / Aus | Aus | Nächster Film in der Reihenfolge der Sammlung. |
| „Noch dabei?“ fragen nach | Aus, 2, 3, 5 Wechsel | 3 | Sichtbar, wenn ein Autoplay an ist. |

### 6.4 Sprungweite & Wiedergabe

| Einstellung | Optionen / Werte | Standard | Beschreibung |
| --- | --- | --- | --- |
| Beste Version fürs Display | An / Aus | An | Wählt die Version, deren Auflösung zum TV passt. |
| Hoch/Runter-Sprung | 15, 30, 60, 90, 120 s | 60 s | Sprungtasten und Hoch/Runter während der Wiedergabe. |
| Anzeigedauer der Player-Steuerung | 3, 5, 8, 15 s | 5 s | Wie lange das OSD ohne Tastendruck sichtbar bleibt (alle Player, auch nach einem Senderwechsel). |
| Zurück im Player | Wiedergabe stoppen · Minimieren (weiterspielen) | Wiedergabe stoppen | Was Zurück bei ausgeblendetem OSD tut (alle Player). Minimieren wirkt wie der Vollbild-verlassen-Button. |
| Netzwerk-Puffer | Klein · Mittel · Groß | Mittel | Vorausladen für Media3 (15–30 s / Standard / 60 s–3 min), MPV (32 MiB·10 s / 64 MiB·20 s / 160 MiB·60 s, 96 MiB auf Geräten mit wenig RAM; nur außerhalb des Heimnetzes) und VLC (0,8 / 1,2 / 3 s). Live-Timeshift behält eigene Werte; SmartTube hat eine eigene Puffergröße. Gilt ab der nächsten Wiedergabe. |

Live-TV: Nach jedem Senderwechsel erscheint das OSD mit dem neuen Sender; solange es nur deshalb offen ist, schalten Hoch/Runter weiter. Aus Live-TV oder dem Programmführer gestartet, bleibt Sender weiter/zurück in der dort gefilterten und sortierten Liste.

### 6.5 Bildprofile

Bildfilter (Skalierung, Schärfe, Entrauschen, HDR, Farbe) stellst du während der Wiedergabe im Player-Panel ein ([§7](#7-player-panel-während-der-wiedergabe)). Diese Seite enthält die Vorgaben für die Renderer-Ausgabe.

| Einstellung | Optionen / Werte | Standard | Engines | Sichtbar wenn / Beschreibung |
| --- | --- | --- | --- | --- |
| Profil für Player | Media3 · VLC · MPV | Aktueller Standard-Player (Media3 bei Automatisch) | — | Welche Engine-Vorgaben bearbeitet werden. Ändert nicht den Standard-Player. |
| Bewegungsglättung (SVP) verfügbar | An / Aus | Aus | MPV, VLC | Aus blendet SVP im Player aus. SVP braucht zusätzlich Wiedergabeleistung „Maximale Leistung“. |
| MPV-Videoausgabe | Automatisch · Android Surface (direkt) · OpenGL (Bildfilter) · Vulkan | Android Surface (direkt) | MPV | Direkt ohne Filter; OpenGL und Vulkan erlauben Bildbearbeitung. Vulkan nur nach bestandener Geräteprüfung. |
| MPV-Videodecoder | Automatisch (Hardware) · MediaCodec (Hardware) · Vulkan Video (Hardware) · CPU (Software) | Automatisch (Hardware) | MPV | Vulkan Video braucht Ausgabe Automatisch oder Vulkan und Geräteunterstützung. |
| VLC-Videoausgabe | Automatisch · Android-Ausgabe (Surface / OpenGL ES) · Vulkan | Automatisch | VLC | Vulkan braucht Vulkan 1.1+, Timeline-Semaphoren und Android-Ausgabeunterstützung. |
| Vulkan-Prüfung · ausgewählter Player | Status + „Gerät und Module erneut prüfen“ | — | MPV, VLC | GPU, Module, Ausgabe, Decodier-Profile, letzter Versuch in dieser Sitzung. |

Media3 nutzt den normalen Android-Weg und hat keine Vulkan-Ausgabe.

### 6.6 Audioprofile

Audio-Einstellungen werden je Ausgabeprofil gespeichert: **TV**, **HDMI / AVR** und **Kopfhörer**. „Automatisch (aktuelle Ausgabe)“ wählt das Profil nach dem aktiven Ausgang. Die Werte gelten ab der nächsten Wiedergabe; das Player-Panel bearbeitet dasselbe Profil.

| Einstellung | Optionen / Werte | Standard | Engines | Sichtbar wenn / Beschreibung |
| --- | --- | --- | --- | --- |
| Passthrough für Bibliothek & Live-Wiedergabe | An / Aus | Aus | Alle | Gibt Dolby und DTS direkt an TV oder Verstärker, wenn das Gerät es kann. Das ist die „Quellvorgabe“ für die Audio-Ausgabe. |
| Profil für Player | Media3 · VLC · MPV | Aktueller Standard-Player | — | Zeigt nur Optionen, die die Engine kann. |
| Audio-Profil | Automatisch (aktuelle Ausgabe) · TV · HDMI / AVR · Kopfhörer | Automatisch | Alle | |
| Audio-Ausgabe | Quellvorgabe · Automatisch · PCM · Passthrough bevorzugen | Quellvorgabe | Alle | Automatisch nutzt Passthrough nur ohne aktiven PCM-Effekt. Vom Ausgang nicht bestätigte Formate bleiben auf dem Gerät (DTS-Kern, lokales AC3 oder PCM). |
| Bitstream-Format → Passthrough erlauben | AC3, E-AC3, DTS, DTS-HD, TrueHD: je an/aus | Alle an | M3, MPV | Sichtbar, wenn Passthrough möglich ist und der Ausgang ein Format bestätigt. DTS-HD an schaltet auch DTS an. VLC schaltet Passthrough für alle Formate gemeinsam. |
| Audio-Umwandlung | Lokal auf diesem Gerät · Durch den Medienserver | Lokal | Alle | Wo nicht unterstützter Ton umgewandelt wird. Quellen ohne Transcoder wandeln immer lokal. |
| Audio-Fallback | Automatisch · Nur PCM · Aus | Automatisch | Alle | Automatisch wandelt in AC3 (bis 5.1, ohne Atmos). Nur PCM vermeidet AC3; bei Server-Umwandlung wird dann AAC angefordert. |
| Audioverzögerung | −2000 … +2000 ms; Auf 0 ms setzen | 0 ms | Alle | Positiv: Ton später. |
| Kanal-Ausgabe | Automatisch · Stereo · Mono · Links auf beiden Lautsprechern · Rechts auf beiden Lautsprechern · 5.1 · 7.1 | Automatisch | M3, MPV | Bei VLC ausgeblendet. |
| Synthetischer Upmix | An / Aus | Aus | M3, MPV | Stereo zu künstlichem 5.1. Sichtbar, wenn der Ausgang ≥ 6 PCM-Kanäle meldet. |
| Downmix-Lautstärke halten | An / Aus | Aus | M3, MPV | |
| Nachtmodus | An / Aus | Aus | Alle | VLC braucht Android DynamicsProcessing, sonst ausgeblendet. |
| Dialogverstärkung | 0–6 | 0 | Alle | Hebt den Sprachbereich an. |
| Lautstärke angleichen | An / Aus | Aus | M3, MPV | Über VLC nicht verfügbar. |
| ReplayGain | Aus · Titel · Album | Aus | MPV | Braucht ReplayGain-Metadaten. |
| Equalizer · Klangvorlage anwenden | Neutral · Sprache · TV · Kopfhörer | Neutral | Alle | Vorlage setzt Vorverstärkung und 10 Bänder. |
| Vorverstärkung | −12 … 0 dB | 0 dB | Alle | Sichtbar bei eingeschaltetem EQ. |
| Frequenzband / Anhebung / Absenkung | 60 Hz … 16 kHz (10 Bänder), −12 … +12 dB | 0 dB | Alle | Sichtbar bei eingeschaltetem EQ. |
| Bevorzugte Spurart | Automatisch · Hauptton ohne Kommentar · Originalton · Kommentar · Audiodeskription | Automatisch | Alle | Ab dem nächsten Streamstart; manuelle Wahl bleibt. |
| Bevorzugte Kanalzahl | Automatisch · Stereo · Surround | Automatisch | Alle | |
| Dieses Audio-Profil zurücksetzen | Aktion | — | Alle | |
| Bevorzugte Audiosprache | Automatisch + Sprachen | Automatisch | Alle | |

Klang- und EQ-Effekte sind reines PCM. Sie bleiben gespeichert, sind bei Passthrough und DTS-Kern ausgesetzt und bei PCM und lokalem AC3 aktiv.

**Nachtmodus nach Uhrzeit** (Einstellungen › Audio): In einem Zeitfenster (auch über Mitternacht, Standard 22:00–07:00) schaltet jeder Player den Nachtmodus ein und hebt die Dialoganhebung auf mindestens die gewählte Stufe. Das Profil selbst bleibt unverändert. Im Player ausgeschaltet bleibt er bis zum Ende des Fensters aus. Wechselt live während der Wiedergabe und ist Teil von Backups und Companion-Abgleich.

### 6.7 Audio-Visualisierung

| Einstellung | Optionen / Werte | Standard | Sichtbar wenn / Beschreibung |
| --- | --- | --- | --- |
| Audio-Visualisierung | MilkDrop / projectM · Spektrum · Aus | MilkDrop / projectM | Für Radio, Musik und andere reine Audio-Wiedergabe. Bleibt an, solange der Ton nicht als Bitstream läuft. |
| Preset-Wechsel | 15, 30, 60, 120, 300 s | 60 s | projectM. |
| Übergang | 0, 1, 2, 5 s | 2 s | projectM. |
| Detailstufe | Sparsam · Ausgewogen · Hoch | Ausgewogen | projectM. |
| Render-Auflösung | Auto, 720p, 1080p, 1440p, 2160p | Auto (1080p) | projectM. 4K nur auf 4K-Displays; Sparsam begrenzt auf 720p. |
| 4K-HDR erlauben (experimentell) | An / Aus | Aus | projectM mit HDR-Unterstützung; HDR-Oberfläche Automatisch und Auflösung über 1080p. |
| Presets | Alle Presets · Nur Favoriten | Alle Presets | projectM. |

### 6.8 Fotos

| Einstellung | Optionen / Werte | Standard | Beschreibung |
| --- | --- | --- | --- |
| Diashow-Intervall | Sekunden (3–60) | 6 s | |
| Übergang | Überblenden · Schieben · Keiner | Überblenden | |
| Ken-Burns-Effekt | An / Aus | Aus | Langsames Schwenken und Zoomen. |
| Zufällige Reihenfolge | An / Aus | Aus | |
| Unterordner einbeziehen | An / Aus | Aus | Diashow und Blättern mit Unteralben. |
| Foto-Infos anzeigen | An / Aus | Aus | Datum, Kamera und Belichtung. |
| Sortierung | Neueste zuerst · Älteste zuerst · Name | Neueste zuerst | |

### 6.9 Live-TV: Timeshift, Direktaufnahme und Erinnerungen

Im Playback-Einstellungs-Panel steht **Live-TV** direkt unter **Untertitel**. Dort sind Videotext, HbbTV, Timeshift und Aufnahme zusammengefasst; die angebotenen Funktionen richten sich nach Sender, Quelle und Berechtigungen. Zurück führt zum Einstellungs-Panel und fokussiert wieder Live-TV. Videotext-Untertitel bleiben in der gemeinsamen Untertitelauswahl.

**Timeshift** ist standardmäßig ausgeschaltet. Der Schalter steht in den TVHeadend-/IPTV-Einstellungen, unter Wiedergabe und im Player unter **Live-TV** zur Verfügung; Änderungen gelten sofort. Bei eingeschaltetem Timeshift steht dort auch die Puffergröße. Eine Größenänderung startet den lokalen Puffer neu. Beim Senderwechsel und während der Aufnahmewiedergabe sind Änderungen gesperrt. Die Puffergröße lässt sich auf 512 MB, 1 GB, 2 GB oder 4 GB setzen. Die Anfangsgröße wird aus dem freien Gerätespeicher gewählt; vorhandene Einstellungen bleiben erhalten.

**Videotext** dekodiert die App selbst, er funktioniert also gleich mit Media3, MPV und VLC. Im Einstellungs-Panel des Live-Players öffnet *Live-TV → Videotext* die Seiten. Videotext-Untertitel stehen unter *Untertitel* in derselben Auswahl wie die normalen Untertitelspuren: *Videotext · Automatisch* wählt möglichst eine Tafel in deiner Untertitelsprache, daneben stehen verfügbare Tafeln wie 150 oder 777 mit Sprache und gegebenenfalls Hörgeschädigten-Kennung. Die Wahl bleibt gespeichert und wird in der Zusammenfassung des Einstellungs-Panels angezeigt. Eine Videotextwahl schaltet normale Untertitel aus, eine normale Spur schaltet Videotext-Untertitel aus; *Aus* beendet beide. Solange die Untertitelauswahl offen ist, sucht die App auch nach weiteren Untertitelseiten. Optionale OSD-Buttons (ab Werk aus): *Videotext* und *Videotext-Untertitel* (an/aus). In der Ansicht wählen Zifferntasten oder ▲▼ / Kanaltasten die Seite, die Farbtasten folgen den Verweisen der Seite (auch als farbige Buttons), die Leiste hat Unterseiten, eine Liste der empfangenen Seiten, Index (100), Transparent und Zurück. Mit Timeshift liest die App den Stream mit, den der Player bekommt (kein zusätzlicher Netzverkehr, Untertitel folgen auch einem pausierten Bild); sonst öffnet sie nur, solange Videotext oder seine Untertitel an sind, eine eigene Verbindung zum Sender (TVHeadend nutzt denselben Tuner). Braucht einen MPEG-TS-Stream (TVHeadend-Profil „pass“, IPTV-TS). Jeder Sender wird beim Start kurz geprüft: Videotext-Einträge und -Buttons erscheinen nur, wenn der Stream Videotext mitbringt. IPTV ohne Timeshift wird nicht geprüft (Anbieter erlauben oft nur eine Verbindung). Untertitel ohne Timeshift können um den Puffer des Players (etwa 1–3 s) zu früh erscheinen.

**Rote Taste (HbbTV, experimentell):** Kündigt der Sender eine HbbTV-Anwendung an, zeigt das Untermenü *Live-TV* den Eintrag *Rote Taste (HbbTV)*; auch die rote Taste der Fernbedienung öffnet sie. Der zusätzliche OSD-Button lässt sich einschalten und ist ab Werk aus. Die Erkennung ist unabhängig von Videotext und liest die vollständigen Programm- und Anwendungstabellen (PMT/AIT), auch wenn diese über mehrere MPEG-TS-Pakete verteilt sind. Ohne erkannte Anwendung erscheinen der Eintrag und der Button nicht. Die TEXT-Taste öffnet den Videotext.

Die HbbTV-Anwendung öffnet sich im Vollbild über dem Live-Bild, mit HbbTV-Browserkennung, Farb-, Medien- und Zurück-Tasten und den grundlegenden OIPF-Objekten; spielt die Anwendung ein eigenes Video ab (oder stoppt das Sendebild), pausiert das Live-Programm und läuft weiter, wenn die Anwendung zurückkehrt oder geschlossen wird; zeigt die Anwendung das Live-Bild in einem kleinen Fenster ihrer Menüs, wird es dorthin verkleinert (alle Player). Senderdaten fehlen, daher läuft nicht jedes Angebot genau wie am Fernseher. Zweimal Zurück schließt sie. Beim ersten Mal erklärt ein Hinweis, dass HbbTV-Inhalte und Nutzungsmessung vom Sender aus dem Internet kommen.

Bei aktiviertem Timeshift puffert die App den laufenden Sender lokal. Pause, Zurückspulen, Vorspulen und **Zurück zu Live** funktionieren mit Media3, VLC und MPV; auch die Rücklauf-/Vorlauftasten der Fernbedienung nutzen die eingestellte Sprungweite. Der Player zeigt den Abstand zu Live und die Wiedergabezeit im Verhältnis zur aktuellen Sendung. Zurückspulen ist nur innerhalb des bereits gepufferten Zeitraums möglich.

Das gilt für TVHeadend und IPTV mit fortlaufenden MPEG-TS-Streams; HLS (`.m3u8`) läuft ohne Timeshift. Der Puffer hält 512 MB Speicher frei und wird beim Beenden gelöscht. TVHeadends HTSP-Timeshift wird nicht verwendet.

**Direktaufnahme:** **Direktaufnahme aktivieren** schaltet die Aufnahmeaktionen im Player frei und ist standardmäßig aus. Im Untermenü **Live-TV** startet **Aktuelle Sendung aufnehmen** die Aufnahme; bei einer vorhandenen Aufnahme heißt die Aktion **Aufnahme stoppen**. Der Freischalter ist bei entsprechenden Serverrechten auch ohne aktuelle EPG-Informationen erreichbar. Die Aufnahmeaktion benötigt eine laufende Sendung mit Ereignis-ID. Geplante Aufnahmen und vom Server bestätigte laufende Aufnahmen werden getrennt angezeigt; **Wird aufgenommen** erscheint nur bei bestätigtem Aufnahmestatus. IPTV bietet keine Aufnahmen/Timer; der lokale Timeshift-Puffer ist keine dauerhafte Aufnahme.

**Erinnerungen:** Künftige Sendungen im Programmführer bieten **Erinnern**, auch ohne Aufnahmerechte. Zum Beginn oder 1, 2, 5 bzw. 10 Minuten vorher erscheinen ein Hinweis mit **Umschalten** und eine Systembenachrichtigung. Der Vorlauf gilt sofort; Erinnerungen überstehen App-Neustarts.

---

## 7. Player-Panel (während der Wiedergabe)

Das Panel öffnet über die Einstellungstaste im Player. Änderungen werden für das aktuelle Video automatisch gemerkt; „Konfiguration speichern / laden“ speichert sie für alle Videos oder alle Videos derselben Breite. Zeilen, die die laufende Engine nicht anwenden kann, sind ausgeblendet.

### 7.1 Menü Wiedergabeeinstellungen

| Eintrag | Optionen / Werte | Sichtbar wenn |
| --- | --- | --- |
| Video | Öffnet [7.2](#72-video) | Video läuft. |
| Player | Automatisch · Media3 · VLC · MPV | Reine Audio-Titel (bei Video unter „Video“). MPV nur, wenn verfügbar. |
| Qualität | Qualitäten des Servers/Streams | Mehr als eine Qualität. |
| Audio | Audiospur + [7.6](#76-audio) | |
| Untertitel | Spur, Untertitel-Verzögerung | |
| Konfiguration speichern / laden | Für alle Videos / diese Breite speichern, laden, zurücksetzen | Video läuft. |
| Serien-Intros überspringen · Abspann überspringen | Aus · Schaltfläche anzeigen · Automatisch | Folgen mit Markierungen. |
| Visualisierung | MilkDrop-Presets | Reine Audio-Wiedergabe. |
| Sleep-Timer | Aus · 15–90 Min. · Am Ende des aktuellen Titels · nach 5–120 Min. Pause · nach 30–120 Min. ohne Eingabe | Beendet die Wiedergabe; schaltet den TV nicht aus. |
| Werkzeuge | A-B-Wiederholung · Einzelbild zurück/vor · Lesezeichen · Bildschirmfoto · Songtexte · lückenlos | Video: A-B, Einzelbild, Lesezeichen, Bildschirmfoto. Musik: A-B, Lesezeichen, Songtexte, lückenlos. |
| OSD & Infos | Bedienleiste · Informationen | Die Beschreibung nennt nur die aktuell angebotenen Einträge. |

**OSD & Infos:** Das gemeinsame Untermenü steht direkt unter *Werkzeuge*. **Bedienleiste** öffnet die Liste der *OSD-Buttons* und bietet die Anzeigedauer der Steuerung mit 3, 5, 8 oder 15 Sekunden (Standard 5). Die Dauer gilt sofort und wird global für alle Player gespeichert. **Informationen** enthält den Schalter *Renderer- und Codec-Leiste* (Standard an). *FPS / Bildwiederholrate / Auflösung* und *Stats für Nerds* erscheinen, wenn der aktuelle Player Diagnosedaten bereitstellt; beide Anzeigen gelten für die laufende Sitzung. Den Wiedergabe-Renderer wählst du unter *Video*.

**Werkzeuge:** Die Beschreibung im Einstellungs-Panel nennt nur die aktuell angebotenen Funktionen und folgt derselben Verfügbarkeitsprüfung wie das Untermenü. Bei Live-TV mit Bild steht dort beispielsweise nur *Bildschirmfoto*; Lesezeichen erscheinen nur bei einem passenden Titel. Wenn keine Werkzeuge angeboten werden, entfällt der Eintrag. Eine aktive A-B-Wiederholung wird innerhalb der Liste gekennzeichnet. *A-B-Wiederholung* spielt in jedem Player zwischen zwei Punkten in Schleife. *Einzelbild* pausiert und zeigt das vorige/nächste Bild. *Lesezeichen* werden je Titel gemerkt (bis zu 30); anspringen, einzeln oder alle löschen. *Bildschirmfoto* speichert das Bild als PNG unter Bilder/Ambient TV (nicht bei geschützten Inhalten). *Songtexte* erscheinen über dem Visualizer: synchronisierte folgen dem Lied mit hervorgehobener Zeile, andere laufen mit. Sie kommen aus Plex-Lyric-Streams oder Jellyfin; *Online suchen (LRCLIB)* ist standardmäßig aus und schickt dann Interpret, Titel, Album und Länge an lrclib.net. *Lückenlose Wiedergabe* (Standard an) reiht in Media3 den nächsten Titel ein, wenn beide direkt abgespielt werden; sonst wechseln Titel wie bisher. Lesezeichen erscheinen als helle Striche in der Seekbar, neben den Kapitelmarken.

**Werkzeug-Buttons:** In den Player-Einstellungen (Zahnrad) › OSD & Infos › OSD-Buttons kann jeder Player die Werkzeuge direkt in der Aktionsleiste zeigen (ab Werk aus, wie Stats für Nerds und FPS): *Bildschirmfoto*, *Lesezeichen* (setzt eins an der aktuellen Stelle), *A-B* (erster Druck A, zweiter B, dritter aus), *Einzelbild* (zurück/vor), *Songtexte* (Musik) und *Werkzeuge* (öffnet das Panel). Ein Button erscheint nur, wo sein Werkzeug passt. Die Liste gruppiert die für den aktuellen Player angebotenen Buttons unter *Wiedergabe*, *Bild & Ton*, *Live-TV*, *YouTube*, *Werkzeuge* und *Informationen*; leere Bereiche entfallen. Jede Zeile behält links das Icon des Buttons, zeigt rechts ein Auge oder durchgestrichenes Auge und nennt den Status *Sichtbar* oder *Ausgeblendet*. Die ganze Zeile dient zum Umschalten.

**Bildaussetzer-Wächter:** Solange Bildbearbeitung läuft (Shader, Filter, SVP, Media3-Effekte, CPU-Deinterlacing), misst jeder Player nach kurzer Anlaufzeit die verworfenen Frames. Über 10 % im Messfenster pausiert die Wiedergabe und fragt: *Einstellungen*, *Reduzieren und weiter* (eine leichtere Stufe, gemerkt wie eine eigene Bildänderung für den Titel: SVP, dann Schärfen, Entrauschen, Deblocking, Filmkorn, Debanding, schwerer Upscaler; VLC schaltet zuletzt die Softwarebearbeitung oder einen CPU-Deinterlacer ab) oder *Trotzdem weiterschauen* (für diesen Titel und diese Filter gemerkt).

**Frame-Drop-Overlay:** Im Videopanel bleibt der schmale, weich animierte Balken erhalten. Daneben steht die Abweichung zur Ziel-Bildrate, etwa **Δ −2,2 FPS · Ziel 50**: Es werden 2,2 Bilder pro Sekunde weniger als erwartet ausgegeben. **Δ 0,0 FPS** bedeutet, dass die Zielrate erreicht wird; ein Plus kennzeichnet eine Ausgabe oberhalb des Ziels im Messfenster. Der Balken ist bei erreichter Zielrate voll und grün, bei 10 % darunter halbvoll und rot. Media3 und VLC messen die Renderer-Ausgabe über die tatsächlich verstrichene Zeit; MPV schätzt die Abweichung anhand verworfener Ausgabe-Frames und kennzeichnet sie mit **≈**. Das Ziel folgt der Videoausgabe einschließlich aktiver Zwischenbildberechnung, bekannter Deinterlacing-Kadenz und Wiedergabegeschwindigkeit. Ein 24-FPS-Video hat deshalb auf einem 60-Hz-Display weiterhin 24 FPS als Ziel. Bei fehlenden Daten, Pause, Pufferung oder einem erkannten Seek zeigt die Anzeige **—** mit neutralem Balken und beginnt anschließend neu zu messen. Unbekannte Filter-Zielraten bleiben ebenfalls **—**.

### 7.2 Video

| Gruppe | Einstellung | Optionen / Werte | Engines | Sichtbar wenn / Beschreibung |
| --- | --- | --- | --- | --- |
| Player | Player wählen | Automatisch · Media3 · VLC · MPV | — | Zeigt, warum die Engine gewählt wurde (globale Vorgabe, Sender, Preset, Ersatz nach Fehler, Dolby-Vision-Schutz). |
| Skalierung | Bildanpassung | Einpassen · Ausfüllen · Strecken | Alle | |
| Skalierung | Seitenverhältnis | Auto · 16:9 · 4:3 · 21:9 | Alle | |
| Skalierung | Zoom-Stufe | 100 %, 110 %, 125 %, 150 % | Alle | |
| Bewegung | Wiedergabegeschwindigkeit | 0,5×, 0,75×, 1×, 1,25×, 1,5×, 2× | Alle | Nicht bei Live-TV. |
| Bewegung | Bewegungsglättung (experimentell) | Aus · Automatisch · Überlastschutz · Ausgewogen · Hohe Qualität | MPV, VLC | SVP verfügbar an, Wiedergabeleistung Maximal, progressives SDR, nicht MPV-Direktausgabe. |
| Ausgabe | Bildschirmmodus | Auto · Videoauflösung + Hz · Auto · volle Displayauflösung + Hz · Systemvorgabe · feste Modi | Alle | Mehr als ein Bildschirmmodus. |
| Ausgabe | MPV-Videoausgabe / MPV-Videodecoder · VLC-Videoausgabe | Wie in [6.5](#65-bildprofile) | MPV, VLC | Gilt für diese Wiedergabe (Live: diesen Sender); Neustart behält die Position. Natives HDR erzwingt die direkte Fläche. |
| Filter / Skalierung / Bildkorrektur / Ausgabe | Engine-Optionen | Siehe 7.3 und 7.4 | je Engine | |

### 7.3 Bildoptionen MPV

Brauchen eine GPU-Ausgabe (OpenGL oder Vulkan). Mit „Android Surface (direkt)“ sind die Filterzeilen ausgeblendet. Shader, CPU-Filter und Bildkorrekturen warten auf bestätigtes SDR.

| Einstellung | Optionen / Werte | Standard | Sichtbar wenn |
| --- | --- | --- | --- |
| Hochskalierung | Integriert: Bilinear, Bicubic, Catmull-Rom, Mitchell, Hermite, Robidoux, RobidouxSharp, Spline16/36/64, Lanczos, Sinc, Ginseng, Gaussian, EWA Lanczos/LanczosSharp/Lanczos4Sharpest/LanczosSoft/Robidoux/RobidouxSharp, Nearest, Oversample. GPU-Shader: NNEDI3-Stufen, RAVU Lite r2/r3/r4, RAVU Zoom r3, FSRCNNX 8/16, ArtCNN C4F16/C4F32 (+DS/DN), Anime4K S/M (+Denoise), CuNNy Fast DS, NVIDIA NIS, FSR (EASU + RCAS) | Lanczos | Video wird hochskaliert (oder Größe unbekannt). Shader nur bei SDR. |
| Chroma-Skalierung | Integrierte außer Oversample, KrigBilateral | Lanczos | Hochskalierung. |
| Verkleinerung | Integrierte außer Oversample / EWA Lanczos4Sharpest, SSimDownscaler | Mitchell | Video wird verkleinert. |
| Anti-Ringing | 0–100 | 0 | Hoch- oder Herunterskalierung. |
| Sigmoid-Skalierung | An / Aus | An | Hoch- oder Herunterskalierung. |
| Farbbänder glätten | Aus · Leicht · Mittel · Stark | Mittel | GPU-Ausgabe. |
| Schärfung | Aus · Standardschärfung: Leicht, Mittel · Shader-Schärfung: AMD CAS, Adaptive Sharpen | Aus | SDR; bei NIS/FSR ersetzt ein Hinweis auf deren integrierte Schärfung die Zeile. |
| Entrauschen · HQDN3D | Aus · Leicht · Mittel | Aus | SDR. |
| Entblocken · CPU | Aus · Leicht · Mittel | Aus | SDR. |
| SSimSuperRes | An / Aus | Aus | SDR; nicht mit NIS/FSR. |
| Helligkeit · Kontrast · Sättigung · Gamma | −20 … +20 | 0 | SDR. |
| HDR-Tonemapping | Auto · BT.2390 · Reinhard · Hable · Möbius · Clip | Auto | HDR-Quelle auf SDR-Ausgabe. |
| Dithering-Bittiefe | Automatisch · Aus · 8-bit · 10-bit | Automatisch | GPU-Ausgabe. |
| Bewegungsglättung (SVP) | Aus · Automatisch · Ausgewogen · Hohe Qualität | Aus | Siehe 7.2. |

### 7.4 Bildoptionen VLC und Media3

| Engine | Einstellung | Optionen / Werte | Standard | Sichtbar wenn |
| --- | --- | --- | --- | --- |
| VLC | Hochskalierung | Fast bilinear, Bilinear, Bicubic, Nearest, Area, Bicublin, Gaussian, Sinc, Lanczos, Spline | Bilinear | SDR, Hochskalierung. Gilt bei FFmpeg-Skalierung; die Android-Surface kann ihn umgehen. |
| VLC | Bildverarbeitung | Hardware · Standard · Software-Filter · SDR | Hardware | SDR. Software startet die Wiedergabe neu. |
| VLC | Farbbänder glätten · Entrauschen · HQDN3D | Aus · Leicht · Mittel | Aus | Software-Filter. |
| VLC | Helligkeit · Kontrast · Sättigung · Gamma | −20 … +20 | 0 | Software-Filter. |
| VLC | Bewegungsglättung (SVP) | Aus · Automatisch · Ausgewogen · Hohe Qualität | Aus | Siehe 7.2. |
| Media3 | Bildverarbeitung | An / Aus | Aus | Bestätigtes SDR (auch H.264 und 8-Bit-HEVC/VP9/AV1 ohne Farbangaben); nicht bei HDR, Dolby Vision oder DRM. Einschalten startet den Decoder an der Position neu. |
| Media3 | Hochskalierung | Bilinear · Bicubic · Catmull-Rom · Lanczos 3 · FSR (EASU + RCAS) · MPV-Kernel (Mitchell, Hermite, Robidoux, RobidouxSharp, Spline16/36/64, Sinc, Ginseng, Gaussian, EWA Lanczos/Sharp/4Sharpest/Soft, EWA Robidoux/Sharp, Nearest, Oversample) · RAVU Lite r2/r3/r4 · RAVU Zoom r3 · NVIDIA NIS | Bilinear | Bildverarbeitung an, Hochskalierung. Bei Renderauflösung Panel/Auto rendert die Kette in der physischen HDMI-Auflösung, 1080p auf einem 4K-Panel wird also mit dem gewählten Scaler hochskaliert. MPV-Kernel folgen MPVs Definitionen (Blur, Fenster, Sigmoid-Upscaling); RAVU und NIS sind MPVs Shader, für Media3 übersetzt. Schwere Scaler fallen über 4K30-Ausgabe auf Catmull-Rom zurück. |
| Media3 | Schärfen | Aus · Leicht · Mittel · Stark · CAS · Adaptiv | Aus | Unscharf-Maske, AMD CAS oder Adaptive Sharpen. NIS schärft selbst. |
| Media3 | Debanding · Entrauschen · Filmkorn | Aus · Leicht · Mittel · Stark | Aus | Entrauschen läuft auf den Quellpixeln vor dem Debanding; Korn kommt nach dem Schärfen. |
| Media3 | Helligkeit · Kontrast · Sättigung · Gamma | −20 … +20 | 0 | Bildverarbeitung an. |

**Media3-Filter öffnen:** Im Videopanel bleibt **Filter** auch bei ausgeschalteter Bildverarbeitung erreichbar. **Bildverarbeitung → An** lässt sich dort direkt wählen; danach erscheinen Schärfen, Entrauschen, Debanding und Filmkorn. Ausschalten lässt den Bereich offen. Bei einer Gerätebeschränkung, Sparsam oder einer ungeeigneten Videospur zeigt der Bereich stattdessen den Grund an. Dazu gehören weiterhin der Schutz vor GPU-Problemen auf NVIDIA-Geräten mit Android 11 oder älter sowie HDR-, Dolby-Vision- und DRM-Spuren.

**Media3-Filterleistung:** Schärfen und Filmkorn werden gegen die Auflösung geprüft, die ihre Shader tatsächlich verarbeiten. Bei Bilinear skaliert erst der Displaypfad: 1080p auf einem 4K-Display belastet diese Filter deshalb wie 1080p. Bei einem aktiven GPU-Upscaler zählt die Ausgabeauflösung. Der bestehende Grenzwert entspricht Full HD bei 60 FPS; bei überschrittenem Budget zeigt der Filterbereich einen Hinweis. Gleiche Video- und Displayauflösung blendet nur den Upscaler aus, nicht die Schärfe- und Filterregler.

Kann die GPU die gewählten Media3-Effekte nicht ausführen (z. B. ohne highp-Präzision), startet die Wiedergabe einmal ohne sie neu, statt abzubrechen.

### 7.5 Deinterlacing

| Einstellung | Optionen / Werte | Standard | Engines | Beschreibung |
| --- | --- | --- | --- | --- |
| Deinterlacing | Aus · Automatisch · An | Aus | VLC, MPV (GPU-Ausgabe) | Bei Media3 und bei HDR unter VLC ausgeblendet. |
| Verfahren (MPV) | Auto · BWDIF · YADIF · W3FDIF · BWDIF 2× · YADIF 2× · W3FDIF 2× · ESTDIF (2×) · NNEDI (2×) · KernelDeint · MCDeint 2× TFF/BFF · IVTC 3:2 | Auto | MPV | CPU-Filter brauchen Copy-back, auch mit Vulkan. |
| Verfahren (VLC) | Auto · Discard · Mean · Blend · X · YADIF · Bob 2× · Linear 2× · YADIF 2× · Phosphor 2× · IVTC 3:2 | Auto | VLC | |

Die Verfahren sind nach Aufwand gruppiert: automatisch, gering, ausgewogen, doppelte Bildrate, Spezialverfahren, Film. Im Modus Sparsam ersetzt die App ein leichteres Verfahren, ohne das gespeicherte zu ändern.

Auf der NVIDIA SHIELD öffnet MPV Interlaced-Sender (1080i H.264, 576i MPEG-2) bei OpenGL- und Vulkan-Ausgabe einmal mit Software-Dekodierung neu: Der MediaCodec-Kopierpfad des Herstellers zeigt solche Streams schwarz oder mit nicht verwobenen Halbbildern und liefert dem automatischen Deinterlacing kein Interlace-Kennzeichen. Renderer und Filter bleiben.

### 7.6 Audio

| Einstellung | Optionen / Werte | Engines | Sichtbar wenn |
| --- | --- | --- | --- |
| Audiospur | Spuren des Titels | Alle | |
| Nachtmodus | An / Aus | Alle | DSP aktiv (nicht bei Passthrough oder DTS-Kern); VLC nur mit DynamicsProcessing. |
| Passthrough bevorzugen | An / Aus | M3, MPV | Nur, wenn der aktuelle Ausgang ein kodiertes Format bestätigt. Ausgeblendet bei Ausgängen ohne Bestätigung, z. B. Handy-Lautsprecher. |
| Komplettes Profil | Alle Zeilen aus [6.6](#66-audioprofile) (Audio-Ausgabe, Klang, Equalizer, Spurvorlieben) | je Engine | Gleiche Sichtbarkeitsregeln wie in den Einstellungen. Live-TV zeigt eine flache Liste. |
| Geplante Ausgabe | Passthrough · DTS-Kern · lokales AC3 · PCM | Alle | Infozeile. |

### 7.7 Konfiguration speichern / laden

| Aktion | Geltungsbereich |
| --- | --- |
| Änderungen werden für dieses Video automatisch gespeichert | Aktueller Titel |
| Für alle Videos mit dieser Breite speichern | Quellbreite, jede Höhe; auch Live-TV, sofern es kein eigenes Preset hat |
| Live-TV-Audio/Video für Breite speichern | Nur Live-Sender; Vorrang vor dem Breiten-Preset |
| Für alle Videos speichern | Globale Vorgaben |
| Konfiguration laden / Dieses Breiten-Preset zurücksetzen / Änderungen für dieses Video zurücksetzen / Vorgaben für alle Videos zurücksetzen | Gewählter Bereich |

Live-TV, Trailer und geteilte Links: Änderungen gelten nur für diese Wiedergabe, außer sie werden ausdrücklich gespeichert.

---

## 8. Leistungsmodi

Oberfläche und Wiedergabe haben getrennte Modi (*Aussehen*). Solange eine Seite nicht eigens gespeichert ist, liest sie die gemeinsame Einstellung, und die ist **Auto**.

| Modus | Oberfläche (reduzierte Effekte?) | Wiedergabe (reduzierte Wiedergabe?) |
| --- | --- | --- |
| Auto | Nur auf schwachen Geräten reduziert (Low-RAM-Kennung, App-Heap ≤ 192 MB oder < 2 GiB RAM). | Nur auf schwachen Geräten reduziert. |
| Ausgewogen | Voll. | Voll. Keine Bewegungsglättung (SVP). |
| Maximale Leistung | Voll. | Voll, und Bewegungsglättung (SVP) ist erlaubt. |
| Sparsam | Immer reduziert. | Immer reduziert. |

**Reduzierte Oberfläche:** kein Blur, keine weichen Schatten und kein Feder-Fokus; ruhiger Startseiten-Hero; kein Home-Hintergrund auf anderen Ansichten; hochauflösende Bilder aus; kleinere Bilddekodierung; Bildschirmschoner auf Sparsam; projectM gedrosselt; Menütöne am TV stumm.

**Reduzierte Wiedergabe:** aufwendige Videopfade nutzen leichtere Ersatzwerte (GPU-Shader, CPU-Entrauschen/-Deblock, VLC-Software-Filter, Media3-Effektkette, 2×-Deinterlacing, Spektrum). Gespeicherte Bildeinstellungen bleiben erhalten und gelten wieder, sobald Sparsam verlassen wird. Audio-DSP bleibt verfügbar.

„Oberfläche prüfen“ und „Wiedergabe prüfen“ messen kurz im Hintergrund und speichern das Ergebnis für die jeweilige Seite.

---

## 9. App & Daten

| Seite | Einstellung | Optionen / Werte | Standard | Beschreibung |
| --- | --- | --- | --- | --- |
| Benachrichtigungen | Anzeigedauer | Aus · Kurz · Normal · Lang | Normal | Der Verlauf zeigt die letzten 20 Ergebnisse dieser Sitzung. |
| Accounts und Einstellungen übertragen | Geräte synchronisieren | Accounts / Accounts mit Einstellungen / Menütöne | — | Sendet an ein gekoppeltes Gerät; das Zielgerät bestätigt. |
| Einstellungen sichern | Ohne Zugangsdaten exportieren · Mit Zugangsdaten exportieren · Importieren | — | — | Export nutzt die gespeicherten Werte. Verschlüsselte Backups fragen nach einer Passphrase. |
| Diagnose | Fehlerprotokoll schreiben | An / Aus | Aus | Bis etwa 3 MB. „Protokoll herunterladen (ZIP)“, „Protokoll löschen“. Geheimnisse werden entfernt. |
| Über die App | Player, Mediendienste, Lizenzen | — | — | App beenden (TV). |

---

## 10. Einrichtung

| Voraussetzung | Wert |
| --- | --- |
| Android | minSdk 29, target/compileSdk 37 |
| Gerät | Android TV / Leanback und/oder Handy/Tablet |
| Netzwerk | LAN zu den Medienservern; Internet für SmartTube, Spotify, Plex-Link, TMDB |
| Lokaler Netzwerkzugriff | Android fragt ggf. für lokale Server, SMB/NFS, WOL, Spotify Connect, UPnP-Receiver |
| Kamera | Nur für „QR-Code scannen“ beim Koppeln des Handys |

Beim ersten Start bietet ein Einrichtungsbildschirm Plex, Jellyfin, Emby, TVHeadend, SmartTube (mit oder ohne Anmeldung), Spotify und Dateien an. Weitere Systeme fügst du später unter *Medienquellen & Konten* hinzu. Plex, Jellyfin, Emby, TVHeadend und Spotify erscheinen in Menü, Startseite und Suche erst, wenn der Server Adresse und Anmeldung akzeptiert hat; ein späteres HTTP 401 blendet das System aus, bis eine Anmeldung wieder gelingt.

---

## 11. Navigation und Bedienung

### 11.1 Android TV

- Sidebar mit Systemreihenfolge (einstellbar), Home, Suche, Einstellungen
- Android-TV-Systemsuche und Sprachsuche („Suche nach … in Ambient TV“) finden Filme und Serien aus Plex, Jellyfin und Emby; ein Treffer verbindet (und weckt) zuerst den Server und öffnet den Titel. Als offline bekannte Server werden übersprungen
- Ein offline Server bleibt als „… · Offline“ im Menü; Übersicht und Mediatheken zeigen eine Offline-Seite mit Aufwecken / Neu verbinden und laden von selbst, sobald er antwortet
- D-Pad: Fokusgruppen, Scrollpositionen in Einstellungs-Unterseiten
- Senkrechter Umlauf in Panels, Dialogen und langen Listen: Ab unten geht es oben weiter, ab oben unten. Der Umlauf greift nur, wenn in der Richtung sonst nichts erreichbar ist — liegt etwas über der Liste (Kopfzeile, Filterchips), führt Ab-nach-oben wie bisher dorthin
- Einstellungen-Zurück: Unterseite → Übersicht/Suche → Bereich → Hub verlassen
- Miniplayer und Hintergrundwiedergabe bleiben beim Stöbern erhalten (sichtbares Hintergrundvideo optional)
- Verlässt du die App (Home, andere App, TV aus, Bildschirm aus): Ein Video pausiert sofort und wird nach 30 Sekunden beendet, die Position ist gespeichert. Bist du vorher zurück, steht es pausiert da. Live-TV und Timeshift enden sofort. Musik und Hörbücher laufen am Handy weiter, am TV bis zum Standby. Schließen über „Zuletzt verwendet“ beendet alles. Dateiauswahl, Installation und Anmeldung, die die App selbst öffnet, beenden nichts.

### 11.1a Android-TV-Startbildschirm

Angefangene Filme und Folgen aus Plex, Jellyfin und Emby erscheinen in der **Weiterschauen**-Reihe des Launchers (Watch Next) und werden beim Stoppen aktualisiert; fertig geschaute Titel verschwinden wieder. Beim Laden der Startseite gleicht die App die Reihe mit der Weiterschauen-Liste jedes Servers ab, sodass auf einem anderen Gerät beendete oder begonnene Titel folgen (nur bei aktiver Weiterschauen-Reihe und erreichbarem Server; gerade hier gestoppte Titel haben eine Minute Vorrang). Zusätzlich veröffentlicht die App die Kanäle **Weiterschauen**, **Kürzlich hinzugefügt** und **Live-TV** (zuletzt gesehene Sender, dann die Live-Reihe mit laufender Sendung; nur aus aktiven Live-TV-Reihen der Startseite), aktualisiert beim Laden der Startseite. Der erste Kanal mit Inhalt wird automatisch angeboten, weitere fügt man im Launcher über „Kanäle anpassen“ hinzu. Ist ein Server offline, schläft er oder lädt Live-TV noch, bleibt der letzte Stand der Kanäle stehen. Ein Klick verbindet zuerst wie die Startseite (VPN, Erreichbarkeit, Wake-on-LAN mit voller Wartezeit) und öffnet dann den Titel (Weiterschauen startet die Wiedergabe) bzw. spielt den Sender ab; ist der Server nicht erreichbar, bleibt die App mit der Verbindungsmeldung auf der Startseite. Die Kanäle übernehmen die Reihen-Einstellungen: nur aktive Reihen, deren eigener Titel (bei genau einer Reihe) und Poster- bzw. Querformat. Poster liefert die App selbst aus, ohne Server-Zugangsdaten an den Launcher zu geben. Abschaltbar unter Einstellungen › Startseite › **Auf dem Startbildschirm zeigen**. Welche Reihen sichtbar sind, entscheidet der Launcher; Google TV zeigt Kanäle fremder Apps nicht mehr an.

### 11.1b Von anderen Apps empfangen (DLNA-Renderer)

Unter Einstellungen › Geräte & Netzwerk › UPnP-Receiver › **Im Netzwerk als Abspielgerät anbieten** (standardmäßig aus) meldet sich die App im Heimnetz als UPnP/DLNA-Abspielgerät („Media Renderer“). Apps wie BubbleUPnP, Web Video Caster, LocalCast oder VLC finden sie dann als Ziel und senden Videos oder Musik; die App spielt sie mit dem eingestellten Player (MPV/VLC/Media3) samt Bild- und Audio-Einstellungen ab. Der Sender steuert Wiedergabe, Pause, Spulen, Stopp und Lautstärke; Position und Status sieht er live. Unterstützt werden direkte HTTP(S)-Medien-URLs (MP4, MKV, WebM, TS, HLS/m3u8, DASH, MP3, FLAC u. a.), auch ohne Dateiendung, wenn der Sender den Typ angibt; DRM-Inhalte und Bilder nicht.

Sicherheit: Der Dienst antwortet nur Geräten im selben lokalen Subnetz (nicht über VPN-Tunnel oder Mobilfunk), Ereignisse gehen nur an den anfragenden Sender zurück (höchstens vier Abos pro Gerät), Anfragen sind in Größe und Dauer begrenzt. Pause, Spulen, Stopp und Lautstärke darf nur das Gerät, das die Wiedergabe gestartet hat (oder ein freigegebenes); die Lautstärke nur, während dessen Wiedergabe läuft. Im Hintergrund startet oder wechselt nichts Neues, die laufende Wiedergabe bleibt steuerbar. Vor dem ersten Abspielen fragt der TV nach („Abspielen“, „Immer von diesem Absender“, „Ablehnen“; ohne Antwort nach einer Minute abgelehnt). Eine Freigabe gilt für die sendende App unter ihrer Adresse: Bekommt später ein anderes Gerät dieselbe IP-Adresse, fragt der TV erneut. Freigegebene Geräte lassen sich in den Einstellungen vergessen; beim Ausschalten werden sie gelöscht. Der Renderer ist erreichbar, solange die App geöffnet ist oder etwas Empfangenes abspielt; im Hintergrund meldet er sich ab. Empfangene Inhalte erscheinen nicht im Verlauf und nicht in „Weiterschauen“.

Der Cast-Button von Chrome/Google Cast kann diese App nicht ansprechen (Google Cast ist an zertifizierte Geräte und die Empfänger-App der jeweiligen Webseite gebunden); dafür sind Sender-Apps mit DLNA-Unterstützung der Weg.

### 11.2 Handy

- Untere Navigation: Home, Systeme, Suche, Einstellungen
- Systeme → **Menü verwalten**: gleiche Reihenfolge und Namen wie am TV (im Hochformat einspaltig)
- Einstellungen: Bereichs-Tabs statt linker Spalte
- Video: Querformat Vollbild; Hochformat mit Navigation; optionaler Vollbild-Schalter bei gesperrter Rotation
- Touch-Zeitleiste: Ziehen = Vorschau, Loslassen = Seek

### 11.3 Medienauswahl

Beim Auswählen in Plex/Jellyfin/Emby: Detailseite, sofort abspielen oder Auswahlfenster. SmartTube hat eine eigene Einstellung in der Ansichtsanpassung.

### 11.3a Bildeinstellungen pro Titel

Bild- und Filtereinstellungen (Upscaler, Schärfe, Deband, Entrauschen, SVP, Deinterlacing, Videoausgabe/Decoder) werden pro Titel gemerkt, wenn sie vom Standard abweichen, und beim nächsten Abspielen desselben Titels sofort angewendet (MPV, VLC, Media3). Gemerkt werden die letzten 200 Titel; Live-TV und Trailer nicht. **Als Standard speichern** gilt weiterhin für alle Titel ohne eigene Werte.
Auch die im Player eingestellte **Audioverzögerung** und der **Untertitel-Verzögerung** (Untertitel-Panel, MPV und VLC) gelten pro Titel; die Standard-Verzögerung steht unter Einstellungen › Audio. Im Video-Panel zeigt eine Zeile, ob für den Titel Werte gespeichert sind, und **Diesen Titel auf Standard zurücksetzen** löscht sie.

### 11.4 Sleep-Timer

Bestehende Sleep-Timer sind eine **Player-Sitzungsfunktion**, keine dauerhafte Einstellungsseite.

Läuft ein Timer mit fester Dauer oder „ohne Eingabe“ ab, wird zuerst **pausiert** und gefragt: **Weiterschauen** (Timer aus), **Timer neu starten** oder **Jetzt beenden**. Ohne Antwort stoppt die Wiedergabe nach 60 Sekunden. Play außerhalb des Dialogs zählt als Weiterschauen. „Pausiert“ und „Ende des Titels“ stoppen ohne Nachfrage.

---

## 12. Handy, Tablet und TV zusammen

**Fernbedienung und Import:** Im verbundenen Gerätefenster stehen D-Pad, Touchpad und Texteingabe sowie **Accounts und Einstellungen senden** bereit. Die Fernbedienung steuert ausschließlich Fenster dieser App; nur ein Sender kann gleichzeitig steuern. Der TV zeigt den aktiven Controller an und kann ihn jederzeit trennen. Sicherheits- und Importbestätigungen lassen sich nicht vom Sender bedienen.

Beim Senden wählt man **Nur Accounts** oder **Accounts und Einstellungen**, optional aus einer vorhandenen Backup-Datei. Zugangsdaten werden nur nach ausdrücklicher Auswahl mitgesendet. Der TV zeigt eine eigene Bestätigung: Accounts können ohne Überschreiben ergänzt werden; Einstellungen benötigen eine ausdrückliche Überschreib-Zustimmung. Gerätespezifische Bild-/Ton-/Player-Einstellungen sind separat abwählbar und standardmäßig ausgeschlossen. Lokale Dateien, VPN, Kopplungen und Verlauf werden nicht übertragen. Vor dem Anwenden müssen Wiedergabe beendet und offene Einstellungsänderungen gespeichert sein. Eine verschlüsselte lokale Sicherung ermöglicht die Wiederherstellung des vorherigen Zustands. Übertragung, Annahme, Ablehnung und Fehler erscheinen als App-Benachrichtigungen. Maximal 512 KiB pro Übertragung, Bestätigung innerhalb von zwei Minuten; keine automatische dauerhafte Synchronisierung. Beide Geräte benötigen die aktualisierte App.

Am TV öffnet **Einstellungen → Geräte & Netzwerk → Handy koppeln → Handy koppeln** eine Einladung für zwei Minuten mit QR-Code, achtstelligem Code und Restzeit; **Neuen Code erzeugen** startet sie neu. Am Handy führt **TV koppeln** (Tab **Fernbedienung** oder Einstellungen → Handy & TV) in drei Schritten durch die Kopplung: 1. Kopplung am TV starten (ein gefundener TV wird angezeigt), 2. QR-Code scannen – oder über **Scannen nicht möglich? Code eingeben** den gefundenen TV wählen und den Code eintippen; ein Einladungslink steht unter **Weitere Möglichkeiten**, 3. Vergleichscode auf beiden Geräten prüfen und bestätigen. Danach schließt der TV seinen Kopplungsdialog, beide Geräte melden „Gekoppelt mit …“, und das Handy bietet Fernbedienung oder Einstellungen übertragen an. Bricht jemand den Vergleich ab, erzeugt der TV automatisch einen neuen Code. Fünf falsche Codes beenden das Kopplungsfenster. Ein alter Eintrag desselben TVs (gleicher Name und gleiche Adresse, z. B. nach Neuinstallation) wird beim erneuten Koppeln ersetzt. Die Übersicht **Handy & TV** am Handy zeigt den Verbindungsstatus und je gekoppeltem TV eine Karte mit **Verbinden**/**Fernbedienung** und **Daten übertragen**; die Karte öffnet die Detailseite mit Aktionen, Verbindung (VPN, Port, Wake-on-LAN) und **Entkoppeln**. Die Ausgabezeile unten erscheint nur, solange dieser TV gekoppelt und verbunden ist.

Die Apps müssen im selben IPv4-LAN (WLAN oder Ethernet, gleiches Subnetz) geöffnet bleiben. Der Empfänger ist zunächst ausgeschaltet und wird mit der ersten Einladung aktiviert. Er läuft nur bei geöffneter TV-App. Es gibt kein Aufwecken einer geschlossenen App, keinen Companion-Zugriff über Internet oder VPN und keine automatische Wiedergabe auf dem Handy bei Verbindungsverlust.

Bibliothekseinträge von Plex/Jellyfin/Emby, YouTube/Trailer, Live-TV, DLNA und SMB/NFS werden als Inhaltsreferenz übergeben. Medienserver, Netzwerkfreigaben beziehungsweise DLNA-Server müssen auch am TV eingerichtet sein. Für Bibliotheken muss derselbe Server aktiv sein; eine abweichende TV-Konfiguration wird nicht automatisch umgeschaltet. Bei Bibliothekseinträgen werden keine Zugangsdaten oder aufgelösten Stream-URLs übertragen. Der TV bestimmt seine passende Medienversion und meldet den Fortschritt an seine Quelle. Lokale Handydateien sind nicht übertragbar. Spotify verwendet weiterhin Spotify Connect.

Android **Teilen → Ambient TV → An [Gerätename] senden** akzeptiert genau einen YouTube-Link oder direkten HTTP(S)-Medienlink. Unterstützte Endungen: MP4/M4V, MKV, WebM, MOV, AVI, TS/M2TS, MPG/MPEG, OGV, HLS/M3U8, DASH/MPD sowie MP3, M4A, AAC, FLAC, OGG/OGA, Opus, WAV und WebA. Tatsächliche Wiedergabe hängt von Erreichbarkeit, Codec und Renderer ab; DRM, Webseiten-Extraktion, Links ohne Medienendung sowie lokale `content://`-/`file://`-Dateien werden nicht unterstützt. Der ausdrücklich geteilte Link wird einschließlich vorhandener Query-Parameter über die verschlüsselte Verbindung übertragen. Der TV fügt keine Zugangsdaten seiner eingerichteten Medienserver hinzu. Geteilte Links liegen nur in einem begrenzten Speicher im laufenden Prozess; sie werden nicht als Bibliothek oder Verlauf gespeichert. HTTP-Medien selbst sind unverschlüsselt; HTTPS bevorzugen.

Für gekoppelte TVs veröffentlicht das Handy zusätzlich Android-**Direct-Share**-Ziele **An [Gerätename] senden**. Diese Auswahl verbindet den konkreten gespeicherten TV und startet den Inhalt ohne erneute Zielauswahl. Android bzw. der Launcher bestimmt Sichtbarkeit, Reihenfolge und Anzahl dieser Vorschläge; der normale App-Eintrag bleibt der Ausweichweg. Die App prüft die gespeicherte Kopplung und den Zertifikats-Fingerabdruck erneut, löst die aktuelle LAN-Adresse auf und sendet den Startbefehl einmal. Kein Ausweichen auf andere Geräte, kein automatisches Wiederholen bei Fehlern. Beim Schließen der Handy-App wird ein noch laufender Sendevorgang abgebrochen. **Entfernen** am Handy entfernt auch den Shortcut; alte Shortcut-Aufrufe können keine entfernte Kopplung ersetzen. Ein Launcher-Shortcut ohne geteilten Link öffnet nur das Gerätefenster.

Der QR-Code enthält eine einmalige Einladung und den vollständigen Zertifikats-Fingerabdruck. TLS 1.2/1.3 prüft diesen Fingerabdruck; das Handy authentifiziert sich mit einer signierten, frischen Herausforderung und einem nicht exportierbaren Android-Keystore-Schlüssel. Erst nach beidseitiger Bestätigung wird die Geräteidentität gespeichert. Sitzungskennung und fortlaufende Befehlsnummern verhindern die Wiederholung alter Befehle. Kopplungen werden nicht im Settings-Backup exportiert. **Entfernen** am TV widerruft den Zugriff und trennt aktive Verbindungen sofort.

Verbindung, Trennung, Wiedergabeanforderung und Widerruf erscheinen in den bestehenden nicht fokussierbaren App-Benachrichtigungen und deren Verlauf; abgewiesene Zugriffe werden zusammengefasst. Die gewählte Anzeigedauer gilt auch hier. Die Ausgabezeile bleibt ausgeblendet, solange kein TV gekoppelt oder die Verbindung getrennt ist. Wiedergabe/Pause, Stoppen und die Zeitleiste steuern den gewählten TV. Ungespeicherte Einstellungen am TV verhindern einen Fernstart.

Entwicklertests: `:app:testDebugUnitTest --tests 'com.ambient.tv.companion.*'`; der Geräte-Audit verwendet eine separate Debug-App mit `-PAUDIT_APP_SUFFIX=.companionaudit`, `:app:assembleDebugAndroidTest` und Instrumentation-Argument `-e companion true`. Er prüft echte Keystore/TLS-Kopplung zwischen zwei isolierten Instanzen, Einwilligung, Widerruf und die Zuordnung von Medienquellen. Das ersetzt keinen Wiedergabetest zwischen einem Handy und einem separaten TV.

---

## 13. Sicherung und Wiederherstellung

Unter **App & Daten › Sicherung**:

- **Ohne Zugangsdaten exportieren** (Standard): keine Plex-Tokens, kein TVHeadend-Passwort; Dateiquellen-Passwörter nur bei Export mit Zugangsdaten. Vor dem Speichern kann optional ein Passwort gesetzt werden (zweimal einzugeben, Anzeigen/Verbergen); leer lassen ergibt wie bisher eine unverschlüsselte JSON-Datei
- **Mit Zugangsdaten exportieren:** nur bei Bedarf; die Datei wird mit einer Passphrase (mindestens 8 Zeichen, AES-256-GCM, PBKDF2) verschlüsselt, die beim Import abgefragt wird. Ohne Passphrase ist das Backup nicht wiederherstellbar. Die Übertragung per Companion bleibt TLS-geschützt.
- Verschlüsselte Dateien sind ein kleiner JSON-Umschlag (`"format":"ambient-tv-encrypted-backup"`, Version 1, PBKDF2-HMAC-SHA256 mit 310 000 Iterationen, zufälliges 16-Byte-Salt und 12-Byte-IV, AES-256-GCM). Der Import erkennt unverschlüsselte und verschlüsselte Dateien automatisch und fragt nach dem Passwort; bei falschem Passwort oder beschädigter Datei erscheint ein Hinweis und der Dialog bleibt für einen neuen Versuch offen.
- Import stellt Einstellungen und Serverprofile wieder her (Schema bis aktuell **72**)
- IPTV-Wiedergabelisten sowie OpenSubtitles-Schlüssel und -Konto sind nur in Backups **mit Zugangsdaten** enthalten (Listen-Adressen enthalten oft das Anbieterkonto); ein Backup ohne Zugangsdaten lässt die vorhandenen auf dem Gerät unverändert. Timeshift-Größe, Timeshift-/Direktaufnahme-Schalter (ab Schema 59) und Erinnerungs-Vorlauf werden immer gesichert, geplante Erinnerungen nicht.
- Dateiquellen und WOL werden gesichert; **lokale Ordner-Berechtigungen (SAF) sind nicht übertragbar** und müssen auf dem neuen Gerät erneut erteilt werden
- OAuth-Sitzungen und Gerätekennungen sind nicht als „übertragbare Anmeldung“ gedacht – Konten ggf. neu anmelden

Unter **App & Daten › Diagnose** (Fehlerprotokoll für Release-Builds):

- **Fehlerprotokoll schreiben** (standardmäßig aus): Warnungen und Fehler der App, fehlgeschlagene Server-Aufrufe (HTTP ≥ 400 und Verbindungsfehler mit Methode, Host, Pfad, Dauer – ohne Query-String), Wiedergabefehler von Media3/VLC/MPV, Fehler-Benachrichtigungen und Abstürze. Rotierende Dateien im App-Speicher, höchstens ca. 3 MB.
- **Protokoll herunterladen (ZIP):** `device.txt` (App-/Android-Version, Gerät), `app.log` und `logcat.txt` (die eigenen Systemprotokoll-Zeilen der App, inkl. Player-Ausgaben). Ohne Dokumentauswahl (häufig auf Android TV) landet die ZIP unter `Android/data/<App>/files/Download/`.
- Tokens, Passwörter, API-Schlüssel, Authorization-Header, Cookies und `user:passwort@` in URLs werden vor dem Schreiben entfernt; Serveradressen und Pfade bleiben für die Fehlersuche erhalten. **Protokoll löschen** entfernt alle Dateien.

---

## 14. Entwicklung und Build

### 14.1 Projektstruktur (kurz)

- `app/` – Android-App (Kotlin, Jetpack Compose, Hilt)
- `native/` / `scripts/build_mpv_android.sh` – MPV-JNI
- `scripts/build_projectm_android.sh` – projectM
- `scripts/build_spotify_engine.sh` – Spotify native (rusty/librespot)
- `scripts/fetch_smarttube_engine.sh` – YouTube/SmartTube-Pins
- `smarttube_wrappers/` – Host-Wrapper für SmartTube-Module
- `gradle/external-versions.properties` – gepinnte externe Engines
- `build.sh` – Haupt-Entrypoint für APK und Native

### 14.2 Anforderungen zum Bauen

- JDK **17**
- Android SDK / NDK (für native Engines)
- Netzwerkzugriff für Abhängigkeiten und ggf. Engine-Fetches
- Optional: `keystore.properties` für signierte Release-Artefakte (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`)
- Optional: `spotify.properties` / Gradle-Properties für `SPOTIFY_REDIRECT_URI`, dazu `SPOTIFY_QR_REDIRECT_URI` für die QR-Anmeldung am TV (Standard: [Relay-Seite](https://seinist.github.io/ambient-tv-spotify-callback/spotify-callback.html) aus [seinist/ambient-tv-spotify-callback](https://github.com/seinist/ambient-tv-spotify-callback); Build-Zeit; es ist keine Client-ID eingebaut – jede:r trägt die eigene in der App ein; keine Secrets in die Doku oder öffentliche Issues schreiben)

### 14.3 `./build.sh` (Auswahl)

```bash
./build.sh debug          # MPV (falls nötig) + Debug-APK
./build.sh release        # Release-APK
./build.sh universal      # universal Release-APK (alle ABIs)
./build.sh arm64          # arm64-v8a
./build.sh armv7          # armeabi-v7a
./build.sh x86            # x86 (Emulator)
./build.sh x86_64         # x86_64 (Emulator)
./build.sh all            # alle Android-Artefakte
./build.sh mpv            # nur native MPV-Bibliotheken
./build.sh libs-check     # Updates gepinnter Engines prüfen
./build.sh libs-update    # Pins aktualisieren (ohne Build)
./build.sh gen-keystore   # keystore/release.jks anlegen
```

Native Engines werden für `arm64-v8a`, `armeabi-v7a`, `x86` und `x86_64` gebaut.  
Ausgabe typischerweise unter `dist/` (z. B. `ambient-tv-arm64-<version>.apk`, `ambient-tv-x86_64-<version>.apk`). Vorab-Suffixe wie `beta`, `alpha` oder `rc` bleiben am Ende, die Build-Nummer steht davor (`ambient-tv-arm64-1.0.270-beta.apk`).

Umgebungsvariablen u. a.: `SKIP_MPV_NATIVE=1`, `REBUILD_MPV=1`, `MPV_ANDROID_REF`, `ADB_DEVICE` für `*-adb`-Modi.

Eine Änderung am MPV-JNI-Shim (`native/ambientmpv/ambientmpv.c`) löst beim nächsten Build einen Neubau der nativen MPV-Bibliotheken aus. libmpv schreibt mit Stufe `warn` und geschwärzten Zugangsdaten ins Logcat (`AmbientMPV`); für ausführliche Diagnose vor dem Abspielen `adb shell setprop debug.ambienttv.mpv.log v` setzen (Stufen: `no`, `fatal`, `error`, `warn`, `info`, `status`, `v`, `debug`, `trace`). Der MPV-Wiedergabestatus ist ereignisbasiert: Der JNI-Shim stellt eine Wakeup-Pipe und beobachtete Properties bereit (Pause, Puffern, Seek, Ende, Dauer, Codecs, Decoder, Spuranzahl); ein Warte-Thread weckt den UI-Thread nur, wenn mpv Ereignisse hat.

Externe Bibliotheken: siehe [`EXTERNAL_LIBRARIES.md`](../EXTERNAL_LIBRARIES.md).

### 14.4 Tests

Unit-Tests liegen unter `app/src/test/`. Vollständige Geräteabnahme (TV-Fernbedienung, echte NAS-/AVR-/Login-Pfade) ist von Quell-/CI-Checks getrennt zu sehen.

**Baseline-Profil:** `scripts/capture_baseline_profile.sh SERIAL` zeichnet auf einem Gerät ab Android 13 auf, welcher App-Code beim Navigieren läuft, und ergänzt `app/src/main/baseline-prof.txt`. Es nutzt den Build-Typ `profileCapture` (Release-Code ohne Verschleierung, nicht debuggable, installiert neben der App). Mit `audit` statt manueller Bedienung fährt der UI-Performance-Audit die Startseiten- und Bibliotheks-Layouts ab. Das Profil wird beim Installieren vorkompiliert, auch bei In-App-Updates.

---

### 14.5 Releases und Versionierung

Ein gepushter `v*`-Tag startet `.github/workflows/release.yml`: MPV wird nativ über `build.sh` gebaut, dazu die vier signierten Release-APKs (`arm64`, `armv7`, `x86`, `x86_64`) und die Debug-APKs (`…-debug.apk`); alle landen zusammen mit `SHA256SUMS.txt` in einem GitHub-Release. Tags mit `alpha`, `beta` oder `rc` werden als Vorabversion markiert.

Benötigte Repository-Secrets: `AMBIENT_TV_KEYSTORE_BASE64` (Release-Keystore als Base64), `AMBIENT_TV_KEYSTORE_PASSWORD`, `AMBIENT_TV_KEY_ALIAS`, `AMBIENT_TV_KEY_PASSWORD`. Optional: `SPOTIFY_REDIRECT_URI`, `SPOTIFY_QR_REDIRECT_URI` (Standard: Relay-Seite aus [ambient-tv-spotify-callback](https://github.com/seinist/ambient-tv-spotify-callback)).

Die Versionierung steht in `version.properties`:
- `VERSION_CODE` steigt bei jedem lokalen Build, der ein APK/AAB erzeugt (`assemble*`, `bundle*`, `install*`, `package*` – auch aus Android Studio und `./build.sh`), um eins. Sync, reine Tests, `clean` und CI (`CI` gesetzt) ändern ihn nicht; `-PversionCode=N` überschreibt. Die Datei wird nicht automatisch committet.
- `VERSION_NAME` gilt für lokale Builds. Release-Builds nehmen den Tag ohne führendes `v` (`v1.0.0-beta` → `1.0.0-beta`) über `-PversionName` / `ORG_GRADLE_PROJECT_versionName`. Die APK-Dateinamen nutzen denselben Wert; ein Vorab-Suffix (`beta`, `alpha`, `rc`, …) bleibt am Ende, die aktuelle Build-Nummer steht davor (`1.0-beta` → `1.0.270-beta`).
- Bei jedem gezählten Build wird die Version zwischen den Markern `<!-- version:start/end -->` und `<!-- version-badge:start/end -->` in beiden READMEs aktualisiert.

## 15. Grenzen und Hinweise

- **Fehlergründe in Hinweisen:** Verbindungstests, Anmeldungen (Plex, Jellyfin, Emby, YouTube, Spotify), Verbindungs-Hinweise der Server und Teilfehler beim Laden der Startseite nennen den konkreten Grund, z. B. „Anmeldung abgelehnt (HTTP 401)“, „keine Verbindung zu 192.168.1.5:32400“, „HTTPS-/Zertifikatsfehler“, „Adresse nicht gefunden“, „Zugriff auf das lokale Netzwerk nicht erlaubt“. Tokens, Passwörter und URL-Parameter werden aus Fehlertexten entfernt; Details stehen zusätzlich im Logcat.

- Keine Garantie für jedes Codec-/Bitstream-/Filter-Szenario auf jedem Gerät
- Dateien: kein NFS-Kerberos, kein NFSv4.1+, keine NetBIOS-Suche; Coverdatenbank für Dateilisten ist bewusst schlicht (Symbole)
- Spotify-DSP der Video-Player gilt nicht für Spotify Native
- Performance-Sparsam ändert effektive Video/Visual-Pfade, löscht aber nicht dauerhaft gespeicherte Bildprofile
- Drittanbieter-Logos und Upstream-Komponenten unterliegen eigenen Lizenzen (`third_party_licenses/`, About-Seite)
- Marken: Plex, Jellyfin, Emby, YouTube, SmartTube, Spotify usw. gehören den jeweiligen Rechteinhabern; der App-Anzeigename steht in `branding.properties`

---

## Siehe auch

- [DOCUMENTATION.md](DOCUMENTATION.md) – English (Standard)
- [docs/README.md](README.md) – Index
- [README.md](../README.md) · [README.de.md](../README.de.md) – Kurzübersicht für GitHub
