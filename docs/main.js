// Single place to change the GitHub repository.
const REPO = "seinist/ambient-tv";
const T = {
  de: {
    skip: "Zum Inhalt springen",
    tagline: "Deine Mediatheken, Live-TV und Dateien auf dem großen Bildschirm. Am Handy stöbern, am TV schauen und selbst wählen, wie deine Videos abgespielt werden.",
    download: "Neueste Version laden",
    meta: "Android TV &amp; Smartphone · Android 10+ (API 29) · Frei &amp; quelloffen (CC0)",
    why_h: "Warum?",
    why1: "Ambient TV begann mit ein paar Wünschen für den eigenen Filmabend: den Server vom Sofa aus wecken, den Player selbst wählen und das Bild passend zum Film einstellen.",
    why2: "Daraus ist ein gemeinsamer Ort für Mediatheken, Netzwerkordner und Live-TV geworden – mit Fernbedienungssteuerung am TV und Touch-Bedienung auf Handy und Tablet.",
    feat_h: "Was drin steckt",
    f1h: "Alle Quellen, eine App", f1: "Plex-, Jellyfin- und Emby-Mediatheken zusammen mit YouTube über SmartTube, Spotify und DLNA-Quellen nutzen.",
    f2h: "MPV · VLC · Media3", f2: "Drei Engines in einem gemeinsamen Player, Bildeinstellungen pro Titel gemerkt.",
    f3h: "Upscaler &amp; SVP", f3: "GPU-Shader-Upscaler wie NNEDI3, RAVU, FSRCNNX, Anime4K, FSR – dazu experimentelle SVP-Zwischenbildberechnung.",
    f4h: "HDR", f4: "Bildmodus-Anpassung, HDR-Tonemapping und optional SDR → HDR10 (Android 14+).",
    f5h: "Wake-on-LAN", f5: "Weckt schlafende Server und NAS-Quellen vor dem Verbinden.",
    f6h: "WireGuard unterwegs", f6: "WireGuard eingebaut, pro System schaltbar – die Mediatheken auch außer Haus.",
    f7h: "Handy und TV zusammen", f7: "Im selben Heimnetz per QR-Code koppeln. Das Handy als Touchpad oder Tastatur nutzen, unterstützte Inhalte am TV starten und Konten sowie Einstellungen nach Bestätigung übertragen. Ambient TV bleibt auf beiden Geräten geöffnet.",
    f8h: "Dateien ohne Medienserver", f8: "Lokale Ordner öffnen oder per SMB, NFS, WebDAV und SFTP verbinden. Eigene Videos und Musik direkt auf dem NAS oder Netzwerkspeicher durchstöbern.",
    f9h: "Live-TV mit Pause", f9: "TVHeadend und IPTV-Listen mit Programmführer und Erinnerungen. Mit Timeshift unterstützte MPEG-TS-Streams pausieren und zurückspulen; Aufnahmen über TVHeadend planen.",
    f10h: "Deine eigene Startseite", f10: "Weiterschauen, neue Titel, zuletzt gesehene Sender und Mediathek-Zugänge so anordnen, wie du sie nutzen möchtest.",
    f11h: "Untertitel passend zum Film", f11: "Bevorzugte Sprachen wählen, Untertitel neben der Videodatei laden und ihre Verzögerung anpassen. Online-Suche mit eigenem OpenSubtitles-API-Schlüssel.",
    f12h: "Musik und Fotos", f12: "Musikordner mit Album-Tags und Coverbildern durchstöbern, Spektrum oder projectM-Visualisierungen ausprobieren oder Fotos als Diashow mit sanften Schwenks und Zooms zeigen.",
    spotify_h: "Spotify einrichten",
    spotify_intro: "Ambient TV enthält keine Spotify-Client-ID. Erstelle deine eigene im Spotify Developer Dashboard und trage sie für die Anmeldung in der App ein.",
    spotify_create: 'Öffne das <a href="https://developer.spotify.com/dashboard">Spotify Developer Dashboard</a>, wähle <strong>Create app</strong>, gib einen Namen und eine Beschreibung ein und wähle <strong>Web API</strong>.',
    spotify_redirects: "Füge beide Redirect URIs hinzu und speichere sie:",
    spotify_device: "Anmeldung auf dem Gerät",
    spotify_tv: "QR-Anmeldung am TV; die Seite leitet den Code an den TV in deinem lokalen Netzwerk weiter",
    spotify_copy: "Kopiere die <strong>Client ID</strong> aus den <strong>Settings</strong> deiner Spotify-App.",
    spotify_enter: "Öffne in Ambient TV <strong>Einstellungen › Medienquellen &amp; Konten › Spotify › Spotify-Konto</strong>, trage deine <strong>Spotify-Client-ID</strong> ein und wähle <strong>Anmelden</strong>.",
    spotify_guide: "Vollständige Spotify-Einrichtungsanleitung",
    shots_h: "Screenshots",
    disclaimer: "Ein Hobbyprojekt, ohne Gewähr. Keine Haftung für geschmolzene Fernseher, explodierende TV-Boxen, überhitzte Wohnzimmer oder plötzliche Lust auf neue Hardware.",
    support: "In der Freizeit gebaut, aus Liebe zu einem guten Fernsehabend. Wenn es deinen Fernseher ein bisschen schöner macht:",
    docs: "Dokumentation", releases: "Releases", close: "Schließen",
  },
};
const html = document.documentElement;
let galleryReady = false;
const nodes = [...document.querySelectorAll("[data-i18n]")];
const EN = Object.fromEntries(nodes.map(n => [n.dataset.i18n, n.innerHTML]));
function setLang(lang) {
  const dict = lang === "de" ? T.de : EN;
  nodes.forEach(n => { const v = dict[n.dataset.i18n]; if (v) n.innerHTML = v; });
  document.querySelectorAll("[data-i18n-aria]").forEach(n => n.setAttribute("aria-label", lang === "de" ? T.de[n.dataset.i18nAria] : "Close"));
  html.lang = lang;
  document.querySelector('[data-spotify-guide]').href = `https://github.com/${REPO}/blob/main/${lang === "de" ? "README.de.md#spotify-einrichten" : "README.md#spotify-setup"}`;
  document.querySelector('[data-docs]').href = `https://github.com/${REPO}/blob/main/docs/${lang === "de" ? "DOCUMENTATION.de.md" : "DOCUMENTATION.md"}`;
  document.querySelectorAll(".lang").forEach(b => b.setAttribute("aria-pressed", String(b.dataset.lang === lang)));
  try { localStorage.setItem("lang", lang); } catch (_) {}
  if (galleryReady) renderGallery();
}
document.querySelectorAll("[data-release]").forEach(a => a.href = `https://github.com/${REPO}/releases/latest`);
document.querySelectorAll("[data-repo]").forEach(a => a.href = `https://github.com/${REPO}`);
document.querySelectorAll("[data-repo-releases]").forEach(a => a.href = `https://github.com/${REPO}/releases`);
document.querySelectorAll(".lang").forEach(b => b.addEventListener("click", () => setLang(b.dataset.lang)));
let saved = new URLSearchParams(location.search).get("lang");
if (!saved) { try { saved = localStorage.getItem("lang"); } catch (_) {} }
if (saved !== "de" && saved !== "en") saved = null;
setLang(saved || ((navigator.language || "en").toLowerCase().startsWith("de") ? "de" : "en"));

