# Ambient TV: Kopfzeilen und Ansichtsaktionen der Browse-Views

## Auftrag, historischer Prüfstand und Umsetzung

- Datum: 29.10.2026, Europe/Berlin.
- Ursprünglicher Auftrag: Live-TV und weitere Übersichten darauf prüfen, ob der Ansichtsbutton und die angrenzenden Bedienelemente anders angeordnet sind als in anderen Views.
- Folgeauftrag: „OK, mach das. Dann Mergen“ – die beschriebene Vereinheitlichung umsetzen und zusammenführen.
- Zusätzlicher Auftrag während der Umsetzung: sinnvolle fehlende „Neu laden“-Aktionen prüfen und in die Vereinheitlichung einbeziehen.
- Auditbasis: sauberer lokaler `main` auf `f48d89f` im ausgecheckten Repository `seinist/ambient-tv`; ursprünglicher Dokumentationsbranch `docs/browse-toolbar-consistency-audit`.
- Umsetzungsbranch: `fix/unify-browse-toolbars`, auf derselben Basis einschließlich Auditdokument.
- Methode: Quellcodevergleich der tatsächlichen Compose-Aufrufer, gemeinsamen Komponenten, Ressourcen und Navigationsrouten; unabhängige Gegenprüfungen vor und nach der Umsetzung.
- Stand vor dem Merge: Ansichtsaktionen, Filter-/Sortierleisten und die ergänzten Reload-Aktionen sind umgesetzt und lokal validiert. Abschnitt 8 dokumentiert die tatsächlich ausgeführten Prüfungen; die vollständigen PR-Checks und der Merge werden im zugehörigen GitHub-Pull-Request protokolliert.

**Abschnitte 1 bis 6 dokumentieren den historischen Befund vor der Umsetzung auf `f48d89f`.** Dortige Aussagen zum damaligen Aufbau und Quellenzeilen beziehen sich auf diesen Ausgangsstand. Abschnitt 7 beschreibt die tatsächlich vorgenommenen Änderungen, Abschnitt 8 ihren aktuellen Nachweisstatus.

Im ursprünglichen Audit wurde zusätzlich der laufende lokale Branch `fix/ui-navigation-diagnostics-build-number` bei `a46c3f2` einschließlich seiner damals vorhandenen Änderungen verglichen. Dessen Fokus- und Settings-Arbeit änderte die hier untersuchten Toolbar-Anordnungen zu diesem Zeitpunkt nicht. Diese fremden laufenden Änderungen wurden ausschließlich gelesen und nicht in den Umsetzungsbranch übernommen.

## 1. Vorhandenes gemeinsames Muster

Die Bibliotheksansicht `SectionScreen` eignet sich als Referenz:

1. Seitentitel und Kontext links, Kontextaktionen und abschließend **Ansicht** rechts.
2. Bereichsreiter darunter: Empfehlungen, Bibliothek, Sammlungen.
3. Darunter bei aktivierter Einstellung eine gemeinsame Filter- und Sortierleiste.
4. Inhalt unterhalb dieser Bedienelemente.

`PageHeader`, `TvChip`, `ViewOptionsChip`, `PageChipRow`, `FilterBar` und `ViewOptionsPanel` sind bereits vorhanden. Ein neuer grundlegender Button-Stil ist für die Vereinheitlichung nicht erforderlich.

Die gemeinsame Ansichtsaktion verwendet das Tune-Symbol und den Text „Ansicht“. Bei ausgeblendeter Filterleiste können aktive Filter als Anzahl am Button erscheinen. `iconOnly` bedeutet bei `TvChip` nur am Handy eine reine Icon-Darstellung; am TV bleibt die Beschriftung sichtbar. Die Handy-Aktionsfläche ist mindestens 48 × 48 dp groß.

Quellen: `app/src/main/java/com/ambient/tv/ui/section/SectionScreen.kt:538`, `app/src/main/java/com/ambient/tv/ui/common/PageChrome.kt:248`, `app/src/main/java/com/ambient/tv/ui/common/ViewOptionsChip.kt:27`, `app/src/main/java/com/ambient/tv/ui/common/TvPolish.kt:687`.

## 2. Historischer Vergleich der Views

