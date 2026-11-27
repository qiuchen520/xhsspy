package com.rxw.xhsspy;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * MiniView — 小程序 / 小工具的浏览、打包、以及「小工具直接跑起来」
 *
 * 小工具是纯 H5（index.html + assets/app.js + assets/style.css），
 * 所以这里直接把它当静态站点托管出来：
 *
 *     /tool                     小工具列表
 *     /tool/<appid>/            index.html（自动注入 window.xhs 的模拟实现）
 *     /tool/<appid>/assets/x.js 资源文件
 *
 * 打开就能在浏览器里点，界面和交互是真的，只有联网 / AI 那类容器能力是假的。
 */
public class MiniView {

    // ------------------------------------------------------------ 列表页

    public static void listPage(OutputStream out) throws IOException {
        List<MiniPacker.Mini> tools = MiniPacker.listByType(MiniPacker.TYPE_TOOL);
        List<MiniPacker.Mini> apps = MiniPacker.listByType(MiniPacker.TYPE_APP);

        StringBuilder b = new StringBuilder();
        b.append("<div class=row style=\"margin-bottom:14px\">")
         .append("<a class=chip href=\"/mini\">刷新</a>")
         .append("<a class=chip href=\"/tool\">小工具预览</a>")
         .append("</div>");

        b.append("<h2>小工具 · ").append(tools.size()).append("</h2>");
        if (tools.isEmpty()) {
            b.append("<p class=hint>还没有缓存。<br>目录：<code>")
             .append(esc(MiniPacker.BASE_TOOL.getAbsolutePath()))
             .append("</code></p>");
        }
        for (MiniPacker.Mini m : tools) b.append(card(m));

        b.append("<h2>小程序 · ").append(apps.size()).append("</h2>");
        if (apps.isEmpty()) {
            b.append("<p class=hint>还没有缓存。<br>目录：<code>")
             .append(esc(MiniPacker.BASE_APP.getAbsolutePath()))
             .append("</code></p>");
        }
        for (MiniPacker.Mini m : apps) b.append(card(m));

        b.append("<h2>说明</h2>")
         .append("<p class=hint>小工具是纯 H5，<b>点「打开预览」能直接在浏览器里跑</b>，")
         .append("window.xhs 会被替换成模拟实现，所以界面和交互是真的，")
         .append("联网和 AI 返回假数据。</p>")
         .append("<p class=hint>下载的 zip 会同时存一份到手机「下载/XhsSpy」。</p>");

        send(out, page("小程序 / 小工具", b.toString()));
    }

    static String card(MiniPacker.Mini m) {
        String name = (m.name == null || m.name.isEmpty()) ? m.appid : m.name;
        String kind = m.isTool() ? "小工具" : "小程序";
        StringBuilder b = new StringBuilder();
        b.append("<div class=listitem>")
         .append("<div class=li-h><b>").append(esc(name)).append("</b>")
         .append("<span class=dim>").append(esc(kind)).append("</span></div>")
         .append("<div class=li-d>").append(esc(m.appid))
         .append(" · ").append(MiniPacker.human(m.bytes))
         .append(" · ").append(m.files).append(" 个文件");
        if (m.hasSource) b.append(" · <span class=ok>含 source/</span>");
        else b.append(" · <span class=bad>无 source/</span>");
        b.append("</div><div class=li-a>");

        if (m.isTool() && m.hasSource) {
            b.append("<a class=chip href=\"/tool/").append(esc(m.appid))
             .append("/\">打开预览</a>");
        }
        if (m.isTool() && m.hasSource) {
            String uq = "appid=" + enc(m.appid) + "&type=" + enc(m.type);
            b.append("<a class=chip href=\"/mini/upload?").append(uq)
             .append("\">导出可上传 zip</a>");
        }
        String qs = "appid=" + enc(m.appid) + "&type=" + enc(m.type);
        b.append("<a class=chip href=\"/mini/pack?").append(qs)
         .append("\">下载源码 zip</a>");
        b.append("<a class=chip href=\"/mini/share?").append(qs)
         .append("\">打包并分享</a>");
        b.append("<a class=chip href=\"/ls?p=").append(enc(m.path))
         .append("\">浏览目录</a>");
        b.append("</div></div>");
        return b.toString();
    }

