/* mood — offline for the installed pc app.
   the app itself (html, fonts, libraries, icons) is kept in a cache: the newest version is
   fetched when online, the cached one is used when not. api calls are never cached. */
const CACHE = 'mood-app-v1';
const CORE = ['./', './mood.html', './manifest.webmanifest', './fonts/fonts.css', './vendor/qrcode.min.js', './vendor/three.min.js',
  './icons/icon-192.png', './icons/icon-512.png'];

self.addEventListener('install', e => {
  e.waitUntil(caches.open(CACHE).then(c => c.addAll(CORE)).then(() => self.skipWaiting()));
});
self.addEventListener('activate', e => {
  e.waitUntil(caches.keys().then(keys => Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k)))).then(() => self.clients.claim()));
});
self.addEventListener('fetch', e => {
  const url = new URL(e.request.url);
  if (e.request.method !== 'GET' || url.origin !== location.origin) return;   // apis, covers: straight to the network
  // the app's own files: network first (so updates arrive), cache when offline
  e.respondWith(
    fetch(e.request).then(r => {
      if (r.ok) { const copy = r.clone(); caches.open(CACHE).then(c => c.put(e.request, copy)); }
      return r;
    }).catch(() => caches.match(e.request).then(m => m || caches.match('./mood.html')))
  );
});
