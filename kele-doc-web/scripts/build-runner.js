// build-runner.js - 后台跑 npm run build, 输出到 build.log, 完成后写 .build-done
const { spawn } = require('child_process');
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const LOG = path.join(__dirname, 'build.log');
const DONE = path.join(__dirname, '.build-done');
const PID_FILE = path.join(__dirname, '.build-pid');

try { fs.unlinkSync(DONE); } catch {}

const out = fs.openSync(LOG, 'a');
const err = fs.openSync(LOG, 'a');
const child = spawn('npm', ['run', 'build'], {
  cwd: ROOT,
  detached: true,
  stdio: ['ignore', out, err],
  windowsHide: true,
  shell: true,
});
child.unref();
fs.writeFileSync(PID_FILE, String(child.pid));
console.log('started pid=' + child.pid + ', log=' + LOG);

child.on('exit', (code, signal) => {
  const msg = `\n[build-runner] child exited code=${code} signal=${signal} at ${new Date().toISOString()}\n`;
  fs.appendFileSync(LOG, msg);
  try { fs.writeFileSync(DONE, JSON.stringify({ code, signal, ts: Date.now() })); } catch {}
});
