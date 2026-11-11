<?php
/*
 * Kontaktformular der Kitzrettung Limburg e. V.
 *
 * Nimmt das Formular von /kontakt/ entgegen, prüft die Angaben und schickt sie als E-Mail an den Verein.
 * Es wird nichts in einer Datenbank gespeichert. Für den Spamschutz merkt sich das Skript höchstens
 * eine Stunde lang einen gekürzten Fingerabdruck (Hash) der IP-Adresse, ohne die Adresse selbst.
 *
 * Einstellungen (Empfänger, Absender) kommen aus formular-config.php, die beim Bauen aus
 * content/einstellungen.json erzeugt wird.
 */

declare(strict_types=1);

date_default_timezone_set('Europe/Berlin');

$CONFIG = require __DIR__ . '/formular-config.php';

// Testmodus: Mails werden nicht verschickt, sondern als Datei abgelegt (nur zum lokalen Ausprobieren).
$TESTMODUS = !empty($CONFIG['testmodus']) || getenv('KITZ_FORMULAR_TEST') === '1';

const MAX_PRO_STUNDE = 5;      // Nachrichten pro IP-Adresse und Stunde
const MIN_SEKUNDEN = 4;        // so lange braucht ein Mensch mindestens zum Ausfüllen
const DATENORDNER = __DIR__ . '/formular-daten';

$ANLIEGEN = [
    'feldsuche'       => 'Feldsuche anmelden',
    'mithelfen'       => 'Mithelfen',
    'spendenquittung' => 'Spendenquittung',
    'sachspende'      => 'Sachspende',
    'presse'          => 'Presseanfrage',
    'kita'            => 'Besuch Kita / Grundschule',
    'allgemein'       => 'Allgemeine Anfrage',
];

// Zusatzfelder je Anliegen: Feldname => [Bezeichnung in der Mail, Pflichtfeld?, maximale Länge]
$ZUSATZ = [
    'feldsuche' => [
        'ort'          => ['Gemeinde / Ort', true, 120],
        'maehdatum'    => ['Geplantes Mähdatum', true, 40],
        'flaeche'      => ['Lage der Fläche (Beschreibung, Flurstück oder Koordinaten)', true, 1500],
        'jaeger_name'  => ['Zuständige jagdausübungsberechtigte Person', true, 120],
        'jaeger_tel'   => ['Mobilnummer dieser Person', true, 40],
    ],
    'mithelfen' => [
        'bereich'      => ['Interesse an', true, 80],
        'wohnort'      => ['Wohnort', false, 120],
        'alter16'      => ['Mindestens 16 Jahre alt', true, 10],
    ],
    'spendenquittung' => [
        'strasse'      => ['Straße und Hausnummer', true, 160],
        'plz_ort'      => ['PLZ und Ort', true, 120],
        'spende_datum' => ['Datum der Spende', true, 40],
        'spende_betrag'=> ['Betrag', true, 40],
        'zahlungsweg'  => ['Zahlungsweg', false, 40],
    ],
    'sachspende' => [
        'sache'        => ['Was möchte gespendet werden', true, 300],
        'uebergabe'    => ['Übergabe', false, 80],
    ],
    'presse' => [
        'medium'       => ['Medium / Redaktion', true, 160],
        'termin'       => ['Gewünschter Termin / Redaktionsschluss', false, 120],
    ],
    'kita' => [
        'einrichtung'  => ['Einrichtung', true, 160],
        'kinder'       => ['Anzahl und Alter der Kinder', false, 120],
        'termin'       => ['Wunschzeitraum', false, 120],
    ],
    'allgemein' => [],
];

// ------------------------------------------------------------------ Hilfen

function istFetch(): bool
{
    return stripos($_SERVER['HTTP_ACCEPT'] ?? '', 'application/json') !== false;
}

function antwort(bool $ok, string $text, int $status = 200): never
{
    if (istFetch()) {
        http_response_code($status);
        header('Content-Type: application/json; charset=utf-8');
        echo json_encode(['ok' => $ok, 'nachricht' => $text], JSON_UNESCAPED_UNICODE);
        exit;
    }
    if ($ok) {
        header('Location: /kontakt/danke/', true, 303);
        exit;
    }
    http_response_code($status);
    header('Content-Type: text/html; charset=utf-8');
    $t = htmlspecialchars($text, ENT_QUOTES, 'UTF-8');
    echo "<!doctype html><html lang=\"de\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
       . "<title>Nachricht nicht gesendet | Kitzrettung Limburg</title><link rel=\"stylesheet\" href=\"/css/style.css\"></head><body>"
       . "<main class=\"abschnitt\"><div class=\"container container--schmal\"><p class=\"kicker\">Kontaktformular</p>"
       . "<h1>Die Nachricht wurde nicht gesendet</h1><p class=\"einleitung\">{$t}</p>"
       . "<p><a class=\"knopf\" href=\"javascript:history.back()\">Zurück zum Formular</a></p></div></main></body></html>";
    exit;
}

