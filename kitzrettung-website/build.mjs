// Baut die statische Webseite der Kitzrettung Limburg e. V.
//
//   src/pages/**/*.html   Seiten (oben ein kurzer Kopf mit Titel und Beschreibung)
//   content/news/*.md     News-Beiträge (werden über Pages CMS gepflegt)
//   content/*.json        Zahlen und Einstellungen (ebenfalls über Pages CMS)
//   public/               Bilder, CSS, JS, PDFs, Spiel – wird 1:1 kopiert
//
// Ergebnis liegt in dist/ und kann auf jeden normalen Webspace hochgeladen werden.
// Aufruf: npm run build

import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import yaml from 'js-yaml';
import { marked } from 'marked';
import QRCode from 'qrcode';
import sharp from 'sharp';

const ROOT = path.dirname(fileURLToPath(import.meta.url));
const SRC = path.join(ROOT, 'src', 'pages');
const CONTENT = path.join(ROOT, 'content');
const PUBLIC = path.join(ROOT, 'public');
const DIST = path.join(ROOT, 'dist');

const warnings = [];

// ---------------------------------------------------------------- Hilfen

const esc = (s) =>
  String(s ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');

function parseFrontMatter(text, file) {
  const m = /^---\r?\n([\s\S]*?)\r?\n---\r?\n?/.exec(text);
  if (!m) return { data: {}, body: text };
  try {
    return { data: yaml.load(m[1]) || {}, body: text.slice(m[0].length) };
  } catch (e) {
    throw new Error(`Kopfbereich in ${file} ist fehlerhaft: ${e.message}`);
  }
}

function toDate(v) {
  if (v instanceof Date) return v;
  const d = new Date(String(v ?? '').trim());
  return isNaN(d) ? null : d;
}

const MONATE = ['Januar', 'Februar', 'März', 'April', 'Mai', 'Juni', 'Juli', 'August', 'September', 'Oktober', 'November', 'Dezember'];
const datumLang = (d) => `${d.getUTCDate()}. ${MONATE[d.getUTCMonth()]} ${d.getUTCFullYear()}`;
const datumIso = (d) => d.toISOString().slice(0, 10);

async function walk(dir) {
  const out = [];
  for (const e of await fs.readdir(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) out.push(...(await walk(p)));
    else out.push(p);
  }
  return out;
}

// Bilder, zu denen es eine WebP-Fassung gibt, als <picture> ausliefern (kleiner, schneller)
const webp = new Set();
function bilderOptimieren(html) {
  return html.replace(/<img\b([^>]*?)\ssrc="(\/images\/[^"]+)\.(jpe?g|png)"([^>]*)>/gi, (tag, vor, base, ext, nach) =>
    webp.has(base) ? `<picture><source srcset="${base}.webp" type="image/webp"><img${vor} src="${base}.${ext}"${nach}></picture>` : tag
  );
}

async function write(rel, content) {
  if (rel.endsWith('.html')) content = bilderOptimieren(content);
  const file = path.join(DIST, rel);
  await fs.mkdir(path.dirname(file), { recursive: true });
  await fs.writeFile(file, content);
}

// ---------------------------------------------------------------- Inhalte laden

const site = JSON.parse(await fs.readFile(path.join(CONTENT, 'einstellungen.json'), 'utf8'));
const zahlen = JSON.parse(await fs.readFile(path.join(CONTENT, 'zahlen.json'), 'utf8'));
const hinweis = JSON.parse(await fs.readFile(path.join(CONTENT, 'hinweis.json'), 'utf8'));
const saison = JSON.parse(await fs.readFile(path.join(CONTENT, 'saison.json'), 'utf8'));
const spenden = JSON.parse(await fs.readFile(path.join(CONTENT, 'spendenbeispiele.json'), 'utf8'));

site.telefon_link = 'tel:' + String(site.telefon).replace(/[^\d+]/g, '');
site.iban_kompakt = String(site.iban).replace(/\s/g, '');
site.jahr = new Date().getFullYear();

