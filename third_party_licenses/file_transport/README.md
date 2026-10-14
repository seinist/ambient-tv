# Dateitransport – Abhängigkeiten und Provenienz

SMBJ wird aus Maven bezogen, nicht als Binärdatei in diesem Quellpaket mitgeliefert. Die folgende Laufzeitliste stammt aus der veröffentlichten SMBJ-0.14.0-POM. Der tatsächliche aufgelöste Android-Dependency-Graph und die APK-Lizenzanzeige sind vor Veröffentlichung zu prüfen.

| Komponente | Version | Lizenz laut Upstream | Primärquelle |
| --- | --- | --- | --- |
| SMBJ | 0.14.0 | Apache-2.0 | https://github.com/hierynomus/smbj |
| asn-one | 0.6.0 | Apache-2.0 | https://github.com/hierynomus/asn-one |
| SLF4J API | 2.0.9 | MIT | https://www.slf4j.org/license.html |
| Bouncy Castle bcprov-jdk18on | 1.79 | MIT-artige Bouncy-Castle-Lizenz | https://www.bouncycastle.org/licence.html |
| MBassador | 1.3.0 | MIT | https://github.com/bennidi/mbassador/blob/master/LICENSE |
| jcifs-ng (nur SMB1-Quellen) | 2.1.10 | LGPL-2.1 | https://github.com/AgNO3/jcifs-ng |

SMBJ-Quellheader: Copyright (C)2016 - SMBJ Contributors. Apache-2.0-Lizenztext liegt bei. Der asn-one-Lizenztext stammt aus dem Upstream-Tag v0.6.0, der SLF4J-Text aus META-INF/LICENSE.txt des 2.0.9-JARs und der Bouncy-Castle-Text aus org.bouncycastle.LICENSE des 1.79-JARs. MBassadors Lizenzdeklaration ist in seiner eingebetteten Maven-POM enthalten; der vollständige MIT-Upstream-Hinweis ist vor APK-Veröffentlichung zu übernehmen.

Die NFSv3- und NFSv4.0-Implementierungen sind eigener Code gemäß RFC 1813, RFC 7530 und RFC 5531. Es wurde kein EMC-NFS-Client und keine zusätzliche Netty-Abhängigkeit eingebaut.
jcifs-ng ist LGPL-2.1: Lizenztext und Hinweis auf die unveränderte Upstream-Quelle sind vor APK-Veröffentlichung mitzuliefern.