| View | Aufbau auf der Auditbasis | Bewertung |
| --- | --- | --- |
| Bibliotheken | Header: Übersicht → Ansicht. Reiter und Filter/Sortierung darunter. | Geeignete Referenz. |
| YouTube / SmartTube | Header: Konto bzw. Abo → Suche → Ansicht. Feed-Reiter, gegebenenfalls Suchfeld und Filterleiste darunter. | Entspricht dem grundlegenden Muster. |
| Live-TV / Senderliste | Header: nur Neu laden. Unter den Reitern: Filter/Sortierung → Ansicht → A–Z → Senderanzahl. | Wesentliche Abweichung; Ansicht gehört zur scrollenden Filterzeile. |
| Programmführer / Guide | Verwendet denselben `LiveTvChannelBrowser` wie die Senderliste. | Dieselbe Abweichung. |
| Aufnahmen / Timer | Header mit Neu laden und Bereichsreitern; feste Inhaltslisten, kein Ansichtsbutton. | Derzeit keine alternative Darstellung implementiert; fehlender Button ist allein kein Fehler. |
| DLNA | Header: Zurück → Ansicht anpassen → Server → Neu laden. Breadcrumbs darunter. | Ansicht an anderer Position und anders beschriftet. |
| Personen-/Sammlungsergebnisse | Zusätzliche obere Zurück-/Settings-Leiste; darunter Titel mit Sortierzyklus und direktem Liste/Raster-Wechsel; Sortierrichtung in weiterer Zeile. | Deutlicher struktureller Sonderfall. |
| Globale Suche | Header mit direktem Reihen/Listen-Wechsel bei vorhandenen Ergebnissen. | Gleicher Zweck, aber andere Interaktion als das Ansichts-Panel. |
| Fotos | Header: gegebenenfalls Zurück → Diashow. Darunter Timeline/Ordner und Datum/Name; zusätzlich eigene Sortierrichtungszeile. | Bereichswahl und Sortierfunktionen sind gemischt. |
| Dateien / NAS | Header: Zurück → Ordner hinzufügen → NAS → Neu laden → gegebenenfalls Diashow. Breadcrumbs darunter. | Kontextaktionen; keine umschaltbare Inhaltsdarstellung implementiert. |
| Spotify | Header: Anmelden/Abmelden → Jetzt läuft. | Kontextaktionen; keine Ansichtsumschaltung implementiert. |
| Provider-Übersichten | Header: Home → Ansicht. Am TV in den Hero eingebunden; am Handy mit explizitem Providertitel. | Bewusste Kontextvariante, gemeinsame Ansichtsbedienung. |

Quellen: `SectionScreen.kt:538`, `YouTubeScreen.kt:320`, `TvheadendScreen.kt:458`, `Guide.kt:206`, `DlnaScreens.kt:242`, `FilteredBrowseScreen.kt:106`, `SearchScreen.kt:138`, `PhotoBrowserScreen.kt:143`, `FilesScreen.kt:127`, `SpotifyHomeScreen.kt:107`, `HomeScreen.kt:360`; jeweils in ihrem Unterverzeichnis unter `app/src/main/java/com/ambient/tv/ui/`.

## 3. Bestätigte Befunde

### B-1: Live-TV verschiebt Ansicht mit der Filterleisten-Einstellung

`TvheadendHeader` enthält als Headeraktion ausschließlich Neu laden. Die Ansichtsaktion wird erst im darunter eingebauten `LiveTvChannelBrowser` angelegt. Dessen `trailing`-Block enthält Ansicht, A–Z und Senderanzahl.

- **Filterleiste eingeblendet:** Der komplette Block folgt auf die Filter, Sortierung, Sortierrichtung und gegebenenfalls Zurücksetzen. `FilterBar` setzt alles in dieselbe horizontal scrollende `Row`.
- **Filterleiste ausgeblendet:** Derselbe Block erscheint in einer eigenen links ausgerichteten `Row` unter den Bereichsreitern.

Damit ändern sich Position und Umgebung einer grundlegenden Seitenaktion mit einer Darstellungspräferenz. Bei begrenzter Breite kann Ansicht erst nach horizontalem Scrollen sichtbar werden. Das Risiko ergibt sich unmittelbar aus der Reihenfolge und dem gemeinsamen Scrollcontainer; das genaue Verhalten bei einzelnen Geräten und Skalierungen wurde nicht gerendert.