const news = [];
for (const file of (await fs.readdir(path.join(CONTENT, 'news'))).filter((f) => f.endsWith('.md')).sort()) {
  const { data, body } = parseFrontMatter(await fs.readFile(path.join(CONTENT, 'news', file), 'utf8'), file);
  if (data.draft === true) continue;
  const date = toDate(data.date);
  if (!data.title || !date) {
    warnings.push(`News ${file}: Titel oder Datum fehlt – Beitrag wird übersprungen.`);
    continue;
  }
  let slug = file.replace(/\.md$/, '').replace(/^\d{4}-\d{2}-\d{2}-/, '');
  slug = slug.toLowerCase().replace(/ä/g, 'ae').replace(/ö/g, 'oe').replace(/ü/g, 'ue').replace(/ß/g, 'ss').replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '') || datumIso(date);
  if (news.some((n) => n.slug === slug)) slug += '-' + datumIso(date);
  news.push({ ...data, date, slug, html: marked.parse(body || '') });
}
news.sort((a, b) => b.date - a.date);

// ---------------------------------------------------------------- Symbole

const ICON = {
  pfeil: '<path d="M5 12h14M13 6l6 6-6 6"/>',
  mail: '<rect x="3" y="5" width="18" height="14" rx="2"/><path d="m3 7 9 6 9-6"/>',
  telefon: '<path d="M5 4h4l2 5-2.5 1.5a11 11 0 0 0 5 5L15 13l5 2v4a2 2 0 0 1-2 2A16 16 0 0 1 3 6a2 2 0 0 1 2-2"/>',
  download: '<path d="M12 4v11M7 10l5 5 5-5M5 20h14"/>',
  extern: '<path d="M14 4h6v6M20 4l-9 9M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5"/>',
  instagram: '<rect x="3" y="3" width="18" height="18" rx="5"/><circle cx="12" cy="12" r="4"/><circle cx="17.5" cy="6.5" r="1" fill="currentColor"/>',
  facebook: '<path d="M14 8h3V4h-3a4 4 0 0 0-4 4v3H7v4h3v6h4v-6h3l1-4h-4V8z"/>',
  kitz: '<path d="M8 6c-1-2-3-2-4-1 1 1 2 2 4 3M16 6c1-2 3-2 4-1-1 1-2 2-4 3"/><path d="M8 8c0 5 1.5 9 4 9s4-4 4-9a4 4 0 0 0-8 0z"/><circle cx="10.5" cy="10" r=".6" fill="currentColor"/><circle cx="13.5" cy="10" r=".6" fill="currentColor"/><path d="M11 15h2"/>',
  einsatz: '<circle cx="12" cy="13" r="7"/><path d="M12 9v4l2.5 2M9 3h6"/>',
  flaeche: '<path d="M3 7l6-3 6 3 6-3v13l-6 3-6-3-6 3z"/><path d="M9 4v13M15 7v13"/>',
  wiese: '<path d="M3 20h18M6 20c0-4-1-7-3-9M10 20c0-6 1-10 3-13M14 20c0-4 1-7 4-9M18 20c0-3 1-5 3-6"/>',
  helfer: '<circle cx="9" cy="8" r="3"/><circle cx="17" cy="9" r="2.5"/><path d="M3 20c0-3.5 2.5-6 6-6s6 2.5 6 6M15 14.5c3 0 5 2 5 5"/>',
  drohne: '<circle cx="5" cy="6" r="2.5"/><circle cx="19" cy="6" r="2.5"/><circle cx="5" cy="18" r="2.5"/><circle cx="19" cy="18" r="2.5"/><rect x="9" y="9" width="6" height="6" rx="1.5"/><path d="M7 8l2 2M17 8l-2 2M7 16l2-2M17 16l-2-2"/>',
  herz: '<path d="M12 20s-7-4.5-7-10a4 4 0 0 1 7-2.6A4 4 0 0 1 19 10c0 5.5-7 10-7 10z"/>',
  menue: '<path d="M4 7h16M4 12h16M4 17h16"/>',
};
const icon = (name, cls = 'icon') =>
  `<svg class="${cls}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${ICON[name] || ICON.herz}</svg>`;

// ---------------------------------------------------------------- Bausteine ({{> name}})

function newsKarte(n) {
  const bild = n.image
    ? `<img src="${esc(n.image)}" alt="${esc(n.image_alt || '')}" loading="lazy">`
    : `<div class="news-karte__platzhalter">${icon('kitz', 'icon icon--gross')}</div>`;
  return `<article class="news-karte">
  <a href="/news/${n.slug}/" class="news-karte__bild">${bild}</a>
  <div class="news-karte__text">
    <time datetime="${datumIso(n.date)}">${datumLang(n.date)}</time>
    <h3><a href="/news/${n.slug}/">${esc(n.title)}</a></h3>
    ${n.excerpt ? `<p>${esc(n.excerpt)}</p>` : ''}
  </div>
</article>`;
}