    // ------------------------------------------------------------ 打包 / 分享

    public static void pack(OutputStream out, Map<String, String> q) throws IOException {
        MiniPacker.Mini m = MiniPacker.find(q.get("appid"), q.get("type"));
        if (m == null) {
            send(out, page("找不到", "<p>没有这个 appid</p>"));
            return;
        }
        try {
            File zip = MiniPacker.pack(XhsSpy.sApp, m);
            MiniPacker.saveToDownloads(XhsSpy.sApp, zip);
            XhsSpy.log("mini", "packed " + m.appid + " -> " + zip.getAbsolutePath());
            sendFile(out, zip);
        } catch (Exception e) {
            send(out, page("打包失败", "<p>" + esc(String.valueOf(e)) + "</p>"));
        }
    }

    /** 导出「可以直接上传」的 zip：根目录就是 index.html */
    public static void uploadZip(OutputStream out, Map<String, String> q) throws IOException {
        MiniPacker.Mini m = MiniPacker.find(q.get("appid"), q.get("type"));
        if (m == null || !m.isTool() || !m.hasSource) {
            send(out, page("不支持",
                    "<p>只有小工具才需要转上传包，而且要含 source/。</p>"
                  + "<p><a href=\"/mini\">返回</a></p>"));
            return;
        }
        try {
            File zip = MiniPacker.packForUpload(XhsSpy.sApp, m);
            MiniPacker.saveToDownloads(XhsSpy.sApp, zip);
            XhsSpy.log("mini", "upload-zip " + m.appid + " -> " + zip.getAbsolutePath());
            sendFile(out, zip);
        } catch (Exception e) {
            send(out, page("导出失败", "<p>" + esc(String.valueOf(e)) + "</p>"));
        }
    }

    public static void share(OutputStream out, Map<String, String> q) throws IOException {
        MiniPacker.Mini m = MiniPacker.find(q.get("appid"), q.get("type"));
        if (m == null) {
            send(out, page("找不到", "<p>没有这个 appid</p>"));
            return;
        }
        String r = MiniPacker.packAndShare(XhsSpy.sApp, m, true);
        send(out, page("已处理",
                "<p>" + esc(r) + "</p><p><a href=\"/mini\">返回</a></p>"
              + "<meta http-equiv=refresh content=\"1;url=/mini\">"));
    }

    // ------------------------------------------------------------ 小工具列表

    public static void toolList(OutputStream out) throws IOException {
        List<MiniPacker.Mini> tools = MiniPacker.listByType(MiniPacker.TYPE_TOOL);
        StringBuilder b = new StringBuilder();
        b.append("<p class=hint>共 ").append(tools.size())
         .append(" 个小工具。点进去就是它的首页，直接在浏览器里跑。</p>");
        b.append("<div class=grid>");
        for (MiniPacker.Mini m : tools) {
            String nm = (m.name == null || m.name.isEmpty()) ? m.appid : m.name;
            b.append("<a class=card href=\"/tool/").append(esc(m.appid)).append("/\">")
             .append("<b>").append(esc(nm)).append("</b>")
             .append("<span>").append(MiniPacker.human(m.bytes)).append("</span></a>");
        }
        b.append("</div>");
        if (tools.isEmpty()) b.append("<p>还没有小工具缓存。</p>");
        send(out, page("小工具预览", b.toString()));
    }

    // ------------------------------------------------------------ 静态托管

