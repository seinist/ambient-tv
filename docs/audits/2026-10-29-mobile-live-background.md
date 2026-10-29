# Live-Wiedergabe auf dem Handy beim Verlassen des Players erhalten

- Datum: 29. Oktober 2026, Europe/Berlin.
- Ausgangsstand: `f48d89fb146b07b6c9af9d4e2b0bf4a97bd58bee`.
- Auftrag: Live-Inhalte auf dem Handy im Hintergrund weiterlaufen lassen.
- Umfang: Gemeinsame Zurück-Entscheidung und ihre Live-TV-/SmartTube-Aufrufer; keine SHOUTcast-Implementierung.

## Ursache

Die Prozess-Hintergrundregel erlaubt auf Handys bereits alle laufenden Inhalte: `backgroundPlaybackAction()` gibt für `!television` vor der TV-Live-Regel `KEEP` zurück. Gewöhnlicher Appwechsel und Displaysperre sind daher nicht der hier gefundene Stop-Fehler.

Der konkrete Fehler liegt beim Verlassen des Players mit der Zurück-Taste beziehungsweise Zurück-Geste. `backLeavesPlaybackRunning()` berücksichtigte bisher nur die globale Minimieren-Einstellung und auf Handys bereits erkannten Audioinhalt. Live-TV mit Video führte deshalb zum expliziten `BackgroundPlaybackSession.stop()`. Bei Radio konnte die noch nicht abgeschlossene Spurerkennung dieselbe Entscheidung auslösen.

Das ist eine andere Situation als der Wechsel der gesamten App in den Hintergrund. Die vorherige Untersuchung hatte diese Situationen sprachlich nicht ausreichend getrennt.

## Änderung

Die gemeinsame Entscheidung erhält das Quellenmerkmal `isLive`. Auf einem Handy bleibt die Wiedergabe beim Verlassen des Players nun erhalten, wenn der Inhalt Audio oder live ist. Die vorhandene Minimieren-Einstellung bleibt übergeordnet.

Das Quellenmerkmal wird in allen sechs Live-Pfaden ausdrücklich weitergegeben:

| Quelle | Media3 | VLC | MPV |
| --- | --- | --- | --- |
| Live-TV einschließlich Radiokanälen | `source.isLive` | `source.isLive` | `source.isLive` |
| SmartTube-Livestream | `playback.isLive` | `playback.isLive` | `playback.isLive` |

Die generischen Bibliotheks-/VOD-Aufrufer verwenden weiterhin den Default `isLive=false`. Abgeschlossene TV-Aufnahmen liefern ebenfalls `false`; Live-Timeshift behält das Live-Merkmal.

Der tatsächliche Wiedergabeeigentümer bleibt die vorhandene gemeinsame Session. Die Quellprüfung bestätigt: Media3 entfernt beim Verlassen der Oberfläche nur die nicht mehr benötigten Listener und gibt eine von der Session gehaltene Engine nicht frei. Die nativen Videooberflächen lösen ihre Videoausgabe, ohne den laufenden Audiostream zu beenden. Der TV-ViewModel gibt die aktuell übernommene Live-Session beim Aufräumen nicht frei. Dafür war keine zusätzliche Lifecycle-Änderung erforderlich.

## Verhaltensgrenzen

- Handy: Live-TV, Live-Radio und SmartTube-Livestreams bleiben beim Zurückgehen hörbar und im Miniplayer erreichbar.
- Die Entscheidung hängt bei Live-Inhalt nicht davon ab, ob Audio-/Videospuren schon erkannt wurden.
- Appwechsel und Displaysperre verwenden weiterhin die bereits vorhandene Handy-Hintergrundregel.
- Das bestehende Verhalten für normales VOD und die TV-Regeln bleiben erhalten.
- Stop im Player, Miniplayer oder in der Systemsteuerung beendet die Wiedergabe weiterhin. Das bewusste Schließen der App aus der Übersicht behält seine separate Stop-Regel.

## Prüfung