const partials = {
  stats: () => `<section class="erfolge" aria-labelledby="erfolge-titel">
  <div class="container">
    <p class="kicker kicker--hell">${esc(zahlen.unterzeile)}</p>
    <h2 id="erfolge-titel">${esc(zahlen.ueberschrift)}</h2>
    <ul class="erfolge__liste">
      ${(zahlen.werte || [])
        .map(
          (w) => `<li>${icon(w.symbol, 'icon icon--erfolg')}<strong>${esc(w.zahl)}</strong><span>${esc(w.text)}</span></li>`
        )
        .join('\n      ')}
    </ul>
    ${zahlen.abschluss ? `<p class="erfolge__abschluss">${esc(zahlen.abschluss)}</p>` : ''}
  </div>
</section>`,

  'news-teaser': () =>
    news.length
      ? `<div class="news-raster">${news.slice(0, 3).map(newsKarte).join('\n')}</div>`
      : '<p>Bald gibt es hier Neuigkeiten.</p>',

  'news-liste': () => `<div class="news-raster">${news.map(newsKarte).join('\n')}</div>`,

  beitraege: () => `<ul class="beitraege">
  <li><span>Einzelmitgliedschaft</span><strong>${esc(site.beitrag_einzel)} pro Jahr</strong></li>
  <li><span>Familienmitgliedschaft</span><strong>${esc(site.beitrag_familie)} pro Jahr</strong></li>
</ul>`,

  'kontakt-box': () => `<div class="kontakt-box">
  <a class="kontakt-box__zeile" href="mailto:${esc(site.email)}">${icon('mail')}<span><small>E-Mail</small>${esc(site.email)}</span></a>
  <a class="kontakt-box__zeile" href="${esc(site.telefon_link)}">${icon('telefon')}<span><small>Telefon</small>${esc(site.telefon)}</span></a>
</div>`,

  spendenbeispiele: () => `<div class="spendenbeispiele">
  ${(spenden.beispiele || [])
    .map(
      (b) => `<div class="spendenbeispiel"><span class="spendenbeispiel__symbol" aria-hidden="true">${ICON[b.symbol] ? icon(b.symbol) : esc(b.symbol)}</span><strong>${esc(b.betrag)}</strong><span>${esc(b.text)}</span></div>`
    )
    .join('\n  ')}
</div>`,

  'spenden-titel': () => esc(spenden.ueberschrift || 'Was deine Spende bewirkt'),
  'spenden-einleitung': () => esc(spenden.einleitung || ''),

  hosting: () =>
    site.hosting?.anbieter
      ? `${esc(site.hosting.anbieter)}${site.hosting.anschrift ? ', ' + esc(site.hosting.anschrift) : ''}`
      : '<mark>[Hosting-Anbieter mit Anschrift hier eintragen – content/einstellungen.json]</mark>',

  bankdaten: () => `<dl class="bankdaten">
  <div><dt>Kontoinhaber</dt><dd>${esc(site.kontoinhaber)}</dd></div>
  <div><dt>Bank</dt><dd>${esc(site.bank)}</dd></div>
  <div><dt>IBAN</dt><dd><span class="mono">${esc(site.iban)}</span> <button type="button" class="kopieren" data-kopieren="${esc(site.iban_kompakt)}">kopieren</button></dd></div>
  <div><dt>BIC</dt><dd class="mono">${esc(site.bic)}</dd></div>
  <div><dt>Verwendungszweck</dt><dd>${esc(site.verwendungszweck)}</dd></div>
</dl>`,
};

function render(template, file) {
  return template
    .replace(/\{\{>\s*([\w-]+)\s*\}\}/g, (_, name) => {
      if (!partials[name]) throw new Error(`Unbekannter Baustein {{> ${name}}} in ${file}`);
      return partials[name]();
    })
    .replace(/\{\{\s*icon:([\w-]+)\s*\}\}/g, (_, name) => icon(name))
    .replace(/\{\{\s*site\.([\w.]+)\s*\}\}/g, (_, key) => {
      const v = key.split('.').reduce((o, k) => (o == null ? o : o[k]), site);
      if (v == null) throw new Error(`Unbekannter Wert {{site.${key}}} in ${file}`);
      return esc(v);
    });
}