    public static void serve(OutputStream out, String path) throws IOException {
        String rest = path.substring("/tool/".length());
        int slash = rest.indexOf('/');
        String appid = slash < 0 ? rest : rest.substring(0, slash);
        String rel = slash < 0 ? "index.html" : rest.substring(slash + 1);
        if (rel.isEmpty() || rel.endsWith("/")) rel = rel + "index.html";

        MiniPacker.Mini m = MiniPacker.find(appid, MiniPacker.TYPE_TOOL);
        if (m == null || !m.hasSource) {
            send(out, page("找不到",
                    "<p>没有这个小工具，或者它还没下载完。</p>"
                  + "<p><a href=\"/tool\">返回列表</a></p>"));
            return;
        }
        try {
            File src = new File(m.sourceDir).getCanonicalFile();
            File f = new File(src, rel).getCanonicalFile();
            if (!f.getPath().startsWith(src.getPath())) {
                send(out, page("越界", "<p>路径不合法</p>"));
                return;
            }
            if (!f.isFile()) {
                send(out, page("404", "<p>文件不存在：" + esc(rel) + "</p>"));
                return;
            }
            String low = f.getName().toLowerCase(Locale.US);
            if (low.endsWith(".html") || low.endsWith(".htm")) {
                String html = MiniPacker.readText(f, 8 * 1024 * 1024);
                html = injectShim(html);
                send(out, html, "text/html; charset=utf-8");
                return;
            }
            sendBytes(out, mimeOf(low), readBytes(f));
        } catch (Exception e) {
            send(out, page("错误", "<p>" + esc(String.valueOf(e)) + "</p>"));
        }
    }

    static String mimeOf(String low) {
        if (low.endsWith(".js") || low.endsWith(".mjs")) return "application/javascript; charset=utf-8";
        if (low.endsWith(".css")) return "text/css; charset=utf-8";
        if (low.endsWith(".json")) return "application/json; charset=utf-8";
        if (low.endsWith(".svg")) return "image/svg+xml";
        if (low.endsWith(".png")) return "image/png";
        if (low.endsWith(".jpg") || low.endsWith(".jpeg")) return "image/jpeg";
        if (low.endsWith(".webp")) return "image/webp";
        if (low.endsWith(".gif")) return "image/gif";
        if (low.endsWith(".ico")) return "image/x-icon";
        if (low.endsWith(".woff")) return "font/woff";
        if (low.endsWith(".woff2")) return "font/woff2";
        if (low.endsWith(".ttf")) return "font/ttf";
        if (low.endsWith(".mp3")) return "audio/mpeg";
        if (low.endsWith(".mp4")) return "video/mp4";
        return "application/octet-stream";
    }

    static byte[] readBytes(File f) throws Exception {
        byte[] b = new byte[(int) f.length()];
        try (InputStream in = new FileInputStream(f)) {
            int off = 0;
            while (off < b.length) {
                int r = in.read(b, off, b.length - off);
                if (r <= 0) break;
                off += r;
            }
        }
        return b;
    }

    static String injectShim(String html) {
        String tag = "<script>" + SHIM + "</script>";
        String low = html.toLowerCase(Locale.US);
        int i = low.indexOf("</head>");
        if (i >= 0) return html.substring(0, i) + tag + html.substring(i);
        int j = low.indexOf("<body");
        if (j >= 0) return html.substring(0, j) + tag + html.substring(j);
        return tag + html;
    }

    // ------------------------------------------------------------ 输出

    static void send(OutputStream out, String html) throws IOException {
        send(out, html, "text/html; charset=utf-8");
    }