/** Text aus dem Formular holen: getrimmt, ohne Steuerzeichen, auf Länge begrenzt. */
function feld(string $name, int $max = 200, bool $mehrzeilig = false): string
{
    $v = $_POST[$name] ?? '';
    if (!is_string($v)) return '';
    $v = trim(str_replace("\r\n", "\n", $v));
    $v = $mehrzeilig
        ? preg_replace('/[^\P{C}\n\t]/u', '', $v)
        : preg_replace('/\p{C}/u', ' ', $v);
    $v = (string) $v;
    return mb_strlen($v) > $max ? mb_substr($v, 0, $max) : $v;
}

function kopfzeile(string $v): string
{
    // Schutz gegen das Einschleusen zusätzlicher Mail-Kopfzeilen
    return trim(preg_replace('/[\r\n]+/', ' ', $v));
}

/** Namen für die Anzeige im Absender: ohne Zeichen, die in Mail-Kopfzeilen eine Bedeutung haben. */
function anzeigename(string $v): string
{
    return trim(preg_replace('/[<>"\\:;,@()\[\]]+/', ' ', kopfzeile($v))) ?: 'Absender';
}

/** Prüft die Begrenzung pro IP-Adresse; mit $merken = true wird eine erfolgreiche Nachricht mitgezählt. */
function zuVieleAnfragen(bool $merken = false): bool
{
    if (!is_dir(DATENORDNER) && !@mkdir(DATENORDNER, 0750, true)) return false;
    $datei = DATENORDNER . '/sperre.json';
    $schluessel = substr(hash('sha256', ($_SERVER['REMOTE_ADDR'] ?? '') . '|' . date('Y-m-d')), 0, 16);
    $jetzt = time();
    $fh = @fopen($datei, 'c+');
    if (!$fh) return false;
    flock($fh, LOCK_EX);
    $daten = json_decode((string) stream_get_contents($fh), true) ?: [];
    foreach ($daten as $k => $zeiten) {
        $daten[$k] = array_values(array_filter($zeiten, fn($t) => $t > $jetzt - 3600));
        if (!$daten[$k]) unset($daten[$k]);
    }
    $zuViel = count($daten[$schluessel] ?? []) >= MAX_PRO_STUNDE;
    if (!$zuViel && $merken) $daten[$schluessel][] = $jetzt;
    ftruncate($fh, 0);
    rewind($fh);
    fwrite($fh, json_encode($daten));
    flock($fh, LOCK_UN);
    fclose($fh);
    return $zuViel;
}

// ------------------------------------------------------------------ Prüfen

if (($_SERVER['REQUEST_METHOD'] ?? '') !== 'POST') {
    header('Location: /kontakt/', true, 303);
    exit;
}

$EMAIL_HINWEIS = ' Sie erreichen uns auch direkt per E-Mail an ' . $CONFIG['empfaenger'] . ' oder telefonisch unter ' . $CONFIG['telefon'] . '.';

// 1) Fangfeld: Menschen sehen es nicht und lassen es leer
if (feld('website') !== '') {
    antwort(true, 'Danke!');   // Bots bekommen eine scheinbare Erfolgsmeldung
}

// 2) Zeitstempel, den das Formular beim Öffnen setzt (Bots füllen in Sekundenbruchteilen aus)
$ts = (int) feld('ts', 20);
$dauer = time() - intdiv($ts, 1000);
if ($ts <= 0 || $dauer < MIN_SEKUNDEN || $dauer > 86400) {
    antwort(false, 'Das Formular konnte nicht überprüft werden. Bitte laden Sie die Seite neu und versuchen Sie es noch einmal.' . $EMAIL_HINWEIS, 400);
}

// 3) Begrenzung pro IP-Adresse
if (zuVieleAnfragen()) {
    antwort(false, 'Sie haben in kurzer Zeit sehr viele Nachrichten gesendet. Bitte versuchen Sie es in einer Stunde noch einmal.' . $EMAIL_HINWEIS, 429);
}

$anliegen = feld('anliegen', 30);
if (!isset($ANLIEGEN[$anliegen])) $anliegen = 'allgemein';

$name = feld('name', 120);
$email = feld('email', 160);
$telefon = feld('telefon', 40);
$nachricht = feld('nachricht', 5000, true);