// ---------------------------------------------------------------- Seitenrahmen

const NAV = [
  ['/ueber-uns/', 'Über uns'],
  ['/news/', 'News & Presse'],
  ['/mitmachen/', 'Mitmachen'],
  ['/fuer-kids/', 'Für Kids'],
  ['/hangar/', 'Hangar'],
  ['/spenden/', 'Spenden'],
  ['/kontakt/', 'Kontakt'],
];

function hinweisLeiste() {
  const h = hinweis || {};
  if (!h.aktiv || !h.text) return '';
  const bis = toDate(h.anzeigen_bis);
  if (bis && bis < new Date(new Date().toDateString())) return '';
  const link = h.link ? ` <a href="${esc(h.link)}">${esc(h.link_text || 'Mehr erfahren')}</a>` : '';
  return `<div class="hinweis-leiste"><div class="container">${esc(h.text)}${link}</div></div>`;
}

function saisonLeiste(url) {
  if (!saison.aktiv) return '';
  const ziel = url === '/landwirte/' ? '#anmelden' : '/landwirte/#anmelden';
  return `<aside class="saison" data-von="${esc(saison.von)}" data-bis="${esc(saison.bis)}" aria-label="Feldsuche in der Mähsaison" hidden>
  <p class="saison__text">${icon('drohne')}<span>${saison.kostenlos !== false ? '<strong class="saison__gratis">100 % kostenlos</strong> ' : ''}${esc(saison.text)}</span></p>
  <div class="saison__knoepfe">
    <a class="knopf" href="${ziel}">Fläche anmelden</a>
    <a class="knopf knopf--hell" href="${esc(site.telefon_link)}">${icon('telefon')} Anrufen</a>
    <button type="button" class="saison__zu" aria-label="Hinweis ausblenden">×</button>
  </div>
</aside>`;
}

function strukturDaten() {
  const d = {
    '@context': 'https://schema.org',
    '@type': 'NGO',
    name: site.vereinsname,
    alternateName: 'Kitzrettung Limburg',
    url: site.domain + '/',
    logo: site.domain + '/images/icon-512.png',
    image: site.domain + '/images/kitz-sicherung.jpg',
    description: 'Gemeinnütziger Verein für die ehrenamtliche Rehkitzrettung mit Wärmebilddrohnen vor der Wiesenmahd – kostenlos für Landwirte und Jäger.',
    email: site.email,
    telephone: site.telefon_link.replace('tel:', '').replace(/^0/, '+49'),
    foundingDate: '2021',
    nonprofitStatus: 'Nonprofit',
    address: { '@type': 'PostalAddress', streetAddress: 'Feldbergstraße 11a', postalCode: '65550', addressLocality: 'Limburg', addressRegion: 'Hessen', addressCountry: 'DE' },
    areaServed: [{ '@type': 'AdministrativeArea', name: 'Landkreis Limburg-Weilburg' }, { '@type': 'State', name: 'Rheinland-Pfalz' }],
    sameAs: [site.instagram, site.facebook].filter(Boolean),
  };
  return `<script type="application/ld+json">${JSON.stringify(d)}</script>`;
}

