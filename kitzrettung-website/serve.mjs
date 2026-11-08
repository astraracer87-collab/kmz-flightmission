// Kleiner Vorschau-Server für dist/ (nur zum Testen auf dem eigenen Rechner).
// Aufruf: npm start  →  http://localhost:8080
import http from 'node:http';
import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const DIST = path.join(path.dirname(fileURLToPath(import.meta.url)), 'dist');
const PORT = Number(process.env.PORT) || 8080;
const TYPES = {
  '.html': 'text/html; charset=utf-8', '.css': 'text/css', '.js': 'text/javascript', '.json': 'application/json',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.jpg': 'image/jpeg', '.gif': 'image/gif', '.webp': 'image/webp',
  '.pdf': 'application/pdf', '.xml': 'application/xml', '.txt': 'text/plain', '.webmanifest': 'application/manifest+json',
};

http
  .createServer(async (req, res) => {
    let p = decodeURIComponent(new URL(req.url, 'http://x').pathname);
    if (p.endsWith('/')) p += 'index.html';
    const file = path.join(DIST, path.normalize(p));
    if (!file.startsWith(DIST)) return res.writeHead(403).end();
    try {
      const data = await fs.readFile(file);
      res.writeHead(200, { 'Content-Type': TYPES[path.extname(file)] || 'application/octet-stream' }).end(data);
    } catch {
      try {
        await fs.access(path.join(file, 'index.html'));
        res.writeHead(301, { Location: p + '/' }).end();
      } catch {
        res.writeHead(404, { 'Content-Type': TYPES['.html'] }).end(await fs.readFile(path.join(DIST, '404.html')));
      }
    }
  })
  .listen(PORT, () => console.log(`Vorschau: http://localhost:${PORT}`));