Der Aufbau betrifft den gemeinsamen Live-TV-Screen. Auch die quellenbezogenen Live-TV-Routen verwenden `TvheadendScreen`; die Feststellung ist deshalb nicht auf die TVheadend-Serveranbindung begrenzt. TV-/Radioauswahl ist ein Filter innerhalb dieses Browsers.

Quellen: `TvheadendScreen.kt:458`, `Guide.kt:206`, `Guide.kt:219`, `app/src/main/java/com/ambient/tv/ui/common/FilterBar.kt:53`, `app/src/main/java/com/ambient/tv/ui/MainActivity.kt:2117`, `GuideFilterBar.kt:18`.

### B-2: Der Live-TV-Ansichtsbutton hat einen reduzierten Funktionsumfang

Live-TV öffnet `FilterOptionsPanel`. Dieses verwendet denselben `ViewOptionsPanel`-Unterbau wie andere Seiten, übergibt aber keine Layoutauswahl und deaktiviert die Dichte-Steuerung.

Bei sichtbarer Filterleiste enthält das Panel folglich im Wesentlichen deren Ein-/Ausschalter. Erst bei ausgeblendeter Leiste werden Sortierung und Filter im Panel angeboten. Das ist die bestehende gemeinsame Regel zur Vermeidung doppelter Bedienelemente; Live-TV besitzt hier aktuell keinen zusätzlichen Raster-/Listenwechsel.

Der generische Hinweis „Darstellung, Sortierung und Filter anpassen“ wird trotzdem geerbt. Bei einer Überarbeitung sollte der Hinweis den tatsächlich vorhandenen Optionen entsprechen. Ein Verschieben des Buttons darf keine nicht vorhandenen Ansichtsmodi suggerieren.

Quellen: `Guide.kt:316`, `app/src/main/java/com/ambient/tv/ui/common/FilterBar.kt:149`, `app/src/main/java/com/ambient/tv/ui/common/ViewOptionsPanel.kt:169`, `app/src/main/res/values-de/strings.xml:359`.

### B-3: DLNA weicht bei Reihenfolge und Beschriftung ab

DLNA öffnet bereits das gemeinsame Ansichts-Panel, verwendet im Header aber einen eigenen `TvChip` mit dem Text „Ansicht anpassen“. Außerdem steht dieser vor Server und Neu laden. Bibliotheken und YouTube platzieren den kurzen Ansichtsbutton am Ende ihrer Aktionsgruppe.

Hier reichen die gemeinsame Komponente `ViewOptionsChip` und eine konsistente Reihenfolge aus. Neue Ansichtsoptionen sind dafür nicht notwendig.

Quellen: `app/src/main/java/com/ambient/tv/ui/dlna/DlnaScreens.kt:244`, `app/src/main/java/com/ambient/tv/ui/common/ViewOptionsChip.kt:27`.

### B-4: Suche und gefilterte Ergebnisse verwenden direkte Umschalter

Suche schaltet sofort zwischen Reihen und Listen um. Personen-/Sammlungsergebnisse schalten sofort zwischen Raster und Liste um; daneben wird die Sortierart zyklisch gewechselt. Beschriftung und Icon zeigen jeweils die Zielansicht. Die normalen Bibliotheken öffnen dagegen ein Panel zur expliziten Auswahl.

Die direkte Umschaltung ist bei zwei Modi funktional nachvollziehbar und kein nachgewiesener Funktionsfehler. Für ein durchgehend gleiches Bedienmuster kann auch hier ein fester Ansichtsbutton das gemeinsame Panel mit nur den tatsächlich vorhandenen Modi öffnen. Abwägung: ein zusätzlicher Aktivierungsschritt für den Wechsel, dafür ein überall gleich verständlicher Einstieg.

Quellen: `app/src/main/java/com/ambient/tv/ui/search/SearchScreen.kt:138`, `app/src/main/java/com/ambient/tv/ui/browse/FilteredBrowseScreen.kt:109`, `FilteredBrowseScreen.kt:123`, `FilteredBrowseScreen.kt:142`.

### B-5: Fotos verteilt Sortierfunktionen über mehrere Ebenen

Timeline/Ordner ist ein Bereichswechsel. Datum/Name und auf-/absteigend sind hingegen Sortieroptionen. Aktuell sind Datum/Name in dieselbe Chipzeile wie die Bereichswahl eingebaut; die Richtung besitzt eine zusätzliche Zeile.

