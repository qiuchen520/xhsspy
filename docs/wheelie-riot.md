# 翘头狂飙 / WHEELIE RIOT 外挂手册

```
appid   6aeaa1ca8eb436001525b283
标题    翘头狂飙 WHEELIE RIOT
类型    小工具（app_type=4），Three.js + WebGL
域名    https://6aeaa1ca8eb436001525b283.minitool.xiaohongshu.com/index.html
```

跑在主进程（不是 `:privileged_process`），`js_eval` 直接可达。

---

## 一、全局能摸到什么

`Object.keys(window)` 里跟游戏相关的：

| 名字 | 说明 |
|---|---|
| `ranking()` | **最重要的一个**，返回全部赛车手的数组 |
| `crash(r, reason)` | 撞毁处理，`r` 是赛车手 |
| `collideWorld(r, dt)` | 撞墙/地形碰撞，`r` 是赛车手 |
| `collideRacers()` | 车与车碰撞，内部遍历 `racers` |
| `updateCamera(dt, t)` | 相机，比赛中的跟随逻辑在里面 |
| `updateHUD(dt)` | HUD，内部 `const r = player` |
| `drawSpeedo(kmh, boost)` | 画速度表，**`boost` 就是氮气** |
| `render()` | 主渲染 |
| `setState` / `resetStats` / `syncGyroState` | 状态控制 |
| `__wrState` | 调试出口（实际是 undefined，没接上） |

**`player`、`racers`、`camera`、`scene`、`renderer`、`STATE`、`camYaw` 全在闭包里，`typeof` 都是 undefined，摸不到。**

唯一能拿到实体的是 **`ranking()`**。

---

## 二、赛车手对象结构

`ranking()` 返回 8 个（1 玩家 + 7 AI），每个对象的字段：

```
ch, k, m, pos, yaw, speed, vy, air, airTime, flip, flipping,
wheelie, wheelieT, idx, lat, along, lap, prog,
nitro, boostT, spinT, spinDir, scaredT,
crashed, crashT, invT, finished, finishTime,
steerS, leanV, lastGy, isPlayer, ai, st, rag, place,
faceState, pitchV, kv, yawV, atk, fx, scale, thr,
lapStart, bestLap, rings, lastHitT, rollCD, voice,
racing, stallT, nitroOn, boosting, spinAng
```

关键几个：

| 字段 | 含义 |
|---|---|
| `isPlayer` | 布尔，**比赛开始后才为 true**，菜单里全是 false |
| `pos` | `{x, y, z}` 位置 |
| `yaw` | 朝向（弧度） |
| `speed` | 当前速度，**仪表盘满格 300** |
| `nitro` | 氮气存量 |
| `nitroOn` / `boosting` | 氮气激活状态 |
| `invT` | **无敌计时器**，> 0 时免疫一切碰撞 |
| `crashed` | 是否撞毁 |
| `crashT` | 撞毁后的计时 |
| `spinT` | 被打转圈的计时 |
| `scale` | **相机距离乘数**（不是模型缩放！） |
| `m.root` | 车的 Three.js Object3D，**模型缩放改这个** |
| `lap` | 圈数，-1 表示还没起跑 |
| `prog` | 赛道进度 0~1 |

---

## 三、无敌的原理

不是硬改物理，游戏自带机制：

```js
function crash(r, reason){
  if (r.crashed || r.invT > 0 || r.fx.gold > 0) return;   // ← 这里直接返回
  r.crashed = true;
  r.crashT = 0;
  r.st.crashes++;
  ...（飞出碎片、扣速、播放音效）
}
```

车与车也一样：

```js
function collideRacers(){
  for (let a = 0; a < racers.length; a++)
    for (let b = a + 1; b < racers.length; b++) {
      const A = racers[a], B = racers[b];
      if (A.crashed || B.crashed
          || A.invT > 0 && A.fx.gold <= 0
          || B.invT > 0 && B.fx.gold <= 0
          || ...) continue;      // ← invT>0 直接跳过这一对
```

**所以把 `r.invT` 拉满 = 撞墙不毁、被撞不翻，走的是原版无敌路径。**

`collideWorld` 单独处理地形推出：

```js
function collideWorld(r, dt){
  if (r.crashed) return;
  const cap = capsuleOf(r), rad = cap[4];
  for (const s of solidsNear(r.pos.x, r.pos.z)) {
    if (d >= rad + s.r) continue;
    r.pos.x += nx * pen;      // ← 被墙推出来
    r.pos.z += nz * pen;
```

**想让墙彻底不存在，hook 它：**

```js
var _cw = window.collideWorld;
window.collideWorld = function(r, dt){
  if (window.__noWall && r && r.isPlayer) return;   // 玩家跳过全部碰撞
  return _cw.apply(this, arguments);
};
```

⚠️ **跳过之后地面判定也没了**，赛道有落差可能掉下去。

---

## 四、相机是怎么跟的

`updateCamera(dt, t)` 里比赛中的分支：