function layout({ title, description, body, url, image }) {
  const voll = url === '/' ? `${site.vereinsname} – Gemeinsam Leben retten` : `${title} | Kitzrettung Limburg`;
  const ogImage = site.domain + (image || '/images/kitz-sicherung.jpg');
  const aktiv = (href) => (url === href || (href !== '/' && url.startsWith(href)) ? ' aria-current="page"' : '');
  return `<!doctype html>
<html lang="de">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${esc(voll)}</title>
<meta name="description" content="${esc(description)}">
<link rel="canonical" href="${site.domain}${url}">
<meta name="theme-color" content="#244c31">
<meta property="og:type" content="website">
<meta property="og:locale" content="de_DE">
<meta property="og:site_name" content="${esc(site.vereinsname)}">
<meta property="og:title" content="${esc(title)}">
<meta property="og:description" content="${esc(description)}">
<meta property="og:url" content="${site.domain}${url}">
<meta property="og:image" content="${esc(ogImage)}">
<meta name="twitter:card" content="summary_large_image">
<link rel="icon" type="image/png" href="/images/favicon.png">
<link rel="apple-touch-icon" href="/images/apple-touch-icon.png">
<link rel="manifest" href="/site.webmanifest">
<link rel="stylesheet" href="/css/style.css?v=${BUILD_ID}">
<script src="/js/main.js?v=${BUILD_ID}" defer></script>
${url === '/' ? strukturDaten() : ''}
</head>
<body>
<a class="skip-link" href="#inhalt">Zum Inhalt springen</a>
${hinweisLeiste()}
<header class="kopf">
  <div class="container kopf__innen">
    <a class="marke" href="/">
      <img src="/images/logo.png" alt="" width="56" height="56">
      <span>Kitzrettung Limburg <small>e. V.</small></span>
    </a>
    <button class="menue-knopf" type="button" aria-expanded="false" aria-controls="hauptmenue">${icon('menue')}<span>Menü</span></button>
    <nav id="hauptmenue" class="hauptmenue" aria-label="Hauptmenü">
      <ul>
        ${NAV.map(([href, label]) => `<li><a href="${href}"${aktiv(href)}>${label}</a></li>`).join('\n        ')}
      </ul>
      <a class="knopf knopf--klein" href="/landwirte/"${aktiv('/landwirte/')}>Feldsuche beauftragen ${icon('pfeil')}</a>
    </nav>
  </div>
</header>
<main id="inhalt">
${body}
</main>
<footer class="fuss">
  <div class="container fuss__raster">
    <div class="fuss__marke">
      <img src="/images/logo.png" alt="Logo der Kitzrettung Limburg e. V." width="88" height="88" loading="lazy">
      <p>Gemeinsam schützen wir Rehkitze im Landkreis Limburg-Weilburg.</p>
    </div>
    <div>
      <h2>Schnellzugriff</h2>
      <ul>
        <li><a href="/landwirte/">Feldsuche beauftragen</a></li>
        <li><a href="/ablauf-der-suche/">Ablauf der Suche</a></li>
        <li><a href="/methoden-der-kitzrettung/">Methoden der Kitzrettung</a></li>
        <li><a href="/mitmachen/">Mitmachen</a></li>
        <li><a href="/spenden/">Spenden</a></li>
        <li><a href="/fuer-kids/">Für Kids</a></li>
      </ul>
    </div>
    <div>
      <h2>Kontakt</h2>
      <ul>
        <li><a href="mailto:${esc(site.email)}">${esc(site.email)}</a></li>
        <li><a href="${esc(site.telefon_link)}">${esc(site.telefon)}</a></li>
        <li>${esc(site.vereinsname)}<br>${esc(site.anschrift)}</li>
      </ul>
    </div>
    <div>
      <h2>Folgen Sie uns</h2>
      <div class="sozial">
        <a href="${esc(site.instagram)}" rel="noopener" target="_blank" aria-label="Instagram (öffnet neues Fenster)">${icon('instagram')}</a>
        <a href="${esc(site.facebook)}" rel="noopener" target="_blank" aria-label="Facebook (öffnet neues Fenster)">${icon('facebook')}</a>
      </div>
    </div>
  </div>
  <div class="container fuss__unten">
    <p>© ${site.jahr} ${esc(site.vereinsname)} · Ehrenamtlich seit 2020 · Hilfe ohne Kosten</p>
    <p><a href="/impressum/">Impressum</a> · <a href="/datenschutz/">Datenschutz</a> · <a href="/downloads/vereinssatzung.pdf">Satzung</a></p>
  </div>
</footer>
${saisonLeiste(url)}
</body>
</html>
`;
}

const BUILD_ID = Date.now().toString(36);

// ---------------------------------------------------------------- Bauen

await fs.rm(DIST, { recursive: true, force: true });
await fs.cp(PUBLIC, DIST, { recursive: true });