Der Bereichswechsel sollte sichtbar bei den Reitern bleiben. Sortierfeld und Richtung können gemeinsam die vorhandene Filter-/Sortierleiste verwenden und bei ausgeblendeter Leiste über das Ansichts-Panel erreichbar sein. Die Diashow bleibt eine direkte Kontextaktion im Header.

Quelle: `app/src/main/java/com/ambient/tv/ui/photos/PhotoBrowserScreen.kt:154`.

## 4. Empfohlener gemeinsamer Aufbau

| Ebene | Inhalt und Regel |
| --- | --- |
| Header | Titel und Kontext links; Kontextaktionen rechts; **Ansicht als letzte Aktion**, soweit Optionen vorhanden sind. Eine eingeblendete Uhr bleibt rechts anschließend Teil des Headers. |
| Bereichszeile | Fachliche Reiter oder Breadcrumbs, beispielsweise Live-TV / Guide / Aufnahmen / Timer. |
| Optionale Filterzeile | Ausschließlich Filter und Sortierung; Grundaktionen wie Ansicht bleiben außerhalb dieses Scrollcontainers. |
| Inhalt | Sender, Guide, Medienraster oder Liste. Eine Ergebnisanzahl gehört in den Titelkontext oder eine feste Inhaltsinformation. |

Für Live-TV lautet die konkrete Header-Reihenfolge **Neu laden → Ansicht**. A–Z bleibt bei der Sendernavigation als Schnellzugriff erhalten; die Senderanzahl kann als Kontextinformation unter dem Titel stehen. Bei aktiven Filtern ist eine unterscheidbare Angabe von sichtbaren und gesamten Sendern sinnvoll, sofern beide Werte verwendet werden.

Für DLNA: **Zurück → Server → Neu laden → Ansicht**. Für YouTube und Bibliotheken ist die Position bereits passend. Für Dateien, Spotify und die festen Aufnahme-/Timerlisten ist ohne zusätzliche verfügbare Optionen keine künstliche Ansichtsaktion erforderlich.

Die Filterleisten-Präferenz behält ihre bestehende Bedeutung: bei sichtbarer Leiste dort filtern und sortieren, bei ausgeblendeter Leiste im Panel. Der Einstieg ins Panel bleibt in beiden Fällen an derselben Stelle.

### Technischer Zuschnitt

1. Live-TV-Optionszustand und Auslöser so an den gemeinsamen Seiten-Header anbinden, dass Kanalbrowser und Guide denselben Zustand benutzen. Ein bloßes Verschieben des gezeichneten Buttons ohne seine Fokus- und Panel-Anbindung reicht nicht.
2. Die vorhandene `ViewOptionsChip`-Komponente für alle Panel-Auslöser einsetzen und Reihenfolge/Beschriftung vereinheitlichen.
3. Panel schließen, Filter bearbeiten und leere Trefferlisten müssen den Fokus weiterhin an die passende Aktion bzw. das bearbeitete Filterfeld zurückgeben.
4. Die laufende gemeinsame Sidebar-Fokuswiederherstellung erhalten. Sie ist bereits Gegenstand eines anderen Changes; dieser Audit meldet sie nicht als neue unbearbeitete Baustelle.
5. Die Änderungen nach Integration der laufenden UI-Arbeit auf deren Stand aufsetzen, da unter anderem `PageChrome`, `TvheadendScreen`, `Guide`, `SectionScreen` und `DlnaScreens` in beiden Vorhaben berührt werden.

## 5. Gezielte Abnahme nach einer Umsetzung

- TV: gleiche Position und Fokusdarstellung der Ansichtsaktion in Bibliothek, Live-TV, Guide, YouTube und DLNA.
- Live-TV/Guide: Filterleiste an/aus, aktive Filter, lange Gruppenbezeichnungen, leere Treffer und Laden/Neu laden; Ansicht bleibt im Header erreichbar.
- D-Pad: Inhalt → Filter → Reiter → Header sowie Rückkehr aus dem Panel auf dessen Auslöser; Sidebar-Rückkehr bleibt auf dem zuvor gewählten Inhalt.
- Handy: schmales Hochformat, Querformat und größere Schrift. Aktionsgruppen müssen einschließlich Konto und aktiver Filteranzeige erreichbar bleiben.
- Foto-/Such-/Ergebnisansichten: nur tatsächlich implementierte Modi anbieten; bestehende Sortierung, Filterwerte und Inhaltsposition erhalten.

