#!/usr/bin/env python3
"""Build preview.html: mood.html inside a phone frame, with a fake MoodNative bridge.

The fake bridge stands in for the Android side (Health Connect, sync folder,
reminders, share target), so the app runs as if it were inside the WebView.
Every bridge call shows up in the log next to the phone.

    python3 tools/make-preview.py   ->  preview.html (git-ignored)
"""
import html
import pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent

# Injected into <head> of mood.html, before the app's own scripts run,
# so `typeof window.MoodNative !== 'undefined'` is true and NATIVE mode kicks in.
MOCK = r"""
<script>
(function(){
  const log = (m) => { try { parent.postMessage({ moodLog: m }, '*'); } catch(_){} };
  const later = (fn) => setTimeout(fn, 400);
  function fakeSteps(days){
    const out = [], now = new Date();
    for (let i = days; i >= 0; i--){
      const d = new Date(now); d.setDate(now.getDate() - i);
      const walk = Math.random() < .5 ? Math.round(15 + Math.random() * 50) : 0;
      out.push({ date: d.toISOString().slice(0,10), steps: Math.round(2500 + Math.random() * 9000),
                 walkMin: walk, walkKm: Math.round(walk * .08 * 10) / 10 });
    }
    return out;
  }
  function fakeExtra(){
    const sleep = [], water = [], now = new Date();
    for (let i = 14; i >= 0; i--){
      const d = new Date(now); d.setDate(now.getDate() - i);
      const date = d.toISOString().slice(0,10);
      sleep.push({ date, min: Math.round(330 + Math.random() * 180) });
      water.push({ date, ml: Math.round(800 + Math.random() * 1600) });
    }
    return { sleep, water };
  }
  function health(days){
    later(() => {
      window.onNativeSteps && window.onNativeSteps(JSON.stringify(fakeSteps(days)));
      window.onNativeHealthExtra && window.onNativeHealthExtra(JSON.stringify(fakeExtra()));
    });
  }
  window.MoodNative = {
    autoSync(){ log('autoSync() → fake 35 days of health data'); health(35); },
    requestSteps(days){ log('requestSteps(' + days + ') → fake health data'); health(days); },
    pickSyncFolder(){ log('pickSyncFolder() → "Drive/mood"'); later(() => window.onNativeFolder('Drive/mood')); },
    writeSyncFile(name, content){ log('writeSyncFile(' + name + ', ' + content.length + ' chars)'); },
    setTheme(dark, color){ log('setTheme(' + dark + ', ' + color + ')'); parent.postMessage({ moodBar: color, moodDark: dark }, '*'); },
    writeWater(ml){ log('writeWater(' + ml + ' ml) → health connect'); },
    setReminders(json){ log('setReminders(' + json + ')'); },
    consumeShare(){ log('consumeShare()'); },
  };
})();
</script>
"""