    static void send(OutputStream out, String body, String ctype) throws IOException {
        byte[] b = body.getBytes("UTF-8");
        String head = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: " + ctype + "\r\n"
                + "Content-Length: " + b.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes("UTF-8"));
        out.write(b);
        out.flush();
    }

    static void sendBytes(OutputStream out, String ctype, byte[] data) throws IOException {
        String head = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: " + ctype + "\r\n"
                + "Content-Length: " + data.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes("UTF-8"));
        out.write(data);
        out.flush();
    }

    static void sendFile(OutputStream out, File f) throws IOException {
        sendBytes(out, "application/zip", readBytesSafe(f));
    }

    static byte[] readBytesSafe(File f) {
        try {
            return readBytes(f);
        } catch (Exception e) {
            return new byte[0];
        }
    }

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    static String enc(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    static String page(String title, String body) {
        return "<!doctype html><html lang=zh><meta charset=utf-8>"
             + "<meta name=viewport content=\"width=device-width,initial-scale=1\">"
             + "<title>" + esc(title) + "</title><style>"
             + "*{box-sizing:border-box}"
             + "body{margin:0;padding:16px;font:15px/1.6 -apple-system,"
             + "\"PingFang SC\",\"Microsoft YaHei\",sans-serif;background:#fafafa;color:#1f1f22}"
             + "h1{font-size:19px;margin:0 0 14px;padding-bottom:10px;border-bottom:2px solid #ff2442}"
             + "h2{font-size:15px;margin:22px 0 10px;color:#ff2442}"
             + "a{color:#0a66c2;text-decoration:none}a:active{opacity:.6}"
             + ".grid{display:grid;grid-template-columns:1fr 1fr;gap:10px}"
             + ".card{display:flex;flex-direction:column;padding:14px;background:#fff;"
             + "border-radius:14px;box-shadow:0 2px 10px rgba(0,0,0,.06)}"
             + ".card b{color:#1f1f22;font-size:15px;word-break:break-all}"
             + ".card span{color:#8a8a95;font-size:12px;margin-top:4px}"
             + ".listitem{background:#fff;border-radius:14px;padding:14px;margin-bottom:10px;"
             + "box-shadow:0 2px 10px rgba(0,0,0,.06)}"
             + ".li-h{display:flex;align-items:center;gap:8px}"
             + ".li-h b{font-size:15px;color:#1f1f22}"
             + ".li-d{font-size:11px;color:#8a8a95;margin:5px 0 10px;word-break:break-all}"
             + ".li-a{display:flex;flex-wrap:wrap;gap:8px}"
             + ".li-a .chip{box-shadow:none;background:#f2f2f5}"
             + ".dim{color:#8a8a95;font-size:12px}"
             + ".ok{color:#12b76a;font-size:12px}.bad{color:#ff2442;font-size:12px}"
             + ".row{display:flex;flex-wrap:wrap;gap:8px}"
             + ".chip{padding:6px 12px;background:#fff;border-radius:999px;"
             + "font-size:13px;box-shadow:0 1px 4px rgba(0,0,0,.06);color:#4a4a52}"
             + ".hint{color:#8a8a95;font-size:12px;margin:10px 0;word-break:break-all}"
             + "code{background:#f0f0f3;padding:1px 5px;border-radius:4px;font-size:11px}"
             + "</style><h1>" + esc(title) + "</h1>" + body + "</html>";
    }

    // ------------------------------------------------------------ window.xhs 模拟

    static final String SHIM = """
(function(){
  if (window.xhs && window.xhs.miniTool) return;
  function cb(n,o,w){
    o = o || {};
    var has = (typeof o.success==='function')||(typeof o.fail==='function')||(typeof o.complete==='function');
    function run(res,rej){
      try{
        w(function(d){
          var r = Object.assign({errMsg:n+':ok'}, d||{});
          if(o.success)o.success(r);
          if(o.complete)o.complete(r);
          if(res)res(r);
        }, function(c,m){
          var e = {errMsg:n+':fail '+(m||'error'), errCode:c||-1};
          if(o.fail)o.fail(e);
          if(o.complete)o.complete(e);
          if(rej)rej(e);
        });
      }catch(e){ if(rej)rej(e); }
    }
    if(has){ run(null,null); return undefined; }
    return new Promise(run);
  }
  var mt = {
    setStorage:function(o){return cb('setStorage',o,function(ok){
      try{localStorage.setItem('__x__'+o.key,JSON.stringify(o.data));}catch(e){} ok({});});},
    getStorage:function(o){return cb('getStorage',o,function(ok){
      var v=null; try{v=JSON.parse(localStorage.getItem('__x__'+o.key));}catch(e){} ok({data:v});});},
    getStorageInfo:function(o){return cb('getStorageInfo',o,function(ok){
      ok({keys:[],currentSize:0,limitSize:10240});});},
    removeStorage:function(o){return cb('removeStorage',o,function(ok){
      try{localStorage.removeItem('__x__'+o.key);}catch(e){} ok({});});},
    clearStorage:function(o){return cb('clearStorage',o,function(ok){ok({});});},
    writeTempFile:function(o){return cb('writeTempFile',o,function(ok){
      try{
        var s=String(o.data||'').replace(/^data:[^;]+;base64,/,'');
        var bin=atob(s), u=new Uint8Array(bin.length);
        for(var i=0;i<bin.length;i++)u[i]=bin.charCodeAt(i);
        ok({filePath:URL.createObjectURL(new Blob([u],{type:o.mimeType||'application/octet-stream'}))});
      }catch(e){ ok({filePath:''}); }
    });},
    saveImageToPhotosAlbum:function(o){return cb('saveImageToPhotosAlbum',o,function(ok){
      var a=document.createElement('a'); a.href=o.filePath||''; a.download='img.png';
      document.body.appendChild(a); a.click(); a.remove(); ok({});});},
    postNote:function(o){return cb('postNote',o,function(ok){console.log('[shim] postNote',o);ok({});});},
    interactionOpenApi:function(o){return cb('interactionOpenApi',o,function(ok){ok({});});},
    getWindowInfo:function(o){return cb('getWindowInfo',o,function(ok){
      ok({windowWidth:innerWidth,windowHeight:innerHeight,pixelRatio:devicePixelRatio||1,
          statusBarHeight:0,
          safeArea:{top:0,bottom:innerHeight,left:0,right:innerWidth,width:innerWidth,height:innerHeight},
          menuButtons:[]});});},
    getLaunchOptions:function(o){return cb('getLaunchOptions',o,function(ok){
      ok({buildVersion:'9.60.0',query:{}});});},
    getLocation:function(o){return cb('getLocation',o,function(ok,bad){
      if(!navigator.geolocation)return bad(-1,'unsupported');
      navigator.geolocation.getCurrentPosition(
        function(p){ok({latitude:p.coords.latitude,longitude:p.coords.longitude});},
        function(){bad(-1,'denied');});});},
    vibrateShort:function(o){return cb('vibrateShort',o,function(ok){
      if(navigator.vibrate)navigator.vibrate(30); ok({});});},
    setKeepScreenOn:function(o){return cb('setKeepScreenOn',o,function(ok){ok({});});},
    share:function(o){return cb('share',o,function(ok){ok({});});},
    rewardAuthor:function(o){return cb('rewardAuthor',o,function(ok){ok({});});},
    setCloseRetentionEnabled:function(o){return cb('setCloseRetentionEnabled',o,function(ok){ok({});});},
    onGyroscopeChange:function(f){
      if(typeof f!=='function') throw new TypeError('need function');
      window.addEventListener('deviceorientation',function(e){
        f({x:(e.beta||0)*Math.PI/180, y:(e.gamma||0)*Math.PI/180, z:(e.alpha||0)*Math.PI/180});
      });
      return undefined;
    },
    offGyroscopeChange:function(){ return undefined; },
    AI:{ createModel:function(){
      return { generateText:function(req){
        console.log('[shim] AI.generateText', req);
        return Promise.resolve({text:'（模拟返回：AI 由小红书容器提供，脱离 App 后不可用）',
                                errMsg:'AI.generateText:ok'});
      }};
    }}
  };
  var x = window.xhs || {};
  x.miniTool = Object.assign(x.miniTool || {}, mt);
  x.miniToolEnv = {buildVersion:'9.60.0', platform:'android', debug:true};
  window.xhs = x;
  console.log('[shim] window.xhs.miniTool ready');
})();
""";
}