Ein zusätzlicher konkreter Handy-Prüffall besteht bereits unabhängig von Live-TV: Der YouTube-Kontobutton kann bis 240 dp breit werden, während die Header-Aktionen in einer nicht umbrechenden Gruppe angeordnet sind. Aktive Filter machen den Ansichtsbutton zum Textchip. Dies ist ein aus dem Code abgeleiteter Breitenprüffall, kein reproduzierter Fehler.

Quellen: `app/src/main/java/com/ambient/tv/ui/common/PageChrome.kt:270`, `app/src/main/java/com/ambient/tv/ui/youtube/YouTubeAccountButton.kt:51`, `app/src/main/java/com/ambient/tv/ui/common/ViewOptionsChip.kt:30`.

## 6. Nachweise der ursprünglichen Auditphase

| Prüfung | Ergebnis |
| --- | --- |
| Referenz-Checkout und Basiscommit | Geprüft; `main` bei `f48d89f`, sauber. |
| Tatsächliche Compose-Aufrufer und gemeinsame Komponenten | Geprüft. |
| Live-TV-Routen und gemeinsame Verwendung des Kanalbrowsers | Geprüft. |
| Vergleich mit laufenden Fokus-/Settings-Änderungen | Geprüft; Anordnungsbefunde bestehen weiterhin. |
| Laufzeitverhalten auf TV/Handy und visuelle Abnahme | Nicht ausgeführt. |
| Android-Build und zusätzliche Tests | In der ursprünglichen Auditphase nicht ausgeführt; Nachweise der anschließenden Umsetzung stehen in Abschnitt 8. |

Die Positionen, Beschriftungen und Interaktionsarten sind anhand des Quellcodes belegt. Konkrete Abschneidefehler, Fokusfehler oder Darstellungsfehler auf einem Gerät werden ohne entsprechenden Lauf nicht behauptet.

## 7. Umsetzung

### 7.1 Live-TV und Guide

Der gemeinsame Seitenheader enthält jetzt **Neu laden → Ansicht**. `ViewOptionsChip` liegt außerhalb der horizontal scrollenden Filterleiste und bleibt bei sichtbarer und ausgeblendeter Leiste an derselben Stelle. Die Ergebnisanzahl erscheint unter dem Titel; bei aktiven Filtern werden sichtbare und gesamte Sender unterschieden. A–Z bleibt als eigener Schnellzugriff neben der Sendernavigation und außerhalb der scrollenden Filterfelder erhalten.

`LiveTvBrowseControls` verbindet Headerauslöser, Filterleiste und Panel mit demselben Fokuszustand innerhalb des jeweiligen Bereichs. Der Panelzustand liegt oberhalb des austauschbaren Inhalts. Dadurch entfernt ein Wechsel zu Laden, Fehler oder leerem Ergebnis weder den Headerauslöser noch ein bereits geöffnetes Panel. Nach dem Schließen wird dessen Auslöser erneut fokussiert. Beim Verlassen des Bereichs werden das alte Panel und eine ausstehende Fokusrückgabe verworfen.

Eine gemeinsame `LiveTvChannelListing` liefert Zeitbasis, gefilterte Sender und Programmlisten für Headerzählung und Browser. Das Ansichtspanel bleibt auf die tatsächlich vorhandenen Filter-/Sortieroptionen beschränkt; zusätzliche Raster-/Listenmodi oder Dichteoptionen wurden für Live-TV nicht eingeführt. Der Hinweistext beschreibt diesen Umfang.

Quellen: `app/src/main/java/com/ambient/tv/ui/tvheadend/TvheadendScreen.kt`, `app/src/main/java/com/ambient/tv/ui/tvheadend/Guide.kt`, `app/src/main/java/com/ambient/tv/ui/tvheadend/LiveTvBrowseControls.kt`, `app/src/main/java/com/ambient/tv/ui/common/FilterBar.kt`, `app/src/main/res/values-de/browse_toolbar.xml`.

### 7.2 DLNA

Die Aktionsgruppe lautet jetzt **Zurück → Server → Neu laden → Ansicht**. Für die letzte Aktion wird `ViewOptionsChip` mit dem einheitlichen kurzen Label verwendet. Vorhandene DLNA-Ansichtsoptionen und der zugehörige Fokusrequester bleiben erhalten.

