#!/usr/bin/env node
/**
 * flowchart build script.
 * Tries Apache Ant first (JS minification via Closure Compiler);
 * falls back to copying src/main/webapp and patching index.html
 * to load individual source files instead of non-existent minified bundles.
 */
const fs = require('fs');
const path = require('path');
const { execSync } = require('child_process');

const ROOT = __dirname;
const SRC = path.join(ROOT, 'src/main/webapp');
const DST = path.join(ROOT, 'dist');

function copyDir(src, dest) {
  fs.mkdirSync(dest, { recursive: true });
  for (const e of fs.readdirSync(src, { withFileTypes: true })) {
    const s = path.join(src, e.name);
    const d = path.join(dest, e.name);
    if (e.isDirectory()) copyDir(s, d);
    else fs.copyFileSync(s, d);
  }
}

function patchIndexHtml() {
  const htmlPath = path.join(DST, 'index.html');
  let html = fs.readFileSync(htmlPath, 'utf-8');

  // Patch 1: Force dev mode script loading block
  html = html.replace(
    "if (urlParams['dev'] == '1') {",
    "if (true) { // forced dev mode: Ant not available, no minified bundles"
  );

  // Patch 2: Set urlParams['dev'] = '1' so mxscript() uses document.write
  // (synchronous loading). Without this, mxscript sees urlParams['dev'] != '1'
  // and falls back to createElement (async), breaking script load order.
  // Insert right before the first mxscript call in the dev block.
  html = html.replace(
    "// Changes paths for local development environment",
    "// Changes paths for local development environment\n" +
    "      urlParams['dev'] = '1'; // forced by build: ensure mxscript uses document.write"
  );

  fs.writeFileSync(htmlPath, html);
}

// try ant first
let antOk = false;
try {
  execSync('ant', { cwd: path.join(ROOT, 'etc/build'), stdio: 'pipe' });
  antOk = true;
} catch (_) { /* ant not available */ }

// 清理旧的 dist 目录，避免残留文件被部署
if (fs.existsSync(DST)) fs.rmSync(DST, { recursive: true, force: true });

copyDir(SRC, DST);

if (antOk) {
  console.log('flowchart: ant build succeeded');
} else {
  patchIndexHtml();
  console.log('flowchart: ant not found, copied src/main/webapp with dev-mode patch');
}
