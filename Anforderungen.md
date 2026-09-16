Projekt-Spezifikation
Arbeitstitel: StoneIntelligence

Geplante Projekt-Architektur:
Backend:
Java 25
Spring Boot Server
Frontend:
React & Vite Webapp
Plugin:
Obsidian Plugin Typescript

Anforderungen an das Projekt
	Sync-Service
StoneIntelligence ist ein Projekt, das Obsidian in ein Enterprise-Grade Kollaborations- und Wissensmanagement-Tool verwandelt. 
Das Tool soll es ermöglichen, dass Notizen wie in Obsidians eigenem Sync-Service über einen self-hostable Webserver live synchronisiert werden können.
Über Websocket-Verbindungen sollen die Cursor anderer Nutzer live sichtbar sein
An Dokumenten soll erkennbar sein, welcher Nutzer dieses zuletzt geöffnet, bearbeitet oder auch erstellt hatte. Hierbei muss eine Differenzierung zwischen Aktionen bestehen. (Öffnen =/ Bearbeitung)
Die Erstellung und aber auch die Löschung von Notizen soll live zu allen Nutzern synchronisiert werden.
Nutzer, die gerade offline sind, sollten die Änderungen erhalten, sobald sie das Obsidian-Vault wieder öffnen. 
Es sollte nicht zu Merge-Konflikten kommen können.
Notes und Dateien brauchen eine eindeutige ID
Nutzer müssen in der Lage sein, Dateien zu verschieben und umzubenennen; auch diese Änderungen sollen synchronisiert werden.
Löschungen sollen über Tombstones oder eine fixierte Löschsemantik funktionieren.
Das ganze Vault sollte aktuell gehalten werden; nicht erst, wenn die Datei aufgerufen wird.
Es sollte eine lokale Queue geben, die eingesehen werden kann.
Es sollte eine Audit-Trail pro Datei geben.
Version History / Wiederherstellung von Dateien sollte vorgesehen und einsehbar sein.
Dateien sollten ein Berechtigungskonzept haben. Es sollte die Möglichkeit geben, selbst Rollen und Gruppen festzulegen, und User bestimmten Rollen hinzuzufügen. Jeder Nutzer sollte sich aus diesem Grund über Accounts identifizieren müssen und mit mehreren Geräten auf das Vault zugreifen können.
Es sollte je Datei verschiedene Berechtigungen geben, u.a. Lesen, Schreiben, Löschen, Erstellen, …
Es sollten Sichteinschränkungen für bestimmte Ordner oder Themen für bestimmte Benutzer möglich sein.
Es sollte ein Dashboard / eine Landing-Page geben, wo u.a. Wiedervorlagen angezeigt werden können.
Anmeldungen sollten über SSO / OIDC unterstützt werden.
Es sollten Rate-Limits auf den Server existieren.
Daten zwischen Client und Server sollten transport verschlüsselt übertragen werden.
Websocket-Verbindungen sollten verschlüsselt sein.
Notes und Dateien sollten unterschiedliche Note-Level besitzen. ZB:
Lvl 1: darf von jedem eingesehen und von MCP verarbeitet werden
Lvl 2: Darf nicht von MCP-Servern verarbeitet werden, aber von allen anderen
Lvl 3: …
Lvl 100: keine Synchronisation der Datei
Lvl 101: E2EE. Server sieht ausschließlich Ciphertext
Notes sollten mit Tags versehen werden, die Zugriffe, aber auch Funktionen steuern können.
U.a. sollten diese Tags in der Lage sein, tatsächliche Client Ende-zu-Ende Verschlüsselung für Dateien zu aktivieren. Entsprechende Dokumente dürfen nicht von Agenten auf dem Server eingelesen oder verarbeitet werden. 

	