PAGE = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>mood phone preview</title>
<style>
  :root { --bg:#1e1d1b; --panel:#2a2825; --ink:#eee6d6; --muted:#9a917f; --line:#3b3833; --accent:#DE5426; }
  * { box-sizing: border-box; }
  body { margin:0; min-height:100vh; background:var(--bg); color:var(--ink);
         font:13px/1.45 ui-monospace, Menlo, Consolas, monospace;
         display:flex; gap:24px; justify-content:center; align-items:flex-start; padding:24px 16px; flex-wrap:wrap; }
  .phone { width:390px; height:844px; max-width:100%; border-radius:46px; background:#0b0b0b;
           padding:12px; box-shadow:0 0 0 2px #444, 0 20px 60px rgba(0,0,0,.5); flex-shrink:0; }
  .screen { width:100%; height:100%; border-radius:36px; overflow:hidden; display:flex; flex-direction:column; background:#EDE4CF; }
  .bar { height:34px; display:flex; align-items:center; justify-content:space-between; padding:0 26px;
         font-size:12px; font-weight:600; background:#EDE4CF; color:#1B1712; flex-shrink:0; }
  .nav { height:22px; display:flex; align-items:center; justify-content:center; background:#EDE4CF; flex-shrink:0; }
  .nav i { width:110px; height:4px; border-radius:2px; background:#1B1712; opacity:.6; }
  iframe { flex:1; width:100%; border:0; background:#EDE4CF; }
  .side { width:340px; max-width:100%; display:flex; flex-direction:column; gap:12px; }
  .card { background:var(--panel); border:1px solid var(--line); border-radius:10px; padding:12px; }
  h2 { margin:0 0 8px; font-size:12px; text-transform:uppercase; letter-spacing:.08em; color:var(--muted); font-weight:600; }
  button { font:inherit; color:var(--ink); background:#35322d; border:1px solid var(--line); border-radius:6px;
           padding:7px 10px; cursor:pointer; margin:0 6px 6px 0; }
  button:hover { border-color:var(--accent); }
  textarea { width:100%; height:64px; font:inherit; color:var(--ink); background:#1b1a18; border:1px solid var(--line);
             border-radius:6px; padding:6px; resize:vertical; }
  #log { height:300px; overflow:auto; white-space:pre-wrap; word-break:break-word; font-size:12px; color:#cfc6b4; }
  #log div { border-bottom:1px solid var(--line); padding:3px 0; }
  .note { color:var(--muted); font-size:12px; margin:0; }
</style>
</head>
<body>
  <div class="phone"><div class="screen">
    <div class="bar" id="bar"><span id="clock"></span><span>▾ ▴ ▮</span></div>
    <iframe id="app" srcdoc="__SRCDOC__"></iframe>
    <div class="nav" id="nav"><i></i></div>
  </div></div>

  <div class="side">
    <div class="card">
      <h2>controls</h2>
      <button id="reload">↻ reopen app</button>
      <button id="back">◁ back button</button>
      <button id="reset">⌫ wipe app data</button>
    </div>
    <div class="card">
      <h2>share into mood</h2>
      <textarea id="share">Lemon pasta — 200g spaghetti, 1 lemon, parmesan, butter, black pepper.</textarea>
      <button id="send">share text ▸</button>
    </div>
    <div class="card">
      <h2>android bridge log</h2>
      <div id="log"></div>
    </div>
    <p class="note">The Android side is faked: Health Connect returns random steps, sleep and water;
       the sync folder, reminders and water writes are only logged. Data is kept in this panel's storage.</p>
  </div>

<script>
  const app = document.getElementById('app'), logEl = document.getElementById('log');
  const tick = () => document.getElementById('clock').textContent =
    new Date().toLocaleTimeString([], { hour:'2-digit', minute:'2-digit' });
  tick(); setInterval(tick, 10000);

  function log(m){
    const d = document.createElement('div');
    d.textContent = new Date().toLocaleTimeString() + '  ' + m;
    logEl.prepend(d);
  }
  addEventListener('message', (e) => {
    if (e.data?.moodLog) log(e.data.moodLog);
    if (e.data?.moodBar){
      for (const id of ['bar', 'nav']) document.getElementById(id).style.background = e.data.moodBar;
      document.getElementById('bar').style.color = e.data.moodDark ? '#eee' : '#1B1712';
      document.querySelector('.nav i').style.background = e.data.moodDark ? '#eee' : '#1B1712';
    }
  });
  const srcdoc = app.getAttribute('srcdoc');
  document.getElementById('reload').onclick = () => { app.srcdoc = srcdoc; log('— app reopened —'); };
  document.getElementById('back').onclick = () => {
    // the real app has no back handling yet: android would close it
    log('back pressed → app has no back handling, android would close it');
    app.srcdoc = srcdoc;
  };
  document.getElementById('reset').onclick = () => {
    try { app.contentWindow.localStorage.clear(); } catch(_){}
    app.srcdoc = srcdoc; log('— app data wiped —');
  };
  document.getElementById('send').onclick = () => {
    const text = document.getElementById('share').value;
    const w = app.contentWindow;
    if (w.onNativeShare){ w.onNativeShare(JSON.stringify({ text })); log('shared text into app'); }
    else log('app has no onNativeShare handler');
  };
</script>
</body>
</html>
"""


def main():
    src = (ROOT / "mood.html").read_text(encoding="utf-8")
    src = src.replace("<head>", "<head>" + MOCK, 1)
    out = PAGE.replace("__SRCDOC__", html.escape(src, quote=True))
    (ROOT / "preview.html").write_text(out, encoding="utf-8")
    print("wrote", ROOT / "preview.html")


if __name__ == "__main__":
    main()
