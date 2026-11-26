# XhsSpy — 小红书运行时侦察模块

只做三件事，做到位：

1. **拆 SSL Pinning** —— 把流量交给电脑上的 mitmproxy
2. **记录所有请求 URL** —— OkHttp / HttpURLConnection / WebView 三条路都埋点
3. **扫描并导出小程序包** —— 模块跑在小红书进程里，天生拥有小红书的文件权限

第 3 条是重点。**小程序包下载落盘之后，根本不需要抓包、不需要解密，直接就能拿出来。**
微信小程序、小红书小程序、任何「先下载包再执行」的框架都是这个流程。

---

## 为什么不用虚拟空间

你原本的计划是「装虚拟空间 → 里面跑小红书 → XP 模块 hook」。这一步是白干的，原因：

- VirtualXposed 在 Android 9+ 基本跑不动小红书这种多进程 + 有安全库的 App
- 太极·阴 对新版小红书注入经常失败，而且它和 VirtualXposed 都是「套壳」，小程序的 native 库加载路径容易断
- 多了两层壳，问题出在哪你都分不清

绕过去，两条更短的路：

| 条件 | 方案 | 需要的动作 |
|---|---|---|
| 设备有 root | **LSPosed**（Magisk 模块） | 装模块 → 勾选小红书 → 重启 App |
| 没 root | **LSPatch**（免 root） | 模块打进 APK 副本 → 安装副本 |

你的机器是 Android 10（SDK 29）。LSPatch 在 10 上工作良好，**推荐走这条**。

---

## 一、编译

需要 Android Studio 或命令行 Gradle + JDK 17。

```bash
cd xhsspy
./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

这个 APK 就是**模块本体**，不是装上去用的独立 App —— 它是给 LSPatch / LSPosed 吃的。

---

## 二、打补丁（免 root 路线）

1. 手机上装 [LSPatch](https://github.com/LSPosed/LSPatch)（官方 Release 的 apk）
2. 准备小红书官方 APK（从 APKPure / 应用商店下载，别用已经魔改过的）
3. LSPatch → 底部「管理」→ 右下角 `+` → **选择已安装的应用程序** → 小红书
4. 模式选 **本地模式**
5. 「嵌入模块」→ 勾选 **XhsSpy**（先把上一步编译出的 APK 装进手机，LSPatch 才能看到它）
6. 勾选 **允许调试**（关键，后面 `run-as` 拉文件要用）
7. 点「开始修补」→ 完成后卸载原版小红书 → 安装修补后的版本

> 修补会把小红书重新签名，所以原版必须卸载。账号数据会掉，重新登录一次。

---

## 三、抓东西

打开修补版小红书，**进那个小工具玩一会儿**（这点很重要，包只有在被访问时才会下载）。

然后看输出目录：

```bash
# 免 root 取文件（因为开了 debuggable）
adb shell run-as com.xingin.xhs ls files/XhsSpy
adb pull /sdcard/Android/data/com.xingin.xhs/files/XhsSpy ./XhsSpy
```

有 root 就直接：

```bash
adb pull /data/data/com.xingin.xhs/files/XhsSpy ./XhsSpy
```

目录结构：

```
XhsSpy/
├── boot.log          启动、hook 挂载情况
├── net.log           所有请求 URL（含请求体，JSON 的）
├── resp.log          同步请求的响应体
├── webview.log       WebView 加载过的地址、evaluateJavascript 的脚本
├── file.log          写文件事件（能看出小程序包往哪落）
├── ssl.log           pinning 绕过记录
├── crash.log         hook 自身异常（不影响 App）
├── raw/              疑似包体的原始响应
└── pkg/
    ├── manifest_boot.txt   全量扫描清单
    └── *.zip               导出的候选包
```

**先看 `pkg/manifest_boot.txt`。** 里面每一行是：

```
[PKG]  2413568  /files/mini/6aef33cb9ec80e0015a88aca/app.zip  -> app-service.js,page-frame.html
```

`[PKG]` 表示这个 zip 内部含有 `app-service.js` / `page-frame` / `__APP__` 这类小程序标志文件 —— 那就是你要的东西。

---

## 四、流量（可选但强烈建议）

模块已经把 pinning 拆了，所以直接：

```bash
# 电脑上
mitmproxy -p 8080
```

手机 WiFi 设代理指向电脑 IP:8080，装 mitmproxy 的 CA 证书（Android 用户证书），
然后在修补版小红书上打开小工具 —— `net.log` 里的 URL 和 mitmproxy 里的完整请求能对上。

**为什么不让模块自己抓响应体？** 因为：
- 读 OkHttp 响应体会消费掉 stream，App 自己就拿不到了，容易把 App 搞崩
- 交给 mitmproxy，你能看到 headers、cookie、压缩、二进制，一应俱全
- 模块只负责「让代理能看见」，职责单一，出错面小

---

## 五、拿到包之后

```bash
cd /workspace/xhs_dl
python3 pkg_analyze.py ./XhsSpy/pkg/xxx.zip -o ./pkg_out
```

它会：识别包格式 → 解包 → 找 JS/JSON → 美化 → 出结构报告。

如果包是加密的（zip 打不开、magic 不是 `PK`），报告里会指出实际的 magic 和熵值，
那时候再针对性地逆向解密逻辑（先找谁调的 `decrypt`，再回到 `main.js` 里找算法）。

---

## hook 点清单

| 目标 | 方法 | 目的 |
|---|---|---|
| `Application.attach` | after | 拿 Context，启动延迟扫描线程 |
| `okhttp3.OkHttpClient.newCall` | before | 记录请求 URL + JSON body |
| `okhttp3.RealCall.execute` | after | `peekBody` 读响应 |
| `okhttp3.RealCall$AsyncCall.run` | before | 异步请求的 URL |
| `java.net.URL.openConnection` | before | 走 URLConnection 的请求 |
| `WebView.loadUrl` (全部重载) | before | H5 里的小工具地址 |
| `WebView.evaluateJavascript` | before | 注入的桥接脚本 |
| `WebViewClient.shouldInterceptRequest` | before | 拦截到的资源 |
| `SSLContext.init` | after | 抓真实 TrustManager 实例 |
| `TrustManagerImpl.checkServerTrusted` | before | Conscrypt 放行 |
| `X509TrustManagerExtensions.checkServerTrusted` | before | OkHttp 放行 |
| `CertificatePinner.check` (全部重载) | before | OkHttp pinning 放行 |
| `HttpsURLConnection.setHostnameVerifier` | before | 换掉用户设置的 verifier |
| `FileOutputStream.<init>` | after | 定位小程序包落盘路径 |

---

## 常见问题

**装完修补版打不开 / 闪退**
小红书有签名校验。LSPatch 的修补有概率触发，换 LSPatch 的「可调试」关掉再试，
或者改用 LSPosed（需要 root）。

**模块没生效**
`boot.log` 里没有 `loaded pid=` 就是没注入。检查 LSPatch 里模块有没有勾上、作用域是不是 `com.xingin.xhs`。

**pkg 目录空的**
说明小工具包没落到 `files/` 下，或者被存在 `/data/data/com.xingin.xhs/cache/` 之外。
去 `file.log` 里看写文件事件，那里会告诉你真实路径；
或者看 `net.log` 里哪个 URL 带 `.zip` / `mini` 关键字，手动下。

**`/sdcard` 写不进去**
Android 10+ 分区存储的正常现象。模块已经自动退到
`/sdcard/Android/data/com.xingin.xhs/files/XhsSpy/`，这个路径不需要任何权限。
