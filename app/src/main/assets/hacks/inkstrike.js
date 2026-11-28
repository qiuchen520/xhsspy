/* 墨线突击 / INK STRIKE 外挂脚本集
 * 由 XhsSpy 注入。每个 hack 有 on() / off()。
 * 所有对象引用都靠 window.__ents / __player / __hurtOrig 维持。
 */
(function(){
  if (window.__XHS_HACKS && window.__XHS_HACKS.__v === 1) return 'already';

  var H = {};
  H.__v = 1;
  var timers = {};

  function every(id, ms, fn){
    if (timers[id]) clearInterval(timers[id]);
    timers[id] = setInterval(fn, ms);
  }
  function stop(id){
    if (timers[id]) { clearInterval(timers[id]); timers[id] = null; }
  }

  /* ---------- 基础：抓实体 ---------- */
  H.capture = {
    on: function(){
      if (window.__capHooked) return;
      window.__capHooked = 1;
      window.__ents = window.__ents || [];
      window.__player = window.__player || null;

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
      if (as) {
        window.animSoldier = function(t, e){
          try { if (t && t.pos && window.__ents.indexOf(t) < 0) window.__ents.push(t); } catch(x) {}
          return as.apply(this, arguments);
        };
      }
      var ub = window.updateBot;
      if (ub) {
        window.updateBot = function(t, e, o){
          try { if (t && t.pos && window.__ents.indexOf(t) < 0) window.__ents.push(t); } catch(x) {}
          return ub.apply(this, arguments);
        };
      }
    }
  };

  /* ---------- 无敌 ---------- */
  H.god = {
    on: function(){
      H.capture.on();
      window.__godOn = true;
      every('god', 150, function(){
        var p = window.__player; if (!p) return;
        p.hp = 100; p.armor = 100; p.alive = true;
      });
      return 'on';
    },
    off: function(){
      window.__godOn = false;
      stop('god');
      return 'off';
    }
  };

  /* ---------- 无限钱 ---------- */
  H.money = {
    on: function(){
      H.capture.on();
      every('money', 150, function(){
        var p = window.__player; if (p) p.money = 999999;
      });
      return 'on';
    },
    off: function(){ stop('money'); return 'off'; }
  };

  /* ---------- 无限弹药 ---------- */
  H.ammo = {
    on: function(){
      H.capture.on();
      every('ammo', 150, function(){
        var p = window.__player; if (!p) return;
        if (p.ammo) for (var k in p.ammo) {
          var a = p.ammo[k]; if (a) { a.mag = 999; a.res = 9999; }
        }
        p.mag = 999;
      });
      return 'on';
    },
    off: function(){ stop('ammo'); return 'off'; }
  };

  /* ---------- 全部勾上（一键） ---------- */
  H.all = {
    on: function(){
      H.god.on(); H.money.on(); H.ammo.on();
      return 'on';
    },
    off: function(){
      H.god.off(); H.money.off(); H.ammo.off();
      return 'off';
    }
  };

  /* ---------- ESP ---------- */
  H.esp = {
    on: function(){
      H.capture.on();
      if (window.__espOn) return 'already';
      window.__espOn = true;
      if (typeof THREE === 'undefined' || typeof camera === 'undefined') return 'no THREE/camera';

      var box = document.getElementById('__esp');
      if (!box) {
        box = document.createElement('div');
        box.id = '__esp';
        box.style.cssText = 'position:fixed;left:0;top:0;width:100%;height:100%;pointer-events:none;z-index:99999';
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
          var sx = (vf.x * 0.5 + 0.5) * w;
          var syFoot = (-vf.y * 0.5 + 0.5) * h;
          var syHead = (-vh.y * 0.5 + 0.5) * h;
          if (sx < -140 || sx > w + 140 || syFoot < -140 || syFoot > h + 140) continue;
          var bh = Math.max(18, syFoot - syHead);
          var bw = Math.max(10, Math.min(64, bh * 0.42));
          var x0 = sx - bw / 2, y0 = syHead;
          var col = isMe ? '#25e07a' : '#ff3355';
          var d = camera.position.distanceTo(t.pos);
          html += '<div style="position:absolute;left:' + x0 + 'px;top:' + y0 + 'px;width:' + bw
                + 'px;height:' + bh + 'px;border:2px solid ' + col + ';border-radius:2px"></div>';
          html += '<div style="position:absolute;left:' + x0 + 'px;top:' + (y0 - 6) + 'px;width:' + bw
                + 'px;height:3px;background:#200"><div style="height:100%;width:'
                + Math.max(0, Math.min(100, t.hp)) + '%;background:' + col + '"></div></div>';
          html += '<div style="position:absolute;left:' + (x0 - 22) + 'px;top:' + (y0 + bh + 1)
                + 'px;width:' + (bw + 44) + 'px;text-align:center;color:' + col
                + ';font:bold 10px monospace;text-shadow:0 0 3px #000">'
                + (isMe ? '我' : '敌') + ' ' + Math.round(d) + 'm</div>';
        }
        box.innerHTML = html;
      });
      return 'on';
    },
    off: function(){
      window.__espOn = false;
      stop('esp');
      var box = document.getElementById('__esp');
      if (box) box.innerHTML = '';
      return 'off';
    }
  };

  /* ---------- 定身 ---------- */
  H.freeze = {
    on: function(){
      H.capture.on();
      window.__freezeOn = true;
      var ents = window.__ents || [];
      for (var i = 0; i < ents.length; i++) {
        var t = ents[i];
        if (t.team !== 'blue' && !t.__frz) t.__frz = {x: t.pos.x, y: t.pos.y, z: t.pos.z};
      }
      every('freeze', 16, function(){
        var arr = window.__ents || [];
        for (var j = 0; j < arr.length; j++) {
          var b = arr[j];
          if (b.team === 'blue' || !b.__frz) continue;
          b.pos.x = b.__frz.x; b.pos.y = b.__frz.y; b.pos.z = b.__frz.z;
          if (b.vel) b.vel.set(0, 0, 0);
        }
      });
      return 'on';
    },
    off: function(){
      window.__freezeOn = false;
      stop('freeze');
      return 'off';
    }
  };

  /* ---------- 自动命中 ---------- */
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
          var dx = t.pos.x - p.pos.x, dy = t.pos.y - p.pos.y, dz = t.pos.z - p.pos.z;
          var d2 = dx*dx + dy*dy + dz*dz;
          if (d2 < bd) { bd = d2; best = t; }
        }
        if (!best || !window.__hurtOrig) return;
        try { window.__hurtOrig(best, 38, p, p.cur, 'body', null, null); } catch(e) {}
      });
      return 'on';
    },
    off: function(){ stop('autohit'); return 'off'; }
  };

  /* ---------- 状态查询 ---------- */
  H.status = function(){
    return JSON.stringify({
      player: !!window.__player,
      ents: (window.__ents || []).length,
      god: !!window.__godOn,
      esp: !!window.__espOn,
      freeze: !!window.__freezeOn
    });
  };

  window.__XHS_HACKS = H;
  return 'ready';
})();