Quelle: `app/src/main/java/com/ambient/tv/ui/dlna/DlnaScreens.kt`.

### 7.3 Fotos

Bereichswahl und Sortierung sind getrennt. Timeline/Ordner bleibt, sofern für die Quelle verfügbar, in der Bereichszeile. Datum/Name und die Sortierrichtung stehen gemeinsam in `FilterBar` und bei global ausgeblendeter Leiste im gemeinsamen Panel. **Diashow** bleibt eine unmittelbare Headeraktion; danach folgt **Neu laden** und in der Ordneransicht **Ansicht** als letzte Aktion.

Die chronologisch gruppierte Timeline erhält keine wirkungslose Sortier- oder Layoutauswahl. Für lokale Dateien ohne Timeline wird keine leere Reiterzeile als Fokusziel registriert. Öffnen, Bearbeiten und Schließen des Panels berücksichtigen die bestehende Inhaltsfokussierung, ohne beim Sortieren automatisch in eine Fotokachel zu springen.

Quelle: `app/src/main/java/com/ambient/tv/ui/photos/PhotoBrowserScreen.kt`.

### 7.4 Globale Suche

Ein fester **Ansicht**-Button ersetzt den wechselnd beschrifteten Sofortumschalter. Das gemeinsame Panel bietet genau die bestehenden Modi **Reihen** und **Liste**. Das reine Fokussieren einer Vorschau ändert den Modus nicht; die Auswahl verwendet einen expliziten, idempotenten Setter. Neue Dichte-, Sortier- oder Filteroptionen wurden nicht ergänzt. Suchfeld, Quellenauswahl und explizite Suchausführung bleiben erhalten.

Nach dem Schließen kehrt der Fokus auf Ansicht zurück. Entfernt eine Zustandsänderung sämtliche Ergebnisse, schließt sich das Panel und kann den Fokus an das Suchfeld zurückgeben. **Eine neue externe Suchanfrage schließt das alte Panel und verwirft dessen ausstehende Fokusrückgabe vor Beginn der neuen Suche.** Dadurch kann eine sehr schnell beantwortete Anfrage keinen alten Panelzustand oder dessen Rückgabefokus übernehmen. Verzögerte Fokusanforderungen der neu komponierten Ergebnisdarstellung werden unterdrückt, solange Panel-/Toolbarbedienelemente den Fokus besitzen.

Quellen: `app/src/main/java/com/ambient/tv/ui/search/SearchScreen.kt`, `app/src/main/java/com/ambient/tv/ui/search/SearchViewModel.kt`.

### 7.5 Gefilterte Personen- und Sammlungsergebnisse

`FilteredBrowseScreen` verwendet eine einzige Kopfzeile mit Titel sowie **Zurück → Einstellungen → Neu laden → Ansicht**. Die zusätzliche `PageUtilityBar` entfällt. Titel, Zurück und Einstellungen stehen auch beim Laden und bei Fehlern bereit. Sortierfeld und Richtung verwenden die gemeinsame `FilterBar`; bei ausgeblendeter Leiste sind sie im Panel erreichbar.

Das Panel bietet genau die vorhandenen Modi **Raster** und **Liste**, ohne neue Dichteoptionen. Explizite Setter ersetzen Toggle-/Cycle-Verhalten. Eine erneute Auswahl derselben Sortierart setzt eine zuvor gewählte Richtung nicht zurück. Gespeicherte Element-ID und bestehende Scrollwiederherstellung werden weiterverwendet. Das Schließen des Panels stellt den Fokus auf Ansicht zurück; die Rückkehr aus Einstellungen auf Einstellungen. Die eigenständige Listenfokusanforderung beachtet die gemeinsame Fokussteuerung.

Quellen: `app/src/main/java/com/ambient/tv/ui/browse/FilteredBrowseScreen.kt`, `app/src/main/java/com/ambient/tv/ui/browse/FilteredBrowseViewModel.kt`.

### 7.6 Im unabhängigen Review korrigierte Fokusfälle