Gezielte Regressionen stehen in `PlayerBackPolicyTest` und `BackgroundPlaybackPolicyTest`. Sie prüfen Handy-Liveinhalt ohne erkannte Audio-only-Spuren, gewöhnliches Audio, VOD, die Minimieren-Einstellung sowie Appwechsel/Displaysperre für Live-Audio und Live-Video. TV-Entscheidungen werden ebenfalls abgesichert.

Der Vorher-/Nachher-Lauf verwendete dieselben beiden Testsuiten und die tatsächlichen Produktionsquellen:

| Prüfung | Vor der Verhaltenskorrektur | Nach der Verhaltenskorrektur |
| --- | --- | --- |
| `PlayerBackPolicyTest` | 5 Tests, 1 erwarteter Fehler | 5 Tests bestanden |
| `BackgroundPlaybackPolicyTest` | 11 Tests bestanden | 11 Tests bestanden |
| Gesamt | 16 Tests, 1 Fehler | **16 Tests bestanden, keine übersprungen** |
| Android-/Compose-Appcode und Testquellen | Erfolgreich kompiliert | Erfolgreich kompiliert; `compileDebugKotlin` erneut ausgeführt |

Der einzige vorherige Fehler war `handheldKeepsLiveContentWithoutWaitingForAudioOnlyTracks`. Zum Reproduzieren war das neue Quellenargument bereits angeschlossen, die Entscheidung aber noch der bisherige Ausdruck ohne Live-Berücksichtigung. Nach Einsetzen der korrigierten Entscheidung bestand derselbe Test.

Gradle-Aufruf: `:app:testDebugUnitTest --tests com.ambient.tv.ui.player.PlayerBackPolicyTest --tests com.ambient.tv.ui.player.BackgroundPlaybackPolicyTest --offline --no-daemon --max-workers=2 -PAPK_ABI=arm64 --console=plain`, mit `CI=true` und `SKIP_MPV_NATIVE=1`. Unabhängige Quellprüfung bestätigte die sechs Quellenflags, Aufnahmen-/Timeshift-Abgrenzung und fortbestehenden Sessionbesitz.

Die verwendete lokale Testumgebung kompiliert den Android-/Compose-Appcode mit den vorhandenen SDK-/Gradle-Abhängigkeiten offline. Der optionale lokale SmartTube-Quellcheckout und die benutzerdefinierte VLC-AAR sind dort nicht eingebunden; VLC wird über den vorhandenen Maven-Fallback kompiliert. Ein Build dieser Varianten oder eine Geräteprüfung wird dadurch nicht ersetzt.

## Geräteabnahme

Für jeden verfügbaren Renderer: Livekanal oder SmartTube-Livestream starten, mit Zurück in die App navigieren, Miniplayer prüfen, zu einer anderen App wechseln, Display sperren und anschließend den Player erneut öffnen. Der Stream soll dabei weiterlaufen. Danach ausdrücklich stoppen und die Freigabe der Verbindung prüfen. Live-Radio zusätzlich während des anfänglichen Bufferings prüfen.

Eine reale Handy-/Streamprüfung wurde in dieser Arbeitsumgebung nicht durchgeführt.

## Codebelege

Alle folgenden Pfade liegen unter `app/src/main/java/com/ambient/tv/`:

- `ui/player/PlayerBackPolicy.kt`: gemeinsame Zurück-Entscheidung.
- `ui/player/BackgroundPlaybackPolicy.kt` und `BackgroundPlaybackGuard.kt`: Appwechsel und Displaysperre.
- `ui/tvheadend/TvheadendPlayerScreen.kt`, `VlcTvheadendPlaybackSurface.kt`, `MpvTvheadendPlaybackSurface.kt`: drei Live-TV-Aufrufer.
- `ui/youtube/YouTubePlayerScreen.kt`: drei SmartTube-Aufrufer.
- `ui/tvheadend/TvheadendPlayerViewModel.kt`: Sessionbesitz in `onCleared()`.
- `ui/player/PlaybackService.kt`: gemeinsame Mediensteuerung und explizites Schließen.