// Bilder: zu große Fotos (z. B. direkt vom Handy hochgeladen) verkleinern und WebP-Fassungen erzeugen
const MAX_BREITE = 1600;
const AUSGENOMMEN = /\/(qr|favicon|icon-\d+|apple-touch-icon)/;
let gespart = 0;
for (const file of (await walk(path.join(DIST, 'images'))).filter((f) => /\.(jpe?g|png)$/i.test(f))) {
  const rel = '/' + path.relative(DIST, file).split(path.sep).join('/');
  if (AUSGENOMMEN.test(rel)) continue;
  try {
    const vorher = (await fs.stat(file)).size;
    const bild = sharp(file, { failOn: 'none' }).rotate();
    const meta = await bild.metadata();
    if (meta.width > MAX_BREITE && /\.jpe?g$/i.test(file)) {
      const buf = await sharp(file).rotate().resize({ width: MAX_BREITE }).jpeg({ quality: 80, mozjpeg: true }).toBuffer();
      await fs.writeFile(file, buf);
    }
    const base = file.replace(/\.(jpe?g|png)$/i, '');
    await sharp(file).rotate().resize({ width: MAX_BREITE, withoutEnlargement: true }).webp({ quality: 74 }).toFile(base + '.webp');
    const nachher = (await fs.stat(base + '.webp')).size;
    gespart += vorher - nachher;
    webp.add(rel.replace(/\.(jpe?g|png)$/i, ''));
  } catch (e) {
    warnings.push(`Bild ${rel} konnte nicht verarbeitet werden: ${e.message}`);
  }
}

// QR-Codes für Spenden (werden bei jeder Änderung der Bankdaten neu erzeugt)
const qrOpt = { type: 'svg', margin: 2, errorCorrectionLevel: 'M', color: { dark: '#16301f', light: '#ffffff' } };
const epc = ['BCD', '002', '1', 'SCT', site.bic, site.kontoinhaber, site.iban_kompakt, '', '', '', site.verwendungszweck].join('\n');
await write('images/qr/girocode.svg', await QRCode.toString(epc, qrOpt));
if (site.paypal) await write('images/qr/paypal.svg', await QRCode.toString(site.paypal, qrOpt));

const sitemap = [];

for (const file of (await walk(SRC)).filter((f) => f.endsWith('.html'))) {
  const rel = path.relative(SRC, file).split(path.sep).join('/');
  const { data, body } = parseFrontMatter(await fs.readFile(file, 'utf8'), rel);
  let url, out;
  if (rel === 'index.html') [url, out] = ['/', 'index.html'];
  else if (rel === '404.html') [url, out] = ['/404.html', '404.html'];
  else {
    const p = rel.replace(/\.html$/, '').replace(/\/index$/, '');
    [url, out] = [`/${p}/`, `${p}/index.html`];
  }
  if (!data.title) warnings.push(`Seite ${rel}: kein Titel angegeben.`);
  await write(out, layout({ title: data.title, description: data.description || '', image: data.image, url, body: render(body, rel) }));
  if (rel !== '404.html') sitemap.push(url);
}

// News: Übersicht + Einzelseiten
for (const n of news) {
  const url = `/news/${n.slug}/`;
  const body = `<article class="artikel">
  <div class="container container--schmal">
    <p class="zurueck"><a href="/news/">← Alle Meldungen</a></p>
    <time datetime="${datumIso(n.date)}" class="kicker">${datumLang(n.date)}</time>
    <h1>${esc(n.title)}</h1>
    ${n.excerpt ? `<p class="einleitung">${esc(n.excerpt)}</p>` : ''}
    ${n.image ? `<figure class="artikel__bild"><img src="${esc(n.image)}" alt="${esc(n.image_alt || '')}"></figure>` : ''}
    <div class="fliesstext">${n.html}</div>
    ${n.link ? `<p><a class="knopf knopf--rand" href="${esc(n.link)}" rel="noopener" target="_blank">${esc(n.link_text || 'Weiterlesen')} ${icon('extern')}</a></p>` : ''}
  </div>
</article>`;
  await write(`news/${n.slug}/index.html`, layout({ title: n.title, description: n.excerpt || n.title, image: n.image, url, body }));
  sitemap.push(url);
}

await write(
  'sitemap.xml',
  `<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n${sitemap
    .map((u) => `  <url><loc>${site.domain}${u}</loc></url>`)
    .join('\n')}\n</urlset>\n`
);
await write('robots.txt', `User-agent: *\nAllow: /\nSitemap: ${site.domain}/sitemap.xml\n`);

if (!site.hosting?.anbieter) warnings.push('Datenschutz: Hosting-Anbieter fehlt noch (content/einstellungen.json → hosting).');

console.log(`Fertig: ${sitemap.length} Seiten, ${news.length} News-Beiträge, ${webp.size} Bilder als WebP (≈ ${Math.round(gespart / 1024)} KB gespart) → dist/`);
for (const w of warnings) console.warn('Hinweis: ' + w);
