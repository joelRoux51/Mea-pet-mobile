import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const PORT = Number(process.env.PORT) || 3000;
const HOST = '0.0.0.0';
const ASSETS_ROOT = path.join(__dirname, 'public', 'assets', 'alita_live2d');

const MIME_TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.psd': 'application/octet-stream',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
};

const POSES = [
  { id: 'normal', label: 'Normal', tag: '[EMOTION: normal]', desc: 'Neutre, attitude calme' },
  { id: 'joie', label: 'Joie / Coucou', tag: '[EMOTION: joie]', desc: 'Sourire radieux, salut amical' },
  { id: 'serieux', label: 'Sérieux', tag: '[EMOTION: serieux]', desc: 'Bras croisés, regard perçant' },
  { id: 'colere', label: 'Colère', tag: '[EMOTION: colere]', desc: 'Sourcils froncés, protestation' },
  { id: 'calme', label: 'Calme / Prière', tag: '[EMOTION: calme]', desc: 'Mains jointes, yeux fermés' },
  { id: 'fatigue', label: 'Fatigue', tag: '[EMOTION: fatigue]', desc: 'Bâillement, yeux mi-clos' },
];

const LAYER_FILES = [
  'meches_cheveux.png',
  'tete.png',
  'oeil_gauche.png',
  'oeil_droit.png',
  'pupille_gauche.png',
  'pupille_droite.png',
  'paupiere_gauche.png',
  'paupiere_droite.png',
  'sourcil_gauche.png',
  'sourcil_droit.png',
  'bouche.png',
  'bouche_joie.png',
  'bouche_colere.png',
  'bouche_malice.png',
  'bouche_surprise.png',
  'bras_main_gauche.png',
  'bras_main_droit.png',
  'torse.png',
  'pose_joie.png',
  'pose_serieux.png',
  'pose_colere.png',
  'pose_calme.png',
  'pose_fatigue.png',
];

