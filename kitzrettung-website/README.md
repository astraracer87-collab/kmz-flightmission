# Webseite Kitzrettung Limburg e. V.

Statische Webseite: schnell, günstig zu hosten, ohne Datenbank, ohne Cookies und ohne Tracking.
News und Zahlen pflegen Vorstandsmitglieder über ein einfaches Redaktionssystem (Pages CMS) im Browser.
Danach baut GitHub die Seite automatisch neu und lädt sie auf den Webspace hoch.

```
Redaktion (Pages CMS im Browser) ──► GitHub-Repository ──► GitHub Action baut dist/ ──► FTPS ──► Webspace
Technik (Code-Editor / git push) ──┘
```

---

## Für die Redaktion: News schreiben (ohne Technikkenntnisse)

1. <https://app.pagescms.org> öffnen und anmelden. Den Zugang richtet der Admin einmalig ein (siehe unten).
2. Links **„News & Presse“** wählen und dann **„Add an entry“** (neuer Eintrag).
3. Überschrift, Datum, Kurztext, Bild und Text eintragen. Ein Foto vom Handy reicht.
   Bitte immer kurz beschreiben, was auf dem Bild zu sehen ist; das hilft blinden Menschen und Google.
4. **„Save“** klicken. Nach etwa 1–2 Minuten ist der Beitrag online.

Außerdem gibt es im Menü:

- **Erfolge (Zahlen)**: die Zahlen auf der Startseite, z. B. nach Saisonende aktualisieren.
- **Hinweis-Balken**: ein orangefarbener Balken oben auf jeder Seite, z. B. für einen Infostand.
  Mit „Automatisch ausblenden nach“ verschwindet er nach dem Termin von selbst.

- **Saison-Leiste**: der Balken „Fläche anmelden / Anrufen“ unten am Bildschirm. Er erscheint automatisch nur im eingestellten
  Zeitraum (Standard 1. Mai bis 31. Juli). Zum Testen außerhalb der Saison an die Adresse `?saison=1` anhängen.
- **E-Mail-Vorlagen**: Betreff und vorausgefüllter Text für alle E-Mail-Links (Feldsuche, Mithelfen, Spendenquittung, Presse, Kita-Besuch …).
  In den Seiten werden sie mit `{{mail:name}}` eingesetzt, z. B. `href="{{mail:spendenquittung}}"`.
- **Spendenbeispiele**: die Kacheln „Was deine Spende bewirkt“ auf der Spendenseite (Betrag, Emoji, kurzer Text).

Ein Häkchen bei *„Entwurf – noch nicht veröffentlichen“* hält einen Beitrag zurück.
Bitte keine Zeitungsartikel als Foto hochladen, ohne vorher beim Verlag nachzufragen (Urheberrecht).

## Für die Technik

### Aufbau

| Ordner / Datei | Inhalt |
|---|---|
| `src/pages/` | Die Seiten als HTML. Oben steht ein kurzer Kopf mit `title` und `description`. Die Adresse ergibt sich aus dem Dateinamen: `hangar.html` wird zu `/hangar/`. |
| `content/news/*.md` | News-Beiträge (Markdown mit Kopfbereich) |
| `content/zahlen.json` | Erfolgszahlen der Startseite |
| `content/hinweis.json` | Hinweis-Balken |
| `content/einstellungen.json` | E-Mail, Telefon, Bankdaten, PayPal-Link, Beiträge, Hosting-Angaben für den Datenschutz |
| `public/` | CSS, JS, Bilder, PDFs, `.htaccess`, das Kitz-Spiel (`public/spiel/`). Wird unverändert kopiert. |
| `build.mjs` | Baut alles nach `dist/` und erzeugt dabei die QR-Codes (GiroCode, PayPal), `sitemap.xml` und `robots.txt`. |
| `.pages.yml` | Konfiguration des Redaktionssystems |
| `.github/workflows/deploy.yml` | Automatisch bauen und per FTPS hochladen |

In den Seiten gibt es ein paar Platzhalter:

- `{{site.email}}`, `{{site.telefon}}`, `{{site.telefon_link}}` usw.: Werte aus `einstellungen.json`
- `{{> stats}}`, `{{> news-teaser}}`, `{{> news-liste}}`, `{{> beitraege}}`, `{{> kontakt-box}}`, `{{> bankdaten}}`, `{{> hosting}}`: fertige Bausteine
- `{{icon:pfeil}}`: Symbole (`pfeil`, `mail`, `telefon`, `download`, `extern`, `kitz`, `drohne`, …, siehe `build.mjs`)

Eine neue Seite legst du als neue Datei in `src/pages/` an, die Navigation steht in `build.mjs` (`NAV`).
Farben und Abstände stehen als Variablen oben in `public/css/style.css`.

### Lokal testen

```bash
npm install
npm start          # baut und startet http://localhost:8080
npm run build      # nur bauen → dist/
```

Voraussetzung ist Node.js ab Version 20.

### Bilder

Beim Bauen werden alle Fotos unter `images/` automatisch auf höchstens 1600 Pixel Breite verkleinert, z. B. direkt vom Handy hochgeladene.
Zusätzlich entsteht eine WebP-Fassung, die moderne Browser bevorzugt laden (`<picture>`). Dafür sorgt das Paket `sharp`.

### Datenschutz-Prinzipien

