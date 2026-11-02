/* Floating chat widget. Usage: <script src="https://HOST/static/embed.js" data-token="ast_..." defer></script> */
(function () {
  var s = document.currentScript; if (!s) return;
  var token = s.getAttribute('data-token'), origin = new URL(s.src).origin;
  var btn = document.createElement('button'); btn.textContent = '💬'; btn.setAttribute('aria-label', 'Open assistant');
  btn.style.cssText = 'position:fixed;right:20px;bottom:20px;width:56px;height:56px;border-radius:50%;border:0;background:#3b5bdb;color:#fff;font-size:24px;cursor:pointer;box-shadow:0 4px 14px rgba(0,0,0,.3);z-index:2147483646';
  var frame = document.createElement('iframe'); frame.src = origin + '/a/' + encodeURIComponent(token); frame.title = 'Assistant';
  frame.style.cssText = 'position:fixed;right:20px;bottom:88px;width:380px;max-width:calc(100vw - 40px);height:560px;max-height:calc(100vh - 110px);border:1px solid #ddd;border-radius:14px;box-shadow:0 8px 30px rgba(0,0,0,.25);display:none;z-index:2147483647;background:#fff';
  btn.onclick = function () { frame.style.display = frame.style.display === 'none' ? 'block' : 'none'; };
  document.body.appendChild(frame); document.body.appendChild(btn);
})();
