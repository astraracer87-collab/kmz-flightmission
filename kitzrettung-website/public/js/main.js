// Kitzrettung Limburg – kleine Helfer für Menü, externe Inhalte, IBAN kopieren und Wetter.
// Die Seite funktioniert auch ohne JavaScript; hier wird nur Komfort ergänzt.
(function () {
  'use strict';

  // Mobiles Menü
  var kopf = document.querySelector('.kopf');
  var knopf = document.querySelector('.menue-knopf');
  if (kopf && knopf) {
    knopf.addEventListener('click', function () {
      var offen = kopf.classList.toggle('offen');
      knopf.setAttribute('aria-expanded', offen ? 'true' : 'false');
    });
    document.addEventListener('keydown', function (e) {
      if (e.key === 'Escape' && kopf.classList.contains('offen')) {
        kopf.classList.remove('offen');
        knopf.setAttribute('aria-expanded', 'false');
        knopf.focus();
      }
    });
  }

  // Externe Inhalte (YouTube, Instagram, UAV Editor) erst nach Klick laden – so fließen
  // keine Daten an Dritte, bevor jemand das ausdrücklich möchte.
  document.querySelectorAll('[data-extern]').forEach(function (box) {
    var btn = box.querySelector('button');
    if (!btn) return;
    btn.addEventListener('click', function () {
      var f = document.createElement('iframe');
      f.src = box.getAttribute('data-extern');
      f.title = box.getAttribute('data-titel') || 'Externer Inhalt';
      f.loading = 'lazy';
      f.allow = 'fullscreen; picture-in-picture; encrypted-media';
      f.referrerPolicy = 'strict-origin-when-cross-origin';
      box.innerHTML = '';
      box.appendChild(f);
      box.classList.add('geladen');
    });
  });

  // Saison-Leiste: nur zwischen den eingestellten Tagen (MM-TT) zeigen.
  // Zum Ausprobieren außerhalb der Saison: Adresse mit ?saison=1 aufrufen.
  var saison = document.querySelector('.saison');
  if (saison) {
    var heute = new Date();
    var mmtt = ('0' + (heute.getMonth() + 1)).slice(-2) + '-' + ('0' + heute.getDate()).slice(-2);
    var von = saison.getAttribute('data-von') || '01-01';
    var bis = saison.getAttribute('data-bis') || '12-31';
    var inSaison = von <= bis ? (mmtt >= von && mmtt <= bis) : (mmtt >= von || mmtt <= bis);
    if (/[?&]saison=1/.test(location.search)) inSaison = true;
    var weg = false;
    try { weg = sessionStorage.getItem('saison-zu') === '1'; } catch (e) {}
    if (inSaison && !weg) {
      saison.hidden = false;
      document.body.classList.add('mit-saison');
    }
    var zu = saison.querySelector('.saison__zu');
    if (zu) zu.addEventListener('click', function () {
      saison.hidden = true;
      document.body.classList.remove('mit-saison');
      try { sessionStorage.setItem('saison-zu', '1'); } catch (e) {}
    });
  }

  // Kontaktformular: passende Zusatzfelder einblenden und ohne Seitenwechsel absenden
  var form = document.querySelector('[data-formular]');
  if (form) {
    var auswahl = form.querySelector('[name="anliegen"]');
    var nachricht = form.querySelector('[name="nachricht"]');
    var nachrichtStern = form.querySelector('[data-nachricht-pflicht]');
    var telHinweis = form.querySelector('[data-telefon-hinweis]');
    var telefon = form.querySelector('[name="telefon"]');
    var meldung = form.querySelector('.formular__meldung');
    var knopfSenden = form.querySelector('button[type="submit"]');
    form.querySelector('[name="ts"]').value = String(Date.now());

    var zeige = function () {
      var a = auswahl.value;
      form.querySelectorAll('.zusatz').forEach(function (fs) {
        var an = fs.getAttribute('data-anliegen') === a;
        fs.hidden = !an;
        fs.disabled = !an;
      });
      var nachrichtPflicht = a === 'allgemein';
      nachricht.required = nachrichtPflicht;
      nachrichtStern.hidden = !nachrichtPflicht;
      var telPflicht = a === 'feldsuche';
      telefon.required = telPflicht;
      telHinweis.textContent = telPflicht ? '(Mobilnummer, Pflichtfeld)' : '(optional)';
    };
    // Anliegen aus der Adresse übernehmen, z. B. /kontakt/?anliegen=spendenquittung#formular
    var m = /[?&]anliegen=([a-z]+)/.exec(location.search);
    if (m && auswahl.querySelector('option[value="' + m[1] + '"]')) auswahl.value = m[1];
    var b = /[?&]bereich=([A-Za-z]+)/.exec(location.search);
    if (b) { var sel = form.querySelector('[name="bereich"]'); if (sel.querySelector('option[value="' + b[1] + '"]')) sel.value = b[1]; }
    auswahl.addEventListener('change', zeige);
    zeige();

    var melden = function (text, fehler) {
      meldung.hidden = false;
      meldung.textContent = text;
      meldung.className = 'formular__meldung' + (fehler ? ' formular__meldung--fehler' : ' formular__meldung--ok');
      meldung.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
    };

    form.addEventListener('submit', function (e) {
      if (!window.fetch || !window.FormData) return; // dann normal absenden
      e.preventDefault();
      knopfSenden.disabled = true;
      knopfSenden.dataset.text = knopfSenden.dataset.text || knopfSenden.innerHTML;
      knopfSenden.textContent = 'Wird gesendet …';
      fetch(form.action, { method: 'POST', body: new FormData(form), headers: { Accept: 'application/json' } })
        .then(function (r) { return r.json().catch(function () { return { ok: false, nachricht: 'Unerwartete Antwort vom Server.' }; }); })
        .then(function (d) {
          if (d.ok) {
            location.href = '/kontakt/danke/';
          } else {
            melden(d.nachricht || 'Die Nachricht konnte nicht gesendet werden.', true);
            knopfSenden.disabled = false;
            knopfSenden.innerHTML = knopfSenden.dataset.text;
          }
        })
        .catch(function () {
          melden('Die Verbindung zum Server ist fehlgeschlagen. Bitte versuchen Sie es noch einmal oder schreiben Sie uns eine E-Mail.', true);
          knopfSenden.disabled = false;
          knopfSenden.innerHTML = knopfSenden.dataset.text;
        });
    });
  }

  // IBAN kopieren
  document.querySelectorAll('[data-kopieren]').forEach(function (b) {
    b.addEventListener('click', function () {
      var text = b.getAttribute('data-kopieren');
      var fertig = function () {
        var alt = b.textContent;
        b.textContent = 'kopiert ✓';
        setTimeout(function () { b.textContent = alt; }, 1800);
      };
      if (navigator.clipboard) navigator.clipboard.writeText(text).then(fertig, function () {});
    });
  });

  // Wetter für Limburg (Open-Meteo, Modell DWD ICON) – nur nach Klick
  var wetter = document.querySelector('[data-wetter]');
  if (wetter) {
    var wbtn = wetter.querySelector('button');
    var ziel = wetter.querySelector('.wetter__tage');
    var status = wetter.querySelector('.wetter__status');
    var TAGE = ['So.', 'Mo.', 'Di.', 'Mi.', 'Do.', 'Fr.', 'Sa.'];
    var zahl = function (v, d) { return v == null || isNaN(v) ? '–' : Number(v).toLocaleString('de-DE', { maximumFractionDigits: d === undefined ? 1 : d }); };
    wbtn.addEventListener('click', function () {
      wbtn.disabled = true;
      status.textContent = 'Vorhersage wird geladen …';
      var url = 'https://api.open-meteo.com/v1/forecast?latitude=50.41&longitude=8.08' +
        '&daily=temperature_2m_min,temperature_2m_max,precipitation_sum,wind_speed_10m_max,wind_gusts_10m_max,sunrise' +
        '&hourly=visibility&models=icon_seamless&wind_speed_unit=ms&timezone=Europe%2FBerlin&forecast_days=4';
      fetch(url).then(function (r) { if (!r.ok) throw new Error(r.status); return r.json(); }).then(function (d) {
        var html = '';
        d.daily.time.forEach(function (t, i) {
          // geringste Sichtweite zwischen 3 und 12 Uhr (Einsatzzeit)
          var sicht = null;
          d.hourly.time.forEach(function (h, j) {
            if (h.indexOf(t) === 0) {
              var std = Number(h.slice(11, 13));
              var v = d.hourly.visibility[j];
              if (std >= 3 && std <= 12 && v != null) sicht = sicht == null ? v : Math.min(sicht, v);
            }
          });
          var datum = new Date(t + 'T12:00:00');
          var boe = d.daily.wind_gusts_10m_max[i];
          var sichtKm = sicht == null ? null : sicht / 1000;
          var sichtKlasse = sichtKm == null ? '' : sichtKm < 1 ? 'warn' : sichtKm < 3 ? 'hinweis' : '';
          var boeKlasse = boe > 12 ? 'warn' : '';
          var auf = d.daily.sunrise[i] ? d.daily.sunrise[i].slice(11, 16) : '–';
          html += '<div class="wetter__tag"><h4>' + TAGE[datum.getDay()] + ' ' + datum.toLocaleDateString('de-DE', { day: '2-digit', month: '2-digit' }) + '</h4><dl>' +
            '<dt>Temperatur</dt><dd>' + zahl(d.daily.temperature_2m_min[i]) + ' bis ' + zahl(d.daily.temperature_2m_max[i]) + ' °C</dd>' +
            '<dt>Niederschlag</dt><dd>' + zahl(d.daily.precipitation_sum[i]) + ' mm</dd>' +
            '<dt>Wind max.</dt><dd>' + zahl(d.daily.wind_speed_10m_max[i]) + ' m/s</dd>' +
            '<dt>Böen max.</dt><dd class="' + boeKlasse + '">' + zahl(boe) + ' m/s</dd>' +
            '<dt>Sicht 3–12 Uhr</dt><dd class="' + sichtKlasse + '">' + (sichtKm == null ? '–' : zahl(sichtKm) + ' km') + '</dd>' +
            '<dt>Sonnenaufgang</dt><dd>' + auf + ' Uhr</dd>' +
            '</dl></div>';
        });
        ziel.innerHTML = html;
        status.textContent = 'Stand: ' + new Date().toLocaleString('de-DE', { dateStyle: 'short', timeStyle: 'short' }) + ' Uhr · Daten: DWD ICON über Open-Meteo.com';
        wbtn.hidden = true;
      }).catch(function () {
        status.textContent = 'Die Vorhersage konnte gerade nicht geladen werden. Bitte später erneut versuchen.';
        wbtn.disabled = false;
      });
    });
  }
})();