- Keine Cookies, kein Tracking, keine Google Fonts. Alle Schriften sind Systemschriften.
- YouTube, Instagram, das UAV-Editor-Formular und die Wettervorhersage (Open-Meteo) laden **erst nach Klick**.
  Dafür im HTML `<div class="extern" data-extern="URL">…<button>…</button></div>` verwenden.
- Das Kontaktformular (`/kontakt/`) schickt die Angaben per E-Mail weiter und speichert nichts auf dem Server
  (siehe unten). Daneben gibt es `mailto:`-Links mit vorausgefülltem Betreff und Text; die Feldanmeldung läuft
  bevorzugt über den UAV Editor.

### Kontaktformular (PHP)

- `public/kontakt.php` nimmt das Formular entgegen, prüft es und verschickt eine E-Mail mit `mail()`. Es braucht **PHP 8.1 oder neuer**, was bei allen gängigen Hostern Standard ist.
- **Einstellungen** stehen in `content/einstellungen.json` unter `formular`:
  - `empfaenger` – wohin die Nachrichten gehen (leer = die Vereins-E-Mail),
  - `absender` – eine Adresse **auf eurer eigenen Domain beim Hoster**, z. B. `formular@kitzrettung-limburg.de`. Dieses Postfach (oder eine Weiterleitung) beim Hoster anlegen,
    sonst landen die Mails leicht im Spam. Man antwortet trotzdem direkt dem Absender (Reply-To),
  - `testmodus` – `true` schickt nichts, sondern legt die Mails als Dateien in `formular-daten/` ab.
- **Spamschutz** ohne Drittanbieter: ein unsichtbares Fangfeld, eine Mindest-Ausfüllzeit von 4 Sekunden und höchstens 5 Nachrichten pro Stunde und IP-Adresse.
  Gespeichert wird nur ein gekürzter Hash der IP-Adresse, eine Stunde lang. `formular-daten/` ist per `.htaccess` gesperrt.
- **Anliegen** und ihre Zusatzfelder (Feldsuche, Mithelfen, Spendenquittung, Sachspende, Presse, Kita, Allgemein) sind in `kontakt.php` (`$ANLIEGEN`, `$ZUSATZ`)
  und im Formular auf `src/pages/kontakt.html` definiert. Links wie `/kontakt/?anliegen=spendenquittung#formular` wählen das Anliegen vor.
- **Lokal testen**: `npm run build`, dann `cd dist && KITZ_FORMULAR_TEST=1 php -S localhost:8092` und `http://localhost:8092/kontakt/` öffnen.
- Auf Netlify, Cloudflare Pages oder GitHub Pages läuft PHP nicht. Dort funktioniert das Formular nicht, die E-Mail-Links schon.

## Einrichtung (einmalig)

1. **Eigenes Repository** anlegen, z. B. `kitzrettung-limburg-website`, und den Inhalt dieses Ordners hineinkopieren,
   sodass `package.json` und `.pages.yml` im Hauptverzeichnis liegen.
2. **Webspace buchen** bei einem deutschen Anbieter mit Apache, FTPS und SSL, z. B. All-Inkl „Privat“,
   IONOS, Strato oder Netcup. Eine Domain ist meist inklusive, das liegt im Budget von 50–100 € pro Jahr.
   PHP und eine Datenbank werden nicht gebraucht.
3. **Domain umziehen**: Bei Wix den Auth-Code für `kitzrettung-limburg.de` anfordern und beim neuen Hoster den Domain-Umzug starten.
   Die E-Mail-Adresse `kitzrettung-limburg@online.de` ist davon nicht betroffen.
4. **FTP-Zugang hinterlegen**: Im Repository unter *Settings → Secrets and variables → Actions* die Werte
   `FTP_SERVER`, `FTP_USERNAME`, `FTP_PASSWORD` und `FTP_DIR` anlegen. Ab dann geht jede Änderung auf `main` automatisch online.
5. **Hosting im Datenschutz eintragen**: In `content/einstellungen.json` unter `hosting` Name und Anschrift des Hosters eintragen
   und beim Hoster den Auftragsverarbeitungsvertrag (AVV) abschließen.
6. **Redaktion freischalten**: Auf <https://app.pagescms.org> mit GitHub anmelden, das Repository auswählen und
   unter *Settings → Collaborators* die Redakteure per E-Mail einladen. Sie brauchen kein eigenes GitHub-Konto.
7. Bei der Google Search Console die neue `sitemap.xml` einreichen. Die alten Wix-Adressen werden über `public/.htaccess` umgeleitet.
8. **Google-Unternehmensprofil** anlegen (kostenlos), damit der Verein bei Google Maps und in der Suche mit Telefonnummer und Webseite erscheint:
   - Auf <https://www.google.com/business> mit einem Vereins-Google-Konto anmelden, nicht mit einem privaten.
   - Name „Kitzrettung Limburg e. V.“, Kategorie z. B. „Tierschutzorganisation“ oder „Gemeinnützige Organisation“.
   - Bei der Frage nach einem Standort, den Kunden besuchen können, „Nein“ wählen. Dann wird die c/o-Adresse nicht öffentlich angezeigt.
     Als Einzugsgebiet „Landkreis Limburg-Weilburg“ eintragen.
   - Telefon, Webseite, Logo und ein paar Einsatzfotos ergänzen. Google bestätigt den Eintrag meist per Video oder Postkarte.
   - Die Webseite liefert Google zusätzlich strukturierte Daten (Name, Kontakt, Einsatzgebiet, Social-Media-Profile) auf der Startseite mit.
