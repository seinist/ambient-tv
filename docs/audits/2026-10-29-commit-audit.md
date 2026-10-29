# Ambient TV – Audit aller Tagescommits vom 29. Oktober 2026

## Ergebnis und Bearbeitungsstand

Der Audit umfasst sämtliche bis zum festgehaltenen Snapshot erreichbaren Änderungen des **29.10.2026 in Europe/Berlin**: **31 Commits, davon 25 Fachcommits und sechs Merge-Commits**. Zusätzlich wurden **23 lokale Vorstufen** über ihren identischen Patchinhalt den veröffentlichten Commits zugeordnet. Der Nettovergleich zur Tagesbasis betrifft **316 Dateien mit 14.932 hinzugefügten und 3.782 entfernten Zeilen**.

Es wurden **sechs P2-Verhaltensbefunde** bestätigt und im lokalen Audit-Worktree korrigiert. Der sechste umfasst zwei zusammenhängende Voraussetzungen der Frame-Drop-Anzeige. Der saubere Android-Abschlusslauf besteht mit **3.143 Tests in 519 Testklassen, null Fehlern und null Skips**, einschließlich der 28 Messlogiktests und sechs neuen Backend-Tests. Außerdem wurden 26 im Tagesverlauf neu entstandene Lücken der expliziten englischen Ressourcen geschlossen. Die bereits vor dem Tag vorhandenen 67 Source-Audit-Warnungen bleiben unverändert.

**Die lokale Android-Kompilierung, der Ressourcenrebuild und die vollständige JVM-/Robolectric-Testsuite sind erfolgreich abgeschlossen.** Die vorhandene grüne CI des ursprünglichen Snapshots ist davon getrennt. Die Korrekturen sind auf dem lokalen Audit-Branch gesichert: `5bccedd` enthält den eigenständigen Media3-/VLC-Overlay-Fix; der nachfolgende Audit-Commit enthält die übrigen Korrekturen und diesen Bericht. Im Rahmen dieses Audits wurde nichts gepusht, gemergt oder remote veröffentlicht.

| ID | Priorität | Bestätigtes Verhalten | Stand im Audit-Worktree |
| --- | --- | --- | --- |
| F-1 | P2 | Touch auf ausblendenden OSD-Buttons kann eine alte Aktion auslösen. | Lokal korrigiert; echter Compose-Vorherlauf rot, beide OSD-Tests im Abschlusslauf grün. |
| F-2 | P2 | Settings-Suche erreicht Server-Konfiguration nicht zuverlässig und verliert die gewünschte Tab-Auswahl. | Lokal korrigiert; Suchanker-Repro vorher rot, nachher grün; alle sechs Repo-Regressionstests grün. |
| F-3 | P2 | Bekannte Live-Radio-Kanäle erhalten durch fehlende Metadaten Video-Pause-Rewind. | Metadaten-Zuweisung lokal korrigiert. |
| F-4 | P2 | Nach Pinch-Zoom überschreibt eine Größenänderung eine spätere manuelle Bildeinstellung. | Lokal korrigiert; zwei echte Compose-Vorherfälle rot, alle vier Zoom-Tests im Abschlusslauf grün. |
| F-5 | P2 | YouTube verliert gespeicherten Fortschritt, wenn Resume-Metadaten vor einem verzögerten Decoder-Mount eintreffen. | Drei Renderer-Initialisierungen lokal korrigiert; gezielter Repro vorher rot, nachher grün. |
| F-6 | P2 | Frame-Drop-Anzeige verwirft verwertbare Messungen bei unbekannter Soll-FPS oder nicht monotoner Live-Position. | Lokal korrigiert; vier Vorher-Repros rot; 28 Messlogik- und sechs Backend-Tests im Abschlusslauf grün. |
| R-1 | Ressourcen-Konsistenz | 26 neue Texte fehlen in `values-en`, obwohl englische Defaulttexte vorhanden sind. | Alle 26 wortgleich ergänzt; Source-Check wieder auf Tagesbasis 67. |

P2 bezeichnet hier eine konkrete Funktionsbeeinträchtigung unter den jeweils beschriebenen Bedingungen. Die Aufzählung ist kein Nachweis, dass alle Geräte-, Netzwerk- und Renderingzustände fehlerfrei sind.

## 1. Prüfgrenzen und Methode

| Merkmal | Festgehaltener Wert |
| --- | --- |
| Tagesgrenze | `2026-10-29T00:00:00+01:00` bis vor `2026-10-30T00:00:00+01:00`, Europe/Berlin |
| Tagesbasis | `63733573c220fdf95ea2a7ebd8487be7f5b77f0c` |
| Audit-Snapshot | `0fd15ec0b9fc5422eee7992499f266838b8c1ef3` |
| Letzter enthaltener Main-Merge | PR #96, `29.10.2026 12:01:30 +01:00` |
| Lokaler Audit-Branch | `audit/2026-10-29-commits` |
| Worktree | `/workspace/scratch/2eacd95fc95a/ambient-tv-audit` |
| Änderungen im Tagesvergleich | 316 unterschiedliche Dateien; `+14932 / -3782` |

„Alle Tagescommits“ bezieht sich auf diesen Snapshot und die dazu inventarisierten lokalen Vorstufen. Ein frischer Abruf während der Abschlussarbeiten bestätigt weiterhin `origin/main = 0fd15ec0b9fc5422eee7992499f266838b8c1ef3`. Spätere Tagesänderungen außerhalb dieses Stands sind nicht eingeschlossen. Dateizahlen pro Einzelcommit können sich überschneiden; ihre Summe entspricht deshalb nicht der Anzahl unterschiedlicher Dateien im Nettovergleich.

Geprüft wurden die einzelnen Diffs gegen ihre Eltern, der integrierte Endstand und relevante Aufrufer. Der Audit kombiniert Quellprüfung, vorhandene Policy-/Modelltests, gezielte Regressionen und eine separate Kontrolle der Merge-Übernahme. Bei asynchronen Abläufen wurden insbesondere Quellidentität, Cancellation, Owner-/Versionsprüfungen und die Reihenfolge zwischen Metadaten, UI-Komposition und Decoderzustand verfolgt. Vermutungen ohne belastbaren Kontrollfluss oder Repro wurden nicht zu bestätigten Befunden hochgestuft.

Die lokalen Vorstufen besitzen andere Commit-IDs, aber laut Patchvergleich denselben Änderungsinhalt wie ihre zugeordneten Main-Commits. Sie sind in Abschnitt 7 vollständig ausgewiesen und werden nicht als 23 zusätzliche, unabhängige Implementierungen gezählt.

## 2. Bestätigte Verhaltensbefunde

### F-1 – Ausblendendes OSD verarbeitet eine veraltete Touch-Aktion

**Ursprung:** `3c126e9`, kompaktes und erweiterbares OSD. **Priorität:** P2.

Auf einem Touch-Gerät wird das OSD geschlossen. Während der etwa 200 ms langen Ausblendung ist ein Play-/Stop-/Header-Element noch gezeichnet. Ein Tap darauf kann dessen alte Aktion auslösen, obwohl die Bedienoberfläche bereits als geschlossen behandelt wird. Der Tap sollte die Bedienung wieder sichtbar machen.

