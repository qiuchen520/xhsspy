# 墨线突击 / INK STRIKE 存档与排行榜操作手册

```
appid   6ab94378dd88340015152901
作者    杏林斑码
类型    小工具（app_type=4），WebGL + three.js
域名    https://6ab94378dd88340015152901.minitool.xiaohongshu.com
作者备注  render_game_to_text() —— 留了调试出口
```

---

## 一、先摸清结构

小工具的代码在：

```
/data/data/com.xingin.xhs/files/mini_tool/release/6ab94378dd88340015152901/<hash>/source/
  ├── index.html      147 KB
  ├── js/app.js       1.8 MB   ← 逻辑全在这
  ├── xhs-boot.js      52 KB
  └── xhs-audio.js    268 KB
```

**它跑在独立进程里**（`com.xingin.xhs:privileged_processN`），主进程的 WebView 池是空的。
XhsSpy build-38 之后会自动跨进程转发，直接用 `js_eval` 就行。

---

## 二、全局有什么

`Object.keys(window)` 里有用的：

| 名字 | 说明 |
|---|---|
| `render_game_to_text()` | **作者留的调试出口**，返回对局状态 JSON |
| `progDefaults()` | 只做初始化，不返回数据 |
| `unlockFollowGun()` | 内部会 `PROG.data.followGun=1; PROG.save()` —— **可用来触发存档** |
| `WORKSHOP_APP` | 工坊，`store` 是武器皮肤，`saveToSlot` / `grantInkCoins` 等 |
| `window.xhs.miniTool` | 小红书原生 API（存储 / 云存储 / 排行榜 / 发请求） |
| `window.xhsStorageRead` / `xhsStorageWrite` | 存档读写，**挂在 window 上** |
| `INKTIDE` / `SEASON` / `T300_PRESETS` / `WEAPONS` | 游戏数据表 |
| `rankTop()` / `activeRankSeal()` | 读 `PROG.data.rankSeal`，返回榜首封号信息 |

`PROG` 本身是闭包里的 `const`，`window.PROG` 不存在。

---

## 三、存档在哪

两个 key：

| key | 内容 |
|---|---|
| `inkstrike_xhs_prog` | 战绩（经验、击杀、撤离、成就、rankSeal） |
| `inkstrike_xhs_workshop_v1` | 工坊（武器皮肤、抽奖货币） |

`localStorage` 里那个 `inkstrike_xhs_cloud`（70 字节）只是云同步指针：

```json
{"id":"94f69dd3540805ffea98562d","at":1795785536426,"reconcile":false}
```

**真正的内容不在 localStorage，在原生存储里**，通过 `xhsStorageRead/Write` 走。

### prog 结构

```json
{
  "kills": {"spear":8200, "awp":7600, "deagle":5400, "lance":4200, "inkclaw":3600},
  "skin": {},
  "mvp": 99,
  "raid": {"bank":999999, "runs":21000, "out":19800, "best":268000, "total":5920000},
  "xp": 1001249,
  "stats": {"followPack":1, "freeIns":1, "bestLife":16, "witchDawn":3,
            "heads":5, "matches":1, "daysTotal":1, "dayRun":1,
            "dayLast":20784, "dayBest":1, "wTide":1},
  "ach": {"first":20784, "life5":20784, "witch":20784, "life10":20784},
  "equip": {"plate":"follow"},
  "followGun": 1,
  "rankSeal": {"board":3, "rank":1, "p":"week", "exp":1827326162577}
}
```

`ach` 里的值是**解锁时间戳**，不是布尔。
`rankSeal.exp` 必须大于 `Date.now()`，否则 `activeRankSeal()` 返回 null。

### workshop 结构

```json
{
  "version": 5,
  "activeGun": "ak",
  "weapons": { "p9": {"equippedSlot":-1, "slots":[skin, null, null]}, ... },
  "gacha": {
    "points": 99999999,
    "essence": 99999999,
    "pityCount": 50,
    "totalPulls": 99999,
    "unlockedIds": ["preset_01_flame_knight", ...],
    "supplyBag": {"raidCash":9999999, "xpBoostGames":9999, "stickerPacks":9999}
  }
}
```

43 把武器的 id：

```
p9 viper deagle rev g18 swift vector brush nova db s12 mpx burst spear aug
qbz191 ak m4 night qbz scout mini14 svd awp m249 lmg m14 bow rpg flamer
shield claw flip sabre tang modao kukri knife he flash smoke fire c4
```

`pityCount` 游戏会自己夹到 **50**（保底上限）。

---

## 四、怎么改

### 原理

`PROG.save()` 长这样：

```js
save(){
  xhsStorageWrite("inkstrike_xhs_prog", JSON.stringify(this.data))
}
```

`xhsStorageWrite` 挂在 `window` 上，而且内部调用走的就是 window 引用 —— **可以覆写**。

验证方法：覆写后调 `unlockFollowGun()`（它会触发 `PROG.save()`），看覆写有没有被调用到。

### 改存档

