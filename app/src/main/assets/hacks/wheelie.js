/* 翘头狂飙 / WHEELIE RIOT
 * XhsSpy 注入脚本。所有 hack 有 on() / off()。
 * 玩家对象只能通过 ranking() 拿，闭包里的东西全摸不到。
 */
(function () {
  if (window.__WRH && window.__WRH.__v === 1) return 'already';

  var H = {}; H.__v = 1;
  var timers = {};
  function every(id, ms, fn) { if (timers[id]) clearInterval(timers[id]); timers[id] = setInterval(fn, ms); }
  function stop(id) { if (timers[id]) { clearInterval(timers[id]); timers[id] = null; } }

  /* 拿玩家。菜单里 isPlayer 全是 false，必须真正起跑。 */
  H.me = function () {
    try {
      var rk = ranking();
      for (var i = 0; i < rk.length; i++) if (rk[i].isPlayer) return rk[i];
    } catch (e) {}
    return null;
  };

  /* ---------- 无限氮气 + 无敌 ---------- */
  H.god = {
    on: function () {
      window.__wrGod = true;
      every('god', 50, function () {
        if (!window.__wrGod) return;
        var p = H.me(); if (!p) return;
        p.nitro = 999;
        p.nitroOn = true;
        p.boosting = true;
        p.invT = 999;
        p.crashed = false;
        p.crashT = 0;
        p.spinT = 0;
      });
      return 'on';
    },
    off: function () { window.__wrGod = false; stop('god'); return 'off'; }
  };

  /* ---------- 速度锁定 ---------- */
  H.speed = {
    on: function (v) {
      window.__wrSpeed = v || 300;
      window.__wrFastOn = true;
      every('speed', 60, function () {
        if (!window.__wrFastOn) return;
        var p = H.me(); if (!p) return;
        if (p.speed > 1 && p.speed < window.__wrSpeed) p.speed = window.__wrSpeed;
      });
      return 'on @' + window.__wrSpeed;
    },
    set: function (v) { window.__wrSpeed = v; return v; },
    off: function () { window.__wrFastOn = false; stop('speed'); return 'off'; }
  };

  /* ---------- 去掉空气墙 ---------- */
  H.nowall = {
    on: function () {
      if (!window.__wrCwHooked) {
        window.__wrCwHooked = 1;
        window.__wrCwOrig = window.collideWorld;
        window.collideWorld = function (r, dt) {
          if (window.__wrNoWall && r && r.isPlayer) return;
          return window.__wrCwOrig.apply(this, arguments);
        };
      }
      window.__wrNoWall = true;
      return 'on';
    },
    off: function () { window.__wrNoWall = false; return 'off'; }
  };

  /* ---------- 俯视镜头（只改 r.scale，不碰模型） ---------- */
  H.cam = {
    on: function (v) {
      window.__wrCamScale = v || 4;
      every('cam', 200, function () {
        var p = H.me(); if (!p) return;
        if (p.scale !== window.__wrCamScale) p.scale = window.__wrCamScale;
      });
      return 'on @' + window.__wrCamScale;
    },
    set: function (v) { window.__wrCamScale = v; return v; },
    off: function () { stop('cam'); window.__wrCamScale = 1; var p = H.me(); if (p) p.scale = 1; return 'off'; }
  };

  /* ---------- 模型放大（一次性，绝不累乘） ---------- */
  H.model = {
    on: function (v) {
      window.__wrModel = v || 3;
      var p = H.me();
      if (!p || !p.m || !p.m.root) return 'no model yet';
      p.m.root.scale.setScalar(window.__wrModel);
      return 'on @' + window.__wrModel;
    },
    off: function () {
      var p = H.me();
      if (p && p.m && p.m.root) p.m.root.scale.setScalar(1);
      return 'off';
    }
  };

  /* ---------- 一键全开，冲榜用 ---------- */
  H.rank = {
    on: function () {
      H.god.on();
      H.speed.on(300);
      H.nowall.on();
      H.cam.on(4);
      H.model.on(3);
      return 'all on';
    },
    off: function () {
      H.god.off(); H.speed.off(); H.nowall.off(); H.cam.off(); H.model.off();
      return 'all off';
    }
  };

  /* ---------- 急救 ---------- */
  H.panic = function () {
    for (var k in timers) { clearInterval(timers[k]); timers[k] = null; }
    window.__wrGod = false; window.__wrFastOn = false;
    window.__wrNoWall = false; window.__wrCamScale = 1;
    var p = H.me();
    if (p) { p.scale = 1; if (p.m && p.m.root) p.m.root.scale.setScalar(1); }
    return 'panic: all off';
  };

  /* ---------- 状态 ---------- */
  H.status = function () {
    var p = H.me();
    return JSON.stringify({
      player: !!p,
      speed: p ? Math.round(p.speed) : 0,
      lap: p ? p.lap : -1,
      nitro: p ? p.nitro : 0,
      invT: p ? p.invT : 0,
      camScale: p ? p.scale : 0,
      modelScale: (p && p.m && p.m.root) ? p.m.root.scale.x : 0
    });
  };

  window.__WRH = H;
  return 'ready';
})();
