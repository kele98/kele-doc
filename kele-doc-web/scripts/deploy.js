#!/usr/bin/env node
/**
 * deploy.js - kele-doc front-end monorepo deploy bundler.
 *
 * Collects 10 sub-project build outputs, then flattens them into a single
 * deploy root that matches the production nginx layout.
 *
 * Source layout (after `npm run build`):
 *   workbench/dist/*     ->  deploy root (flattened)
 *   doc/dist/*           ->  deploy root/doc/
 *   markdown/dist/*      ->  deploy root/markdown/
 *   note/dist/*          ->  deploy root/note/
 *   sheet/dist/*         ->  deploy root/sheet/
 *   ppt/dist/*           ->  deploy root/ppt/
 *   bpmn/dist/*          ->  deploy root/bpmn/
 *   whiteboard/dist/*    ->  deploy root/whiteboard/
 *   mind-map/dist/*      ->  deploy root/mind-map/
 *   flowchart/dist/*     ->  deploy root/flowchart/
 *
 * Usage:
 *   node ./scripts/deploy.js <target-dir> [--clean] [--dry-run]
 *
 *   --clean    wipe target dir before copy (rm -rf <target>)
 *   --dry-run  show what would be copied, don't actually copy
 *
 * Why not rsync: cross-platform (Win/Linux), zero deps, pure stdlib.
 */
const fs = require('fs');
const path = require('path');

const ROOT = __dirname.replace(/[\\/]scripts$/, '');

// sub-project -> source dir (relative to monorepo root)
const SUBPROJECTS = [
  { key: 'workbench',  src: 'workbench/dist',                 dest: ''          },
  { key: 'doc',        src: 'doc/dist',                       dest: 'doc'       },
  { key: 'markdown',   src: 'markdown/dist',                  dest: 'markdown'  },
  { key: 'note',       src: 'note/dist',                      dest: 'note'      },
  { key: 'sheet',      src: 'sheet/dist',                     dest: 'sheet'     },
  { key: 'ppt',        src: 'ppt/dist',                       dest: 'ppt'       },
  { key: 'bpmn',       src: 'bpmn/dist',                      dest: 'bpmn'      },
  { key: 'whiteboard', src: 'whiteboard/dist',                dest: 'whiteboard'},
  { key: 'mind-map',   src: 'mind-map/dist',                  dest: 'mind-map'  },
  { key: 'flowchart',  src: 'flowchart/dist',                  dest: 'flowchart' },
];

const c = (s, color) => {
  const codes = { red: 31, green: 32, yellow: 33, blue: 34, magenta: 35, cyan: 36, gray: 90, reset: 0 };
  return `\x1b[${codes[color] || 0}m${s}\x1b[0m`;
};

// ---- args ----
const args = process.argv.slice(2);
const dryRun = args.includes('--dry-run');
const clean  = args.includes('--clean');
const target = args.find(a => !a.startsWith('--'));
if (!target) {
  console.error('Usage: node ./scripts/deploy.js <target-dir> [--clean] [--dry-run]');
  process.exit(1);
}
const targetAbs = path.resolve(target);

// ---- helpers ----
function exists(p) { try { fs.accessSync(p); return true; } catch { return false; } }
function dirSize(p) {
  let total = 0;
  const walk = (d) => {
    for (const e of fs.readdirSync(d, { withFileTypes: true })) {
      const fp = path.join(d, e.name);
      if (e.isDirectory()) walk(fp);
      else if (e.isFile()) total += fs.statSync(fp).size;
    }
  };
  if (!exists(p)) return 0;
  walk(p);
  return total;
}
function fmtBytes(n) {
  if (n < 1024) return n + 'B';
  if (n < 1024 * 1024) return (n / 1024).toFixed(1) + 'KB';
  return (n / 1024 / 1024).toFixed(2) + 'MB';
}
function copyDir(src, dest) {
  fs.mkdirSync(dest, { recursive: true });
  for (const e of fs.readdirSync(src, { withFileTypes: true })) {
    const sp = path.join(src, e.name);
    const dp = path.join(dest, e.name);
    if (e.isDirectory()) copyDir(sp, dp);
    else fs.copyFileSync(sp, dp);
  }
}
function rmrf(p) {
  if (!exists(p)) return;
  fs.rmSync(p, { recursive: true, force: true });
}

// ---- preflight ----
console.log(c('\n=== kele-doc deploy bundler ===\n', 'cyan'));
console.log(`monorepo : ${ROOT}`);
console.log(`target   : ${targetAbs}`);
console.log(`mode     : ${dryRun ? 'DRY-RUN' : clean ? 'CLEAN+COPY' : 'COPY-ONLY'}`);

const missing = SUBPROJECTS.filter(sp => !exists(path.join(ROOT, sp.src)));
if (missing.length) {
  console.error(c(`\nMissing ${missing.length} source dir(s):`, 'red'));
  for (const m of missing) console.error(c(`  - ${m.key}: ${m.src}  (run: npm run build:${m.key})`, 'red'));
  process.exit(1);
}

// ---- clean target ----
if (clean && !dryRun) {
  if (exists(targetAbs)) {
    console.log(c(`\n[clean] removing ${targetAbs}`, 'yellow'));
    rmrf(targetAbs);
  }
  fs.mkdirSync(targetAbs, { recursive: true });
} else if (!exists(targetAbs)) {
  if (dryRun) {
    console.log(c(`\n[dry-run] would create ${targetAbs}`, 'gray'));
  } else {
    fs.mkdirSync(targetAbs, { recursive: true });
  }
}

// ---- copy loop ----
console.log(c('\n--- copy ---\n', 'yellow'));
let grandTotal = 0;
for (const sp of SUBPROJECTS) {
  const src  = path.join(ROOT, sp.src);
  const dest = path.join(targetAbs, sp.dest);
  const size = dirSize(src);
  grandTotal += size;

  if (dryRun) {
    console.log(`  ${sp.key.padEnd(12)} ${fmtBytes(size).padStart(8)}  ${sp.src}  ->  ${sp.dest || '(root)'}`);
  } else {
    try {
      copyDir(src, dest);
      console.log(`  ${c(sp.key.padEnd(12), 'green')} ${fmtBytes(size).padStart(8)}  ${sp.src}  ->  ${sp.dest || '(root)'}`);
    } catch (e) {
      console.error(`  ${c(sp.key.padEnd(12), 'red')}  FAILED: ${e.message}`);
      process.exit(1);
    }
  }
}

// ---- postflight ----
console.log(c(`\ntotal: ${fmtBytes(grandTotal)} across ${SUBPROJECTS.length} sub-projects`, 'cyan'));

// gentle reminder: flowchart build falls back to copying webapp when ant is unavailable
console.log(c('\nnote: flowchart build falls back to src/main/webapp/ when Apache Ant is not installed.', 'yellow'));
console.log(c('      for minified output, install ant and run: cd flowchart/etc/build && ant', 'yellow'));
console.log(c(`\n${dryRun ? 'dry-run: ' : ''}deployed to ${targetAbs}`, dryRun ? 'gray' : 'green'));

// list final top-level for sanity
if (!dryRun) {
  console.log(c('\n--- deploy root ---\n', 'yellow'));
  for (const e of fs.readdirSync(targetAbs, { withFileTypes: true })) {
    const mark = e.isDirectory() ? '/' : '';
    console.log(`  ${e.name}${mark}`);
  }
}