```js
var tail = window.xhsStorageWrite;
window.xhsStorageWrite = function (k, v) {
  if (k === 'inkstrike_xhs_prog' && v) {
    var o = JSON.parse(v);
    o.xp = 1000000;
    o.raid.bank = 999999;
    o.raid.runs = 3650;
    o.kills.spear = 8200;
    o.rankSeal = { board: 3, rank: 1, p: 'week', exp: Date.now() + 31536000000 };
    v = JSON.stringify(o);
  }
  return tail(k, v);
};
unlockFollowGun();      // 触发一次存档
location.reload();      // 让 PROG.data 从存储重新读
```

**必须 reload** —— `PROG.data` 是启动时读进内存的，不刷新看不到新值。

### 改工坊

工坊走的是 `U.saveStore(this.store)` → `xhsStorageWrite('inkstrike_xhs_workshop_v1', ...)`。

但**光改写入端不够**，内存里的 `WORKSHOP_APP.store` 也要改，否则下次存档会把旧值写回去：

```js
var g = WORKSHOP_APP.store.gacha;
g.points = 99999999;
g.essence = 99999999;
WORKSHOP_APP._saveTimer = 0;        // 清掉 debounce 计时器
WORKSHOP_APP.scheduleSaveStore();   // 450ms 后落盘
```

---

## 五、排行榜

### API

```js
window.xhs.miniTool.getRankList({board, period, success, fail})
window.xhs.miniTool.getMyRank({board, period, success, fail})
window.xhs.miniTool.submitScore({board, period, score, success, fail})
```

只有这三个。没有 `removeScore` / `deleteScore` / `resetScore`。

### 参数

| 参数 | 取值 |
|---|---|
| `board` | 1 靶场 / 2 死斗 / 3 撤离 / 4 KD / 5 等级 |
| `period` | `day` / `week` / `month` / `all` |
| `score` | **[-1000000, 1000000]**，超了报 `errCode 1004` |

**`board` 是有效参数**，但要用第二名来判断，因为第一名可能都是自己。

### 各榜的真实量级（2026-11 抓的）

| board | 榜名 | 榜一 | 分数 | 数值含义 |
|---|---|---|---|---|
| 1 | 靶场 | 鐵飝 | 18434 | 靶场得分 |
| 2 | 死斗 | 小红薯6752A98A | **100** | 单局最高击倒，100 封顶 |
| 3 | 撤离 | W0423 | 24480 | 累计带出 |
| 4 | KD | 国际象棋小白 | **289.25** | 击杀死亡比，支持小数 |
| 5 | 等级 | 采蘑菇的憨憨 | 998315 | 经验值 |

### 三个大坑

**1. 只涨不跌。** 分数写进去就锁死，提交更低的直接返回当前值：

```
submitScore({board:2, score:5720}) → {"score":999999}
submitScore({board:2, score:0})    → {"score":999999}
submitScore({board:2, score:-1})   → {"score":999999}
submitScore({score:null})          → "must be a finite number"
```

**2. `period` 不隔离分数。** 日榜周榜月榜共享同一个数值：

```
submitScore({board:2, period:'day', score:100}) → {"score":130}
getRankList({board:2, period:'day'}) → 1.丅sss丅 130
```

所以你只能有"一个分数"，不能给不同周期写不同值。

**3. 提交前必须先拉榜。** 不然会像我一样往"100 封顶"的死斗榜写 130，永久洗不掉。

### 正确流程

```js
// 1. 先看榜
api.getRankList({board: 3, period: 'all', success: r => {
  console.log(r.data.slice(0, 5).map(x => x.nickname + ' ' + x.score));
}});

// 2. 确认量级和含义，算出"比榜一高一点点"的数
// 3. 再提交
api.submitScore({board: 3, period: 'all', score: 25200, success: r => {}});
```

---

## 六、其他能改的

### 伪造榜首封号

```js
o.rankSeal = {board: 3, rank: 1, p: 'week', exp: Date.now() + 31536000000};
```

- `board`：对应 `RANK_SEAL_SHORT` = `{1:靶场, 2:死斗, 3:撤离, 4:KD, 5:等级}`
- `p`：`day` / `week` / `month`，对应 `RANK_PERIOD_NAME`
- `rank`：1-100，1-3 会给「榜首金印 / 榜眼朱印 / 探花青印」
- `exp` 必须 > 现在，否则判定过期

### 读对局状态

```js
render_game_to_text()
```

返回：

```json
{
  "coordinates": "meters; x east, y up, z south; north is -z",
  "state": "menu", "mode": "raid", "teamSize": 8,
  "score": {"red":0,"blue":0}, "round": 0,
  "player": null, "bots": 0,
  "objective": {"state":"none","site":null,"seconds":0},
  "performance": {"p90":0,"cpuP90":0}
}
```

进对局后 `player` 才有值 —— 那时候才能摸血量、弹药、后坐力这些运行时数据。

---

## 七、排查

| 现象 | 原因 |
|---|---|
| `js_vars` 说没有 WebView | 小工具在子进程，需要 build-38+ 的跨进程转发 |
| `rankTop()` 返回 null | `PROG.data.rankSeal` 不存在、`rank` 不在 1-100、或者 `exp` 已过期 |
| 改完没生效 | 没 reload，`PROG.data` 还是内存里的旧值 |
| 工坊改了又被写回 | 只改了写入端，没改 `WORKSHOP_APP.store` 内存对象 |
| `submitScore` 报 1004 | 分数超出 ±100 万 |