Der bisherige Eltern-Handler in `app/src/main/java/com/ambient/tv/ui/player/PlayerOverlayVisibility.kt` verwendet `detectTapGestures` im Main-Pass. Das anklickbare Kind kann den Down zuerst konsumieren und seine Aktion ausführen. Der Interaktivitäts-Guard der TV-Bedienleiste schützt den früh zurückkehrenden Handheld-Zweig nicht.

**Lokale Korrektur:** Der Eltern-Handler fängt den Beginn im Initial-Pass ab, konsumiert die gesamte Geste bis zum Loslassen aller Finger und hält diese Sperre über die durch Reveal ausgelöste Recomposition. Ein zweiter Finger darf keine inzwischen sichtbare Aktion auslösen. Nach Ende der abgefangenen Geste wird der zusätzliche Fullscreen-Pointer-Handler wieder entfernt, damit freie Bildflächen normal reagieren. Ein darüberliegendes Panel behält seine eigenen Eingaben; bei offenem Panel wird das OSD nicht zusätzlich eingeblendet.

**Nachweis:** `PlayerOverlayTouchTest.touchOnFadingChildReopensControlsAndConsumesTheWholeGesture` verwendet die echte Compose-Oberfläche und injizierte Touch-Ereignisse. Der Kontrolllauf mit alter OSD-Implementierung schlägt erwartungsgemäß fehl. Der zweite Fall, `panelAboveFadingOsdKeepsItsOwnTouchInput`, prüft die Panelgrenze. Im Abschlusslauf bestehen beide OSD-Tests; die gesamte Testsuite ist grün.

### F-2 – Settings-Suche verliert Zielposition und Tabpräferenz

**Ursprung:** `9ed3fc5`; neue Auswirkung im mobilen Konsumpfad, ein vergleichbarer Lifecycle-Konflikt bestand am TV bereits. **Priorität:** P2.

Bei einem konfigurierten Plex-/Jellyfin-/Emby-System mit genügend gespeicherten oder gefundenen Servern liegt die Anpassungssektion unterhalb des ersten Viewports. Eine Suche nach Startseite, Home-Widgets oder Reihen öffnet die Seite oben. Beim späteren Herunterscrollen ist „Untermenü“ statt „Startseite“ ausgewählt.

`SettingsHub.kt` stellte den kurzlebigen Suchauftrag direkt über `LocalSettingsSearchLanding` bereit und konsumierte ihn nach erster Listenmessung und Navigation. `SettingsIndexedList` zählt jedoch auch Items, die noch nicht komponiert wurden. `SystemCustomizationTabs` konnte dadurch bei seiner ersten tatsächlichen Komposition bereits `null` lesen. Zusätzlich fehlten für die drei Serverseiten passende semantische Suchziele; der Fallback führte zu Index 0.

**Lokale Korrektur:** `SettingsSearchLanding` in `SettingsNavigation.kt` hält pro Seiteninstanz den letzten nichtnull Suchauftrag. Ein neuer Auftrag ersetzt ihn, die normale Konsumierung löscht die Information für später komponierte Inhalte nicht. `SettingsHub.kt` liefert diesen Wert an die Inhalte. In `SettingsSystemPages.kt` tragen die realen Anpassungsitems die Schlüssel `plex:customization`, `jellyfin:customization` und `emby:customization`. Bereits komponierte Tabs reagieren auf eine geänderte Suchpräferenz. Normales Back löst keinen neuen Scrollauftrag aus und behält den vorhandenen Scrollzustand.

**Nachweis:** Der gezielte Repro gegen unverändert extrahierte Produktions-Suchfunktionen und die echte Indexklasse schlug vorher mit `PLEX expected:<20> but was:<0>` fehl; nach dem Fix besteht derselbe Test. Vier neue JVM-Fälle plus bestehender Index-Test und separater Repro ergeben sechs erfolgreiche Tests. Die zwei neuen Compose-Fälle laufen ebenfalls in der achtteiligen UI-Vorherprobe grün. Sie sind deshalb **kein separater roter Nachweis des alten Settings-Hubs**; der direkte Vorher-/Nachher-Nachweis betrifft die Suchankerauflösung. Die Compose-Fälle sichern die verzögerte Komposition und neue Suchpräferenz im vorgesehenen Fixture ab.

### F-3 – Live-Radio wird als videofähig behandelt

**Neue Auswirkung durch:** `2142ca0`, konfigurierbares Pause-Rewind. **Priorität:** P2.

Ein als Radio bekannter Live-Kanal mit nutzbarem Timeshift und aktiviertem Video-Pause-Rewind kann nach einer expliziten längeren Pause zurückspringen. Außerdem wird die Videooption angeboten. Die neue Capability-Auswertung liest `PlaybackMetadata.hasVideo`, dessen Default `true` ist; `TvheadendPlayerViewModel.metadata()` setzte diesen Wert für bekannte Radiokanäle bislang nicht.

**Lokale Korrektur:** Die Metadaten erhalten `hasVideo = state.channel?.kind != LiveTvChannelKind.RADIO`. Bekannte Radioquellen werden dadurch ausgeschlossen. TV, unbekannter Kanaltyp und fehlende Kanalmetadaten behalten ihr bisheriges Verhalten. Aufnahmen ohne Kanaltyp werden nicht pauschal neu klassifiziert.

**Nachweis und Grenze:** Der Datenfluss vom bekannten Kanaltyp zur Resume-Capability wurde unabhängig gegengeprüft. Die vorhandenen Tests `PlaybackPauseResumeTest.audioAndSpotifyNeverGetVideoPauseRewind` und `PlaybackResumePolicyTest.capabilitiesExcludeMusicAndUnavailableSeekWindows` prüfen den Audio-/Capability-Ausschluss. Für die einzelne neue ViewModel-Zuweisung wurde kein künstliches Testmodell eingeführt. Ein vollständiger Live-Radio-/Timeshift-Gerätetest wurde nicht ausgeführt.

### F-4 – Resize verwirft eine spätere manuelle Bildeinstellung

**Ursprung:** `816edd1`, mobiles Pinch-Zoom. **Priorität:** P2.

Reproduktion: Im selben mobilen Player per Geste zoomen, danach im Videopanel ein manuelles Seitenverhältnis oder einen anderen Zoom wählen und anschließend das Gerät drehen oder das Fenster per Split-Screen ändern. Die bisherige `PlayerTouchLayer` setzt dann AUTO/FIT/100 % zurück und verwirft die spätere bewusste Wahl.

Das Bool-Flag `wasPinched` verfolgt nur, ob irgendwann in dieser Komposition gepincht wurde. Ein manueller Einstellungswechsel setzt das gespeicherte Flag bei unverändertem Player-/Controller-Key nicht zurück; eine Recomposition allein erzeugt keinen neuen Remember-Zustand.

