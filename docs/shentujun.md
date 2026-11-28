# 神图君 / SHENTUJUN 接口逆向手册

```
appid   6800e1cb993c2f0001f2dfba
类型    小程序（app_type=0）
folder  shentujun
域名    https://stapi.youpengw.com/minixhs/v1/
图床    https://img.yugew.com/   ← 裸链，无防盗链
```

主包 71 KB（`service.js` 3357 行美化后），架构是**小程序 + WebView 混合**：
真正的接口调用全走 `wx.request` 包一层 `minixhs/v1/<method>`。

---

## 一、签名算法

**只有 POST 才带签名，而且只有一个头：`sign`。** 没有 token 头、没有 timestamp 头。
GET 请求什么都不带。

```js
var t = "PXhw7UT1B0a9kQDKZsjIASmOezxYG4CHo5Jyfg2b8FLpEvRr3WtVnlqMidu6cN";  // 62 字表

function sign(token) {
  var e = token + "###" + Math.round(Date.now() / 1000);   // 秒级
  var out = "";
  for (var i = 0; i < e.length; i++) {
    var c = e.charAt(i);
    var idx = t.indexOf(c);                                // ← 查字表下标
    var m = idx < 0 ? c : t.charAt((idx + 3) % 62);        // ← 下标 +3，不是 charCode
    out += rnd() + m + rnd();
  }
  return out;
}
```

⚠️ **最容易写错的地方**：是 `字表.indexOf(字符)` 得到下标再 +3，
不是 `charCode + 3`。写成 charCode 的话永远返回「信息有误」。

字符不在 62 字表里（比如 JWT 里的 `-` `_` `.`）时**原样保留**，这一步不能省。

因为只是「下标平移 + 首尾插随机字符」，服务端可以逆推：
每 3 个字符取中间那个，再反向平移 -3，就还原出 `token###时间戳`。
所以 sign 不是哈希，是可逆混淆。

---

## 二、响应解密

服务端返回的 `data` 字段是加密的，两次 base64 + 一次 XOR：

```js
function decrypt(s) {
  var a = atob(s);          // 第一次 base64
  var b = atob(a);          // 第二次 base64
  var k = "shentu";
  var out = "";
  for (var i = 0; i < b.length; i++) {
    out += String.fromCharCode(b.charCodeAt(i) ^ k.charCodeAt(i % k.length));
  }
  return out;               // 通常还是 JSON 字符串
}
```

XOR 密钥就是字面量 `"shentu"`（6 字节，循环）。

---

## 三、响应体的脏数据

服务端会在 JSON 前面先吐一段 PHP 的 `print_r` 调试输出，直接 `JSON.parse` 会炸。

```python
raw = resp.text
i = raw.rfind('{"code"')
data = json.loads(raw[i:])
```

**必须用 `rfind` 不是 `find`** —— 调试输出里本身就可能带 `{"code"`。

---

## 四、接口清单

全部是 `POST https://stapi.youpengw.com/minixhs/v1/<method>`。

### 能通（免登录）

```
indexAuthorList        推荐作者列表   GET，无参数也能通
getOpenid              拿 openid    不带 code 时返回匿名号
uv                     埋点          带任意参数返回 ok
```

### 需要真实 openid

```
indexRecommendPhotoList   推荐图流      返回 404（接口已下线）
photoDetail               图片详情
search                    搜索
homepageInfo              主页信息
photoList                 图片列表
articlePhotoList          图文列表
getPhotoListByDate        按日期取图
homepageAlbumList         主页相册
albumPhotoList            相册图片
getAlbumPhotoList         同上，另一种参数
moreAuthor                更多作者
joinUs                    入驻
order                     下单
addAdvise                 反馈
checkImage                图片审核
checkTxt                  文本审核
getOssSignInfo            OSS 直传签名
getQuestions              题库
```

除前三个，其余在匿名身份下**全部返回 `{"code":0,"msg":"信息有误"}`**。

---

## 五、匿名 openid 是怎么回事

**身份不在 header 里，在 `data` 里。** 每个业务接口都要带 `openid`（64 位 hex），
外加 `version`、`appid`、`timestamp`（毫秒）。

`/getOpenid` 不带 `code` 时：

```json
{
  "openid": "EMst1795834698",
  "anonymous_openid": "EMsta1795834698",
  "unionid": "EMst1795834698",
  "token": "eyJ0eXAiOiJKV1Qi..."
}
```

三者开头都是 **`EMst`**，`anonymous_openid` 多一个 `a`。

包里代码明确写着：

```js
if (openid.indexOf("EMst") === 0) {
  // 匿名号，需要走 wx.login 换真实 openid
  refreshOpenid();
}
```

**所以匿名号不能用于搜索、主页这些接口。** 这就是所有请求「信息有误」的根因 —— 不是签名错，是身份不对。

标签里 `getOpenid` 的参数结构（从 `app.js` 里挖到的）：

```js
wx.request({
  url: BASE + "getOpenid",
  data: { code: <wx.login 的 code>, encryptedData, iv },
  header: { token: "", timestamp: ts, sign: sign("", ts) }
})
```

注意换 openid 时 **token 传空串**，签名用空串算。

---

## 六、拿 token 和 openid

两条路，都不需要用户退出重登。

### 6.1 读缓存（最快）

登录态明文躺在 MMKV 里，URL 编码的 JSON，随便读：

