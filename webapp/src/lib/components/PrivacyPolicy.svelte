<script lang="ts">
  let { onBack }: { onBack: (event: MouseEvent) => void } = $props();
</script>

<article class="legal">
  <p><a href="/" onclick={onBack}>← Zurück</a></p>
  <h1>Datenschutzerklärung</h1>
  <p class="lead">StoneIntelligence ist ein privat betriebener Dienst, mit dem du Obsidian-Notizen und Dateien mit anderen teilst und gemeinsam bearbeitest – im Browser unter kb.tstieh.de und über das Obsidian-Plugin. Hier steht, welche Daten dabei verarbeitet werden, wozu und wie lange.</p>

  <section>
    <h2>1. Verantwortlicher</h2>
    <p>Tom Stieh. Anschrift und Kontaktwege stehen im <a href="https://tstieh.de/impressum" rel="noopener">Impressum</a>.</p>
  </section>

  <section>
    <h2>2. Welche Daten verarbeitet werden</h2>
    <ul>
      <li><strong>Konto:</strong> Benutzername, Name und E-Mail-Adresse aus der Anmeldung.</li>
      <li><strong>Inhalte:</strong> deine Notizen (einschließlich ihres Bearbeitungsverlaufs), Dateien wie PDFs und Bilder, Ordner, Vaults, Mitgliedschaften und Rechte.</li>
      <li><strong>Änderungsprotokoll:</strong> wer eine Notiz wann angelegt, umbenannt, gespeichert oder gelöscht hat.</li>
      <li><strong>Einladungen:</strong> die E-Mail-Adresse einer eingeladenen Person, wer eingeladen hat und wann.</li>
      <li><strong>Technische Zugriffsdaten:</strong> IP-Adresse, Zeitpunkt, abgerufene Adresse und Browserkennung, die der vorgeschaltete Webserver beim Aufruf erfasst.</li>
    </ul>
    <p>Es gibt keine Werbung, kein Tracking und keine Reichweitenmessung.</p>
  </section>

  <section>
    <h2>3. Zwecke und Rechtsgrundlagen</h2>
    <ul>
      <li>Bereitstellen des Dienstes – Anmeldung, Speichern, Synchronisieren und Teilen deiner Inhalte, Einladungen, die du verschickst: Art. 6 Abs. 1 lit. b DSGVO.</li>
      <li>Sicherer Betrieb, Fehlersuche und Schutz vor Missbrauch (technische Zugriffsdaten, Änderungsprotokoll): Art. 6 Abs. 1 lit. f DSGVO. Das berechtigte Interesse liegt darin, den Dienst stabil und nachvollziehbar zu betreiben.</li>
      <li>Einladungs-E-Mail an eine Person ohne Konto: Art. 6 Abs. 1 lit. f DSGVO – das Interesse des Einladenden, eine bestimmte Person zur Zusammenarbeit einzuladen. Die Adresse wird nur dafür verwendet.</li>
      <li>KI-Verarbeitung (Abschnitt 6): deine ausdrückliche Anforderung und Einwilligung, Art. 6 Abs. 1 lit. a und b sowie Art. 49 Abs. 1 lit. a DSGVO.</li>
    </ul>
  </section>

  <section>
    <h2>4. Hosting und Speicherorte</h2>
    <p>Server, Datenbank, Dateien und Sicherungen liegen bei der <strong>Hetzner Online GmbH</strong> (Industriestr. 25, 91710 Gunzenhausen) in Rechenzentren in der EU. Dateien werden auf einer Hetzner Storage Box gespeichert. Mit Hetzner besteht ein Vertrag zur Auftragsverarbeitung (Art. 28 DSGVO). Anmeldung (Authentik unter portal.tstieh.de) und E-Mail-Versand laufen auf eigenen Servern, ebenfalls bei Hetzner.</p>
  </section>

  <section>
    <h2>5. Anmeldung und Speicherung im Browser</h2>
    <p>Angemeldet wird über ein selbst betriebenes Authentik. Das Dashboard hält die Anmeldung im Sitzungsspeicher deines Browsers (sessionStorage), das Obsidian-Plugin in seinen Einstellungen auf deinem Gerät. Cookies zu Tracking- oder Werbezwecken gibt es nicht. Diese Speicherung ist für den von dir gewünschten Dienst unbedingt erforderlich (§ 25 Abs. 2 Nr. 2 TDDDG).</p>
  </section>

  <section>
    <h2>6. KI-Funktion (nur auf Anforderung)</h2>
    <p>Nur wenn du im Reiter „KI-Wissen" ein Dokument einlesen lässt, wird dessen Text (bei gescannten Seiten ein Bild der Seite) an einen KI-Anbieter übermittelt, der daraus Notizen erstellt. Andere Notizen des Vaults werden dabei nicht übermittelt. Eingesetzt werden je nach Verfügbarkeit:</p>
    <ul>
      <li><strong>Groq, Inc.</strong>, USA (Sprachmodelle über die Groq-API),</li>
      <li><strong>Google</strong> (Gemini-API; Google LLC, USA).</li>
    </ul>
    <p>Dabei werden Daten in die USA übermittelt. Für dieses Land besteht nur für nach dem EU-U.S. Data Privacy Framework zertifizierte Unternehmen ein Angemessenheitsbeschluss; ob ein Anbieter zertifiziert ist, kann sich ändern. Die Übermittlung erfolgt deshalb auf Grundlage deiner ausdrücklichen Einwilligung, die du vor jedem Hochladen erteilst (Art. 49 Abs. 1 lit. a DSGVO). Risiko: Behörden in den USA könnten auf die Daten zugreifen, ohne dass dir dieselben Rechtsbehelfe wie in der EU offenstehen. <strong>Je nach Vertragsstufe dürfen die Anbieter übermittelte Inhalte auch zur Verbesserung ihrer Dienste verwenden und von Menschen prüfen lassen.</strong> Lies deshalb keine Dokumente mit vertraulichen Inhalten oder personenbezogenen Daten anderer ein.</p>
    <p>Jede Verarbeitung lässt sich im Reiter „KI-Wissen" vollständig rückgängig machen. Es findet keine automatisierte Entscheidung im Sinne von Art. 22 DSGVO statt.</p>

    <h3>Verlinkung von Notizen</h3>
    <p>Verwaltende eines Vaults können die nächtliche Verlinkung einschalten (standardmäßig aus). Sie fügt in Notizen – auch in von Menschen geschriebene, sofern der Vault das erlaubt – Links auf andere Notizen ein; der übrige Text bleibt unverändert, jeder Lauf lässt sich rückgängig machen.</p>
    <ul>
      <li><strong>Wörtliche Nennungen und ähnliche Inhalte</strong> werden ausschließlich auf unserem Server berechnet. Für die Ähnlichkeit werden aus den Notizen mit einem lokal betriebenen Sprachmodell Zahlenvektoren gebildet und gespeichert; sie werden mit der Notiz gelöscht. Dabei werden keine Daten an Dritte übermittelt (Rechtsgrundlage: Art. 6 Abs. 1 lit. b DSGVO, die von dir gewünschte Funktion des Vaults).</li>
      <li><strong>„Von einer KI geprüft"</strong> (nur, wenn die Verwaltenden diesen Modus wählen): Dann gehen nachts Auszüge ähnlicher Notizen an den oben genannten KI-Anbieter, der beurteilt, ob ein Link sinnvoll ist. Das betrifft nur Notizen von Personen, die dem für ihre eigenen Notizen ausdrücklich zugestimmt haben (Art. 49 Abs. 1 lit. a DSGVO), und von der KI erzeugte Notizen. Die Einwilligung gibst und widerrufst du jederzeit in den Einstellungen der Verlinkung; ein Widerruf wirkt ab dem nächsten Lauf. Für die Übermittlung in die USA gilt das oben Gesagte.</li>
    </ul>
  </section>

  <section>
    <h2>7. Speicherdauer</h2>
    <ul>
      <li>Notizen, Dateien und Ordner: bis sie gelöscht werden oder der Vault aufgelöst wird. Ersetzte Fassungen von Dateien und die Inhalte gelöschter Dateien werden nach 24 Stunden entfernt.</li>
      <li>Zum Einlesen hochgeladene Dokumente: bis die Verarbeitung abgeschlossen ist; Angaben zur Verarbeitung und die Aufzeichnung der KI-Änderungen (für „Rückgängig"): 90 Tage.</li>
      <li>Ähnlichkeitsvektoren, gesetzte und abgelehnte Verknüpfungen: solange die beteiligten Notizen bestehen; Einwilligungen zur KI-Prüfung: bis zum Widerruf; sie wirken nur, solange du Mitglied des Vaults bist.</li>
      <li>Einladungen: 30 Tage nach Annahme, Widerruf oder Ablauf.</li>
      <li>Änderungsprotokoll: solange der Vault besteht.</li>
      <li>Technische Zugriffsdaten: werden fortlaufend überschrieben.</li>
      <li>Sicherungen: tägliche Datenbanksicherungen 30 Tage, Monatsstände 12 Monate; gelöschte Dateien bleiben höchstens 30 Tage in der Sicherung.</li>
    </ul>
  </section>

  <section>
    <h2>8. Empfänger</h2>
    <p>Mitglieder eines Vaults sehen dessen Inhalte im Rahmen ihrer Rechte. Darüber hinaus erhalten nur die oben genannten Dienstleister Daten (Hetzner als Auftragsverarbeiter, KI-Anbieter nur auf deine Anforderung bzw. mit deiner Einwilligung). Eine Weitergabe an sonstige Dritte findet nicht statt.</p>
  </section>

  <section>
    <h2>9. Deine Rechte</h2>
    <p>Du hast das Recht auf Auskunft (Art. 15), Berichtigung (Art. 16), Löschung (Art. 17), Einschränkung der Verarbeitung (Art. 18), Datenübertragbarkeit (Art. 20) und Widerspruch gegen Verarbeitungen auf Grundlage berechtigter Interessen (Art. 21 DSGVO). Eine Einwilligung kannst du jederzeit mit Wirkung für die Zukunft widerrufen – für die KI-Funktion genügt es, sie nicht mehr zu nutzen. Wende dich dafür an die im Impressum genannten Kontaktwege. Du kannst dich außerdem bei einer Datenschutz-Aufsichtsbehörde beschweren, insbesondere in deinem Bundesland.</p>
  </section>

  <p class="hint">Stand: 23. September 2026</p>
</article>

<style>
  .legal { max-width: 46rem; }
  h1 { font-size: clamp(2rem, 4.5vw, 2.8rem); letter-spacing: -.03em; margin: 1rem 0 .75rem; }
  .lead { color: var(--ink-dim); max-width: 62ch; margin-bottom: 2rem; }
  section { margin-bottom: 1.75rem; }
  h2 { font-size: 1.1rem; margin: 0 0 .5rem; }
  p, li { line-height: 1.65; }
  ul { padding-left: 1.2rem; }
  li + li { margin-top: .35rem; }
</style>