**Lokale Korrektur:** `app/src/main/java/com/ambient/tv/ui/common/PlayerTouchLayer.kt` merkt sich die zuletzt tatsächlich per Geste gesetzte normalisierte `VideoPresentation`. Beim Größenwechsel wird der Marker gelöscht. Ein Reset erfolgt nur, wenn die aktuelle Darstellung noch dieser Gestendarstellung entspricht. Eine später geänderte manuelle Wahl bleibt erhalten.

**Nachweis:** `PlayerPinchZoomResizeTest` verwendet die reale Touch-Layer und injizierte Zwei-Finger-Bewegungen. Im Kontrolllauf mit alter Implementierung schlagen `manualAspectAfterPinchingSurvivesResizeForTheSamePlayer` und `manualZoomAfterPinchingSurvivesResizeForTheSamePlayer` erwartungsgemäß fehl. Weitere Fälle sichern den weiterhin vorgesehenen Reset einer reinen Gestendarstellung und das Verwerfen eines veralteten Markers ab. Im Abschlusslauf bestehen alle vier Zoom-Tests; die gesamte Testsuite ist grün.

### F-5 – YouTube überspringt Resume nach verzögertem Decoder-Mount

**Ursprung:** `2142ca0`. **Priorität:** P2.

Ein konkreter Pfad ist eine globale MPV-/VLC-Voreinstellung mit gespeichertem Media3-Profil für den betreffenden Titel oder dessen Auflösung. SmartTube bereitet die MPD asynchron vor. Der Screen merkt sich zunächst einen Start bei 0. Während er auf die MPD wartet, können die Watch-next-Metadaten bereits einen zurückgespulten Fortschritt von beispielsweise 20 Sekunden liefern. Der später montierte Decoder erhält trotzdem den ursprünglich gemerkten Startwert 0.

Die bisherige Initialisierung `playback.resumeResolved || startPositionMs > 0L` setzt `lateResumeApplied` bereits wegen aufgelöster Metadaten auf `true`. Aufgelöste Metadaten belegen jedoch nicht, dass der Decoder auf die Position gesetzt wurde. Der anschließende Late-Resume-Effekt überspringt seinen Seek; das Video startet vorn.

**Lokale Korrektur:** In den drei Rendererpfaden von `app/src/main/java/com/ambient/tv/ui/youtube/YouTubePlayerScreen.kt` wird der Flag nur noch mit `startPositionMs > 0L` initialisiert. Der bestehende Effekt verarbeitet einen aufgelösten Null-Start weiterhin einmalig. Handoffs und explizite Benutzer-Seeks behalten ihre vorhandenen Schutzbedingungen.

**Nachweis:** Der gezielte Repro verwendet echte Produktionsmodelle, Resume-Preparation und Policy sowie den unverändert extrahierten Initializer aller drei Rendererstellen. Vorher: `expected:<20000> but was:<null>`, ein Test fehlgeschlagen. Nachher: derselbe Test bestanden. Das ist ein belegter Kontrollfluss-/Policy-Repro, kein Android-/Netzwerk-End-to-End-Test mit SmartTube und einem echten Decoder.

### F-6 – Frame-Drop-Anzeige verlangt unnötig bekannte Soll-FPS und eine monotone Live-Position

**Ursprung:** `57eba82`. **Priorität:** P2. **Status:** Lokal korrigiert; Messlogik, Backend-/Reader-Integration und vollständige Android-Testsuite grün.

Dieser Befund umfasst zwei Bedingungen derselben Anzeige. Erstens wurde `targetFps` zur zwingenden Voraussetzung für ein gültiges Messergebnis. Media3 liefert die Sollrate hier allein aus `videoFormat.frameRate`; diese darf unbekannt sein. In den geprüften H.264-/H.265-TS-Readern der verwendeten Media3-Version 1.11.1 wird der Wert nicht gesetzt. Auch VLC kann bei unbekannter Track-FPS oder automatisch gewähltem Deinterlacing mit doppelter Ausgaberate keine sichere Sollrate liefern. Verfügbare Render-/Drop-Zähler werden dadurch verworfen.

