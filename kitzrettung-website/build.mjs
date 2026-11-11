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
const mailvorlagen = JSON.parse(await fs.readFile(path.join(CONTENT, 'mailvorlagen.json'), 'utf8'));

// E-Mail-Link mit vorausgefülltem Betreff und Text aus content/mailvorlagen.json
function mailLink(key, quelle = '') {
  const v = mailvorlagen[key];
  if (!v) throw new Error(`Unbekannte E-Mail-Vorlage {{mail:${key}}}${quelle ? ' in ' + quelle : ''}`);
  const teile = [];
  if (v.betreff) teile.push('subject=' + encodeURIComponent(v.betreff));
  if (v.text) teile.push('body=' + encodeURIComponent(String(v.text).replace(/\r?\n/g, '\r\n')));
  return `mailto:${site.email}${teile.length ? '?' + teile.join('&amp;') : ''}`;
}

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
  pfeil: '<path d="M4.5 12h14.5"/><path d="M13 6l6 6-6 6"/>',
  mail: '<rect class="d" x="3" y="5" width="18" height="14" rx="3"/><path d="M3.8 7.2l7 5.3a2 2 0 0 0 2.4 0l7-5.3"/>',
  telefon: '<path class="d" d="M6.6 3.5h2.6c.5 0 .9.3 1 .8l.9 3.6c.1.4-.1.9-.4 1.1l-1.9 1.3a12 12 0 0 0 4.9 4.9l1.3-1.9c.2-.3.7-.5 1.1-.4l3.6.9c.5.1.8.5.8 1v2.6a2.1 2.1 0 0 1-2.2 2.1C10.5 19.1 4.9 13.5 4.4 5.7a2.1 2.1 0 0 1 2.2-2.2z"/>',
  download: '<path d="M12 3.5v11"/><path d="M7.5 10.5L12 15l4.5-4.5"/><path class="d" d="M4 15.5v2.5a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-2.5"/>',
  extern: '<path d="M13.5 4.5h6v6"/><path d="M19.5 4.5l-8.5 8.5"/><path class="d" d="M18 13.5V18a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4.5"/>',
  instagram: '<rect x="3.5" y="3.5" width="17" height="17" rx="5"/><circle cx="12" cy="12" r="4"/><circle class="f" cx="17.2" cy="6.8" r="1.1"/>',
  facebook: '<path class="f" d="M13.6 21v-7.6h2.6l.4-3h-3V8.5c0-.9.3-1.5 1.5-1.5h1.6V4.3c-.3 0-1.2-.1-2.3-.1-2.3 0-3.9 1.4-3.9 4v2.2H8v3h2.5V21z"/>',
  kitz: '<path class="d" d="M8.3 7.9C6 8 3.6 6.6 2.8 4.3c2.3-.6 4.6.2 6 2"/><path class="d" d="M15.7 7.9c2.3.1 4.7-1.3 5.5-3.6-2.3-.6-4.6.2-6 2"/><path class="d" d="M12 6.4c-2.6 0-4.6 2.1-4.6 4.9 0 2.5 1.7 5 3.2 7.1.4.6.8 1.1 1.4 1.1s1-.5 1.4-1.1c1.5-2.1 3.2-4.6 3.2-7.1 0-2.8-2-4.9-4.6-4.9z"/><circle class="f" cx="10" cy="11.6" r=".95"/><circle class="f" cx="14" cy="11.6" r=".95"/><ellipse class="f" cx="12" cy="17.6" rx="1.15" ry=".85"/><circle class="f" cx="11.1" cy="8.7" r=".45"/><circle class="f" cx="12.9" cy="9.3" r=".45"/><circle class="f" cx="12" cy="7.9" r=".4"/>',
  einsatz: '<path d="M2.5 19h19"/><path class="d" d="M6.5 19a5.5 5.5 0 0 1 11 0"/><path d="M12 6.5v2.5M5.9 9.4l1.6 1.6M18.1 9.4l-1.6 1.6M3.5 14.5h2M18.5 14.5h2"/><path d="M8 21.5h8"/>',
  flaeche: '<path class="d" d="M3 7.5l5.5-2.5 7 2.5 5.5-2.5v12l-5.5 2.5-7-2.5L3 19.5z"/><path stroke-dasharray="1.4 2" d="M7.5 9.6v6.6M12 8.6v8.6M16.5 9.6v6.6"/>',
  wiese: '<path d="M2.5 20.5h19"/><path class="d" d="M5.5 20.5C5.4 17 4.4 14.3 2.8 12.3c2.6.9 4.4 3.6 4.7 8.2"/><path class="d" d="M10.5 20.5c0-5.2 1-9.4 3-12.4.2 4.8-.6 8.9-1.8 12.4"/><path class="d" d="M15.2 20.5c.4-2.8 1.8-5 4-6.3-.9 2.3-1.7 4.4-1.9 6.3"/><path d="M7.5 20.5V10.5"/><circle class="d" cx="7.5" cy="8" r="2.5"/><circle class="f" cx="7.5" cy="8" r=".9"/>',
  helfer: '<circle class="d" cx="9" cy="7.5" r="3.3"/><path class="d" d="M2.8 20c0-3.6 2.8-6.4 6.2-6.4s6.2 2.8 6.2 6.4z"/><circle cx="17.2" cy="8.6" r="2.6"/><path d="M16.6 13.7c.3 0 .5-.1.8-.1 2.4 0 4.3 2 4.3 4.7v1.7h-4.1"/>',
  drohne: '<path d="M9.6 9.6L6.6 6.6M14.4 9.6l3-3M9.6 14.4l-3 3M14.4 14.4l3 3"/><ellipse class="d" cx="5.3" cy="5.3" rx="3.4" ry="1.5"/><ellipse class="d" cx="18.7" cy="5.3" rx="3.4" ry="1.5"/><ellipse class="d" cx="5.3" cy="18.7" rx="3.4" ry="1.5"/><ellipse class="d" cx="18.7" cy="18.7" rx="3.4" ry="1.5"/><rect class="d" x="8.8" y="8.8" width="6.4" height="6.4" rx="2"/><circle class="f" cx="12" cy="12" r="1.3"/>',
  herz: '<path class="d" d="M12 20.3s-7.8-4.6-7.8-10.4A4.4 4.4 0 0 1 12 7.2a4.4 4.4 0 0 1 7.8 2.7c0 5.8-7.8 10.4-7.8 10.4z"/>',
  menue: '<path d="M4 7h16M4 12h16M4 17h10"/>',
  haken: '<path d="M5 12.5l4.2 4.2L19 7"/>',
  uhr: '<circle cx="12" cy="12" r="8.5"/><path d="M12 7.5V12l3 2"/>',
  kreuz: '<path d="M6 6l12 12M18 6L6 18"/>',
};
const icon = (name, cls = 'icon') =>
  `<svg class="${cls}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${ICON[name] || ICON.herz}</svg>`;

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
          (w) => `<li><span class="plakette">${icon(w.symbol, 'icon icon--erfolg')}</span><strong>${esc(w.zahl)}</strong><span>${esc(w.text)}</span></li>`
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
  <a class="kontakt-box__zeile" href="${mailLink('allgemein')}"><span class="symbolkachel">${icon('mail')}</span><span><small>E-Mail</small>${esc(site.email)}</span></a>
  <a class="kontakt-box__zeile" href="${esc(site.telefon_link)}"><span class="symbolkachel">${icon('telefon')}</span><span><small>Telefon</small>${esc(site.telefon)}</span></a>
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
    .replace(/\{\{\s*mail:([\w-]+)\s*\}\}/g, (_, key) => mailLink(key, file))
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
  const fakten = [
    saison.kostenlos !== false ? `<li>${icon('haken')}Kostenlos</li>` : '',
    saison.hinweis ? `<li>${icon('uhr')}${esc(saison.hinweis)}</li>` : '',
  ].join('');
  return `<aside class="saison" data-von="${esc(saison.von)}" data-bis="${esc(saison.bis)}" aria-label="Kitzrettung: Feldsuche in der Mähsaison" hidden>
  <div class="saison__signal" aria-hidden="true">${icon('telefon')}</div>
  <div class="saison__inhalt">
    <p class="saison__kicker">${esc(saison.kicker || 'Mähsaison')} <span>·</span> Kitzrettung Limburg</p>
    <p class="saison__titel">${esc(saison.text)}</p>
    <p class="saison__kurz">${esc(saison.kurztext || 'Kitzrettung anfordern')}${saison.kostenlos !== false ? '<small>Kostenlos</small>' : ''}</p>
    <ul class="saison__fakten">${fakten}</ul>
  </div>
  <div class="saison__knoepfe">
    <a class="saison__tel" href="${esc(site.telefon_link)}" aria-label="Kitzrettung anrufen: ${esc(site.telefon)}"><small>Einsatz-Telefon</small><strong>${esc(site.telefon)}</strong><span class="saison__mobil">${icon('telefon')}Anrufen</span></a>
    <a class="saison__anmelden" href="${ziel}"><span class="saison__voll">Fläche anmelden</span><span class="saison__mobil">Anmelden</span>${icon('pfeil')}</a>
  </div>
  <button type="button" class="saison__zu" aria-label="Hinweis ausblenden">${icon('kreuz')}</button>
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

function layout({ title, description, body, url, image, noindex }) {
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
<link rel="canonical" href="${site.domain}${url}">${noindex ? '\n<meta name="robots" content="noindex">' : ''}
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
        <li><a href="${mailLink('allgemein')}">${esc(site.email)}</a></li>
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
  await write(out, layout({ title: data.title, description: data.description || '', image: data.image, noindex: data.noindex, url, body: render(body, rel) }));
  if (rel !== '404.html' && !data.noindex) sitemap.push(url);
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

// Einstellungen für das PHP-Kontaktformular (public/kontakt.php liest diese Datei)
const formular = site.formular || {};
const phpString = (v) => "'" + String(v ?? '').replace(/\\/g, '\\\\').replace(/'/g, "\\'") + "'";
await write(
  'formular-config.php',
  `<?php\n// Automatisch erzeugt aus content/einstellungen.json – bitte dort ändern.\nreturn [\n` +
    `  'empfaenger' => ${phpString(formular.empfaenger || site.email)},\n` +
    `  'absender' => ${phpString(formular.absender || '')},\n` +
    `  'telefon' => ${phpString(site.telefon)},\n` +
    `  'domain' => ${phpString(site.domain.replace(/^https?:\/\//, ''))},\n` +
    `  'testmodus' => ${formular.testmodus ? 'true' : 'false'},\n];\n`
);
if (!formular.absender) warnings.push('Kontaktformular: Absender-Adresse fehlt (content/einstellungen.json → formular.absender), z. B. formular@kitzrettung-limburg.de beim Hoster anlegen.');

if (!site.hosting?.anbieter) warnings.push('Datenschutz: Hosting-Anbieter fehlt noch (content/einstellungen.json → hosting).');

console.log(`Fertig: ${sitemap.length} Seiten, ${news.length} News-Beiträge, ${webp.size} Bilder als WebP (≈ ${Math.round(gespart / 1024)} KB gespart) → dist/`);
for (const w of warnings) console.warn('Hinweis: ' + w);
