# Transportprüfungen

Diese zusätzlichen JVM-Programme prüfen Dateiübertragung und HTTP-Ranges, keine Videodekodierung. `sample.mp4` enthält lediglich ein deterministisches Byte-Muster. Die Fixture-Kennung und ihr Passwort sind öffentliche Testdaten. Alle Server binden ausschließlich an 127.0.0.1.

- `SmbSmoke.kt`: SMB2-Anmeldung gegen Impacket, Auflistung, Dateistatistik, positionsbezogenes Lesen, 100.000 Byte über die HTTP-Bridge und Ablehnung eines falschen Passworts.
- `NfsSmoke.kt`: eigener NFSv3/NFSv4.0-Protokollserver, RPCbind/MOUNT, AUTH_SYS, fragmentierte RPC-Antworten, paginierte Ordner (READDIRPLUS und, in einem zweiten Lauf, der READDIR-Rückfall), NFSv4-COMPOUND mit Pseudodateisystem, anonymem READ und Symlink-Ablehnung, UTF-8, positionsbezogenes Lesen, 150.000 Byte über HTTP und Zugriffsverweigerung. Dies ist kein Interoperabilitätstest gegen ein reales NAS.
- WOL-Paketformat und tatsächlicher UDP-Empfang sowie HTTP-/Range- und Persistenztests stehen unter `app/src/test/.../data/files` und laufen mit dem normalen Gradle-Testtask, sobald die Projektabhängigkeiten aufgelöst werden können.

## Erneute Ausführung

Benötigt werden Python 3, Java 17+, Kotlin und die kompilierten Transportklassen der App samt Laufzeitabhängigkeiten. Für den SMB-Server in einer separaten Testumgebung `python -m pip install impacket==0.13.1` installieren. Die beiden Kotlin-Dateien gegen die App-Klassen kompilieren und ihr Ausgabeverzeichnis zum Classpath hinzufügen. `TRANSPORT_CHECK_CLASSPATH` auf diesen vollständigen Classpath setzen und `python scripts/transport_checks/run.py` starten. Server und Client müssen im selben Netzwerk-Namespace laufen. Ports 14450, 23111, 23050 und 23049 müssen frei sein.

Die Audit030-Ausführung nutzte Kotlin 2.2.21, SMBJ 0.14.0, SLF4J 2.0.9, BC 1.79, MBassador 1.3.0 und asn-one 0.6.0. Die Scripts installieren selbst nichts und greifen auf keine produktiven NAS-Geräte zu. SMB3-Verschlüsselung, reale Android-SAF-Provider und Android-Renderer bleiben separate Gerätetests.