$fehler = [];
if ($name === '') $fehler[] = 'Bitte geben Sie Ihren Namen an.';
if ($email === '' || !filter_var($email, FILTER_VALIDATE_EMAIL)) $fehler[] = 'Bitte geben Sie eine gültige E-Mail-Adresse an.';
if ($anliegen === 'feldsuche' && $telefon === '') $fehler[] = 'Für die Feldsuche brauchen wir Ihre Mobilnummer.';
if ($anliegen === 'allgemein' && $nachricht === '') $fehler[] = 'Bitte schreiben Sie uns eine Nachricht.';
if (empty($_POST['datenschutz'])) $fehler[] = 'Bitte stimmen Sie der Verarbeitung Ihrer Angaben zu.';

$zusatz = [];
foreach ($ZUSATZ[$anliegen] as $feldname => [$label, $pflicht, $max]) {
    $wert = feld($feldname, $max, $max > 300);
    if ($feldname === 'alter16') $wert = $wert !== '' ? 'ja' : '';
    // Datumsfelder aus dem Browser (2026-06-14) in deutscher Schreibweise (14.06.2026)
    if (preg_match('/^(\d{4})-(\d{2})-(\d{2})$/', $wert, $d)) $wert = "{$d[3]}.{$d[2]}.{$d[1]}";
    if ($pflicht && $wert === '') $fehler[] = "Bitte füllen Sie das Feld „{$label}“ aus.";
    $zusatz[$label] = $wert;
}

// Links in der Nachricht sind bei Spam üblich – mehr als drei werden abgelehnt
if (preg_match_all('~https?://~i', $nachricht) > 3) $fehler[] = 'Bitte senden Sie höchstens drei Links mit.';

if ($fehler) {
    antwort(false, implode(' ', $fehler), 422);
}

// ------------------------------------------------------------------ E-Mail zusammenbauen

$titel = $ANLIEGEN[$anliegen];
$betreff = kopfzeile("[Webseite] {$titel} – " . anzeigename($name));

$zeilen = ["Neue Nachricht über das Kontaktformular auf {$CONFIG['domain']}", str_repeat('=', 60), ''];
$zeilen[] = "Anliegen:  {$titel}";
$zeilen[] = "Name:      {$name}";
$zeilen[] = "E-Mail:    {$email}";
if ($telefon !== '') $zeilen[] = "Telefon:   {$telefon}";
foreach ($zusatz as $label => $wert) {
    if ($wert === '') continue;
    $zeilen[] = str_contains($wert, "\n") ? "\n{$label}:\n{$wert}\n" : "{$label}: {$wert}";
}
if ($nachricht !== '') {
    $zeilen[] = '';
    $zeilen[] = 'Nachricht:';
    $zeilen[] = $nachricht;
}
$zeilen[] = '';
$zeilen[] = str_repeat('-', 60);
$zeilen[] = 'Gesendet am ' . date('d.m.Y \u\m H:i') . ' Uhr. Mit „Antworten“ schreiben Sie direkt an den Absender.';
$text = implode("\n", $zeilen) . "\n";

$absender = $CONFIG['absender'] !== '' ? $CONFIG['absender'] : $CONFIG['empfaenger'];
$kopf = [
    'From' => mb_encode_mimeheader('Webseite Kitzrettung Limburg', 'UTF-8', 'Q') . " <{$absender}>",
    'Reply-To' => mb_encode_mimeheader(anzeigename($name), 'UTF-8', 'Q') . ' <' . kopfzeile($email) . '>',
    'MIME-Version' => '1.0',
    'Content-Type' => 'text/plain; charset=UTF-8',
    'Content-Transfer-Encoding' => '8bit',
    'X-Mailer' => 'Kitzrettung-Webseite',
];

if ($TESTMODUS) {
    if (!is_dir(DATENORDNER)) @mkdir(DATENORDNER, 0750, true);
    $roh = "To: {$CONFIG['empfaenger']}\nSubject: {$betreff}\n";
    foreach ($kopf as $k => $v) $roh .= "{$k}: {$v}\n";
    zuVieleAnfragen(true);
    file_put_contents(DATENORDNER . '/test-' . date('Ymd-His') . '-' . bin2hex(random_bytes(3)) . '.eml', $roh . "\n" . $text);
    antwort(true, 'Vielen Dank! Ihre Nachricht ist bei uns angekommen. (Testmodus: nicht wirklich versendet.)');
}

$ok = mail(
    $CONFIG['empfaenger'],
    mb_encode_mimeheader($betreff, 'UTF-8', 'Q'),
    $text,
    $kopf,
    '-f' . $absender
);

if (!$ok) {
    error_log('Kontaktformular: mail() fehlgeschlagen');
    antwort(false, 'Beim Versenden ist leider ein technischer Fehler aufgetreten.' . $EMAIL_HINWEIS, 500);
}

zuVieleAnfragen(true);
antwort(true, 'Vielen Dank! Ihre Nachricht ist bei uns angekommen. Wir melden uns so bald wie möglich.');
