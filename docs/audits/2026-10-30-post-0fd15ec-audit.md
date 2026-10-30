# Voll-Audit seit 0fd15ec — 2026-10-30

## Scope

- Letzter vollständiger Änderungs-Audit: 2026-10-29, dokumentiert in `docs/audits/2026-10-29-commit-audit.md`.
- Audit-Baseline: `0fd15ec`.
- Geprüfter `main`-Stand vor den Audit-Fixes: `b33e8a4b88298de172e65fd542ed0aa349017e67`.
- Delta: **250 Commits, 216 geänderte Dateien**.
- Dazwischen liegende Teil-Audits (u. a. Browse-Toolbar, Mobile-Live-Background) wurden als zusätzliche Evidenz verwendet, ändern aber nicht die Voll-Audit-Baseline.

## Ergebnis

Keine P0/P1-Regression gefunden. Zwei neue P2-Funktionsregressionen wurden bestätigt und auf
`audit/2026-10-30-since-0fd15ec` behoben.

### F-1 — P2: Radio-Browser-Cancellation wurde als normaler Fehler/Fallback behandelt

Betroffene neue Pfade:

- `RadioBrowserRepository.playbackUrl`
- `RadioPlaybackLauncher.playInternal`
- `RadioViewModel.loadFacets/loadStations/play`
- Radio-Home-/Settings-Facetten und Home-Auswahl

Problem:

Mehrere neue Radio-Pfade verwendeten `runCatching` direkt um suspendierende Aufrufe. Kotlin
`runCatching` fängt auch `CancellationException`. Ein supersedierter Request konnte deshalb nach
einem schnellen Bereichs-/Such-/Senderwechsel noch Fehlerstatus publizieren oder beim Auflösen der
Radio-Browser-Click-URL auf die alte Katalog-URL zurückfallen.

Zusätzlich wurden Queue und „Zuletzt gehört“ vor erfolgreicher Erstellung des neuen Players
aktualisiert. Ein fehlgeschlagener Start konnte dadurch den sichtbaren Queue-Zustand auf einen
Sender setzen, der nie gestartet war.

Fix:

- suspendierende Radio-Fehlergrenzen verwenden jetzt `suspendResult`, das Cancellation weiterwirft;
- Queue und Recent-History werden erst nach erfolgreichem `PlaybackEngineFactory.create` committed;
- der neue Sender wird erst nach erfolgreichem Start Eigentümer des Reconnect-Zustands;
- Home, Settings und Home-Auswahl folgen derselben Cancellation-Regel.

Regressionstest:

- `RadioBrowserRepositoryTest.cancelledPlaybackResolutionDoesNotFallBackToCatalogUrl`.

### F-2 — P2: Radio-Untermenüänderungen konnten im Sidebar-State hängen bleiben

`SidebarUiState` und `refresh()` kannten `radioSubmenuKeys`, aber der deduplizierte
`sidebarRefreshSnapshot()` enthielt sie nicht.

Da `observeSidebarAvailability(...).distinctUntilChanged()` nur geänderte Snapshots weitergibt,
konnte eine reine Änderung von Settings → Radio Browser → Menüeinträge unterdrückt werden. Das Menü
aktualisierte sich dann erst nach einer anderen beobachteten Änderung oder einem Neuaufbau.

Fix:

- gemeinsamer `SidebarSubmenuSnapshot` für YouTube, Spotify, Radio Browser und TVHeadend;
- `radioSubmenuKeys` ist dadurch Teil des deduplizierten Refresh-Snapshots.

Regressionstest:

- `SidebarAvailabilityTest.radioSubmenuChangeInvalidatesSidebarConfigurationSnapshot`.

## Geprüfte Änderungsbereiche ohne neuen Befund

### Player / OSD / Panels

- gemeinsamer Panel-Host, verschachtelte Panel-Navigation und Animation;
- Auto-Hide bei Inaktivität, Dialog-/IME-/Popup-Fokus und Pointer-/Key-Aktivität;
- SmartTube Previous/Next/Restart und Player-Item-Controls;
- Frame-Drop-Live-Graph und Media3/VLC-Sampling;
- Audio-/Visualizer-/ProjectM-Pfade einschließlich Radio;
- Background-Playback und Fullscreen-Rückkehr.

