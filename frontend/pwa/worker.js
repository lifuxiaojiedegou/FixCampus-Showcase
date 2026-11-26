/* global self, caches, URL, Request, fetch, shellBuild */

const scope = new URL(self.registration.scope);
const prefix = 'fixcampus-shell:' + scope.pathname + ':';
const name = prefix + shellBuild.version;
const index = new URL('index.html', scope).href;
const entries = new Map(
  shellBuild.files.map(({ path, integrity }) => [
    new URL(path, scope).href,
    integrity,
  ]),
);

self.addEventListener('install', (event) => {
  const requests = [...entries].map(
    ([url, integrity]) =>
      new Request(url, {
        credentials: 'omit',
        cache: 'reload',
        redirect: 'error',
        integrity,
      }),
  );
  event.waitUntil(caches.open(name).then((cache) => cache.addAll(requests)));
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then(async (keys) => {
      await Promise.all(
        keys
          .filter((key) => key.startsWith(prefix) && key !== name)
          .map((key) => caches.delete(key)),
      );
      await self.clients.claim();
    }),
  );
});

self.addEventListener('fetch', (event) => {
  const { request } = event;
  const url = new URL(request.url);
  if (
    request.method !== 'GET' ||
    url.origin !== scope.origin ||
    !url.pathname.startsWith(scope.pathname)
  ) {
    return;
  }
  const path = url.pathname.slice(scope.pathname.length);
  const isPage =
    request.mode === 'navigate' && !path.split('/').at(-1).includes('.');
  const cacheKey = entries.has(url.href) ? url.href : isPage ? index : null;
  if (cacheKey) {
    event.respondWith(
      caches
        .open(name)
        .then((cache) => cache.match(cacheKey))
        .then((response) => response ?? fetch(request)),
    );
  }
});
