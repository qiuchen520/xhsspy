package com.rxw.xhsspy;

import android.app.Application;
import android.content.Context;
import android.net.http.X509TrustManagerExtensions;
import android.webkit.WebView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * XhsSpy — 小红书运行时侦察模块
 *
 * 只干三件事，干到极致：
 *   1. 拆掉 SSL Pinning，把流量交给电脑上的 mitmproxy 去看
 *   2. 记录所有 OkHttp / WebView 请求 URL，帮你定位小工具包的下载地址
 *   3. 扫描 App 私有目录，把疑似小程序包原样导出到 /sdcard/XhsSpy/pkg/
 *
 * 第 3 条是关键：模块跑在小红书进程里，天生拥有小红书的文件权限。
 * 小程序包下载落盘之后，不需要解密、不需要抓包，直接就能拿出来。
 *
 * 输出目录：/sdcard/XhsSpy/
 *   net.log        所有请求 URL
 *   webview.log    WebView 加载过的地址
 *   pkg/           导出的小程序包 + 清单 manifest.txt
 *   crash.log      hook 自身的异常（不影响 App 运行）
 */
public class XhsSpy implements IXposedHookLoadPackage {

    static final String TAG = "XhsSpy";
    static final String TARGET = "com.xingin.xhs";

    // Android 10+ 分区存储下，/sdcard 根目录往往写不进去。
    // 优先落到 App 专属外部目录（无需任何权限，adb pull 也能取），
    // 再退到私有 files 目录。
    static volatile File OUT = new File("/sdcard/XhsSpy");
    static volatile File PKG_OUT = new File(OUT, "pkg");

    // 单个文件导出上限，防止把 1GB 的缓存也搬出来
    static final long MAX_PKG = 64L * 1024 * 1024;
    static final long MIN_PKG = 8L * 1024;

    static volatile Context sApp;
    static volatile android.app.Activity sActivity;
    static volatile boolean sSwept = false;

    // 性能开关：默认全关。抓包/看写入时才打开，否则会拖慢整机
    static volatile boolean sLogBody = false;   // 记响应 body（很重）
    static volatile boolean sLogFile = false;   // hook 文件写（很重）
    static volatile boolean sLogNet  = false;   // 记每个网络 URL
    static volatile MiniServer sServer;

    public static File outDir() {
        return OUT;
    }

    static final String[] SKIP_EXT = {
            ".jpg", ".jpeg", ".png", ".webp", ".gif", ".mp4", ".mov", ".mp3",
            ".ttf", ".otf", ".so", ".dex", ".apk", ".db", ".db-wal", ".db-shm",
            ".xml", ".arsc", ".oat", ".vdex", ".art", ".odex"
    };

    static final String[] HOT_EXT = {
            ".zip", ".pkg", ".wxapkg", ".rpkg", ".redpkg", ".mtpkg",
            ".js", ".json", ".bin", ".tar", ".gz", ".7z", ".dat"
    };

    // 小程序包内部的标志性文件名（微信系 / 小红书系都覆盖）
    static final String[] PKG_INNER_MARKS = {
            "app-service", "page-frame", "__APP__", "app-config",
            "redmini", "__subPageFrame__", "game.js", "game.json",
            "miniprogram", "mini_tool", "minitool", "plugin.json",
            "app-service.js", "workers.js"
    };

    // ------------------------------------------------------------ 入口

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (!TARGET.equals(lp.packageName)) return;

        log("boot", "loaded pid=" + android.os.Process.myPid()
                + " proc=" + lp.processName);

