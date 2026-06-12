#!/usr/bin/env node
/**
 * info.js — kele-doc front-end monorepo status snapshot.
 * Lists every sub-project: name, port, build tool, framework, whether node_modules is installed.
 * Pure Node.js stdlib, no deps.
 */
const fs = require('fs');
const path = require('path');

const ROOT = __dirname.replace(/[\\/]scripts$/, '');
const SUBPROJECTS = [
 { key: 'workbench', port:9090, build: 'vite2.8', framework: 'Vue3 + Element Plus', note: 'main entry' },
 { key: 'doc', port:9093, build: 'vite2.8', framework: 'Vue3 + Element Plus', note: 'wangEditor' },
 { key: 'markdown', port:9092, build: 'vite2.8', framework: 'Vue3 + Element Plus', note: 'md-editor-v3' },
 { key: 'note', port:9099, build: 'vite2.8', framework: 'Vue3 + Element Plus', note: 'editor.js' },
 { key: 'sheet', port:9094, build: 'vite2.8', framework: 'Vue3 + Element Plus', note: 'luckysheet' },
 { key: 'ppt', port:9096, build: 'vite5 + TS', framework: 'Vue3', note: 'pptxgenjs (TypeScript strict)' },
 { key: 'bpmn', port:9098, build: 'vite2.8', framework: 'Vue3 + Element Plus', note: 'bpmn-js' },
 { key: 'whiteboard', port:9095, build: 'vite2.9', framework: 'React18 + antd', note: 'excalidraw (异框架)' },
 { key: 'mind-map', port:9091, build: 'vue-cli (webpack4)', framework: 'Vue2 + Element UI', note: '需 --legacy-peer-deps install' },
 { key: 'flowchart', port:9097, build: 'serve + ant', framework: '静态 drawio', note: 'dev 是静态 serve, build 用 Apache Ant' },
];

const c = (s, color) => {
 const codes = { red:31, green:32, yellow:33, blue:34, magenta:35, cyan:36, gray:90, reset:0 };
 return `\x1b[${codes[color] ||0}m${s}\x1b[0m`;
};

console.log(c('\n=== kele-doc front-end monorepo status ===\n', 'cyan'));
console.log(`root: ${ROOT}`);
console.log(`node: ${process.version}`);

console.log(c('\n--- sub-projects ---\n', 'yellow'));
console.log(
 ['name'.padEnd(13), 'port'.padEnd(6), 'framework'.padEnd(22), 'build tool'.padEnd(22), 'deps', 'pkg.json']
 .join(' ')
);
console.log('-'.repeat(100));

let installedCount =0;
for (const sp of SUBPROJECTS) {
 const dir = path.join(ROOT, sp.key);
 const pkgPath = path.join(dir, 'package.json');
 const nmPath = path.join(dir, 'node_modules');
 const hasPkg = fs.existsSync(pkgPath);
 const hasNm = fs.existsSync(nmPath);
 if (hasNm) installedCount++;
 const deps = hasNm ? c('installed', 'green') : c('missing ', 'red');
 const pkg = hasPkg ? c('yes', 'green') : c('NO', 'red');
 console.log(
 [sp.key.padEnd(13), String(sp.port).padEnd(6), sp.framework.padEnd(22), sp.build.padEnd(22), deps, pkg]
 .join(' ')
 );
}

console.log(c(`\n${installedCount}/${SUBPROJECTS.length} sub-projects have node_modules installed.`, installedCount === SUBPROJECTS.length ? 'green' : 'yellow'));

console.log(c('\n--- quick commands ---\n', 'yellow'));
console.log(' install everything (first time): npm run install:all');
console.log(' start ALL dev servers (one cmd): npm run dev (Ctrl+C to stop all)');
console.log(' start dev without flowchart: npm run dev:no-fc');
console.log(' start dev (Vue3 + React only): npm run dev:modern');
console.log(' build ALL sub-projects (serial): npm run build');
console.log(' build ALL (parallel, faster): npm run build:parallel');
console.log(' build only modern8 (skip MM/FC): npm run build:modern');
console.log(' clean all dist + .vite cache: npm run clean');
console.log('\n single sub-project (replace X): npm run dev:X / npm run build:X / npm run install:X');
console.log(' X ∈ {workbench, doc, markdown, note, sheet, ppt, bpmn, whiteboard, mind-map, flowchart}');

console.log(c('\n--- port map ---\n', 'yellow'));
SUBPROJECTS.forEach(sp => console.log(` http://localhost:${sp.port}/ ${sp.key.padEnd(13)} (${sp.note})`));
console.log();