Intelligence-Service
Es sollte eine Möglichkeit geben, Dokumente auf einer Webapp hochzuladen; auch mehrere gleichzeitig. 
Diese Dokumente sollten von einer KI analysiert und im Vault abgelegt werden.
Es sollten zu dem Dokument ausführliche Wissens-Notizen angelegt werden, die sich primär aus den abgelegten Dokumenten speichern.
Zusätzliche Informationen, die nicht aus den hochgeladenen Dokumenten stammen, dürfen nur ergänzt werden, wenn die Informationen zum Verständnis sinnvoll oder notwendig sind und die Quelle vertrauenswürdig ist. Wikipedia alleine wäre Beispielsweise keine hinreichende Quelle.
Die Wissensdokumente, die von KI erstellt werden, müssen atomar gehalten werden. (aka. nicht eine große Datei, sondern evtl viele kleinere Dateien, die jeweils ein spezifisches Thema behandeln).
Die einzelnen Themen sollten sinnvoll in Themengebiete gruppiert werden.
Die KI sollte spezifisch die Funktionen von Obsidian zum Verknüpfen von Dokumenten nutzen.
Begrifflichkeiten sollten sowohl vorwärts als auch rückwärts (beidseitig) automatisch verknüpft werden können. Hierfür lesen Agenten neu gespeicherte Notes und erhalten von einem Algorithmus die Mentions auf semantischer Basis, sodass diese nicht nur wortgleich funktionieren. Anschließend sollte ein stärkeres KI-Modell entscheiden, ob diese Verknüpfungen im Kontext überhaupt Sinn ergeben.
KI-Änderungen sollten zu ihren Quelldokumenten zurückführbar sein
Es sollte möglich sein, KI-Änderungen zu revidieren und über wenige Klicks alle Änderungen rückgängig zu machen, inkl. Datei-Erstellung, Bearbeitung und Löschung.
Ein bestehender Eintrag sollte mit neuen Daten, die eingelesen werden, überarbeitet werden können, ohne den Umfang des Eintrags zu vermindern oder den Eintrag zu verschlechtern.
Ein Überschreiben von Daten darf nicht zur Löschung von weiterhin bestandskräftigen Inhalten führen.
Es sollte nachvollzogen werden können, welche Änderungen vorgenommen worden sind, um diese im Zweifel rückgängig machen zu können; also im Grunde eine Revisionshistorie.
Es sollte automatisch Frontmatter für die jeweiligen Dateien generiert werden, u.a. mit den Inhalten: 
ID
Note-Typ
Themen
Quelle
Created By
KI-Endpunkte und AI-Provider sollten individuell konfigurierbar sein.
Das KI-Modell sollte das Vault als Knowledge Graph und die Konzepte innerhalb des Vaults tatsächlich verstehen.
Das gesamte Vault sollte via Semantic Embeddings indiziert werden.
Es sollte konfigurierbar sein, ob KI Dateien bearbeiten darf, die von Menschen erstellt wurden, oder nicht.
Es sollte optional konfigurierbar sein, dass Agenten Informationen aus dem Internet zu Notes und Artikeln beitragen dürfen. Dies sollte per Default aus sein.
Der Knowledge-Graph sollte Beziehungen typisieren. Bspw: 
Uses
Requires
Part of
Related to
Described by
…


Agent Services
Es sollte einen MCP-Server auf dem Server geben.
KI-Agenten sollten in der Lage sein, Informationen über die Semantic Embeddings & Linked Memory Agent Search Engine (SELMA) zu holen.
Der Zugriff auf den MCP-Server sollte nach Ordnern und Dateien filterbar sein
Zugriffe sollten über denselben Auth-Service wie Webapp und Obsidian selbst geregelt sein.
Agenten sollten über den MCP in der Lage sein, sebst Notes anzulegen, und auch Dateien hochzuladen.
Alle Änderungen von Agenten sollten geloggt und dauerhaft gespeichert werden.
von Agenten erstellte oder bearbeitete Dateien sollten im Frontmatter entsprechend gekennzeichnet werden.
Agenten sollten eine eigene Identität besitzen und über einen Menschenlesbaren Namen identifizierbar sein.

Allgemein
Aufbewahrungsdauer und Detailgrad der Serverlogs müssen konfigurierbar sein
Server-Logs sollten u.a. in Dateien geschrieben werden, allerdings vorzugsweise so klein wie möglich gehalten werden.
