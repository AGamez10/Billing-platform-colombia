// Prerenderiza la página pública en dist/landing.html (nginx la sirve solo en "/").
// Se ejecuta después de `vite build` y `vite build --ssr src/landing/prerender.tsx`.
import { readFileSync, rmSync, writeFileSync } from 'node:fs';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { resolve } from 'node:path';

const root = resolve(fileURLToPath(new URL('.', import.meta.url)), '..');
const { render, structuredData, SEO } = await import(pathToFileURL(resolve(root, 'dist-ssr/prerender.js')).href);

// CSS del paquete de la página pública (se carga perezosamente en la app; aquí va desde el inicio).
const manifest = JSON.parse(readFileSync(resolve(root, 'dist/.vite/manifest.json'), 'utf8'));
const css = new Set();
const seen = new Set();
(function collect(key) {
  const entry = manifest[key];
  if (!entry || seen.has(key)) return;
  seen.add(key);
  (entry.css ?? []).forEach((c) => css.add(c));
  (entry.imports ?? []).forEach(collect);
})('src/landing/Landing.tsx');
if (css.size === 0) throw new Error('No se encontró el CSS de la página pública en el manifiesto');

const esc = (s) => s.replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;');
const jsonLd = JSON.stringify(structuredData()).replace(/</g, '\\u003c');
const head = `
    <title>${esc(SEO.title)}</title>
    <meta name="description" content="${esc(SEO.description)}" />
    <meta name="robots" content="index, follow, max-image-preview:large" />
    <link rel="canonical" href="__SITE_URL__/" />
    <link rel="alternate" hreflang="es-CO" href="__SITE_URL__/" />
    <meta property="og:type" content="website" />
    <meta property="og:locale" content="es_CO" />
    <meta property="og:site_name" content="Nítido" />
    <meta property="og:title" content="${esc(SEO.title)}" />
    <meta property="og:description" content="${esc(SEO.description)}" />
    <meta property="og:url" content="__SITE_URL__/" />
    <meta property="og:image" content="__SITE_URL__/og.png" />
    <meta property="og:image:width" content="1200" />
    <meta property="og:image:height" content="630" />
    <meta property="og:image:alt" content="Mapa de Colombia con un punto por cada municipio y el texto Factura electrónica para todo Colombia" />
    <meta name="twitter:card" content="summary_large_image" />
    <meta name="twitter:title" content="${esc(SEO.title)}" />
    <meta name="twitter:description" content="${esc(SEO.description)}" />
    <meta name="twitter:image" content="__SITE_URL__/og.png" />
${[...css].map((c) => `    <link rel="stylesheet" href="/${c}" />`).join('\n')}
    <script type="application/ld+json">${jsonLd}</script>
  `;

let html = readFileSync(resolve(root, 'dist/index.html'), 'utf8');
if (!html.includes('<div id="root"></div>')) throw new Error('index.html sin <div id="root"></div>');
html = html
  .replace(/<title>[\s\S]*?<\/title>/, '')
  .replace(/<meta name="description"[^>]*>/, '')
  .replace('</head>', `${head}</head>`)
  .replace('<div id="root"></div>', `<div id="seo-landing">${render()}</div><div id="root"></div>`);

writeFileSync(resolve(root, 'dist/landing.html'), html);
rmSync(resolve(root, 'dist/.vite'), { recursive: true, force: true });
console.log(`landing.html: ${(html.length / 1024).toFixed(0)} KB, ${css.size} hoja(s) de estilo`);