Zweitens verlangt das Positionsgate einen passenden monotonen Fortschritt von `positionMs`. Für Live-Streams ist die Position relativ zum aktuellen Fenster kein zuverlässiger Zeitmesser: Verschiebungen des Live-Fensters können die relative Position ändern oder nahezu stationär halten, obwohl tatsächlich Frames ausgegeben werden. Die offizielle [Format-Dokumentation](https://developer.android.com/reference/androidx/media3/common/Format#frameRate) erlaubt eine unbekannte Frame-Rate; die [Live-Streaming-Dokumentation](https://developer.android.com/media/media3/exoplayer/live-streaming) beschreibt die Position relativ zum Beginn des beweglichen Fensters.

Der Abhängigkeitsnachweis basiert auf `javap` der tatsächlich heruntergeladenen Media3-1.11.1-AAR: H264Reader und H265Reader erzeugen einen Format-Builder ohne `setFrameRate`; der Builder initialisiert das Feld mit `-1.0f`. Die geprüfte VLC-Java-Abhängigkeit enthält die reflektierten Namen `displayedPictures`, `lostPictures`, `frameRateNum` und `frameRateDen`. Ein allgemeiner Reflection-Fehler wurde daher nicht als Ursache behauptet. Die konkreten Geräteparameter des Nutzerfalls lagen nicht vor.

**Lokale Korrektur:** `PlaybackFrameDropMetrics.kt` wertet verwertbare tatsächliche Render-/Drop-Zähler über verstrichene monotone Echtzeit aus. `PlaybackFrameDropOverlay.kt` zeigt bei unbekannter Sollrate tatsächliche Ausgabe-FPS und beobachteten Drop-Anteil, ohne eine Sollrate zu erfinden. Bei bekannter Sollrate bleibt die FPS-Abweichung verfügbar. Fehlende Drop-Zähler oder 0 angezeigte plus 0 verlorene Frames werden nicht als gesunde Counter-Messung bewertet. MPVs Schätzung behält die konservative Soll-/Medienpositionsprüfung. Glättung mischt keine unterschiedlichen Soll- oder Counter-Verfügbarkeitszustände.

`Media3PlaybackHandle.kt` und `backend/VlcPlaybackView.kt` grenzen die Messepochen bei Seek, kurzen Pause-/Buffering-Übergängen, Reprepare und Zähler-/Decoderwechsel ab. Damit bleiben Diskontinuitäten auch dann erkennbar, wenn sie vollständig zwischen zwei Polls liegen. Neue Overlay-Texte wurden in Default, Deutsch und Englisch ergänzt.

**Vorher-/Nachher-Nachweis:** Vier Tests wurden gegen die alte Produktions-Messlogik ausgeführt: `media3UnknownNominalRateStillUsesItsRealCounters`, `vlcUnknownFilterCadenceStillUsesItsRealCounters`, `liveCounterMeasurementSurvivesAWindowRelativePositionJump` und `liveCounterMeasurementDoesNotRequireAnAvailableMediaClock`. Vorher ergeben sich 20 Tests mit genau diesen vier Fehlern; die bisherigen 16 bleiben grün. Nach Korrektur und zusätzlichen Schutz-/Formatierungsfällen bestehen **28 von 28 Tests**.

Drei Fälle in `Media3FrameDropIntegrationTest` und drei in `VlcFrameDropSampleStateTest` ergänzen die Reader-/Epochengrenzen. Alle sechs Tests bestehen im vollständigen Android-Abschlusslauf. Die unabhängigen Gegenprüfungen der vollständigen Integration sind ohne weiteren bestätigten Befund abgeschlossen. Ein echter Media3-/VLC-Gerätetest ist damit weiterhin nicht behauptet.

## 3. Ressourcenbefund R-1 und Source-Audit

Das unveränderte `scripts/audit_source.py` wurde auf einer mit `git archive` isolierten Tagesbasis und dem aktuellen Worktree ausgeführt. Beide Checker-Dateien haben denselben SHA-256-Hash: `36645e20c168e0fe8b9bfad0b1184a11409bec33189d4e4de6c974bcb5ebbaeb`.

| Vergleichsstand | Befunde | Veränderung zur Tagesbasis |
| --- | ---: | ---: |
| Tagesbasis `63733573` | 67 | — |
| Audit-Stand vor Ressourcenfix | 93 | +26 |
| Nach gezieltem Ressourcenfix | **67** | **0** |

Alle Befunde betreffen fehlende explizite `values-en`-Einträge. Die 26 neuen stammen aus `43efed6`: zwölf Teletext-, zehn HbbTV- und vier Farbtasten-Texte. Sämtliche standen bereits auf Englisch in den Default-Ressourcen. Der funktionierende Default-Fallback bedeutet, dass dies kein belegter fehlender Bildschirmtext oder Absturz war.

Die neue Datei `app/src/main/res/values-en/teletext_hbbtv_controls.xml` übernimmt genau diese 26 Texte wortgleich. Schlüsselmenge, Attribute, Textwerte und Format-Platzhalter wurden gegen die Default-Ressourcen verglichen; alle EN-XML-Dateien sind wohlgeformt und enthalten keine doppelten Stringnamen. Die 67 älteren Befunde wurden nicht verändert. Der finale Checker-Lauf liefert deshalb weiterhin **Exitcode 1**, jedoch exakt dieselbe Befundmenge wie die Tagesbasis. Auch die erneute Prüfung nach allen Frame-Drop-Overlay-Texten bestätigt diese 67 Altbefunde; sie umfasst 188 XML-Dateien, 836 Kotlin-Produktionsdateien und 3227 Default-, 3226 deutsche sowie 3159 explizite englische Strings.

Es gibt keine weiteren Befunde dieses Checkers zu XML-Parsing, doppelten Ressourcen/Imports, Format-Platzhaltern, undefinierten `R.string`-Referenzen oder den geprüften JNI-/Versionskatalogregeln. In beiden Vergleichsläufen wurden null native ELF-Dateien gefunden; daraus folgt keine Validierung nativer Binärdateien. Der Source-Check ersetzt keine Android-/Kotlin-Kompilierung.

Die getrennt geprüften drei neuen Ressourcenfamilien des Settings-/Panel-Refactorings enthalten in Default, Deutsch und Englisch jeweils dieselben 97 Schlüssel; Default- und Englischwerte stimmen überein.

## 4. Wesentliche Bereiche ohne zusätzlichen bestätigten Befund

### Media3: physische Rendergröße und Upscaler

`02c9198` und `e06249c` wurden zusätzlich unabhängig gegengeprüft. Die Berechnung unterscheidet die logische UI-Größe vom physischen Displayziel und berücksichtigt das belegte Rechteck sowie Rotation. Der Surface-Controller beobachtet Display, Attach und Layout; beim Detach/Release werden Listener bzw. feste Größen korrekt freigegeben und beim Reattach erneut angewendet.

Die Menüvorschau verwendet das angeforderte Ziel. Shader und Budgets verwenden bestätigte Surface-Abmessungen. Veraltete Owner dürfen die neue Geometrie nicht überschreiben oder löschen. Die bestätigte Größe bleibt über ein vorübergehendes Surface-Destroy-Ereignis erhalten. Performance-, Fehler-, direkte/HDR- und gesperrte Verarbeitungspfade behalten ihre Gates.

Bei bilinearer Skalierung durch die Displayausgabe wird das Schärfungsbudget aus der Quellgröße bestimmt; eine tatsächliche GPU-Scale-Stufe verwendet die Ausgabegröße. Der Filterbereich bleibt bei ausgeschaltetem Master erreichbar. In diesen Änderungen wurde keine neue konkrete Regression bestätigt. Das ist eine Quell-/Testaussage: Eine tatsächliche 4K-Surface auf einem konkreten Fernseher, die Bildqualität der Upscaler und deren GPU-Leistung wurden im Audit nicht am Gerät gemessen.

### Browse, Reconnect, Cache und Fokus

Geprüft wurden Refresh-Queue und Quellfamilien-Sperren, Cachegenerationen, Profil-/Endpunktidentität sowie die Einbindung in Home, Section, Detail, Suche, Dateien, DLNA, Fotos, Spotify, YouTube, Trailer und Live TV. Veraltete Antworten dürfen invalidierte Daten nicht wieder frisch machen; nach entferntem Konstruktorload muss ein erreichbarer Entry-Hook bestehen. Fehlgeschlagene Recovery darf sichtbare Daten erhalten. Es wurde keine zusätzliche belastbare Regression bestätigt.

Detailzeilen berücksichtigen gespeicherte Auswahl und Server-Next-Episode vor allgemeinem Unwatched-Fallback. Die Einzelstaffel wird als Episode-Liste veröffentlicht. D-pad-Wrap ist an erreichbare Ziele, Zeile und Fokusgeneration gebunden. Filteränderungen verhindern verspätete automatische Content-Fokusanfragen; Reset bleibt auf der richtigen Bar-/Panel-Oberfläche. SmartTube-Kanalkacheln bleiben von reinen Videofiltern unberührt. Diese Aussagen stützen sich auf die tatsächlichen Aufrufer und die unten dokumentierten 108 JVM-Tests; echte Netzwerkwechsel und physische Fokusfolgen bleiben Gerätetests.

### Playback, Transport, Live TV und Next Up

Die geänderten Transportaufrufer für Media3, VLC, MPV, Bibliothek, Live TV, YouTube, Medien-/Notification-Steuerung, Companion, DLNA, YouTube-TV-Link und Miniplayer wurden verfolgt. Nur explizite Benutzerpause armiert Pause-Rewind. Technische Pause, unter anderem durch HbbTV, bleibt getrennt. Seek geht Play voraus; Owner-/Versionsprüfungen verhindern verspätete Aktionen auf einer neuen Quelle.

Timeshift berücksichtigt tatsächliche Seek-Fähigkeit und Buffergrenzen. Aufnahmeoptionen beachten Berechtigungen, laufende Aktionen und den aktuellen Kanal; veraltete Refresh-Ergebnisse werden verworfen. Next Up verwendet responsive Aktionen mit enthaltenem Fokus und stabilem initialem Play-Fokus. Backup-/Settings-Anbindung von Resume-Rewind entspricht Schema 74 und bewahrt ältere lokale Werte bei fehlenden neuen Feldern. Außer den beschriebenen Befunden wurde keine zusätzliche konkrete Regression bestätigt.

### Settings, Panels und Uhren

Alle 50 Dateien des Settings-Refactorings wurden geprüft. Ein zusätzlicher Vergleich der Zugriffe auf ViewModels, Manager, Preferences und Repositories fand die erwartete Umstellung von `wakeScrollIndex` auf `wakeTargetKey` und den zusätzlichen Launcher-Header-Guard. Vorhandene Persistenz- und Aktionsziele blieben gebunden. Auswahllisten erhalten Mehrfachauswahl, Disabled-Zustände, Farbfelder und Callbacks; Aktionszeilen ändern die Anordnung, nicht die Ausführung.

Die OSD-Buttonsektionen decken das Enum ab, und Werkzeugübersicht sowie Untermenü verwenden dieselbe Verfügbarkeitsliste. Uhren übernehmen die gemeinsame `ClockAppearance` einschließlich Sichtbarkeit, Größe, Farbe, Deckkraft, Font, Schatten, Zeitformat und Datum. Home, Header und Player bleiben separat gegatet; die doppelte Uhr in FilteredBrowse wird vermieden. Schmale Geräte, große Schrift und tatsächlicher TV-Fokus wurden nicht visuell abgenommen.

## 5. Vollständige Matrix der 25 Fachcommits

„Keine bestätigte neue Regression“ bedeutet: Im geprüften Diff und integrierten Kontrollfluss wurde kein weiterer belastbarer Fehler gefunden. Daraus folgt keine pauschale Gerätefreigabe. Die Testnachweise in Abschnitt 8 gelten mit ihrem jeweiligen Umfang.

| Nr. | Commit | Änderung | Prüfschwerpunkte | Ergebnis |
| ---: | --- | --- | --- | --- |
| 1 | `57eba82` | FPS-Abweichung im Frame-Drop-Overlay (13 Dateien) | Soll-FPS, reale Render-/Drop-Zähler, Messintervalle, Media3/VLC/MPV und Live-Zeitbasis | P2 F-6: unbekannte Sollrate und Live-Positionsgate; lokal behoben, 28 Messlogiktests grün. |
| 2 | `e06249c` | Media3-Filterzugang und Schärfungsbudget (15 Dateien) | Master-Schalter, erreichbares Filterpanel, Quell- versus Ausgabepixel, bilineare Skalierung | Keine zusätzliche bestätigte Regression. |
| 3 | `16bd2e4` | HbbTV-Erkennung über TS-Paketgrenzen (6 Dateien) | PSI-Assembler, Paketgrenzen, Kontinuität und Teletext-/AIT-Erkennung | Keine bestätigte neue Regression. |
| 4 | `20c3244` | Teletext-Untertitel in gemeinsamer Auswahl (8 Dateien) | Untertitelauswahl, Backend-/Teletext-Ausschluss und bestehende Subtitle-Policy | Keine bestätigte neue Regression. |
| 5 | `283bc4b` | Live-TV-Einstellungen als Untermenü (19 Dateien) | Timeshift, Aufnahmeberechtigung, Busy-/Kanalwechsel-Gates, bestätigte Aufnahme | Keine zusätzliche bestätigte Regression. |
| 6 | `c53c7f9` | Werkzeugübersicht nach Verfügbarkeit (8 Dateien) | Gemeinsame verfügbare Gruppen für Zusammenfassung und Panel, globale Audiooptionen | Keine bestätigte neue Regression. |
| 7 | `4cd8959` | OSD und Wiedergabeinformationen gruppieren (11 Dateien) | Untermenü, Callback-Ziele und Rücknavigation | Keine bestätigte neue Regression. |
| 8 | `a025530` | OSD-Buttons gruppieren und Sichtbarkeit zeigen (9 Dateien) | Enum-Abdeckung, persistierte Sichtbarkeit, aktivierte/deaktivierte Aktionen | Keine bestätigte neue Regression. |
| 9 | `e90a9c9` | Sichtbare Ansichten nach Reconnect aktualisieren (75 Dateien) | Cachegenerationen, Quell-/Profilidentität, Paging, Entry-Hooks und UI-Integration | Keine bestätigte neue Regression; Browse-JVM-Läufe grün. |
| 10 | `eb7fc4b` | Refresh beim Einstieg und gemeinsamer Offlinezustand (59 Dateien) | Refresh-Queue, Mutation/Refresh-Sperren, Erreichbarkeit und veraltete Antworten | Keine bestätigte neue Regression; Browse-JVM-Läufe grün. |
| 11 | `367ddbb` | Verfügbaren Platz für Hero-Texte nutzen (6 Dateien) | Höhenbudgets, Metadaten, Logo-Fallback und tatsächliche Constraints der Aufrufer | Keine bestätigte neue Regression. |
| 12 | `b54d1c1` | Filterfokus erhalten, SmartTube nur nach Videos filtern (19 Dateien) | Bar-/Panel-Fokus, Reset, leere Ergebnisse, Kanalverzeichnisse und gemischte Feeds | Keine bestätigte neue Regression; Policy-/Video-Filtertests grün. |
| 13 | `9bfbbf9` | Cast-Startfehler behandeln und Streamtypen erhalten (10 Dateien) | Start-/Fehlerpfade, Remote-Player-Zustand und MIME-/Streamtyp-Weitergabe | Keine bestätigte neue Regression; Geräteverbindung nicht praktisch geprüft. |
| 14 | `43efed6` | Teletext-/HbbTV-Bedienung vereinheitlichen (12 Dateien) | Controls, Loading/Fehler, technische Pause, Teletext-Seiten und Ressourcen | R-1: 26 neue explizite EN-Lücken; lokal geschlossen. Kein zusätzlicher belegter Laufzeitfehler. |
| 15 | `23854af` | Detailfokus und Einzelstaffel-Laden korrigieren (11 Dateien) | Episode-Liste statt Zwischen-Staffel, Parent-Identität und gespeicherter Fokus | Keine bestätigte neue Regression; Detailtests grün. |
| 16 | `752407d` | Detailzeilen bei D-pad rechts zum Anfang führen (8 Dateien) | Erreichbare Ziele, Zeilen-/Fokusgeneration, begrenzte Frame-Retries | Keine bestätigte neue Regression; Wrap-Policytests grün. |
| 17 | `25ee984` | SmartTube-Badge für neue Inhalte präzisieren (4 Dateien) | Provider-Neuinhalt versus Online-/Livezustand, Kachelzuordnung | Keine bestätigte neue Regression. |
| 18 | `07bd48b` | Browse-Positionen bei neuer UI-Sitzung zurücksetzen (12 Dateien) | Activity-Owner, Konfigurationswechsel, Servicefortbestand, spätes onDestroy | Keine bestätigte neue Regression; Session-Policytests grün. |
| 19 | `1c40d81` | Next-Up-Autoplay in responsiven Footer verschieben (7 Dateien) | FlowRow, Initialfokus, enthaltene Navigation und wechselnde Buttonlabels | Keine bestätigte neue Regression; keine physische D-pad-Abnahme. |
| 20 | `3c126e9` | Kompaktes, erweiterbares Player-OSD vereinheitlichen (30 Dateien) | Zustandsautomat, Autohide, Panels, Fokus, Touch beim Ausblenden | P2 F-1: ausblendende Kindaktion bleibt berührbar; lokal behoben. |
| 21 | `2142ca0` | Konfigurierbares Zurückspulen bei Start und Pause (61 Dateien) | Benutzer-/Technikpause, Seek-Fähigkeit, Owner/Generationen, Backup und spätes Resume | P2 F-3 und F-5: Live-Radio-Metadaten und YouTube-Late-Mount; lokal behoben. |
| 22 | `364b3b5` | Resume-/Transport-Test-Erwartungen anpassen (3 Dateien) | Schema 74, expliziter Benutzertransport und erhaltene Native-Guards | Erwartungen passen zur beabsichtigten Änderung; kein belegtes Kaschieren eines Produktionsfehlers. |
| 23 | `816edd1` | Mobiles Pinch-Zoom und einheitliche Uhren (26 Dateien) | Alle neun Videopfade, Panel-/Audio-/TV-Gates, Resize, ClockAppearance und doppelte Headeruhr | P2 F-4: Resize verwirft spätere manuelle Wahl; lokal behoben. Uhren ohne weiteren Befund. |
| 24 | `02c9198` | Media3 an physische Ausgabe binden, Upscaler freischalten (11 Dateien) | Surface-Lifecycle, Owner, bestätigte Geometrie, Displaymodus und Performance-/HDR-Gates | Keine bestätigte neue Regression; unabhängige Gegenprüfung und 12 gezielte Tests grün. |
| 25 | `9ed3fc5` | Settings-Seiten und Playback-Panels vereinheitlichen (50 Dateien) | Alle 50 Dateien: Callbacks, Persistenz, Scope, Fokus, bedingte Items, Suchanker, Ressourcen | P2 F-2: Suchziel und Tabpräferenz gehen verloren; lokal behoben. |

## 6. Vollständige Prüfung der sechs Merge-Commits

Der separat gespeicherte Integritätsvergleich bestätigt für jeden der sechs Merges, dass der eingehende Patch erhalten ist. Die Prüfung des endgültigen Kontrollflusses schließt zusätzlich Überschneidungen zwischen OSD, Resume, Zoom/Uhren, Media3 und Settings ein. Ein erhaltener Patch allein wäre noch kein Nachweis korrekten Laufzeitverhaltens.

| Merge | Integration | Eltern | Ergebnis |
| --- | --- | --- | --- |
| `8d5da92` | PR #92: kompaktes OSD | `1c40d81` + `3c126e9` | Eingehender OSD-Patch erhalten; kein zusätzlicher manueller Merge-Befund. F-1 stammt aus dem Fachcommit. |
| `92435d7` | PR #93: Resume-/Pause-Rewind | `8d5da92` + `364b3b5` | Resume- und Teständerungen erhalten; kein zusätzlicher manueller Merge-Befund. F-3/F-5 stammen aus den Fachänderungen. |
| `389a86f` | PR #94: Pinch-Zoom und Uhren | `92435d7` + `816edd1` | Eingehender Patch erhalten; F-4 ist ein Verhalten der neuen Touch-Layer, kein verlorener Merge-Hunk. |
| `a134ad3` | Main in den Media3-Branch | `02c9198` + `389a86f` | Main-/Zoom-/Uhränderungen übernommen; Media3-Integration im endgültigen Host-/Controllerpfad gegengeprüft. |
| `c84039f` | PR #95: Media3-4K-Renderziele | `389a86f` + `a134ad3` | Eingehender integrierter Media3-Patch erhalten; kein zusätzlicher Merge-Befund. |
| `0fd15ec` | PR #96: Settings und Panels | `c84039f` + `9ed3fc5` | Settings-Patch erhalten; Uhren- und Settingsänderungen in PageChrome/SettingsHub sind beide vorhanden. |

## 7. Vollständige Zuordnung der 23 lokalen Vorstufen

Für alle folgenden Paare wurde identischer Patchinhalt festgestellt. Unterschiedliche Commit-IDs sind durch die lokale bzw. integrierte Historie erklärbar. Die Prüfung des zugeordneten veröffentlichten Patches deckt damit auch den Änderungsinhalt der lokalen Vorstufe ab; es wurden keine 23 zusätzlichen Implementierungen oder zusätzlichen Fehlerfälle behauptet.

| Nr. | Lokale Vorstufe | Identischer veröffentlichter Patch | Inhalt |
| ---: | --- | --- | --- |
| 1 | `ae285e5` | `57eba82` | FPS-Abweichung im Frame-Drop-Overlay |
| 2 | `a7b6702` | `e06249c` | Media3-Filterzugang und Schärfungsbudget |
| 3 | `529a45e` | `16bd2e4` | HbbTV-Erkennung über TS-Paketgrenzen |
| 4 | `eda6046` | `20c3244` | Teletext-Untertitel in gemeinsamer Auswahl |
| 5 | `f9f171a` | `283bc4b` | Live-TV-Einstellungen als Untermenü |
| 6 | `d0b6bdc` | `c53c7f9` | Werkzeugübersicht nach Verfügbarkeit |
| 7 | `2bac86f` | `4cd8959` | OSD und Wiedergabeinformationen gruppieren |
| 8 | `4aef085` | `a025530` | OSD-Buttons gruppieren und Sichtbarkeit zeigen |
| 9 | `c041593` | `e90a9c9` | Sichtbare Ansichten nach Reconnect aktualisieren |
| 10 | `cb30e31` | `eb7fc4b` | Refresh beim Einstieg und gemeinsamer Offlinezustand |
| 11 | `c009814` | `367ddbb` | Verfügbaren Platz für Hero-Texte nutzen |
| 12 | `fbdcc81` | `b54d1c1` | Filterfokus erhalten, SmartTube nur nach Videos filtern |
| 13 | `fbe5116` | `9bfbbf9` | Cast-Startfehler behandeln und Streamtypen erhalten |
| 14 | `d5dd6b5` | `43efed6` | Teletext-/HbbTV-Bedienung vereinheitlichen |
| 15 | `467ef28` | `23854af` | Detailfokus und Einzelstaffel-Laden korrigieren |
| 16 | `b9f9a51` | `752407d` | Detailzeilen bei D-pad rechts zum Anfang führen |
| 17 | `ddcbf0e` | `25ee984` | SmartTube-Badge für neue Inhalte präzisieren |
| 18 | `afa4fac` | `07bd48b` | Browse-Positionen bei neuer UI-Sitzung zurücksetzen |
| 19 | `ebf59d1` | `1c40d81` | Next-Up-Autoplay in responsiven Footer verschieben |
| 20 | `229abde` | `3c126e9` | Kompaktes, erweiterbares Player-OSD vereinheitlichen |
| 21 | `e150fdf` | `2142ca0` | Konfigurierbares Zurückspulen bei Start und Pause |
| 22 | `4445b23` | `364b3b5` | Resume-/Transport-Test-Erwartungen anpassen |
| 23 | `6a673da` | `816edd1` | Mobiles Pinch-Zoom und einheitliche Uhren |

## 8. Tatsächlich ausgeführte Verifikation

### 8.1 CI auf dem unveränderten Snapshot

Die GitHub-Check-Runs für **`0fd15ec0b9fc5422eee7992499f266838b8c1ef3`** wurden über die API geprüft und stehen sämtlich auf `success`:

| Check | Run / Job | Ergebnis |
| --- | --- | --- |
| `unit-tests` | [Run 40862146164](https://github.com/seinist/ambient-tv/actions/runs/40862146164), Job `123407904810` | `success` |
| `build` | [Run 40862145262](https://github.com/seinist/ambient-tv/actions/runs/40862145262), Job `123407787412` | `success` |
| `deploy` | derselbe Run, Job `123407867805` | `success` |
| `report-build-status` | derselbe Run, Job `123407867713` | `success` |

Diese bereits vorhandenen CI-Ergebnisse betreffen den Snapshot vor den lokalen Auditkorrekturen. Sie belegen weder die neuen Regressionstests noch eine erneute Veröffentlichung des Audit-Branches. Der Audit selbst hat keinen Deploy ausgelöst.

### 8.2 Gezielte lokale Läufe

Die folgenden Zahlen beschreiben einzelne tatsächlich ausgeführte Prüfungen. Wegen Überschneidungen und wiederholter Vorher-/Nachher-Läufe werden sie nicht zu einer irreführenden Gesamt-Testzahl addiert.

| Prüfung | Beobachtetes Ergebnis | Aussagegrenze |
| --- | --- | --- |
| Browse/Reconnect/Fokus: echte Produktionsdateien, 16 vorhandene Testklassen | **108 bestanden**, aufgeteilt in 82 + 26 | Kotlin/JVM; keine simulierte Android-Oberfläche, keine echten Netzwerkwechsel. |
| `PlaybackResumePolicyTest` | **15 bestanden** | Policy-/Capability-Verhalten. |
| `SavedResumeRequestTest` und `YouTubeResumeTest` | **6 + 8 bestanden** | Gespeicherter Start und YouTube-Resume-Policy. |
| `Media3RenderGeometryTest` und `Media3FilterBudgetTest` | **12 bestanden** | Echte unabhängige Geometrie-/Budgetquellen, kein 4K-Gerätetest. |
| Settings-Suchanker-Repro | Vorher **1 Fehler**, nachher **bestanden** | Unverändert extrahierte Suchfunktionen und echte Indexklasse. |
| Settings-Gesamtlauf im kleinen JVM-Runner | **6 bestanden** | Vier neue Repo-Tests, bestehender Index-Test, separater Repro. |
| YouTube-Late-Mount-Repro | Vorher **1 Fehler**, nachher **bestanden** | Echte Modelle/Policy und extrahierter Initializer; kein Decoder-/Netzwerk-End-to-End-Lauf. |
| Compose-/Robolectric-Vorherprobe | **8 Tests: 3 erwartete Fehler, 5 bestanden** | Alter OSD-/Zoom-Code reproduziert F-1 und zwei F-4-Fälle; zwei Settings-Compose-Fälle sind grün. |
| Frame-Drop-Messlogik, Vorherprobe | **20 Tests: 4 erwartete Fehler, 16 bestanden** | Echte Produktions-Messfunktion; unbekannte Media3-/VLC-Sollrate und zwei Live-Zeitfälle. |
| Frame-Drop-Messlogik nach Korrektur | **28 bestanden** | Counter, unbekanntes Soll, Live-Zeit, Schutzbedingungen, Glättung und Formatierung. |
| Neue Media3-/VLC-Backend-Regressionen | **6 bestanden** im vollständigen Abschlusslauf | Echter Media3-Handle/Reader mit Interface-Double; VLC-Ereigniszustand ohne native JNI-Wiedergabe. |
| Vollständiger Android-Abschlusslauf | **3.143 bestanden, 0 Fehler, 0 Skips; 519 Testklassen** | Sämtliche lokalen Auditkorrekturen; JVM und Robolectric, kein Hardware- oder APK-Release-Test. |
| Python-Skripttests | **41 bestanden, 2 übersprungen**, 43 insgesamt | `python3 -m unittest discover -s scripts/tests -p 'test_*.py'`; vorhandene Skips. |
| Source-Audit nach EN-Korrektur | **67 Befunde**, identisch zur Tagesbasis | Ausschließlich alte explizite EN-Lücken; Exitcode 1 bleibt korrekt. |
| Gezielte Kotlin-PSI-Prüfungen | **Keine Syntaxfehler** in den geprüften Kandidaten/Testfixtures | Kein Ersatz für Android-Typprüfung. |
| `git diff --check` | **Sauber** beim jeweiligen lokalen Prüfstand | Whitespaceprüfung; kein Funktionsnachweis. |

Die UI-Vorherprobe besteht aus zwei OSD-, vier Zoom- und zwei Settings-Compose-Fällen. Der erwartete rote OSD-Fall ist der Tap auf das ausblendende Kind. Die zwei roten Zoom-Fälle betreffen manuelles Seitenverhältnis bzw. manuelle Zoomstufe nach einer Geste. Die grünen Kontrollfälle prüfen verbleibendes Verhalten. Ein grünes Fixture ersetzt insbesondere keinen roten Nachweis aller Integrationspfade des Settings-Hubs.

Die kleinen Offline-Runner wurden ohne künstliche Android-Ersatzklassen verwendet. Einzelne nicht isoliert kompilierbare Tests mit zusätzlichen Android-/App-Abhängigkeiten wurden nicht als Projekt-Buildfehler gewertet und nicht durch erfundene Stubs „grün“ gemacht. Die im Projekt enthaltenen Klassen sind im erfolgreichen Android-Gesamtlauf erfasst.

### 8.3 Erfolgreicher Abschlusslauf

Der vollständige saubere Lauf mit **Android SDK 37, NDK 30 und JDK 21** endet mit `BUILD SUCCESSFUL in 4m 21s`; alle 41 Gradle-Tasks wurden ausgeführt. App- und Test-Kotlin, Java/Hilt, Ressourcen und die Testausführung sind erfolgreich. Die Auswertung sämtlicher JUnit-XML-Dateien ergibt **3.143 Tests in 519 Testklassen, null Fehler, null Errors und null Skips**.

| Gezielt relevante Testklasse | Tests | Fehler / Skips |
| --- | ---: | --- |
| `PlaybackFrameDropTest` | 28 | 0 / 0 |
| `Media3FrameDropIntegrationTest` | 3 | 0 / 0 |
| `VlcFrameDropSampleStateTest` | 3 | 0 / 0 |
| `PlayerOverlayTouchTest` | 2 | 0 / 0 |
| `PlayerPinchZoomResizeTest` | 4 | 0 / 0 |
| `SettingsSearchLandingTest` | 4 | 0 / 0 |
| `SettingsSearchLandingComposeTest` | 2 | 0 / 0 |
| `Media3SurfaceResolutionControllerTest` | 4 | 0 / 0 |

Diese Klassen sind in der Gesamtzahl enthalten und werden nicht zusätzlich addiert. Geprüft wurde der Audit-Worktree auf Basis `0fd15ec`, einschließlich aller in diesem Bericht beschriebenen Produktionskorrekturen, Ressourcen und Regressionstests. Nach dem grünen Lauf werden nur dieser Bericht und Git-Metadaten aktualisiert; der geprüfte Produktions- und Testcode bleibt unverändert.

Reproduzierbarer Kernaufruf bei eingerichteter Android-/Java-Umgebung:

```bash
CI=true SKIP_MPV_NATIVE=1 ./gradlew :app:clean :app:testDebugUnitTest \
  -PAPK_ABI=arm64 -Pkotlin.incremental=false -Pksp.incremental=false \
  --no-build-cache --continue --max-workers=2 --no-daemon
```

In der Auditumgebung wurde zusätzlich ein temporäres Gradle-Init-Script verwendet, das den konfigurierten Proxy und Truststore an die Robolectric-Test-JVM weiterreicht. Die Projekt-Buildkonfiguration wurde dafür nicht geändert. Der erste inkrementelle Versuch hatte doppelte Top-Level-Symbole gemeldet, obwohl die betreffenden Quellen jeweils nur einmal existieren. Nach `clean` und ohne inkrementelle Kotlin-/KSP-Ausgaben kompiliert derselbe Quellstand erfolgreich. Dieser frühere Compile-Abbruch war kein Ergebnis der Tests.

Das vollständige Abschlussprotokoll liegt unter `/tmp/ambient-tv-android-audit/final-clean-audit.log`, die JUnit-Auswertung unter `/tmp/ambient-tv-audit-final-tests.json`. `SKIP_MPV_NATIVE=1` überspringt den umfassenden MPV-/Audio-Nativbuild; der Vulkan-Probe-Task lief erfolgreich. Dieser Lauf ist eine Android-Kompilierung mit vollständiger Unit-/Robolectric-Suite, keine neue APK-Veröffentlichung und kein Test tatsächlicher MediaCodec-/LibVLC-Ausgabe auf Hardware.

## 9. Verbleibende praktische Abnahme

Die folgenden Punkte sind gezielte Restvalidierung, keine zusätzlichen behaupteten Fehler:

- Physischer 4K-Fernseher mit niedrigerer UI-Auflösung: angefordertes und bestätigtes Surface-Ziel, erreichbare Upscaler, GPU-Budget, HDR-/Direktpfade sowie Wechsel des Displaymodus.
- Echtes Live-TV mit unbekannter FPS und beweglichem Fenster: kontinuierliche, plausibel beschriftete Frame-/Drop-Messung sowie neue Messepochen nach Seek, Pause und Reprepare.
- Handheld-Touch beim OSD-Ausblenden, zweiter Finger über eine Recomposition hinweg und darunterliegende Bildgesten; dieselben Abläufe bei geöffnetem Panel.
- Pinch-Zoom, anschließende manuelle Bildeinstellung, Rotation und Split-Screen auf realen Geräten; schmale Layouts und große Schrift einschließlich Uhren.
- Lange Serverlisten und wiederholte Settings-Suche auf TV und Handy; normales Back mit erhaltenem Scrollzustand.
- Reconnect bei mehrseitigen Daten, Profilwechsel während Refresh, neue App-UI bei weiterlaufendem Service sowie D-pad-Wrap und Filterreset in langen bzw. leeren Ansichten.
- Bekanntes Live-Radio mit Timeshift und aktivierter Pause-Rewind-Voreinstellung; YouTube-Start mit verzögerten Metadaten/MPD und abweichendem Titel-Rendererprofil.

## 10. Belege und Wiederholbarkeit

Die Ausgangsinventare dieses Berichts sind `ambient-tv-audit-2026-10-29-inventory.json`, `ambient-tv-audit-local-equivalents.json` und `ambient-tv-audit-merge-integrity.json` unter `/tmp` der Auditumgebung. Die Teilprüfungen sind dort als `ambient-tv-audit-browse.md`, `ambient-tv-audit-playback.md`, `ambient-tv-audit-settings.md`, `ambient-tv-audit-media3-zoom-second-review.md` und `ambient-tv-audit-frame-drops.md` abgelegt. Ihre älteren Zwischenstände zu noch nicht angewandten Patches werden durch den neueren Bearbeitungsstand in diesem Gesamtbericht ersetzt.

Wesentliche lokale Testbelege sind `/tmp/ambient-tv-audit-browse-tests.log`, `/tmp/ambient-tv-audit-browse-content-tests.log`, `/tmp/ambient-tv-audit-media3-second-review-tests.log`, `/tmp/ambient-tv-audit-settings-repro` sowie der YouTube-Mount-Repro `/tmp/YouTubeLateMountAuditTest.kt`. Der vollständige Source-Vergleich liegt in `/tmp/ambient-tv-audit-source-diff.json`; Baseline, ursprünglicher Audit-Stand und Finalstand sind separat in `ambient-tv-audit-source-baseline.txt`, `ambient-tv-audit-source-current.txt` und `ambient-tv-audit-source-final.txt` festgehalten.

Die im Repository dauerhaft ergänzten bzw. erweiterten Regressionen sind:

- `app/src/test/java/com/ambient/tv/ui/player/PlayerOverlayTouchTest.kt`
- `app/src/test/java/com/ambient/tv/ui/player/PlayerPinchZoomResizeTest.kt`
- `app/src/test/java/com/ambient/tv/ui/settings/SettingsSearchLandingTest.kt`
- `app/src/test/java/com/ambient/tv/ui/settings/SettingsSearchLandingComposeTest.kt`
- `app/src/test/java/com/ambient/tv/ui/player/PlaybackFrameDropTest.kt`
- `app/src/test/java/com/ambient/tv/ui/player/Media3FrameDropIntegrationTest.kt`
- `app/src/test/java/com/ambient/tv/ui/player/backend/VlcFrameDropSampleStateTest.kt`

Die Frame-Drop-Vorher-/Nachher-Läufe sind in `/tmp/ambient-tv-audit-frame-drop-before.log` und `/tmp/ambient-tv-audit-frame-drop-after.log` dokumentiert. Die endgültigen Abschlussresultate stehen in Abschnitt 8.3; die maschinenlesbare Auswertung der 519 JUnit-XML-Dateien liegt unter `/tmp/ambient-tv-audit-final-tests.json`. Die im Bericht aufgeführten Commit-IDs, Reproduktionsbedingungen und beobachteten Ergebnisse erlauben die Zuordnung auch unabhängig von den temporären Arbeitsbelegen.