1. **Veraltetes Up-Ziel nach Tabwechsel oder leerem Ergebnis:** Eine geteilte `PageFocusChain` konnte nach Verlassen des Kanalbrowsers noch auf dessen Filter-/A–Z-Requester zeigen. Nachfolgende Listen hätten dadurch die Reiter überspringen können. Jeder Live-TV-Tab besitzt jetzt eine eigene Chain. Zusätzlich registriert eine dauerhaft vorhandene Inhalts-Box im Kanalbrowser das Up-Ziel auch bei leeren Ergebnissen und dem dortigen Ladeindikator: sichtbarer Filter, sonst A–Z bei vorhandenen Sendern, sonst Bereichsreiter. So bleibt kein Ziel auf einem entfernten A–Z-Button zurück.
2. **Doppelte Filterrequester während Crossfade:** Ein- und ausblendender Live-/Guide-Inhalt erhielten zunächst dieselben Controls. Dadurch war ein Filterrequester vorübergehend an zwei Felder gebunden. Senderliste und Guide besitzen jetzt getrennte `LiveTvBrowseControls` mit eigenen Requestern und Filterregistrierungen; die Crossfade-Inhalte erhalten die zu ihrem Tab gehörenden Controls und Chains.

Die korrigierten Aufrufer und Zielzuordnungen wurden anschließend erneut im Quellcode geprüft. Eine Laufzeitabnahme der gesamten D-Pad-Kette wird dadurch nicht vorweggenommen.

Quellen: `TvheadendScreen.kt` – `channelControls`, `guideControls`, `pageFocusChains` und tabbezogene Crossfade-Aufrufe; `Guide.kt` – dauerhaft vorhandene Inhalts-Box mit `pageContentUp`; `LiveTvBrowseControls.kt` – `leavePage`.

### 7.7 Zusätzliche Neu-laden-Aktionen

Die Ergänzung betrifft Ansichten mit veränderlichen externen Inhalten und verwendet vorhandene Refreshpfade. Die Reihenfolge lautet **Kontextaktionen → Neu laden → Ansicht**, sofern die Seite ein Ansichtspanel besitzt.

| Bereich | Umsetzung / Bedeutung |
| --- | --- |
| Mediathek, Empfehlungen, Sammlungen | Erneuert den Cache der aktuellen Bibliothek und lädt mit bestehenden Filtern, Sortierung und Seitenwahl neu. |
| Plex-/Jellyfin-/Emby-Übersicht | Verwendet den vorhandenen Refresh im aktiven Providerkontext. Globale Home-Hero-Anordnung bleibt ohne zusätzliche Kopfzeile. |
| Darsteller-/Sammlungsergebnisse | Bereitet die betreffende Quelle mit Cache-Erneuerung vor und lädt die aktuelle Liste erneut. |
| YouTube / SmartTube | Aktualisiert den aktuellen Feed, Kanal oder die aktive Suche einschließlich zuvor geladener Reihenlänge. |
| Spotify | Aktualisiert Home, die aktuelle Bibliothekssektion oder die laufende Suchanfrage; nur bei autorisiertem Webkonto bedienbar. |
| Fotos | Aktualisiert das offene Album oder die Timeline bei beibehaltener Sortierung. |
| Trailer-Katalog | Erneuert die TMDB-Caches und baut die bereits geladene Feed-Seitentiefe wieder auf; bestehende Rate-Limit-Sperren bleiben wirksam. |

Die neuen Buttons nutzen einen beobachtbaren Status für den vollständigen Refresh, statt das nur für leere/erstmals geladene Inhalte gedachte `loading`-Flag zu verwenden. Abgebrochene ältere Anfragen dürfen den Status einer neueren Anfrage nicht zurücksetzen. Vorhandene Inhalte bleiben bei laufendem Abruf sichtbar; manuelle Fehler erhalten eine Rückmeldung über die bestehenden Fehler-/Benachrichtigungswege.

Live-TV/Guide und Aufnahmen/Timer hatten bereits eine gemeinsame Aktualisierung. Die Live-TV-Aktion berücksichtigt jetzt auch `actionInProgress` und hält den Fokus bei den Headerkontrollen. Die vorhandenen Buttons in Dateien und DLNA zeigen den laufenden Abruf auch nach Einblenden eines gespeicherten Snapshots an und unterdrücken eine automatische Inhaltsfokussierung nach manuellem Neuladen. DLNA baut beim manuellen Reload jetzt denselben zuvor geladenen Seitenumfang wie beim Reconnect wieder auf. Die globale Suche behält ihre explizite Suchaktion; DLNA-Serververwaltung behält „Server suchen“.

