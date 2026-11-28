/* 墨线突击 / INK STRIKE
 * XhsSpy 注入脚本。每个 hack 有 on() / off()。
 */
(function(){
  if (window.__XHS_HACKS && window.__XHS_HACKS.__v === 2) return 'already';

  var H = {}; H.__v = 2;
  var timers = {};
  function every(id, ms, fn){ if (timers[id]) clearInterval(timers[id]); timers[id] = setInterval(fn, ms); }
  function stop(id){ if (timers[id]) { clearInterval(timers[id]); timers[id] = null; } }

  /* ================= 实体捕获 ================= */
  H.capture = { on: function(){
    if (window.__capHooked) return;
    window.__capHooked = 1;
    window.__ents = window.__ents || [];
    var h = window.hurt;
    window.__hurtOrig = window.__hurtOrig || h;
    window.hurt = function(t, e, o, s, a, n, i){
      try {
        if (t && t.isPlayer) {
          window.__player = t;
          if (window.__godOn) { t.hp = 100; t.armor = 100; t.alive = true; return; }
        }
        if (t && t.pos && !t.isPlayer && window.__ents.indexOf(t) < 0) window.__ents.push(t);
        if (o && o.pos && window.__ents.indexOf(o) < 0) window.__ents.push(o);
      } catch(x) {}
      return window.__hurtOrig.apply(this, arguments);
    };
    var as = window.animSoldier;
    if (as) window.animSoldier = function(t, e){
      try { if (t && t.pos && window.__ents.indexOf(t) < 0) window.__ents.push(t); } catch(x) {}
      return as.apply(this, arguments);
    };
    var ub = window.updateBot;
    if (ub) window.updateBot = function(t, e, o){
      try { if (t && t.pos && window.__ents.indexOf(t) < 0) window.__ents.push(t); } catch(x) {}
      return ub.apply(this, arguments);
    };
  }};

  /* ================= 基础功能 ================= */
  H.god = {
    on: function(){ H.capture.on(); window.__godOn = true;
      every('god', 150, function(){ var p = window.__player; if (p) { p.hp = 100; p.armor = 100; p.alive = true; } });
      return 'on'; },
    off: function(){ window.__godOn = false; stop('god'); return 'off'; }
  };

  H.money = {
    on: function(){ H.capture.on();
      every('money', 150, function(){ var p = window.__player; if (p) p.money = 999999; });
      return 'on'; },
    off: function(){ stop('money'); return 'off'; }
  };

  H.ammo = {
    on: function(){ H.capture.on();
      every('ammo', 150, function(){ var p = window.__player; if (!p) return;
        if (p.ammo) for (var k in p.ammo) { var a = p.ammo[k]; if (a) { a.mag = 999; a.res = 9999; } }
        p.mag = 999; });
      return 'on'; },
    off: function(){ stop('ammo'); return 'off'; }
  };

  /* ================= ESP ================= */
  H.esp = {
    on: function(){
      H.capture.on();
      if (window.__espOn) return 'already';
      window.__espOn = true;
      if (typeof THREE === 'undefined' || typeof camera === 'undefined') {
        window.__espOn = false; return 'no THREE';
      }
      var box = document.getElementById('__esp');
      if (!box) {
        box = document.createElement('div');
        box.id = '__esp';
        box.style.cssText = 'position:fixed;left:0;top:0;width:100%;height:100%;pointer-events:none;z-index:99997';
        document.body.appendChild(box);
      }
      every('esp', 50, function(){
        var ents = window.__ents || [], me = window.__player;
        var html = '', w = window.innerWidth, h = window.innerHeight;
        for (var i = 0; i < ents.length; i++) {
          var t = ents[i];
          if (!t.alive || !t.pos) continue;
          var isMe = (t === me) || t.isPlayer === true;
          if (!isMe && t.team === 'blue') continue;
          var vf = new THREE.Vector3(t.pos.x, t.pos.y, t.pos.z); vf.project(camera);
          var vh = new THREE.Vector3(t.pos.x, t.pos.y + (t.hgt || 1.8), t.pos.z); vh.project(camera);
          if (vf.z > 1 || vh.z > 1) continue;
          var sx = (vf.x*0.5+0.5)*w, syF = (-vf.y*0.5+0.5)*h, syH = (-vh.y*0.5+0.5)*h;
          if (sx < -140 || sx > w+140 || syF < -140 || syF > h+140) continue;
          var bh = Math.max(18, syF - syH), bw = Math.max(10, Math.min(64, bh*0.42));
          var x0 = sx - bw/2, y0 = syH, col = isMe ? '#25e07a' : '#ff3355';
          var d = camera.position.distanceTo(t.pos);
          html += '<div style="position:absolute;left:'+x0+'px;top:'+y0+'px;width:'+bw+'px;height:'+bh
                + 'px;border:2px solid '+col+';border-radius:2px"></div>';
          html += '<div style="position:absolute;left:'+x0+'px;top:'+(y0-6)+'px;width:'+bw
                + 'px;height:3px;background:#200"><div style="height:100%;width:'
                + Math.max(0, Math.min(100, t.hp)) + '%;background:'+col+'"></div></div>';
          html += '<div style="position:absolute;left:'+(x0-22)+'px;top:'+(y0+bh+1)+'px;width:'
                + (bw+44)+'px;text-align:center;color:'+col
                + ';font:bold 10px monospace;text-shadow:0 0 3px #000">'
                + (isMe?'我':'敌')+' '+Math.round(d)+'m</div>';
        }
        box.innerHTML = html;
      });
      return 'on';
    },
    off: function(){
      window.__espOn = false; stop('esp');
      var b = document.getElementById('__esp'); if (b) b.innerHTML = '';
      return 'off';
    }
  };

  /* ================= 定身 ================= */
  H.freeze = {
    on: function(){
      H.capture.on();
      window.__freezeOn = true;
      var ents = window.__ents || [];
      for (var i = 0; i < ents.length; i++) {
        var t = ents[i];
        if (t.team !== 'blue' && !t.__frz) t.__frz = {x:t.pos.x, y:t.pos.y, z:t.pos.z};
      }
      every('freeze', 16, function(){
        var arr = window.__ents || [];
        for (var j = 0; j < arr.length; j++) {
          var b = arr[j];
          if (b.team === 'blue' || !b.__frz) continue;
          b.pos.x = b.__frz.x; b.pos.y = b.__frz.y; b.pos.z = b.__frz.z;
          if (b.vel) b.vel.set(0,0,0);
        }
      });
      return 'on';
    },
    off: function(){ window.__freezeOn = false; stop('freeze'); return 'off'; }
  };

  /* ================= 自动命中 ================= */
  H.autohit = {
    on: function(){
      H.capture.on();
      window.__lastShotSeen = null;
      every('autohit', 25, function(){
        var p = window.__player; if (!p) return;
        var ls = p.lastShot;
        if (window.__lastShotSeen === null) { window.__lastShotSeen = ls; return; }
        if (ls === window.__lastShotSeen) return;
        window.__lastShotSeen = ls;
        var best = null, bd = 1e18, arr = window.__ents || [];
        for (var i = 0; i < arr.length; i++) {
          var t = arr[i];
          if (t.team === 'blue' || !t.alive || !t.pos || t.isPlayer) continue;
          var dx = t.pos.x-p.pos.x, dy = t.pos.y-p.pos.y, dz = t.pos.z-p.pos.z;
          var d2 = dx*dx+dy*dy+dz*dz;
          if (d2 < bd) { bd = d2; best = t; }
        }
        if (!best || !window.__hurtOrig) return;
        try { window.__hurtOrig(best, 38, p, p.cur, 'body', null, null); } catch(e) {}
      });
      return 'on';
    },
    off: function(){ stop('autohit'); return 'off'; }
  };

  /* ================= 秒杀 ================= */
  H.oneshot = {
    on: function(){
      H.capture.on(); window.__oneShot = true;
      if (!window.__osHooked) {
        window.__osHooked = 1;
        var orig = window.__hurtOrig;
        window.__hurtOrig = function(t, e, o, s, a, n, i){
          try { if (window.__oneShot && t && t.team && t.team !== 'blue' && !t.isPlayer) e = 9999; } catch(x) {}
          return orig.apply(this, arguments);
        };
      }
      return 'on';
    },
    off: function(){ window.__oneShot = false; return 'off'; }
  };

  /* ================= 移速 ================= */
  H.speed = {
    on: function(){
      H.capture.on();
      if (!window.__mvHooked) {
        window.__mvHooked = 1;
        window.__mvOrig = window.moveEntity;
        window.moveEntity = function(e, dt){
          var mul = window.__speedMul || 1;
          if (e && e.isPlayer && mul > 1 && e.vel) {
            e.vel.x *= mul; e.vel.z *= mul;
            var r = window.__mvOrig.apply(this, arguments);
            e.vel.x /= mul; e.vel.z /= mul;
            return r;
          }
          return window.__mvOrig.apply(this, arguments);
        };
      }
      if (!(window.__speedMul > 1)) window.__speedMul = 2;
      return window.__speedMul + 'x';
    },
    set: function(m){ window.__speedMul = m || 1; if (m > 1) H.speed.on(); return m + 'x'; },
    off: function(){ window.__speedMul = 1; return '1x'; }
  };

  /* ================= 敌人互殴 ================= */
  H.madness = {
    on: function(){
      H.capture.on();
      every('madness', 260, function(){
        var arr = window.__ents || [], reds = [];
        for (var i = 0; i < arr.length; i++) {
          var t = arr[i];
          if (t.team !== 'blue' && t.alive && t.pos) reds.push(t);
        }
        if (reds.length < 2) return;
        var a = reds[Math.floor(Math.random()*reds.length)];
        var b = reds[Math.floor(Math.random()*reds.length)];
        if (!a || !b || a === b) return;
        try { window.__hurtOrig(b, 30, a, 'ak', 'body', null, null); } catch(e) {}
      });
      return 'on';
    },
    off: function(){ stop('madness'); return 'off'; }
  };

  /* ================= 强开商店 ================= */
  H.shop = {
    on: function(){
      H.capture.on();
      if (!window.__shopOrig) window.__shopOrig = window.canBuy;
      window.canBuy = function(){ return true; };
      return 'on';
    },
    off: function(){ if (window.__shopOrig) window.canBuy = window.__shopOrig; window.__shopOrig = null; return 'off'; }
  };

  /* ================= 飞行（画在游戏画面上） ================= */
  H.fly = {
    on: function(){
      H.capture.on();
      window.__flyY = window.__flyY || 4;
      window.__flyOn = true;
      every('fly', 16, function(){
        var p = window.__player;
        if (!p || !p.pos) return;
        p.pos.y = window.__flyY;
        if (p.vel) p.vel.y = 0;
        p.onGround = false;
      });
      H.fly.ui(true);
      return 'on y=' + window.__flyY;
    },
    up: function(){ window.__flyY = Math.min(60, (window.__flyY||4)+1); H.fly.on(); return window.__flyY+'m'; },
    down: function(){ window.__flyY = Math.max(0, (window.__flyY||4)-1); H.fly.on(); return window.__flyY+'m'; },
    off: function(){
      window.__flyOn = false; stop('fly');
      var p = window.__player;
      if (p) { p.onGround = false; if (p.vel) p.vel.y = -7; }
      H.fly.ui(false);
      return 'off';
    },
    /* 游戏画面上的圆钮，跟游戏纸墨画风对齐 */
    ui: function(show){
      var old = document.getElementById('__flyui');
      if (old) old.remove();
      if (!show) return;

      var box = document.createElement('div');
      box.id = '__flyui';
      box.style.cssText = 'position:fixed;right:16px;bottom:150px;z-index:99996;'
        + 'display:flex;flex-direction:column;gap:9px;align-items:center';

      var lab = document.createElement('div');
      lab.textContent = (window.__flyY||4) + 'm';
      lab.style.cssText = 'color:#d42a2a;font:bold 12px "IBM Plex Mono",monospace;'
        + 'background:rgba(245,242,234,.88);border:2px solid #16161c;padding:1px 6px';

      function mk(txt, sz, fn){
        var b = document.createElement('div');
        b.textContent = txt;
        b.style.cssText = 'width:'+sz+'px;height:'+sz+'px;display:flex;align-items:center;'
          + 'justify-content:center;background:rgba(245,242,234,.92);color:#16161c;'
          + 'border:2px solid #16161c;box-shadow:4px 4px rgba(22,22,28,.55);'
          + 'font:bold '+(sz*0.44)+'px "IBM Plex Mono",monospace;'
          + 'user-select:none;-webkit-user-select:none';
        var h = function(e){ e.preventDefault(); e.stopPropagation(); fn(); };
        b.addEventListener('touchstart', h, {passive:false});
        b.addEventListener('mousedown', h);
        return b;
      }

      var up = mk('↑', 54, function(){ lab.textContent = H.fly.up(); });
      var dn = mk('↓', 54, function(){ lab.textContent = H.fly.down(); });
      box.appendChild(up);
      box.appendChild(lab);
      box.appendChild(dn);
      document.body.appendChild(box);
    }
  };

  /* ================= 榜单 ================= */
  H.rank = {
    submit: function(board, period, score){
      var api = window.xhs.miniTool;
      if (!api || !api.submitScore) return 'no api';
      window.__rankLast = null;
      api.submitScore({
        board: board || 1, period: period || 'all', score: Number(score) || 0,
        success: function(r){ window.__rankLast = 'OK ' + JSON.stringify(r); },
        fail: function(e){ window.__rankLast = 'FAIL ' + JSON.stringify(e); }
      });
      return 'sent';
    },
    result: function(){ return window.__rankLast || '...'; },
    my: function(board, period){
      var api = window.xhs.miniTool;
      if (!api || !api.getMyRank) return 'no api';
      window.__rankMe = null;
      api.getMyRank({board: board||1, period: period||'all',
        success: function(r){ window.__rankMe = JSON.stringify(r); },
        fail: function(e){ window.__rankMe = 'FAIL'; }});
      return 'asked';
    },
    me: function(){ return window.__rankMe || '...'; }
  };

  /* ================= 存档修改 ================= */
  H.save = {
    /* patch 形如 {"xp":999999,"raid.bank":888888}，支持点号路径 */
    apply: function(patch){
      window.__savePatch = patch || {};
      if (!window.__saveHooked) {
        window.__saveHooked = 1;
        var t = window.xhsStorageWrite;
        window.xhsStorageWrite = function(k, v){
          if (k === 'inkstrike_xhs_prog' && v && window.__savePatch) {
            try {
              var o = JSON.parse(v);
              for (var key in window.__savePatch) {
                var path = String(key).split('.');
                var cur = o;
                for (var i = 0; i < path.length - 1; i++) {
                  if (!cur[path[i]] || typeof cur[path[i]] !== 'object') cur[path[i]] = {};
                  cur = cur[path[i]];
                }
                cur[path[path.length - 1]] = window.__savePatch[key];
              }
              v = JSON.stringify(o);
            } catch(e) {}
          }
          return t(k, v);
        };
      }
      try { unlockFollowGun(); } catch(e) {}
      return 'applied';
    },
    reload: function(){ try { location.reload(); } catch(e) {} return 'reloading'; },
    /* 工坊货币：改内存 + 落盘 */
    workshop: function(points, essence){
      var app = window.WORKSHOP_APP;
      if (!app || !app.store || !app.store.gacha) return 'no workshop';
      var g = app.store.gacha;
      if (points != null) g.points = Number(points) || 0;
      if (essence != null) g.essence = Number(essence) || 0;
      try { app._saveTimer = 0; app.scheduleSaveStore(); } catch(e) {}
      return 'ok ' + g.points + '/' + g.essence;
    }
  };


  /* ================= 无后坐力 ================= */
  H.nospread = {
    on: function(){
      H.capture.on();
      if (window.__csHooked) return 'already';
      window.__csHooked = 1;
      window.__csOrig = window.curSpread;
      window.curSpread = function(pl, w){
        if (window.__noSpreadOn && pl && pl.isPlayer) return 0.004;
        return window.__csOrig.apply(this, arguments);
      };
      window.__noSpreadOn = true;
      return 'on';
    },
    off: function(){ window.__noSpreadOn = false; return 'off'; }
  };

  /* ================= 无限手雷/医疗 ================= */
  H.nades = {
    on: function(){
      H.capture.on();
      every('nades', 500, function(){
        var p = window.__player;
        if (!p) return;
        p.meds = 99;
        p.nades = {he: 99, flash: 99, smoke: 99, fire: 99};
      });
      return 'on';
    },
    off: function(){ stop('nades'); return 'off'; }
  };

  /* ================= 超级跳 ================= */
  H.superjump = {
    on: function(){
      H.capture.on();
      window.__sjOn = true;
      every('sj', 60, function(){
        var p = window.__player;
        if (!p || !p.pos) return;
        if (p.onGround && p.vel && Math.abs(p.vel.y) < 0.6) {
          p.vel.y = 11;
          p.onGround = false;
        }
      });
      return 'on';
    },
    off: function(){ window.__sjOn = false; stop('sj'); return 'off'; }
  };

  /* ================= 自动瞄准 ================= */
  H.aimbot = {
    on: function(){
      H.capture.on();
      window.__aimOn = true;
      every('aim', 16, function(){
        var p = window.__player;
        if (!p || !p.pos || !p.alive) return;
        if (window.__flyOn) return;
        var best = null, bd = 1e18, arr = window.__ents || [];
        for (var i = 0; i < arr.length; i++) {
          var t = arr[i];
          if (t.team === 'blue' || !t.alive || !t.pos || t.isPlayer) continue;
          var dx = t.pos.x - p.pos.x, dz = t.pos.z - p.pos.z;
          var d2 = dx*dx + dz*dz;
          if (d2 < bd && d2 < 2500) { bd = d2; best = t; }
        }
        if (!best) return;
        var ex = best.pos.x - p.pos.x;
        var ey = (best.pos.y + 1.45) - (p.pos.y + 1.6);
        var ez = best.pos.z - p.pos.z;
        var len = Math.sqrt(ex*ex + ey*ey + ez*ez);
        if (len < 0.3) return;
        var ty = Math.atan2(-ex, -ez);
        var tp = Math.asin(Math.max(-1, Math.min(1, ey / len)));
        var dy = ty - p.yaw;
        while (dy > Math.PI) dy -= Math.PI * 2;
        while (dy < -Math.PI) dy += Math.PI * 2;
        p.yaw += dy * 0.45;
        p.pitch += (tp - p.pitch) * 0.45;
      });
      return 'on';
    },
    off: function(){ window.__aimOn = false; stop('aim'); return 'off'; }
  };

  /* ================= 瞬移 ================= */
  H.tp = {
    on: function(){
      H.capture.on();
      window.__tpOn = true;
      every('tp', 900, function(){
        var p = window.__player;
        if (!p || !p.pos || window.__flyOn) return;
        var best = null, bd = 1e18, arr = window.__ents || [];
        for (var i = 0; i < arr.length; i++) {
          var t = arr[i];
          if (t.team === 'blue' || !t.alive || !t.pos || t.isPlayer) continue;
          var dx = t.pos.x - p.pos.x, dz = t.pos.z - p.pos.z;
          var d2 = dx*dx + dz*dz;
          if (d2 < bd) { bd = d2; best = t; }
        }
        if (!best) return;
        // 落到敌人旁边 1.6 米，别贴脸卡模型
        var ang = Math.atan2(p.pos.x - best.pos.x, p.pos.z - best.pos.z);
        p.pos.x = best.pos.x + Math.sin(ang) * 1.6;
        p.pos.z = best.pos.z + Math.cos(ang) * 1.6;
        p.pos.y = best.pos.y;
      });
      return 'on';
    },
    off: function(){ window.__tpOn = false; stop('tp'); return 'off'; }
  };

  /* ================= 穿墙 ================= */
  H.noclip = {
    on: function(){
      H.capture.on();
      window.__noclipOn = true;
      if (!window.__ovHooked) {
        window.__ovHooked = 1;
        window.__ovOrig = window.entOverlap;
        window.entOverlap = function(e, x, y, z, near){
          if (window.__noclipOn && e && e.isPlayer) return null;
          return window.__ovOrig.apply(this, arguments);
        };
      }
      return 'on';
    },
    off: function(){ window.__noclipOn = false; return 'off'; }
  };

  /* ================= 子弹时间（敌人变慢） ================= */
  H.slowmo = {
    on: function(){
      H.capture.on();
      window.__slowOn = true;
      if (!window.__mvHook2) {
        window.__mvHook2 = 1;
        var prev = window.moveEntity;
        window.moveEntity = function(e, dt){
          if (window.__slowOn && e && !e.isPlayer && window.__player && e.team !== window.__player.team) {
            return prev.call(this, e, dt * 0.28);
          }
          return prev.apply(this, arguments);
        };
      }
      return 'on';
    },
    off: function(){ window.__slowOn = false; return 'off'; }
  };

  /* ================= 一枪爆头 ================= */
  H.headshot = {
    on: function(){
      H.capture.on();
      window.__headOn = true;
      if (!window.__hdHooked) {
        window.__hdHooked = 1;
        var orig = window.__hurtOrig;
        window.__hurtOrig = function(t, e, o, s, a, n, i){
          if (window.__headOn && t && t.team && t.team !== 'blue' && !t.isPlayer) {
            a = 'head';
            e *= 2.2;
          }
          return orig.call(this, t, e, o, s, a, n, i);
        };
      }
      return 'on';
    },
    off: function(){ window.__headOn = false; return 'off'; }
  };

  /* ================= 一键全开 ================= */
  H.all = {
    on: function(){ H.god.on(); H.money.on(); H.ammo.on(); H.speed.set(3); return 'on'; },
    off: function(){ H.god.off(); H.money.off(); H.ammo.off(); H.speed.off(); return 'off'; }
  };

  H.warmup = function(){ H.capture.on(); return 'warm'; };

  H.status = function(){
    return JSON.stringify({
      player: !!window.__player, ents: (window.__ents||[]).length,
      god: !!window.__godOn, esp: !!window.__espOn, freeze: !!window.__freezeOn,
      fly: !!window.__flyOn, speed: window.__speedMul || 1
    });
  };

  window.__XHS_HACKS = H;
  return 'ready';
})();
