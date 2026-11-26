import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

function files(dir) {
  return readdirSync(dir, { withFileTypes: true }).flatMap((e) =>
    e.isDirectory() ? files(join(dir, e.name)) : [join(dir, e.name)],
  );
}
const forbidden =
  /\b(fetch|XMLHttpRequest|WebSocket|EventSource|sendBeacon|localStorage|sessionStorage)\b|document\s*\.\s*cookie|https?:\/\/|wss?:\/\/|\bimport\s*\(/;
const failures = files('src')
  .filter(
    (f) =>
      !/\.test\.[tj]sx?$/.test(f) &&
      !f.includes('src\\test\\') &&
      !f.includes('src/test/'),
  )
  .filter(
    (f) =>
      !f.replaceAll('\\', '/').endsWith('src/shared/api/rest/studentClient.ts'),
  )
  .filter((f) => forbidden.test(readFileSync(f, 'utf8')));
const config = readFileSync('vite.config.ts', 'utf8');
if (/host:\s*(true|['"](?:0\.0\.0\.0|::)['"])/.test(config))
  failures.push('vite.config.ts');
// M4.2 has one explicit same-origin transport and one fixed, opt-in loopback proxy.
const transport = readFileSync('src/shared/api/rest/studentClient.ts', 'utf8');
if (
  /\b(XMLHttpRequest|WebSocket|EventSource|sendBeacon|localStorage|sessionStorage)\b|document\s*\.\s*cookie|https?:\/\//.test(
    transport,
  ) ||
  (transport.match(/\bfetch\s*\(/g) ?? []).length !== 1 ||
  !transport.includes('fetch(`/api/v1${path}`') ||
  !transport.includes("credentials: 'same-origin'") ||
  !transport.includes("redirect: 'error'")
)
  failures.push('studentClient transport boundary');
if (
  (config.match(/\btarget\s*:/g) ?? []).length !== 1 ||
  !config.includes("target: 'http://127.0.0.1:18080'") ||
  !config.includes("mode === 'web-slice'") ||
  !config.includes("'/api/v1'")
)
  failures.push('Vite opt-in proxy boundary');
const html = readFileSync('index.html', 'utf8');
if (/https?:\/\/|\/\//.test(html)) failures.push('index.html');
const worker = readFileSync('pwa/worker.js', 'utf8');
if (
  /\b(XMLHttpRequest|WebSocket|EventSource|sendBeacon|importScripts|indexedDB|localStorage|sessionStorage|skipWaiting)\b|https?:\/\/|\.put\s*\(/.test(
    worker,
  ) ||
  (worker.match(/\bfetch\s*\(/g) ?? []).length !== 1 ||
  !worker.includes('response ?? fetch(request)') ||
  !worker.includes("credentials: 'omit'") ||
  !worker.includes("redirect: 'error'") ||
  !worker.includes('cache.addAll(requests)') ||
  !worker.includes("request.method !== 'GET'") ||
  !worker.includes('url.origin !== scope.origin') ||
  !worker.includes('!url.pathname.startsWith(scope.pathname)')
)
  failures.push('PWA static cache boundary');
console.log(
  JSON.stringify(
    {
      scope:
        'Mock has no business transport; one relative REST adapter, opt-in loopback proxy and a static-only PWA worker; runtime evidence separately required',
      status: failures.length ? 'FAIL' : 'PASS',
      failures,
    },
    null,
    2,
  ),
);
process.exitCode = failures.length ? 1 : 0;
