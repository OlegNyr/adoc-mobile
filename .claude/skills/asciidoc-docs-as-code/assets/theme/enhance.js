/* ==========================================================================
   enhance.js — микро-интеракции для документации.
   Ванильный JS, ноль зависимостей, ноль сетевых запросов.
   Подключение:
     Asciidoctor : docinfo-footer.html -> <script src="theme/enhance.js"></script>
     Antora      : supplemental-ui/js/enhance.js + partials/footer-scripts.hbs
   Работает и с разметкой Asciidoctor, и с разметкой Antora.
   ========================================================================== */
(function () {
  'use strict';

  var STORE_KEY = 'docs-theme';

  /* ---------- 1. Тема: системная по умолчанию, ручной тумблер поверх ------ */

  function applyTheme(mode) {
    if (mode === 'system') {
      document.documentElement.removeAttribute('data-theme');
    } else {
      document.documentElement.setAttribute('data-theme', mode);
    }
  }

  function currentMode() {
    try { return localStorage.getItem(STORE_KEY) || 'system'; } catch (e) { return 'system'; }
  }

  function storeMode(mode) {
    try { localStorage.setItem(STORE_KEY, mode); } catch (e) { /* приватный режим — не беда */ }
  }

  function initTheme() {
    applyTheme(currentMode());

    var btn = document.createElement('button');
    btn.id = 'theme-toggle';
    btn.type = 'button';
    btn.setAttribute('aria-label', 'Переключить тему');
    btn.title = 'Светлая / тёмная / системная';

    var label = { system: '◐', light: '☀', dark: '☾' };
    var order = ['system', 'light', 'dark'];

    function render() {
      var m = currentMode();
      btn.textContent = label[m] || label.system;
      btn.dataset.mode = m;
    }

    btn.addEventListener('click', function () {
      var next = order[(order.indexOf(currentMode()) + 1) % order.length];
      storeMode(next);
      applyTheme(next);
      render();
    });

    render();
    document.body.appendChild(btn);
  }

  /* ---------- 2. Кнопка копирования на блоках кода ------------------------ */

  function initCopyButtons() {
    var blocks = document.querySelectorAll('.listingblock > .content, .literalblock > .content');

    Array.prototype.forEach.call(blocks, function (wrap) {
      var pre = wrap.querySelector('pre');
      if (!pre || wrap.querySelector('.code-copy')) return;

      var btn = document.createElement('button');
      btn.className = 'code-copy';
      btn.type = 'button';
      btn.textContent = 'Копировать';

      btn.addEventListener('click', function () {
        // callout-маркеры <1> в текст копировать не нужно
        var clone = pre.cloneNode(true);
        Array.prototype.forEach.call(clone.querySelectorAll('.conum, b.conum'), function (n) {
          n.parentNode.removeChild(n);
        });
        var text = clone.textContent.replace(/[ \t]+$/gm, '');

        var done = function () {
          btn.textContent = 'Скопировано';
          btn.dataset.done = '1';
          setTimeout(function () {
            btn.textContent = 'Копировать';
            delete btn.dataset.done;
          }, 1600);
        };

        if (navigator.clipboard && navigator.clipboard.writeText) {
          navigator.clipboard.writeText(text).then(done, fallback);
        } else {
          fallback();
        }

        function fallback() {
          var ta = document.createElement('textarea');
          ta.value = text;
          ta.setAttribute('readonly', '');
          ta.style.position = 'fixed';
          ta.style.opacity = '0';
          document.body.appendChild(ta);
          ta.select();
          try { document.execCommand('copy'); done(); } catch (e) { btn.textContent = 'Ctrl+C'; }
          document.body.removeChild(ta);
        }
      });

      wrap.appendChild(btn);
    });
  }

  /* ---------- 3. Якоря на заголовках ------------------------------------- */

  function initAnchors() {
    var heads = document.querySelectorAll('#content h2[id], #content h3[id], #content h4[id], .doc h2[id], .doc h3[id], .doc h4[id]');

    Array.prototype.forEach.call(heads, function (h) {
      if (h.querySelector('a.anchor')) return;
      var a = document.createElement('a');
      a.className = 'anchor';
      a.href = '#' + h.id;
      a.setAttribute('aria-label', 'Ссылка на раздел');
      a.textContent = ' #';
      h.appendChild(a);
    });
  }

  /* ---------- 4. Полоса прогресса чтения --------------------------------- */

  function initProgress() {
    var bar = document.createElement('div');
    bar.id = 'read-progress';
    document.body.appendChild(bar);

    var ticking = false;
    function update() {
      var doc = document.documentElement;
      var max = doc.scrollHeight - doc.clientHeight;
      var pct = max > 0 ? (doc.scrollTop / max) * 100 : 0;
      bar.style.width = pct.toFixed(2) + '%';
      ticking = false;
    }

    window.addEventListener('scroll', function () {
      if (!ticking) { ticking = true; window.requestAnimationFrame(update); }
    }, { passive: true });

    update();
  }

  /* ---------- 5. Подсветка активного пункта оглавления -------------------- */

  function initScrollSpy() {
    var links = document.querySelectorAll('#toc a[href^="#"], .toc a[href^="#"]');
    if (!links.length || !('IntersectionObserver' in window)) return;

    var map = {};
    Array.prototype.forEach.call(links, function (a) {
      var id = decodeURIComponent(a.getAttribute('href').slice(1));
      if (id) map[id] = a;
    });

    var targets = Object.keys(map)
      .map(function (id) { return document.getElementById(id); })
      .filter(Boolean);
    if (!targets.length) return;

    var active = null;
    var obs = new IntersectionObserver(function (entries) {
      entries.forEach(function (e) {
        if (!e.isIntersecting) return;
        var a = map[e.target.id];
        if (!a || a === active) return;
        if (active) active.classList.remove('is-active');
        a.classList.add('is-active');
        active = a;
      });
    }, { rootMargin: '-10% 0px -75% 0px', threshold: 0 });

    targets.forEach(function (t) { obs.observe(t); });
  }

  /* ---------- запуск ------------------------------------------------------ */

  function boot() {
    initTheme();
    initCopyButtons();
    initAnchors();
    initProgress();
    initScrollSpy();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }
})();