        safe(() -> hookApplication(lp.classLoader), "hookApplication");
        safe(() -> hookOkHttp(lp.classLoader), "hookOkHttp");
        safe(() -> hookHttpUrlConnection(lp.classLoader), "hookHttpUrlConnection");
        safe(() -> hookWebView(lp.classLoader), "hookWebView");
        safe(() -> hookSslPinning(lp.classLoader), "hookSslPinning");
        safe(() -> hookCertificatePinner(lp.classLoader), "hookCertificatePinner");
        safe(() -> hookFileWrite(lp.classLoader), "hookFileWrite");
        safe(() -> hookActivity(lp.classLoader), "hookActivity");
    }

    // ------------------------------------------------------------ 工具

    interface Block { void run() throws Throwable; }

    /** 选一个真的能写的输出目录 */
    static void initOut(Context ctx) {
        File cand = null;
        try {
            File ext = ctx.getExternalFilesDir(null);
            if (ext != null) cand = new File(ext, "XhsSpy");
        } catch (Throwable ignored) {}
        if (cand == null) {
            try {
                cand = new File(ctx.getFilesDir(), "XhsSpy");
            } catch (Throwable ignored) {}
        }
        if (cand != null && (cand.isDirectory() || cand.mkdirs())) {
            OUT = cand;
            PKG_OUT = new File(cand, "pkg");
        }
    }

    static void safe(Block b, String name) {
        try {
            b.run();
            log("hook", "ok " + name);
        } catch (Throwable t) {
            log("crash", "fail " + name + " : " + t);
        }
    }

    static synchronized void log(String file, String line) {
        try {
            if (!OUT.exists()) OUT.mkdirs();
            File f = new File(OUT, file + ".log");
            FileOutputStream fos = new FileOutputStream(f, true);
            String ts = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
                    .format(new Date());
            fos.write((ts + "  " + line + "\n").getBytes("UTF-8"));
            fos.close();
        } catch (Throwable ignored) {}
    }

    // ------------------------------------------------------------ Application

    static void hookApplication(ClassLoader cl) {
        XposedHelpers.findAndHookMethod(Application.class, "attach",
                Context.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        sApp = (Context) p.args[0];
                        initOut(sApp);
                        log("boot", "app attached: " + sApp.getPackageName());
                        log("boot", "OUT=" + OUT.getAbsolutePath());

                        // 小红书是多进程的，HTTP 服务只在主进程起，
                        // 否则每个子进程占一个端口，乱。
                        String proc = currentProcessName();
                        boolean main = proc == null || !proc.contains(":");
                        log("boot", "process=" + proc + " main=" + main);
                        if (main) {
                            startServer();
                            new Thread(() -> {
                                try {
                                    Thread.sleep(25000);
                                    sweep("boot");
                                } catch (Throwable t) {
                                    log("crash", "sweep thread " + t);
                                }
                            }, "XhsSpySweep").start();
                        }
                    }
                });
    }

    static String currentProcessName() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                return android.app.Application.getProcessName();
            }
            java.lang.reflect.Method m = Class.forName("android.app.ActivityThread")
                    .getMethod("currentProcessName");
            return (String) m.invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------ WebView 实例池（给 MCP 探针用）

    static final java.util.List<Object> sWebViews =
            java.util.Collections.synchronizedList(new java.util.ArrayList<Object>());

    static void registerWebView(Object wv) {
        if (wv == null) return;
        synchronized (sWebViews) {
            if (!sWebViews.contains(wv)) {
                sWebViews.add(wv);
                while (sWebViews.size() > 6) sWebViews.remove(0);
            }
        }
    }

    static final String PROBE_JS =
            "(function(){try{"
          + "var out=[],seen={};"
          + "function add(u){if(!u)return;u=String(u);"
          + "if(u.indexOf('data:')===0)return;if(u.length>2000)return;"
          + "if(!seen[u]){seen[u]=1;out.push(u);}}"
          + "var els=document.querySelectorAll('img');"
          + "for(var i=0;i<els.length;i++){add(els[i].currentSrc||els[i].src);"
          + "if(els[i].srcset)add(els[i].srcset.split(',')[0].split(' ')[0]);}"
          + "var all=document.querySelectorAll('*');"
          + "for(var j=0;j<all.length&&j<4000;j++){"
          + "var bg=getComputedStyle(all[j]).backgroundImage;"
          + "if(bg&&bg!=='none'){var m=bg.match(/url\\([\"']?(.*?)[\"']?\\)/);if(m)add(m[1]);}}"
          + "var vs=document.querySelectorAll('video');"
          + "for(var k=0;k<vs.length;k++){if(vs[k].poster)add(vs[k].poster);"
          + "if(vs[k].currentSrc)add(vs[k].currentSrc);}"
          + "return JSON.stringify({url:location.href,title:document.title,"
          + "count:out.length,images:out.slice(0,300)});"
          + "}catch(e){return JSON.stringify({error:String(e)});}})()";

    /** 让页面自己把 DOM 里的图片 URL 吐出来。返回 JSON 文本。 */
    static String collectPageImages() {
        StringBuilder sb = new StringBuilder();
        java.util.List<Object> snapshot;
        synchronized (sWebViews) { snapshot = new java.util.ArrayList<>(sWebViews); }
        if (snapshot.isEmpty()) {
            return "{\"error\":\"还没抓到 WebView 实例。先在小红书里打开小工具，让它加载一次。\"}";
        }
        sb.append("{\"pages\":[");
        boolean first = true;
        for (Object wv : snapshot) {
            if (!(wv instanceof android.webkit.WebView)) continue;
            final android.webkit.WebView web = (android.webkit.WebView) wv;
            final Object lock = new Object();
            final String[] box = new String[]{null};
            try {
                web.post(() -> {
                    try {
                        web.evaluateJavascript(PROBE_JS, value -> {
                            synchronized (lock) {
                                box[0] = value;
                                lock.notifyAll();
                            }
                        });
                    } catch (Throwable t) {
                        synchronized (lock) { box[0] = null; lock.notifyAll(); }
                    }
                });
                synchronized (lock) {
                    if (box[0] == null) lock.wait(4000);
                }
            } catch (Throwable ignored) {}
            if (box[0] == null) continue;
            String v = box[0];
            // evaluateJavascript 返回的是 JSON 字符串的 JSON 字面量，去掉外层引号
            if (v.startsWith("\"") && v.endsWith("\"")) {
                v = v.substring(1, v.length() - 1)
                        .replace("\\\"", "\"").replace("\\\\", "\\")
                        .replace("\\n", "\n").replace("\\/", "/");
            }
            if (!first) sb.append(',');
            first = false;
            sb.append(v);
        }
        sb.append("]}");
        if (first) return "{\"error\":\"WebView 没有响应\"}";
        return sb.toString();
    }

    // ------------------------------------------------------------ 图片轮询（不依赖 MCP）

    static final java.util.Set<String> sPageImgs =
            java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<String>());
    static volatile boolean sPolling = false;

    public static java.util.List<String> getPageImages() {
        synchronized (sPageImgs) {
            return new java.util.ArrayList<>(sPageImgs);
        }
    }

    static void clearPageImages() {
        sPageImgs.clear();
    }

    /** 在第一个可用的 WebView 上执行一段 JS，返回结果字符串（已解一层 JSON 转义） */
    public static String evalJs(String code, long timeoutMs) {
        java.util.List<Object> snapshot;
        synchronized (sWebViews) { snapshot = new java.util.ArrayList<>(sWebViews); }
        if (snapshot.isEmpty()) return null;
        for (Object wv : snapshot) {
            if (!(wv instanceof android.webkit.WebView)) continue;
            final android.webkit.WebView web = (android.webkit.WebView) wv;
            final Object lock = new Object();
            final String[] box = new String[]{null};
            try {
                web.post(() -> {
                    try {
                        web.evaluateJavascript(code, value -> {
                            synchronized (lock) {
                                box[0] = value == null ? "null" : value;
                                lock.notifyAll();
                            }
                        });
                    } catch (Throwable t) {
                        synchronized (lock) { box[0] = null; lock.notifyAll(); }
                    }
                });
                synchronized (lock) {
                    if (box[0] == null) lock.wait(Math.max(1000, timeoutMs));
                }
            } catch (Throwable ignored) {}
            if (box[0] == null) continue;
            String v = box[0];
            try {
                Object o = new org.json.JSONTokener(v).nextValue();
                if (o == org.json.JSONObject.NULL) return "null";
                return String.valueOf(o);
            } catch (Throwable t) {
                return v;
            }
        }
        return null;
    }

    /** 页面变量 + localStorage 的快照（JS 侧枚举，结果是一段 JSON） */
    public static final String VAR_PROBE_JS =
            "(function(){try{"
          + "var skip={window:1,document:1,location:1,navigator:1,top:1,parent:1,self:1,frameElement:1,"
          + "localStorage:1,sessionStorage:1,indexedDB:1,caches:1,crypto:1,performance:1,"
          + "history:1,screen:1,frames:1,customElements:1,visualViewport:1,"
          + "speechSynthesis:1,webkitStorageInfo:1,scheduler:1,chrome:1};"
          + "var out=[];"
          + "for(var k in window){"
          + "  if(skip[k])continue;"
          + "  try{"
          + "    var v=window[k],t=typeof v;"
          + "    if(t==='function'){out.push({k:k,t:'function',v:'(函数)',w:false});continue;}"
          + "    if(v===null){out.push({k:k,t:'null',v:'null',w:true});continue;}"
          + "    if(t==='object'){"
          + "      var tag=Object.prototype.toString.call(v);"
          + "      var n=-1;try{if(typeof v.length==='number')n=v.length;}catch(e){}"
          + "      var arr=Array.isArray(v);"
          + "      out.push({k:k,t:arr?'数组':'对象',v:tag+(n>=0?(' 长度'+n):''),w:false});"
          + "      continue;}"
          + "    if(t==='number'||t==='string'||t==='boolean'){"
          + "      var s=String(v);"
          + "      if(s.length>300)s=s.slice(0,300)+'…';"
          + "      out.push({k:k,t:t==='number'?'数字':(t==='string'?'文本':'布尔'),v:s,w:true});}"
          + "  }catch(e){}"
          + "}"
          + "var ls=[];"
          + "try{for(var i=0;i<localStorage.length;i++){"
          + "  var key=localStorage.key(i);var val=localStorage.getItem(key);"
          + "  if(val&&val.length>400)val=val.slice(0,400)+'…';"
          + "  ls.push({k:key,v:val});}}catch(e){}"
          + "out.sort(function(a,b){return a.w===b.w?0:(a.w?-1:1);});"
          + "return JSON.stringify({title:document.title,url:location.href,vars:out,storage:ls});"
          + "}catch(e){return JSON.stringify({error:String(e)});}})()";

    public static String probeVars() {
        String r = evalJs(VAR_PROBE_JS, 5000);
        if (r == null) {
            return "{\"error\":\"没有可用的 WebView。先在 App 里打开一个小工具或页面。\"}";
        }
        return r;
    }

    /** 每 5 秒问一次页面有没有新图，有就攒起来。这样不打 MCP 也能在网页上看到。 */
    static void startImagePoller() {
        if (sPolling) return;
        sPolling = true;
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(5000);
                    if (sWebViews.isEmpty()) continue;
                    String json;
                    try {
                        json = collectPageImages();
                    } catch (Throwable e) {
                        continue;
                    }
                    if (json == null || json.contains("\"error\"")) continue;
                    org.json.JSONObject o = new org.json.JSONObject(json);
                    org.json.JSONArray pages = o.optJSONArray("pages");
                    if (pages == null) continue;
                    int added = 0;
                    for (int i = 0; i < pages.length(); i++) {
                        org.json.JSONObject pg = pages.optJSONObject(i);
                        if (pg == null) continue;
                        org.json.JSONArray imgs = pg.optJSONArray("images");
                        if (imgs == null) continue;
                        for (int j = 0; j < imgs.length(); j++) {
                            String u = imgs.optString(j, "");
                            if (u.isEmpty()) continue;
                            if (sPageImgs.add(u)) {
                                log("pageimg", u);
                                added++;
                            }
                        }
                    }
                    if (added > 0) log("boot", "pageimg +" + added
                            + "（累计 " + sPageImgs.size() + "）");
                } catch (Throwable ignored) {}
            }
        }, "XhsSpyImgPoll");
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------ 启动 HTTP + MCP 服务

    static void startServer() {
        if (sServer != null) return;
        try {
            startImagePoller();
            File base = null;
            try { base = sApp.getFilesDir().getParentFile(); } catch (Throwable ignored) {}
            File ext = null;
            try { ext = sApp.getExternalFilesDir(null); } catch (Throwable ignored) {}

            File[] roots = new File[]{
                    base,                                        // /data/data/com.xingin.xhs
                    new File("/data/data/" + TARGET),            // 同上的绝对路径写法
                    new File("/sdcard/Android/data/" + TARGET),  // 应用专属外部目录
                    ext,                                         // 同上（Context 版）
                    OUT,                                         // 日志输出目录
                    new File("/sdcard/XhsSpy"),                  // 老路径兜底
            };

            MiniServer s = new MiniServer(roots, "", XhsSpy::sweepNow);
            if (s.start()) {
                sServer = s;
                log("boot", "HTTP 服务已启动: " + s.localUrl()
                        + "  lan=" + s.lanBound + "  port=" + s.port);
                // 监控小程序包目录，抓到新的就提示
                MiniPacker.startWatcher(sApp);
                // 悬浮窗
                if (!"0".equals(System.getenv("XHSSPY_NO_FLOAT"))) {
                    FloatingWindow.show(sApp);
                }
            } else {
                log("crash", "HTTP 服务启动失败（端口全占用）");
            }
        } catch (Throwable t) {
            log("crash", "startServer " + t);
        }
    }

    // ------------------------------------------------------------ 转屏

    static void hookActivity(ClassLoader cl) {
        XC_MethodHook grab = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                try {
                    android.app.Activity a = (android.app.Activity) p.thisObject;
                    if (a != null) sActivity = a;
                } catch (Throwable ignored) {}
            }
        };
        try {
            XposedHelpers.findAndHookMethod(android.app.Activity.class,
                    "onResume", grab);
        } catch (Throwable ignored) {}
        try {
            XposedHelpers.findAndHookMethod(android.app.Activity.class,
                    "onCreate", android.os.Bundle.class, grab);
        } catch (Throwable ignored) {}
    }

    /** 强制当前 Activity 转屏。mode: land / port / auto / toggle */
    public static String rotate(String mode) {
        final android.app.Activity a = sActivity;
        if (a == null) return "{\"error\":\"找不到前台 Activity，先让小红书停在一个页面上\"}";
        String m = (mode == null || mode.isEmpty()) ? "toggle" : mode.toLowerCase(java.util.Locale.US);
        final int target;
        if ("land".equals(m)) target = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
        else if ("port".equals(m)) target = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT;
        else if ("auto".equals(m)) target = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
        else {
            // toggle：现在横的转竖，现在竖的转横
            int rot = 0;
            try {
                android.view.WindowManager wm = (android.view.WindowManager)
                        a.getSystemService(Context.WINDOW_SERVICE);
                rot = wm.getDefaultDisplay().getRotation();
            } catch (Throwable ignored) {}
            boolean land = (rot == android.view.Surface.ROTATION_90
                    || rot == android.view.Surface.ROTATION_270);
            target = land
                    ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                    : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
        }
        try {
            a.runOnUiThread(() -> {
                try { a.setRequestedOrientation(target); } catch (Throwable ignored) {}
            });
            return "{\"ok\":true,\"mode\":\"" + m + "\",\"orientation\":" + target
                    + ",\"activity\":\"" + a.getClass().getName() + "\"}";
        } catch (Throwable t) {
            return "{\"error\":\"" + t + "\"}";
        }
    }

    /** 运行时开关，返回当前全部状态 */
    public static String setSwitch(String name, String val) {
        boolean on = "1".equals(val) || "true".equalsIgnoreCase(val) || "on".equalsIgnoreCase(val);
        String n = name == null ? "" : name.toLowerCase(java.util.Locale.US);
        if ("body".equals(n)) sLogBody = on;
        else if ("file".equals(n)) sLogFile = on;
        else if ("net".equals(n)) sLogNet = on;
        else return "{\"error\":\"unknown switch\"}";
        return switches();
    }

    public static String switches() {
        return "{\"logBody\":" + sLogBody + ",\"logFile\":" + sLogFile
                + ",\"logNet\":" + sLogNet + "}";
    }

    static void sweepNow() {
        sSwept = false;
        sweep("manual");
    }

    // ------------------------------------------------------------ OkHttp

    static void hookOkHttp(ClassLoader cl) {
        Class<?> client = XposedHelpers.findClass("okhttp3.OkHttpClient", cl);
        Class<?> request = XposedHelpers.findClass("okhttp3.Request", cl);

        XposedHelpers.findAndHookMethod(client, "newCall", request,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            Object req = p.args[0];
                            String m = (String) XposedHelpers.callMethod(req, "method");
                            String u = String.valueOf(XposedHelpers.callMethod(req, "url"));

                            StringBuilder sb = new StringBuilder();
                            sb.append(m).append(' ').append(u);

                            // 请求体：小程序包下载、上报、登录都可能带
                            try {
                                Object body = XposedHelpers.callMethod(req, "body");
                                if (body != null) {
                                    Object contentType = XposedHelpers.callMethod(
                                            body, "contentType");
                                    long len = (Long) XposedHelpers.callMethod(
                                            body, "contentLength");
                                    if (contentType != null
                                            && String.valueOf(contentType).contains("json")
                                            && len > 0 && len < 200000) {
                                        Object okioBuf = XposedHelpers.newInstance(
                                                XposedHelpers.findClass("okio.Buffer", cl));
                                        XposedHelpers.callMethod(body, "writeTo", okioBuf);
                                        String s = (String) XposedHelpers.callMethod(
                                                okioBuf, "readUtf8");
                                        sb.append("\n    body: ").append(clip(s, 4000));
                                        XposedHelpers.callMethod(okioBuf, "close");
                                    }
                                }
                            } catch (Throwable ignored) {}

                            // 响应：execute 同步路径能拿到 peekBody
                            logNet(sb.toString());
                        } catch (Throwable t) {
                            log("crash", "okhttp " + t);
                        }
                    }
                });

        // 同步请求的响应体
        try {
            Class<?> realCall = XposedHelpers.findClass("okhttp3.RealCall", cl);
            XposedHelpers.findAndHookMethod(realCall, "execute", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    dumpResponse(p.getResult(), cl);
                }
            });
        } catch (Throwable ignored) {}

        // 异步请求：hook AsyncCall 的 run，结束后从这里拿不到 body，只记 URL
        try {
            Class<?> asyncCall = XposedHelpers.findClass("okhttp3.RealCall$AsyncCall", cl);
            XposedHelpers.findAndHookMethod(asyncCall, "run", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        Object call = XposedHelpers.getObjectField(p.thisObject, "this$0");
                        Object req = XposedHelpers.callMethod(call, "request");
                        String u = String.valueOf(XposedHelpers.callMethod(req, "url"));
                        logNet("ASYNC " + u);
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable ignored) {}
    }

    static void dumpResponse(Object resp, ClassLoader cl) {
        if (resp == null || !sLogBody) return;
        try {
            Object req = XposedHelpers.callMethod(resp, "request");
            String u = String.valueOf(XposedHelpers.callMethod(req, "url"));
            Object code = XposedHelpers.callMethod(resp, "code");

            Object body = XposedHelpers.callMethod(resp, "peekBody", 1024L * 1024L);
            String s = "";
            if (body != null) {
                s = String.valueOf(XposedHelpers.callMethod(body, "string"));
            }
            log("resp", code + " " + u + "\n    " + clip(s, 6000));

            // 二进制响应单独存，可能是包体
            if (isLikelyPackageUrl(u) && s != null) {
                saveRaw(u, s);
            }
        } catch (Throwable t) {
            log("crash", "dumpResponse " + t);
        }
    }

    static void saveRaw(String url, String body) {
        try {
            File dir = new File(OUT, "raw");
            if (!dir.exists()) dir.mkdirs();
            String name = url.replaceAll("[^A-Za-z0-9._-]", "_");
            if (name.length() > 120) name = name.substring(name.length() - 120);
            File f = new File(dir, name);
            FileOutputStream fos = new FileOutputStream(f, true);
            fos.write(("\n### " + url + "\n").getBytes("UTF-8"));
            fos.write(clip(body, 5 * 1024 * 1024).getBytes("UTF-8"));
            fos.close();
        } catch (Throwable ignored) {}
    }

    static boolean isLikelyPackageUrl(String u) {
        String l = u.toLowerCase();
        return l.contains(".zip") || l.contains(".pkg") || l.contains("mini")
                || l.contains("mpkg") || l.contains("rnbundle")
                || l.contains("bundle.js") || l.contains("app-service");
    }

    static void logNet(String s) {
        if (!sLogNet) return;
        log("net", s);
    }

    static String clip(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + " …(截断)";
    }

    // ------------------------------------------------------------ HttpURLConnection

    static void hookHttpUrlConnection(ClassLoader cl) {
        XposedHelpers.findAndHookMethod(java.net.URL.class, "openConnection",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            logNet("URLCONN " + p.thisObject.toString());
                        } catch (Throwable ignored) {}
                    }
                });
    }

    // ------------------------------------------------------------ WebView

    static void hookWebView(ClassLoader cl) {
        // 先把所有 WebView 实例登记下来 —— 不管它是怎么被 load 的
        try {
            XposedBridge.hookAllConstructors(android.webkit.WebView.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            try {
                                registerWebView(p.thisObject);
                                log("webview", "new WebView registered");
                            } catch (Throwable ignored) {}
                        }
                    });
        } catch (Throwable t) {
            log("crash", "hook webview ctor " + t);
        }

        for (Method m : WebView.class.getDeclaredMethods()) {
            if (m.getName().equals("loadUrl")) {
                final Class<?>[] types = m.getParameterTypes();
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            String u = String.valueOf(p.args[0]);
                            log("webview", "loadUrl " + u);
                            if (isLikelyPackageUrl(u)) saveRaw(u, "(loadUrl)");
                        } catch (Throwable ignored) {}
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            registerWebView(p.thisObject);
                        } catch (Throwable ignored) {}
                    }
                });
            }
            if (m.getName().equals("evaluateJavascript")) {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            log("webview", "evalJS " + clip(String.valueOf(p.args[0]), 2000));
                        } catch (Throwable ignored) {}
                    }
                });
            }
        }

        // 拦截 WebView 的资源请求（小程序包很多时候走这里）
        try {
            Class<?> wvc = XposedHelpers.findClass(
                    "android.webkit.WebViewClient", cl);
            for (Method m : wvc.getDeclaredMethods()) {
                if (!m.getName().equals("shouldInterceptRequest")) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            Object wr = p.args[1];
                            String u = (String) XposedHelpers.callMethod(wr, "getUrl");
                            log("webview", "intercept " + u);
                        } catch (Throwable ignored) {}
                    }
                });
            }
        } catch (Throwable ignored) {}

        // 小程序常见的 JSBridge 调用
        try {
            Class<?> bridge = XposedHelpers.findClass(
                    "android.webkit.JavascriptInterface", cl);
            log("boot", "jsbridge annotation found: " + (bridge != null));
        } catch (Throwable ignored) {}
    }

    // ------------------------------------------------------------ SSL unpinning

    static void hookSslPinning(ClassLoader cl) {
        // 注意：不能直接 hook javax.net.ssl.X509TrustManager 接口本身 ——
        // ART 下接口方法没有实现体，hook 上去是空转。
        // 正确姿势是等 SSLContext.init 时把真实的 TrustManager 实例抓出来，
        // 逐个 hook 它们自己的 checkServerTrusted。

        try {
            XposedHelpers.findAndHookMethod(
                    javax.net.ssl.SSLContext.class, "init",
                    javax.net.ssl.KeyManager[].class,
                    javax.net.ssl.TrustManager[].class,
                    java.security.SecureRandom.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            javax.net.ssl.TrustManager[] tms =
                                    (javax.net.ssl.TrustManager[]) p.args[1];
                            if (tms == null) return;
                            for (javax.net.ssl.TrustManager tm : tms) {
                                if (tm == null) continue;
                                neutralize(tm.getClass());
                            }
                        }
                    });
            log("ssl", "SSLContext.init hooked");
        } catch (Throwable t) {
            log("crash", "ssl init " + t);
        }

        // Conscrypt 的实现类，很多 App 直接用它绕开默认 TrustManager
        for (String cn : new String[]{
                "com.android.org.conscrypt.TrustManagerImpl",
                "org.conscrypt.TrustManagerImpl"}) {
            try {
                neutralize(XposedHelpers.findClass(cn, cl));
                log("ssl", "conscrypt neutralized " + cn);
            } catch (Throwable ignored) {}
        }

        // X509TrustManagerExtensions（OkHttp 走这条）
        try {
            XposedHelpers.findAndHookMethod(
                    X509TrustManagerExtensions.class, "checkServerTrusted",
                    X509Certificate[].class, String.class, String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            X509Certificate[] chain = (X509Certificate[]) p.args[0];
                            p.setResult(chain == null
                                    ? new java.util.ArrayList<X509Certificate>()
                                    : new java.util.ArrayList<>(
                                            java.util.Arrays.asList(chain)));
                        }
                    });
        } catch (Throwable ignored) {}

        // HostnameVerifier 一律放行
        try {
            XposedHelpers.findAndHookMethod(
                    javax.net.ssl.HttpsURLConnection.class,
                    "setHostnameVerifier", javax.net.ssl.HostnameVerifier.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            p.args[0] = new javax.net.ssl.HostnameVerifier() {
                                @Override
                                public boolean verify(String h,
                                                      javax.net.ssl.SSLSession s) {
                                    return true;
                                }
                            };
                        }
                    });
        } catch (Throwable ignored) {}
    }

    /** 把一个 TrustManager 实现类的所有 checkServerTrusted 变成空操作 */
    static void neutralize(Class<?> c) {
        if (c == null) return;
        for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
            if (!m.getName().equals("checkServerTrusted")) continue;
            try {
                XposedBridge.hookMethod(m, BYPASS);
            } catch (Throwable ignored) {}
        }
    }

    static final XC_MethodHook BYPASS = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam p) {
            try {
                Class<?> rt = ((java.lang.reflect.Method) p.method).getReturnType();
                if (rt == void.class) {
                    p.setResult(null);
                } else if (java.util.List.class.isAssignableFrom(rt)) {
                    // X509TrustManagerExtensions.checkServerTrusted 返回 List
                    p.setResult(new java.util.ArrayList<Object>());
                } else {
                    p.setResult(null);
                }
                log("ssl", "bypass " + p.method.getName());
            } catch (Throwable t) {
                p.setResult(null);
            }
        }
    };

    static void hookCertificatePinner(ClassLoader cl) {
        try {
            Class<?> cp = XposedHelpers.findClass("okhttp3.CertificatePinner", cl);
            for (Method m : cp.getDeclaredMethods()) {
                if (m.getName().equals("check")) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            p.setResult(null);
                        }
                    });
                }
            }
            log("ssl", "CertificatePinner neutralized");
        } catch (Throwable ignored) {}
    }

    // ------------------------------------------------------------ 文件写入监控

    static void hookFileWrite(ClassLoader cl) {
        // 关掉时连 hook 都不装，省掉每次文件写走一遍 Xposed 桥
        if (!sLogFile) return;
        XposedHelpers.findAndHookMethod(FileOutputStream.class, "<init>",
                File.class, boolean.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            File f = (File) p.args[0];
                            if (f == null) return;
                            String path = f.getAbsolutePath();
                            if (isHotPath(path)) {
                                log("file", "write " + path + " (" + f.length() + "B)");
                                // 小程序包写完后会再读，这里只记录；真正导出靠 sweep
                            }
                        } catch (Throwable ignored) {}
                    }
                });

        XposedHelpers.findAndHookMethod(FileOutputStream.class, "<init>",
                String.class, boolean.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            String path = (String) p.args[0];
                            if (path != null && isHotPath(path)) {
                                log("file", "write " + path);
                            }
                        } catch (Throwable ignored) {}
                    }
                });
    }

    static boolean isHotPath(String p) {
        String l = p.toLowerCase(Locale.US);
        if (!l.contains("/data/data/" + TARGET) && !l.contains("/data/user/0/" + TARGET))
            return false;
        for (String s : SKIP_EXT) if (l.endsWith(s)) return false;
        return l.endsWith(".zip") || l.contains("mini") || l.contains("tool")
                || l.contains(".pkg") || l.contains("bundle") || l.contains("resource");
    }

    // ------------------------------------------------------------ 目录扫描

    static void sweep(String tag) {
        if (sSwept || sApp == null) return;
        sSwept = true;
        try {
            File base = sApp.getFilesDir().getParentFile();
            if (base == null || !base.exists()) {
                log("pkg", "私有目录不可见: " + base);
                return;
            }
            if (!PKG_OUT.exists()) PKG_OUT.mkdirs();
            StringBuilder manifest = new StringBuilder();
            manifest.append("# XhsSpy 包体扫描 ").append(new Date()).append("\n");
            manifest.append("# base=").append(base.getAbsolutePath()).append("\n\n");
            int[] cnt = {0};
            walk(base, base, new File(PKG_OUT, tag), manifest, cnt, 0);
            File mf = new File(PKG_OUT, "manifest_" + tag + ".txt");
            FileOutputStream fos = new FileOutputStream(mf);
            fos.write(manifest.toString().getBytes("UTF-8"));
            fos.close();
            log("pkg", "sweep done, exported=" + cnt[0] + " -> " + mf);
        } catch (Throwable t) {
            log("crash", "sweep " + t);
        } finally {
            sSwept = false;
        }
    }

    static void walk(File base, File dir, File outRoot, StringBuilder mf,
                     int[] cnt, int depth) {
        if (depth > 8 || dir == null || !dir.isDirectory()) return;
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File f : kids) {
            try {
                if (f.isDirectory()) {
                    walk(base, f, outRoot, mf, cnt, depth + 1);
                    continue;
                }
                long len = f.length();
                if (len < MIN_PKG || len > MAX_PKG) continue;
                String path = f.getAbsolutePath();
                String rel = path.substring(base.getAbsolutePath().length());
                String low = path.toLowerCase(Locale.US);

                boolean skip = false;
                for (String s : SKIP_EXT) if (low.endsWith(s)) skip = true;
                if (skip) continue;

                boolean hot = false;
                for (String s : HOT_EXT) if (low.endsWith(s)) hot = true;
                if (!hot && !looksLikeZip(f)) continue;

                String inner = inspectZip(f);
                boolean pkg = inner != null && !inner.isEmpty();

                // 命中内部标志 → 高优先级
                String level = pkg ? "PKG" : (hot ? "hot" : "raw");
                mf.append(String.format(Locale.US, "[%s] %10d  %s%s\n",
                        level, len, rel, pkg ? ("  -> " + inner) : ""));

                // 只导出高优先级的，避免把整个 cache 搬空
                if (!pkg && len < 64 * 1024) continue;
                copyTo(f, new File(outRoot, rel.replace('/', '_')), mf, level);
                cnt[0]++;
            } catch (Throwable ignored) {}
        }
    }

    static boolean looksLikeZip(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[4];
            if (in.read(b) != 4) return false;
            return b[0] == 'P' && b[1] == 'K' && (b[2] == 3 || b[2] == 5 || b[2] == 7);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 打开 zip 看里面有没有小程序标志文件，返回命中项 */
    static String inspectZip(File f) {
        if (!looksLikeZip(f)) return "";
        try (ZipFile z = new ZipFile(f)) {
            Enumeration<? extends ZipEntry> en = z.entries();
            StringBuilder hit = new StringBuilder();
            int n = 0;
            while (en.hasMoreElements() && n < 400) {
                ZipEntry e = en.nextElement();
                n++;
                String name = e.getName().toLowerCase(Locale.US);
                for (String mark : PKG_INNER_MARKS) {
                    if (name.contains(mark)) {
                        if (hit.length() > 0) hit.append(',');
                        hit.append(e.getName());
                        break;
                    }
                }
                if (hit.length() > 200) break;
            }
            return hit.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    static void copyTo(File src, File dst, StringBuilder mf, String level) {
        try {
            File parent = dst.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            try (InputStream in = new FileInputStream(src);
                 FileOutputStream out = new FileOutputStream(dst)) {
                byte[] buf = new byte[65536];
                int r;
                long total = 0;
                while ((r = in.read(buf)) > 0) {
                    out.write(buf, 0, r);
                    total += r;
                    if (total > MAX_PKG) break;
                }
            }
            mf.append("         -> exported ").append(dst.getAbsolutePath())
                    .append("\n");
        } catch (Throwable t) {
            mf.append("         -> copy fail ").append(t).append("\n");
        }
    }
}