```js
const r = player, sf = clamp(Math.abs(r.speed) / 60, 0, 1.4);
let back = (7.2 + sf * 1.6) * Math.max(1, r.scale * 0.9);
let up   = (3 + sf * 0.3) * Math.max(1, r.scale * 0.8);

camYaw += wrapA(r.yaw - camYaw) * Math.min(1, dt * 8.5);   // ← 转向延迟在这
const cx = Math.sin(camYaw), cz = Math.cos(camYaw);
camPos.set(r.pos.x - cx * back, r.pos.y + up, r.pos.z - cz * back);

const cgy = groundAtXZ(camPos.x, camPos.z, r.idx)[0] + 1.2;
camPos.y < cgy && (camPos.y = cgy);

camera.position.x = camPos.x;      // ← x/z 是直接赋值，没有延迟
camera.position.z = camPos.z;
camera.position.y += (camPos.y - camera.position.y) * Math.min(1, dt * 8);
```

**结论：**

- 位置不滞后（x/z 直赋），**滞后的是 `camYaw` 转向**（每帧只追 8.5·dt）
- `back` 默认只有 9.4 米（`sf` 封顶 1.4），高速时车快贴脸
- **`r.scale` 同时乘进 `back` 和 `up`，是唯一能从外部调镜头距离的旋钮**

`scale = 4` 时：

```
back ≈ 9.44 * 3.6 = 34 米
up   ≈ 3.42 * 3.2 = 11 米   → 自然变成俯视角
```

---

## 五、模型缩放和镜头缩放是两回事

**踩过的坑：**

`r.scale` 看着像"缩放"，实际只喂给相机。真正控制模型的是 `r.m.root.scale`。

```js
r.scale = 4;                       // 只影响镜头
r.m.root.scale.setScalar(3);       // 只影响外形
```

**两个互不干扰，可以随便组合。**

### 累乘的坑

第一版写成了：

```js
// ❌ 错的
if (!r.m.__base) r.m.__base = r.m.root.scale.x || 1;
r.m.root.scale.setScalar(r.m.__base * 3);
```

`r.m.__base` 在玩家对象还没生成时读不到，`|| 1` 兜底成 1，然后每 150ms 又拿当前值当基准乘一次 —— **4.94、还在涨**，越滚越大。

**正确做法**：先确认游戏不会重置它，然后一次性赋值。

实测复位到 `1` 之后观察 2 秒，数值稳定不变，说明游戏不碰这个字段。于是：

```js
r.m.root.scale.setScalar(3);   // 只调一次，不要定时器
```

只有换车、重开一局时会被重置回 1。

---

## 六、完整 hack 代码

```js
/* ===== 1. 拿玩家 ===== */
function me() {
  var rk = ranking();
  for (var i = 0; i < rk.length; i++) if (rk[i].isPlayer) return rk[i];
  return null;
}

/* ===== 2. 无限氮气 + 无敌 ===== */
window.__wrTimer = setInterval(function () {
  var p = me();
  if (!p) return;
  p.nitro = 999;
  p.nitroOn = true;
  p.boosting = true;
  p.invT = 999;
  p.crashed = false;
  p.crashT = 0;
  p.spinT = 0;
}, 50);

/* ===== 3. 速度锁定 ===== */
window.__fastSpeed = 300;
window.__fastOn = true;
window.__fastTimer = setInterval(function () {
  if (!window.__fastOn) return;
  var p = me();
  if (!p) return;
  if (p.speed > 1 && p.speed < window.__fastSpeed) p.speed = window.__fastSpeed;
}, 60);

/* ===== 4. 去掉空气墙 ===== */
window.__noWall = true;
var _cw = window.collideWorld;
window.collideWorld = function (r, dt) {
  if (window.__noWall && r && r.isPlayer) return;
  return _cw.apply(this, arguments);
};

/* ===== 5. 俯视镜头 ===== */
window.__carScale = 4;          // 4 = 俯视
window.__scaleTimer = setInterval(function () {
  var p = me();
  if (p && p.scale !== window.__carScale) p.scale = window.__carScale;
}, 200);

/* ===== 6. 模型放大 ===== */
var p = me();
if (p && p.m && p.m.root) p.m.root.scale.setScalar(3);
```

---

## 七、调试速查

```js
me()                              // 拿玩家对象
me().speed                        // 当前速度
me().invT                         // 无敌剩余
me().pos                          // 位置
me().lap                          // 圈数，-1 = 未起跑
ranking().length                  // 场上车数（含 AI）
ranking().map(r => r.isPlayer)    // 确认哪个是自己
```

**菜单里 `isPlayer` 全是 false、`lap = -1`、`speed = 0`**，必须真正起跑才会有玩家对象。监控挂上去等它自己生效就行。

---

## 八、没做出来的

**相机完全不跟随。** `camera` 是闭包变量，`updateCamera` 无参数，hook 它只能做到"不更新相机"，那样镜头会停在原地、车跑几十米就出画。想真正做成「固定机位看车跑」，需要 hook `render()` 并在里面改 camera —— 同样拿不到引用。

目前能做到的最接近的是 `r.scale` 拉到 6 以上，镜头会退到 60 米外、20 米高，接近监控视角。
