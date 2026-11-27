# XhsSpy

注入小红书的 Xposed 模块。不 root，用 **LSPatch 本地模式**。

抓起小程序/小工具源码、就地预览、改运行时数值、把手机交给 AI 操作。

---

## 装

1. 下载 `XhsSpy.apk` 装上
2. LSPatch 里选中「小红书」，勾上本模块，**勾「允许调试」**（`classes` 工具靠它）
3. 打补丁，装补丁包
4. 开小红书 → 悬浮胶囊出来 → 点开就是面板

**签名已经固定**（`CN=XhsSpy`，SHA256 `CE:B6:3F:69:...`）。
以后新版本直接覆盖安装，不用卸载。LSPatch 那边也只需要重打一次补丁。

---

## 悬浮窗

收起是一条胶囊，点开从胶囊里长成两栏。左边分类，右边就地干活，基本不跳浏览器。

| 分类 | 能干什么 |
|---|---|
| 小程序 | 列出 `redmpV2/release` 下的包，导出/分享 |
| 小工具 | 同上，多一个「预览」直接跑起来 |
| 数值 | 搜 window 变量 / localStorage，就地改；带 JS 控制台和常用脚本 |
| 壁纸 | 掘金壁纸，两列缩略图就地显示 |
| 日志 | 读日志文件末尾，可选中复制 |
| 内存 | 运行时内存 + 三个性能开关 |
| 进程 | pid / 端口 / 各进程的 WebView，诊断用 |

**操作**：点胶囊任意位置 = 开/合。长按 0.7 秒 = 打开网页版。右边 `⟳` = 转屏（横屏小游戏用）。

---

## 跨进程

小红书是多进程的，小工具跑在 `:privileged_processN` 里。`static` 变量不跨进程，
所以模块的做法是：

- **每个进程各起一个 HTTP 服务**，主进程 8686，子进程自动往上找
- 子进程起完服务后 `GET /announce?port=N` 告诉主进程
- 主进程重启时主动扫一遍 `/ping` 把子进程找回来
- `evalJs` 先问本进程，没有就转发给 `sPeerPorts` 里的端口

所以 `js_vars` / `js_eval` 对你看到的任何一个页面都有效，不管它在哪个进程。

---

## 抓 WebView

不认类，认 View 树。

小红书的 R1 内核不一定用 `android.webkit.WebView`，所以 `hookAllConstructors` 会漏。
现在两条路一起走：

1. hook `WebView` 构造（覆盖标准实现）
2. `Activity.onResume` 后延迟 900ms / 3500ms 遍历 DecorView 的整棵 View 树，
   把类名含 `webview` 的都登记下来（覆盖任何实现）

执行 JS 时也分两条：标准 WebView 直接调 `evaluateJavascript`，
其它实现用反射找同名同签名的方法，配 `Proxy` 接回调。

---

## 性能开关

默认全关。开着会拖慢整机，抓包时才需要。

| 开关 | 作用 |
|---|---|
| `body` | 记录 HTTP 响应体（每个请求 `peekBody(1MB)`，非常重） |
| `file` | hook `FileOutputStream` 构造（每次写文件都穿一次 Xposed 桥） |
| `net` | 记录每个网络 URL |

悬浮窗「内存」页能点，MCP 用 `set_switch`。

另外 `MiniPacker.list()` 有 60 秒缓存（125 个小工具目录递归数文件很贵），
`startWatcher` 只比目录名不递归。

---

## MCP

26 个工具，客户端连 `http://127.0.0.1:8686/mcp`。

**文件**：`ls` `tree` `search` `read_file` `read_image` `list_images` `stat`
`find_packages` `zip_list` `zip_image` `grep`

**包**：`sweep` `packages`

**页面**：`page_images` `fetch_image` `fetch_text`

**运行时**：`js_vars` `js_set` `js_eval` `process_mem` `switches` `set_switch`

**诊断**：`probe` `webviews` `classes` `roots`

> `grep` 是给 1.8MB 那种打包 JS 用的：`grep(path=/…/js/app.js, q=prog)`，
> 返回命中行号加前后文。`probe` 一次把所有进程的 WebView 情况列出来。

---

## 网页版

同样的东西在浏览器里也能用：`http://127.0.0.1:8686/`

```
/          首页
/mini      小程序/小工具列表
/tool/<appid>/   小工具静态托管（带 window.xhs shim）
/vars      数值面板
/wall      掘金壁纸
/pageimg   WebView 里抓到的图片
/log?n=    日志（boot/float/mini/net/webview/float/crash）
/probe     诊断
/grep?path=&q=   文件里搜关键词
```

---

## 排错

**悬浮窗不出来**
设置 → 应用 → 小红书 → 显示在其他应用上层。

**`js_vars` 说没有 WebView**
小工具跑在子进程，主进程的池子是空的。先看 `/probe` 或者 MCP 的 `probe`
，它会列出每个进程登记到的 WebView。一个都没有的话用 `classes(q=webview)`
看那个进程里到底有哪些 WebView 类。

**卡**
先关性能开关。还卡就看 `/log?n=float`。

**装不上**
卸载旧版再装。签名从 build-45 起就固定了，之后不该再有这个问题。

---

## 构建

GitHub Actions 自动编译。`versionCode` 用 run_number，每次递增。

```
https://github.com/qiuchen520/xhsspy/releases/download/build-N/XhsSpy.apk
```