```
/data/user/0/com.xingin.xhs/files/mmkv/
    xhsmp_storage_6800e1cb993c2f0001f2dfba_<uin>
```

里面 `userInfo` 长这样（解码后）：

```json
{
  "openid": "aa47588df4696078ff103ebe828e76ea6f266d761e9a23312a552ff3a66e7a55",
  "anonymous_openid": "an17958500085770",
  "unionid": "un17958500087994",
  "token": "eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9....",
  "userInfoCacheVersion": 1,
  "nickName": "你的昵称"
}
```

⚠️ `openid` 是 **64 位 hex**，不是 `EMst` 开头那种匿名号。看到 EMst 说明没真登录。

⚠️ 缓存里有好几条 userInfo（退出登录会写一条昵称为空的），取**最后一条**。

### 6.2 主动登录（token 过期时）

缓存里那条 token 过期了，小程序也不会自己刷新 ——
`getUserOpenId` 判断 `userInfoCacheVersion` 一致就直接用缓存，永远不会去换。

但可以在渲染层直接调底层桥强制登录：

```js
// 渲染层没有 xhs.login，但这个有
const r = await xhsBridge.callNative('login', { force: true });
// → {"code":"e55f5c6226804219ab573d4d8bd78418","errMsg":"login:ok"}
```

拿到 `code` 之后换 token：

```js
await new Promise(res => xhs.request({
  url: 'https://stapi.youpengw.com/minixhs/v1/shentujun/getOpenid',
  method: 'POST',
  data: { code: r.code, appid: '6800e1cb993c2f0001f2dfba', timestamp: Date.now() },
  success: res, fail: () => res(null)
}));
// → data: { openid, token, unionid, anonymous_openid }
```

注意 `getOpenid` 这个请求 **不带 sign**，参数只要 `code` / `appid` / `timestamp`（毫秒）。
`timestamp` 是毫秒，别跟签名里那个秒级时间戳搞混。

新 token 有效期 **1 小时**，到期必须重新换。

---

## 七、图片下载

图床是裸链，**不需要 Referer，不需要 Cookie**：

```
https://img.yugew.com/<path>
```

拿到 `photoList` / `albumPhotoList` 的返回之后，批量取 `url` 字段直接下就行。
缩略图一般带 `?imageView2/...` 参数，**去掉 query 才是原图**。

---

## 八、调试速查

```bash
# 免登录，验证域名通不通
curl -s 'https://stapi.youpengw.com/minixhs/v1/indexAuthorList' | tail -c 400

# 抓匿名 openid
python3 /workspace/shentujun/st.py

# 带签名打一个需要身份的接口（会返回 信息有误，但能确认签名对不对）
python3 /workspace/shentujun/combo.py
```

判断签名对不对的方法：**错签名和错身份返回的 msg 不一样**。

```
{"code":0,"msg":"签名错误"}      ← 算法不对
{"code":0,"msg":"信息有误"}      ← 签名对，身份不对
```

---

## 九、三个坑和一条死路

四个都实测过，别再踩。

### 坑 1：UA 必须是小红书小程序的

这是最坑的一个。外面怎么发都是 `{"code":0,"msg":"信息有误"}`，
不是签名错、不是 token 过期，是 **User-Agent 不对**。

抓包看宿主实际发的请求头，干净得离谱 —— 没有 cookie、没有身份票据：

```
Accept-Language: zh-CN,zh;q=0.9,en;q=0.8
Content-Type: application/json
Referer: https://www.xiaohongshu.com/6800e1cb993c2f0001f2dfba/1.0.15
User-Agent: Dalvik/2.1.0 (Linux; U; Android 10; ...) discover/9.53.3 xhsminiapp/3.152.1
```

服务端认的就是 UA 里的 **`xhsminiapp`**。Referer 反而不重要（去掉照样通）。

对照实验：

| UA | Referer | sign | 结果 |
|---|---|---|---|
| 小程序 UA | 有 | 有 | ✅ success |
| 小程序 UA | 无 | 有 | ✅ success |
| 普通 UA | 有 | 有 | ❌ 信息有误 |
| 小程序 UA | 有 | 无 | ❌ 当前访问人数过多 |

**所以服务器完全可以自己抓，不需要手机参与。**

### 坑 2：token 只有 1 小时

JWT payload 里 `expire_time` 是对的，签发后 3600 秒失效。
过期后所有业务接口都返回「信息有误」，很容易误判成签名写错。

好在 `openid` 不随 token 变，一个 token 能抓**全站所有作者**，不是只有自己关注的。

### 坑 3：photoList 固定每页 30 条

`limit` 传 30、36、60 都一样，永远返回 30 条。
所以翻页判断**不能写 `list.length < limit`**，会一页就停。
正确做法：整页没有新数据就认为到底了。

### 死路：自造 token

试过把 payload 里 `expire_time` 改成 2100 年，签名乱填：

```json
{"openid":"aa47588d...","expire_time":4102415999}
```

结果：`{"code":-10002,"msg":"参数异常,请退出重试"}`

服务端真的在验 HS256 签名，密钥在它那边。**token 造不出来。**

---

## 十、一句话总结

```
带小程序 UA + 真实 openid + 正确 sign  →  任何机器都能抓
token 一小时过期，过期前用 xhsBridge.callNative('login') 换新的
自造 token 无解
```