function renderIndexHtml() {
  const poseButtons = POSES.map(
    (p, idx) => `
      <button
        type="button"
        class="pose-btn${idx === 0 ? ' active' : ''}"
        data-id="${p.id}"
        data-label="${p.label}"
        data-tag="${p.tag}"
        data-desc="${p.desc}"
      >
        <span class="pose-name">${p.label}</span>
        <code class="pose-tag">${p.tag}</code>
      </button>
    `
  ).join('');

  const layerLinks = LAYER_FILES.map((file) => {
    const label = file.replace('.png', '');
    return `<a class="layer-chip" href="/assets/alita_live2d/layers/${file}" download="${file}">${label}</a>`;
  }).join('');

  const spriteLinks = POSES.map(
    (p) =>
      `<a class="layer-chip" href="/assets/alita_live2d/sprites/alita_${p.id}.png" download="alita_${p.id}.png">alita_${p.id}.png</a>`
  ).join('');

  return `<!DOCTYPE html>
<html lang="fr">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
  <title>MeaPet — Aperçu & Assets Alita</title>
  <style>
    :root {
      color-scheme: dark;
      --bg: #0b0f17;
      --panel: #121826;
      --border: #233048;
      --text: #e6edf7;
      --muted: #93a4bd;
      --accent: #38bdf8;
      --accent-soft: rgba(56, 189, 248, 0.14);
    }
    * { box-sizing: border-box; }
    body {
      margin: 0;
      font-family: Inter, -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
      background: var(--bg);
      color: var(--text);
      min-height: 100vh;
    }
    .container {
      max-width: 1120px;
      margin: 0 auto;
      padding: 28px 20px 44px;
    }
    header {
      display: flex;
      flex-wrap: wrap;
      justify-content: space-between;
      align-items: center;
      gap: 16px;
      margin-bottom: 24px;
      padding-bottom: 18px;
      border-bottom: 1px solid var(--border);
    }
    h1 {
      margin: 0 0 6px;
      font-size: 1.5rem;
      letter-spacing: -0.02em;
    }
    .subtitle {
      margin: 0;
      color: var(--muted);
      font-size: 0.92rem;
    }
    .primary-download {
      display: inline-flex;
      align-items: center;
      gap: 8px;
      background: var(--accent);
      color: #06121e;
      font-weight: 600;
      text-decoration: none;
      padding: 10px 16px;
      border-radius: 10px;
      font-size: 0.92rem;
    }
    .primary-download:hover { filter: brightness(1.08); }
    .grid {
      display: grid;
      grid-template-columns: minmax(280px, 420px) 1fr;
      gap: 24px;
      align-items: start;
    }
    @media (max-width: 820px) {
      .grid { grid-template-columns: 1fr; }
    }
    .card {
      background: var(--panel);
      border: 1px solid var(--border);
      border-radius: 14px;
      padding: 18px;
    }
    .stage {
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      background:
        radial-gradient(circle at 50% 35%, rgba(56, 189, 248, 0.12), transparent 65%),
        repeating-conic-gradient(#161f30 0% 25%, #121826 0% 50%) 50% / 20px 20px;
      border-radius: 12px;
      padding: 16px;
      min-height: 460px;
    }
    .stage img {
      max-height: 430px;
      width: auto;
      max-width: 100%;
      object-fit: contain;
      filter: drop-shadow(0 12px 24px rgba(0, 0, 0, 0.45));
      transition: transform 0.2s ease;
    }
    .stage-caption {
      margin-top: 12px;
      text-align: center;
      font-size: 0.9rem;
      color: var(--muted);
    }
    .stage-caption strong { color: var(--text); }
    h2 {
      margin: 0 0 12px;
      font-size: 1rem;
      color: var(--text);
    }
    .poses {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
      gap: 10px;
      margin-bottom: 20px;
    }
    .pose-btn {
      text-align: left;
      background: #0e1420;
      border: 1px solid var(--border);
      color: var(--text);
      border-radius: 10px;
      padding: 10px 12px;
      cursor: pointer;
      display: flex;
      flex-direction: column;
      gap: 4px;
    }
    .pose-btn:hover { border-color: var(--accent); }
    .pose-btn.active {
      background: var(--accent-soft);
      border-color: var(--accent);
    }
    .pose-name { font-weight: 600; font-size: 0.94rem; }
    .pose-tag { font-size: 0.78rem; color: var(--accent); }
    .chips {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
      margin-bottom: 18px;
    }
    .layer-chip {
      display: inline-block;
      padding: 6px 10px;
      border-radius: 8px;
      background: #0e1420;
      border: 1px solid var(--border);
      color: var(--text);
      text-decoration: none;
      font-size: 0.8rem;
      font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    }
    .layer-chip:hover {
      border-color: var(--accent);
      color: var(--accent);
    }
    .note {
      margin: 0;
      font-size: 0.85rem;
      color: var(--muted);
      line-height: 1.5;
    }
  </style>
</head>
<body>
  <div class="container">
    <header>
      <div>
        <h1>MeaPet — Assets Alita (PSD &amp; Sprites PNG)</h1>
        <p class="subtitle">Aperçu interactif des 6 émotions pour le webhook MeaPet (application Android native Kotlin/Gradle)</p>
      </div>
      <a class="primary-download" href="/assets/alita_live2d/alita_live2d_layers.psd" download="alita_live2d_layers.psd">
        Télécharger le PSD (23 calques)
      </a>
    </header>

    <div class="grid">
      <div class="card">
        <div class="stage">
          <img id="preview-img" src="/assets/alita_live2d/sprites/alita_normal.png" alt="Alita Normal" />
        </div>
        <div class="stage-caption" id="preview-caption">
          <strong>Normal</strong> — Neutre, attitude calme (<code>[EMOTION: normal]</code>)
        </div>
      </div>

      <div class="card">
        <h2>Tester les 6 émotions</h2>
        <div class="poses">${poseButtons}</div>

        <h2>Télécharger les 6 sprites PNG transparents</h2>
        <div class="chips">${spriteLinks}</div>

        <h2>Télécharger les 23 calques PNG séparés</h2>
        <div class="chips">${layerLinks}</div>

        <p class="note">
          L'application Android MeaPet se compile via Gradle (<code>./gradlew :app:assembleDebug</code>). Ce serveur sert uniquement à prévisualiser et télécharger les fichiers PSD/PNG générés.
        </p>
      </div>
    </div>
  </div>

  <script>
    const buttons = document.querySelectorAll('.pose-btn');
    const img = document.getElementById('preview-img');
    const caption = document.getElementById('preview-caption');

    buttons.forEach((btn) => {
      btn.addEventListener('click', () => {
        buttons.forEach((b) => b.classList.remove('active'));
        btn.classList.add('active');
        const id = btn.dataset.id;
        const label = btn.dataset.label;
        const desc = btn.dataset.desc;
        const tag = btn.dataset.tag;
        img.src = '/assets/alita_live2d/sprites/alita_' + id + '.png';
        img.alt = 'Alita ' + label;
        caption.innerHTML = '<strong>' + label + '</strong> — ' + desc + ' (<code>' + tag + '</code>)';
      });
    });
  </script>
</body>
</html>`;
}

const server = http.createServer((req, res) => {
  const urlPath = decodeURIComponent((req.url || '/').split('?')[0]);

  if (urlPath === '/' || urlPath === '/index.html') {
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
    res.end(renderIndexHtml());
    return;
  }

  if (urlPath.startsWith('/assets/alita_live2d/')) {
    const relPath = urlPath.replace(/^\/assets\/alita_live2d\//, '');
    const safePath = path.normalize(relPath).replace(/^(\.\.(\/|\\|$))+/, '');
    const fullPath = path.join(ASSETS_ROOT, safePath);

    if (!fullPath.startsWith(ASSETS_ROOT) || !fs.existsSync(fullPath) || fs.statSync(fullPath).isDirectory()) {
      res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
      res.end('Not found');
      return;
    }

    const ext = path.extname(fullPath).toLowerCase();
    const contentType = MIME_TYPES[ext] || 'application/octet-stream';
    const headers = { 'Content-Type': contentType };
    if (ext === '.psd') {
      headers['Content-Disposition'] = `attachment; filename="${path.basename(fullPath)}"`;
    }
    res.writeHead(200, headers);
    fs.createReadStream(fullPath).pipe(res);
    return;
  }

  res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
  res.end('Not found');
});

server.listen(PORT, HOST, () => {
  console.log(`MeaPet Alita preview server listening on http://${HOST}:${PORT}`);
});
