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

每个请求都要带三个头，缺一个就是 `{"code":0,"msg":"信息有误"}`：

| 头 | 来源 |
|---|---|
| `token` | 登录后拿到的 JWT，直接原样放 |
| `timestamp` | `Math.round(Date.now() / 1000)` **秒级**，不是毫秒 |
| `sign` | 下面这段算出来的 |

```js
var t = "PXhw7UT1B0a9kQDKZsjIASmOezxYG4CHo5Jyfg2b8FLpEvRr3WtVnlqMidu6cN";  // 62 字表

function sign(token, ts) {
  var e = token + "###" + ts;      // 注意是三个 #
  var out = "";
  for (var i = 0; i < e.length; i++) {
    out += rnd() + t[(e.charCodeAt(i) + 3) % 62] + rnd();
  }
  return out;
}
function rnd() {
  return t[Math.floor(Math.random() * 62)];
}
```

也就是：**每个字符映射到字表偏移 +3，前后各插一个随机字符**。
长度恒为 `(token.length + 4 + ts.length) * 3`。

特征：整个 sign 串里只会出现那 62 个字符，没有数字、没有大写 O 之类。

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

## 六、拿真实 token 的路子

绕不开 `wx.login`，但可以在页面里直接读缓存 —— 用户登录过一次之后，小程序会把 `userInfo` 塞进本地缓存：

```js
// 在当前小程序的 WebView 里跑
getApp().getCache("userInfo")
```

返回大概是：

```json
{"openid":"o...真实号","token":"eyJ...","nickname":"...","avatar":"..."}
```

如果拿不到 `getApp()`，退而求其次扫全部 storage：

```js
(function () {
  var out = {};
  try {
    for (var i = 0; i < localStorage.length; i++) {
      var k = localStorage.key(i);
      out[k] = localStorage.getItem(k);
    }
  } catch (e) {}
  try {
    var app = getApp();
    if (app && app.__cache) out.__cache = app.__cache;
  } catch (e) {}
  return JSON.stringify(out);
})()
```

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

## 九、卡住的地方

1. `wx.login` 的 `code` 只能在小程序运行时拿，离线脚本造不出来
2. `getOpenid` 换号必须带 `encryptedData` + `iv`，都是从 `wx.getUserProfile` 出来的
3. 所以**只能在页面里现场读缓存**，不能纯脚本复现

结论：**先登录一次，再读 `getApp().getCache("userInfo")`**，之后所有接口就都通了。