Quellen: jeweils Screen und ViewModel unter `app/src/main/java/com/ambient/tv/ui/{section,home,browse,youtube,spotify,photos,files,dlna}/`; außerdem `app/src/main/java/com/ambient/tv/trailers/TrailerScreen.kt`, `TrailerSnapshotViewModels.kt` und `ui/tvheadend/TvheadendScreen.kt`.

## 8. Nachweisstatus der Umsetzung

| Prüfung | Status | Nachweis |
| --- | --- | --- |
| Quellcodevergleich der Toolbar-Anordnung | PASS | Gemeinsame Komponenten, Ansicht als letzte Aktion; fachliche Reiter und Sortierung getrennt. |
| Unabhängiges Fokus-/Refresh-Review | PASS auf Quellcodeebene | Getrennte Live-TV-Controls/Chains, leere Ergebnisse, Crossfade, Generationenschutz, vollständiges Collection-Enrichment und manuelle Mehrfachaktivierung geprüft. |
| Neue externe Suche bei offenem Ansichtspanel | PASS auf Quellcodeebene | `consumeExternalQuery` schließt das alte Panel und verwirft den Rückgabefokus vor der neuen Anfrage. |
| Android-Produktionscode und JVM-Testquellen | PASS | Vollständig neu aufgebaut mit Java 21, Android SDK 37 und `-PAPK_ABI=arm64`; `SKIP_MPV_NATIVE=1`, Kotlin-Inkrementalität für diesen lokalen Lauf deaktiviert. |
| Gezielte JVM-/Compose-Regressionssuite | PASS | **82 Tests, 0 Fehler, 0 übersprungen**, verteilt auf 14 Testklassen. |
| Neue Regressionstests | PASS | 17 neue Tests: Live-TV-Header/Panel (4), Bibliotheks-/Provider-Reload (2), schmaler YouTube-Kontoheader (1), FilteredBrowse-Refresh (3), Trailer-Loadtokens (3), Foto-Reload und abgebrochene Antworten (4). |
| Android-Gerätetestquellen | PASS – kompiliert | `:app:compileDebugAndroidTestKotlin`; angepasste Guide-/Suchfixtures und drei ältere API-Drifts in vorhandenen Geräte-Audits korrigiert. Kein Gerätelauf behauptet. |
| Buildskripte | PASS | `bash -n build.sh`; Python-Suite mit 43 Tests, davon 2 regulär übersprungen, ohne Fehler. |
| Patch- und Ressourcenprüfung | PASS | `git diff --check`; identische neue Ressourcenschlüssel und Formatplatzhalter in Standard-, deutscher und englischer Übersetzung. |
| Reale TV-/Handybedienung und visuelle Geräteabnahme | NICHT AUSGEFÜHRT | Kein Emulator oder physisches Gerät in dieser Umgebung; die Layout-/Fokusfälle wurden mit Robolectric/Compose geprüft. |
| Vollständige PR-Checks und Merge | Separater Nachweis in GitHub | Diese lokale Abnahme ist der Stand vor der Veröffentlichung. Der zugehörige Pull Request enthält die Ergebnisse des vollständigen CI-Laufs und den endgültigen Merge-Commit. |

Die 14 ausgeführten Testklassen sind `LiveTvBrowseToolbarTest`, `BrowseReloadControlsTest`, `YouTubeAccountToolbarTest`, `FilteredBrowseRefreshTest`, `TrailerCatalogRefreshTest`, `PhotoBrowserReloadTest`, `FilterFocusPolicyTest`, `LiveTvReturnFocusTest`, `GuideChannelsTest`, `GuideOptionsTest`, `DlnaBrowserFocusTest`, `LibraryViewOptionsTest`, `YouTubeViewOptionsTest` und `TmdbTrailerRepositoryTest`.

Ein lokaler inkrementeller Compile meldete identische alte und neue File-Facade-Deklarationen. Der bereinigte vollständige Compile war erfolgreich; dafür war keine Produktionskorrektur nötig. Der zusätzliche Android-Testcompile deckte außerdem bereits auf `main` vorhandene veraltete `ClockSettings`-/Hero-Höhen-Aufrufe und eine entfernte Audio-Ressource in drei Geräte-Testfixtures auf. Diese wurden minimal an die aktuellen Produktionssignaturen angepasst.