const reveal = document.querySelectorAll(".reveal");
if ("IntersectionObserver" in window && !matchMedia("(prefers-reduced-motion: reduce)").matches) {
  const io = new IntersectionObserver(es => es.forEach(e => { if (e.isIntersecting) { e.target.classList.add("in"); io.unobserve(e.target); } }), { threshold: 0.12 });
  reveal.forEach(el => io.observe(el));
} else reveal.forEach(el => el.classList.add("in"));
// Ordered screenshots shared with the README galleries, relative to docs/index.html.
const SCREENSHOTS = [
  {
    "name": "01-home",
    "en": [
      "Home",
      "Continue watching and recently added movies at a glance."
    ],
    "de": [
      "Startseite",
      "Weiterschauen und kürzlich hinzugefügte Filme auf einen Blick."
    ]
  },
  {
    "name": "02-movie-details",
    "en": [
      "Movie details",
      "Synopsis, media information and resume playback."
    ],
    "de": [
      "Filmdetails",
      "Inhalt, Medieninformationen und Wiedergabe fortsetzen."
    ]
  },
  {
    "name": "03-movie-player",
    "en": [
      "Movie player",
      "Playback controls and the movie timeline."
    ],
    "de": [
      "Filmwiedergabe",
      "Wiedergabesteuerung und Zeitleiste des Films."
    ]
  },
  {
    "name": "04-player-engine-selection",
    "en": [
      "Player selection",
      "Choose Automatic, Media3, VLC or MPV in the player."
    ],
    "de": [
      "Player-Auswahl",
      "Automatisch, Media3, VLC oder MPV direkt im Player wählen."
    ]
  },
  {
    "name": "05-movie-player-seek",
    "en": [
      "Seeking in a movie",
      "Navigate along the playback timeline."
    ],
    "de": [
      "Im Film spulen",
      "Auf der Zeitleiste durch den Film navigieren."
    ]
  },
  {
    "name": "06-live-tv-channels",
    "en": [
      "Live TV channels",
      "Channel list with current and upcoming programmes."
    ],
    "de": [
      "Live-TV-Sender",
      "Senderliste mit laufenden und folgenden Sendungen."
    ]
  },
  {
    "name": "07-tv-programme-guide",
    "en": [
      "Programme guide",
      "Browse the TVHeadend programme schedule across channels."
    ],
    "de": [
      "Programmführer",
      "Das TVHeadend-Programm senderübergreifend durchstöbern."
    ]
  },
  {
    "name": "08-live-tv-player",
    "en": [
      "Live TV player",
      "Current programme, next show and playback controls."
    ],
    "de": [
      "Live-TV-Wiedergabe",
      "Aktuelle Sendung, nächstes Programm und Wiedergabesteuerung."
    ]
  },
  {
    "name": "09-radio-visualizer",
    "en": [
      "Radio with visualisation",
      "Radio playback with an animated visualisation."
    ],
    "de": [
      "Radio mit Visualisierung",
      "Radiowiedergabe mit animierter Visualisierung."
    ]
  },
  {
    "name": "10-smarttube-music",
    "en": [
      "SmartTube music",
      "Music video recommendations in SmartTube."
    ],
    "de": [
      "SmartTube-Musik",
      "Musikvideo-Empfehlungen in SmartTube."
    ]
  },
  {
    "name": "11-settings-media-sources",
    "en": [
      "Media sources & accounts",
      "Manage services and add media sources."
    ],
    "de": [
      "Medienquellen und Konten",
      "Dienste verwalten und Medienquellen hinzufügen."
    ]
  },
  {
    "name": "12-settings-interface",
    "en": [
      "Interface settings",
      "Language, appearance, home rows and navigation options."
    ],
    "de": [
      "Oberflächeneinstellungen",
      "Sprache, Darstellung, Startseiten-Reihen und Navigation einstellen."
    ]
  }
];
const gallery = document.querySelector(".gallery");
const shotsSection = document.getElementById("screens");
const lightbox = document.querySelector(".lightbox");
function renderGallery() {
  const lang = html.lang === "de" ? "de" : "en";
  gallery.querySelectorAll(".slot").forEach(li => {
    const item = SCREENSHOTS.find(s => s.name === li.dataset.name);
    const [title, text] = item[lang];
    li.querySelector("h3").textContent = title;
    li.querySelector("p").textContent = text;
    li.querySelector("img").alt = title;
    li.querySelector(".frame").setAttribute("aria-label", title);
  });
}
{
  SCREENSHOTS.forEach(s => {
    const src = `screenshots/${s.name}.png`;
    const li = document.createElement("li");
    li.className = "slot";
    li.dataset.name = s.name;
    li.innerHTML = '<button type="button" class="frame"><img loading="lazy" decoding="async" width="1920" height="1080"></button><h3></h3><p></p>';
    li.querySelector("img").src = src;
    li.querySelector(".frame").addEventListener("click", () => {
      if (!lightbox.showModal) return;
      lightbox.querySelector("img").src = src;
      lightbox.querySelector("img").alt = li.querySelector("h3").textContent;
      lightbox.querySelector("p").textContent = li.querySelector("p").textContent;
      lightbox.showModal();
    });
    gallery.appendChild(li);
  });
  shotsSection.hidden = !gallery.children.length;
  galleryReady = true;
  renderGallery();
}
lightbox.querySelector(".lb-close").addEventListener("click", () => lightbox.close());
lightbox.addEventListener("click", e => { if (e.target === lightbox) lightbox.close(); });