Die neuen Panel-/OSD-Regressionstests decken die besonders riskanten Fokus- und Lifecycle-Übergänge
ab. Im Audit wurde keine neue Blocker-/P1-/P2-Regression bestätigt.

### Browse / Fokus / Toolbar / Reload

- `BrowseFocusReturn` und Rückkehr aus Sidebar/Fullscreen;
- Grid/List/Wall/Cinematic-Wechsel;
- Reload-/Options-Controls in Files, DLNA, Live TV, Photos, Search, SmartTube und Provider-Views;
- Restore nach Filter-/Sortierwechseln.

Der separate Browse-Toolbar-Audit vom 2026-10-29 wurde gegengeprüft; seitdem wurde keine neue
inkonsistente Vollbreiten-/Toolbar-Sonderlösung gefunden.

### Settings UI

- Kategorie-Header und Panel-Homogenisierung;
- normale Actions kompakt, breite Zeilen nur für echte Navigation/Listeneinträge;
- WireGuard-Datei-/Geräte-/Quellenzeilen bleiben bewusst vollbreit;
- Navigationssound-Sync ist kompakt.

### Auth / QR / Security

- SmartTube/YouTube gemeinsamer Settings-/Browse-QR-Flow;
- Plex Account-Link QR;
- Spotify vorhandener PKCE-/QR-Flow;
- Jellyfin offizieller Quick-Connect-Flow;
- Emby offizieller Connect-PIN-/QR-Flow mit lokalem `Connect/Exchange`;
- Emby `ExchangeToken` bevorzugt, `AccessKey` als Kompatibilitätsfallback;
- `X-Connect-UserToken` und lokale Server-Tokens sind durch `CredentialRedirectGuard` gegen
  Fremdhost-Redirects geschützt;
- WireGuard-QR verwendet denselben Parser/TokenStore-Pfad wie Datei/Paste.

Keine neue Credential-Leak-Regression bestätigt.

### TVHeadend / Live TV / HbbTV

- serverseitiger HbbTV-Metadaten-Fallback;
- HbbTV-Kategorie nur, wenn HbbTV ohne Öffnen des Streams aus Metadaten erkannt wird;
- Stream-AIT bleibt autoritativ;
- Guide-/Live-TV-Optionen und HbbTV-Metadatentests geprüft.

Keine neue bestätigte Regression.

### Radio Browser

Geprüft wurden API-Mirror-Discovery, GET-only-Failover, Repository-Normalisierung, Facetten,
Lokalisierung, Pagination, Favoriten/Recent, Home/Search/Sidebar, Player/OSD, Reconnect und
Background-Playback.

Neben F-1/F-2 wurde kein weiterer konkreter Funktionsfehler bestätigt. Der Failover-Interceptor hat
derzeit keinen eigenen dedizierten Unit-Test; das bleibt ein Testabdeckungs-Hinweis, kein bestätigter
Produktfehler.

### Build / Release / Ressourcen

- APK-Dateinamen/Buildnummer-Änderungen gegengeprüft;
- `main` `b33e8a4b`: PR checks erfolgreich;
- Ressourcen-Deltacheck `0fd15ec...b33e8a4b`: **58 neue Basis-Strings**, alle mit DE und EN;
- **0 fehlende DE-Keys, 0 fehlende EN-Keys, 0 Placeholder-Mismatches**.

## Bekannte Grenzen des Audits

- Statische Prüfung + JVM/CI kann reale HDMI-/Display-Mode-, Decoder-, Netzwerk-, Kamera- und
  TV-Remote-Hardware nicht vollständig simulieren.
- Radio-Browser-Mirror-Failover besitzt noch keinen dedizierten Interceptor-Test.
- HbbTV-Verhalten hängt zusätzlich von Sender-/Server-Metadaten und externen HbbTV-Anwendungen ab.

## Abnahme

Vor Merge der Audit-Fixes:

1. PR-Script-Checks müssen grün sein.
2. `:app:testDebugUnitTest` muss kompilieren und vollständig grün laufen.
3. Besonders relevant: Radio-Repository-Cancellation, Sidebar-Refresh, bestehende Player-/Focus- und
   Credential-Redirect-Tests.
