# Media3: 4K-Renderziel und Upscaler-Freigabe

Stand: 29. Oktober 2026. Ausgangspunkt: `main` / `92435d7`.

## Ziel

Bei aktiver 3840×2160-Ausgabe und einer 1920×1080-TV-Oberfläche soll Media3s
freigegebener GPU-Bildverarbeitungspfad auf das physische Video-Rechteck rendern.
Für 1080p-/720p-Quellen müssen die dafür geeigneten Upscaler auswählbar sein.
Native 4K-Quellen benötigen bei einem gleich großen Ziel keinen Upscaler.

## Befund

- Die bestehende Panel-Option setzte bereits eine feste Surface-Größe. Sie
  beobachtete jedoch nur View-Größenänderungen. Ein physischer Moduswechsel bei
  unveränderter logischer Oberfläche konnte deshalb die alte Größe zurücklassen.
- Menüfreigabe und Shaderziel verwendeten dieselben Analytics-Surface-Abmessungen.
  Eine noch 1080p große Surface klassifizierte eine 1080p-Quelle als native Ausgabe,
  auch wenn die ausgewählte Render-Policy ein 4K-Ziel vorsah.
- Media3 meldete keine renderer-eigene Skalierung an die Displaymoduswahl. Bei
  Systemmodus mit einer Liste erlaubter Modi konnte diese auf die Quellauflösung
  umschalten und damit die gewünschte 4K-Zielfläche entfernen.

## Änderung

`Media3SurfaceResolutionController` beobachtet Attach, Detach, View-Größe und den
aktiven Displaymodus. Er berechnet das physische Ziel aus der vorhandenen
`physicalVideoRenderSize`-Abbildung und veröffentlicht es auch vor Aktivierung
der Bildverarbeitung. Die feste Surface-Größe wird nur auf dem eingeschalteten,
geeigneten SDR-Pfad gesetzt und bei unveränderter Größe nicht erneut angefordert.

`Media3RenderGeometry` trennt das angeforderte Ziel für die Menüfreigabe von der
bestätigten Surface-Größe. Effektstufen, Shaderausgabe und GPU-Budgets verwenden
weiter ausschließlich die tatsächlich gemeldete Surface-Größe. Ein altes
Fullscreen-/Miniplayer-View kann die Geometrie eines neuen Hosts nicht löschen
oder überschreiben. Erneutes Anhängen beansprucht die Geometrie neu.

Der Host berücksichtigt Sparsam und Effektfehler. Performanceänderungen erreichen
die Bildverarbeitung auch ohne Größenänderung oder geöffnetes Einstellungsmenü.
Der eingeschaltete, geeignete Bildverarbeitungspfad meldet frühzeitig
`usesRendererScaling`, damit Systemmodus mit erlaubter Modusliste die ursprüngliche
Ausgabeauflösung erhalten beziehungsweise wiederherstellen kann. Dafür muss noch
keine Scale-Stufe laufen und kein bestimmter Upscaler ausgewählt sein.

## Beibehaltene Entscheidungen

- `Panel` folgt der **aktiven** Ausgabeauflösung und dem belegten Video-Rechteck.
- `UI` bleibt eine ausdrückliche Wahl der logischen Auflösung.
- `Auto` behält die bestehende Low-End-Policy; auf solchen Geräten kann `Panel`
  ausdrücklich gewählt werden, sofern die Bildverarbeitung freigegeben ist.
- `Auto · Videoauflösung + Hz` hat weiterhin Vorrang und darf auf 1080p umschalten.
  Für Hochskalierung auf die höchste erlaubte Displayauflösung ist
  `Auto · volle Displayauflösung + Hz` vorgesehen.
- HDR-/DRM-Sperren, Geräteausnahmen und die Freigabe über den Master
  „Bildverarbeitung“ werden nicht aufgehoben. Stream-Qualitätsgrenzen bleiben
  unabhängig von der Renderauflösung.

## Verifikation

Neue JVM-Regressionen prüfen Policy und Sicherheitsbedingungen, das getrennte
angeforderte/bestätigte Ziel, Owner-Wechsel sowie System-/Whitelist-Moduswahl.
Die bestehende Upscaling-Prüfung wird zusätzlich ausgeführt. Die lokalen Tests
verwenden den vorhandenen Kotlin-/JUnit-Runner; Android-freie Policy- und
Matcher-Teile werden für diesen Lauf unverändert aus dem Produktivcode extrahiert.

Ausgeführt: **21 JVM-Tests erfolgreich** (9 neue Surface-Policy-Tests,
7 neue Geometry-Tests, 3 neue Displaymodus-Tests und 2 bestehende Upscaling-Tests).
Zusätzlich sind 4 Robolectric-Tests für die tatsächliche Display-/Attach-Verkabelung
vorbereitet und syntaxgeprüft; sie wurden hier nicht Android-kompiliert oder ausgeführt.

Die Kotlin-Syntaxprüfung ist keine vollständige Android-Typprüfung. Der vorhandene
Source-Audit meldet im Ausgangsstand und im geänderten Stand dieselben 93 fehlenden
englischen Übersetzungen; durch diese Änderung kommen keine Befunde hinzu.

Android-SDK, Gradle-Distribution und ADB/Emulator stehen in dieser Umgebung nicht
zur Verfügung. Ein vollständiger Android-Build, Android-Lifecycle-Tests und die
Messung am 4K-Gerät sind daher separat erforderlich. Für die Geräteprüfung:

1. 1080p-UI, aktive 4K-Ausgabe, 1080p-SDR-Quelle: Bildverarbeitung einschalten,
   Upscaler auswählen, 3840×2160-Surface und passende Scale-Stufe bestätigen.
2. Bei unveränderter UI zwischen 1080p und 4K wechseln: Ziel und Upscaler-Freigabe
   folgen dem aktiven Modus.
3. Native 4K-Quelle: keine unnötige Upscale-Stufe.
4. Miniplayer, Letterbox, Rotation und erneutes Anhängen der Surface prüfen.
5. Bildverarbeitung ausschalten beziehungsweise Sparsam aktivieren: feste
   GPU-Surface-Größe wird freigegeben, gespeicherte Bildoptionen bleiben erhalten.
