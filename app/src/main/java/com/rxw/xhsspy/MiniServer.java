package com.rxw.xhsspy;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MiniServer — 跑在小红书进程里的只读 HTTP 文件服务
 *
 * 为什么要它：模块已经拥有小红的全部文件权限，但手机上看不到 /data/data 目录。
 * 起一个本地 HTTP 服务，用浏览器就能浏览、预览、下载私有目录里的任何东西，
 * 包括小工具包里被用来「预览」的那些图片。
 *
 * 路由：
 *   GET  /                      首页
 *   GET  /ls?p=/abs/path        列目录
 *   GET  /get?p=/abs/path       下载 / 预览文件
 *   GET  /img                   图片墙（递归扫全部根目录）
 *   GET  /log?n=net             看日志（boot/net/resp/webview/file/ssl/crash/pkg）
 *   GET  /pkg                   包清单（manifest_*.txt）
 *   GET  /sweep                 触发一次目录扫描
 *   GET  /json?p=/abs/path      目录列表 JSON
 *   GET  /api/status            状态 JSON
 *
 * 安全：只读、只允许访问配置的根目录、路径穿越被挡。
 *       如果设置了 token，所有请求都必须带 ?k=<token>。
 */
public class MiniServer {

    public static final int PORT_START = 8686;
    public static final int PORT_TRIES = 6;

    private final File[] roots;
    private final String token;
    private final Runnable onSweep;

    public int port = PORT_START;
    public boolean lanBound = false;

    private ServerSocket ss;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private volatile boolean running = false;

    public MiniServer(File[] roots, String token, Runnable onSweep) {
        this.roots = roots == null ? new File[0] : roots;
        this.token = token == null ? "" : token.trim();
        this.onSweep = onSweep;
    }

    // ------------------------------------------------------------ 启停

    public boolean start() {
        // 先试 0.0.0.0（同一 WiFi 下电脑也能访问），失败再退 127.0.0.1
        for (int i = 0; i < PORT_TRIES && !running; i++) {
            int p = PORT_START + i;
            try {
                ServerSocket s = new ServerSocket();
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress("0.0.0.0", p), 8);
                ss = s; port = p; lanBound = true; running = true;
            } catch (Exception ignored) {}
        }
        if (!running) {
            for (int i = 0; i < PORT_TRIES && !running; i++) {
                int p = PORT_START + i;
                try {
                    ServerSocket s = new ServerSocket();
                    s.setReuseAddress(true);
                    s.bind(new InetSocketAddress(
                            InetAddress.getByName("127.0.0.1"), p), 8);
                    ss = s; port = p; lanBound = false; running = true;
                } catch (Exception ignored) {}
            }
        }
        if (!running) return false;

        Thread t = new Thread(() -> {
            while (running) {
                try {
                    final Socket c = ss.accept();
                    pool.submit(() -> handle(c));
                } catch (Exception e) {
                    if (!running) break;
                }
            }
        }, "XhsSpyHttp");
        t.setDaemon(true);
        t.start();
        return true;
    }

    public void stop() {
        running = false;
        try { if (ss != null) ss.close(); } catch (Exception ignored) {}
        try { pool.shutdownNow(); } catch (Exception ignored) {}
    }

    public String localUrl() {
        return "http://127.0.0.1:" + port + "/" + (token.isEmpty() ? "" : "?k=" + token);
    }

    // ------------------------------------------------------------ HTTP

    private void handle(Socket sock) {
        try {
            sock.setSoTimeout(20000);
            InputStream in = new BufferedInputStream(sock.getInputStream());
            OutputStream out = new BufferedOutputStream(sock.getOutputStream());

            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isEmpty()) {
                sock.close();
                return;
            }
            int contentLength = 0;
            String h;
            int guard = 0;
            while ((h = readLine(in)) != null && !h.isEmpty() && guard++ < 200) {
                int c = h.indexOf(':');
                if (c > 0 && h.substring(0, c).trim().equalsIgnoreCase("Content-Length")) {
                    try { contentLength = Integer.parseInt(h.substring(c + 1).trim()); }
                    catch (Exception ignored) {}
                }
            }
            byte[] body = new byte[0];
            if (contentLength > 0 && contentLength <= 8 * 1024 * 1024) {
                body = new byte[contentLength];
                int read = 0;
                while (read < contentLength) {
                    int r = in.read(body, read, contentLength - read);
                    if (r <= 0) break;
                    read += r;
                }
            }

            String[] p = requestLine.split(" ");
            if (p.length < 2) { sock.close(); return; }
            String method = p[0];
            String target = p[1];

            String path = target, query = "";
            int q = target.indexOf('?');
            if (q >= 0) {
                path = target.substring(0, q);
                query = target.substring(q + 1);
            }
            Map<String, String> params = parseQuery(query);

            if (!token.isEmpty() && !token.equals(params.get("k"))) {
                sendHtml(out, 401, page("需要口令",
                        "<p>请在 URL 后面加上 <code>?k=" + esc(token) + "</code></p>"));
                sock.close();
                return;
            }

            if (!"GET".equals(method) && !"POST".equals(method)) {
                sendHtml(out, 405, page("不支持", "<p>只支持 GET / POST。</p>"));
                sock.close();
                return;
            }

            route(path, params, out, body);
            out.flush();
            sock.close();
        } catch (Exception ignored) {
            try { sock.close(); } catch (Exception ignored2) {}
        }
    }

    private void route(String path, Map<String, String> q, OutputStream out, byte[] body)
            throws IOException {
        // 壁纸站：/wall 是页面，/wall/api/* 是接口
        if (path.startsWith("/wall/api/")) {
            sendJson(out, 200, Wall.apiRoute(path.substring("/wall/api/".length()), q));
            return;
        }
        if ("/wall".equals(path) || "/wall/".equals(path)) {
            try {
                Wall.page(out);
            } catch (Exception e) {
                sendHtml(out, 500, page("错误", "<p>" + esc(String.valueOf(e)) + "</p>"));
            }
            return;
        }

        if (path.startsWith("/tool/")) {
            MiniView.serve(out, path);
            return;
        }

        switch (path) {
            case "/":            home(out, q); break;
            case "/ls":          listDir(out, q); break;
            case "/get":         getFile(out, q); break;
            case "/img":         imageWall(out, q); break;
            case "/mini":        MiniView.listPage(out); break;
            case "/tool":        MiniView.toolList(out); break;

            case "/mini/pack":   MiniView.pack(out, q); break;
            case "/mini/share":  MiniView.share(out, q); break;
            case "/pageimg":     pageImageWall(out, q); break;
            case "/proxy":       proxyImage(out, q); break;
            case "/clearimg":    XhsSpy.clearPageImages();
                                 sendHtml(out, 200, page("已清空",
                                         "<p>图片记录已清空。<a href=\"/pageimg\">回去</a></p>"));
                                 break;
            case "/zipimg":      zipImage(out, q); break;
            case "/log":         showLog(out, q); break;
            case "/pkg":         showPkg(out, q); break;
            case "/sweep":       doSweep(out, q); break;
            case "/json":        jsonDir(out, q); break;
            case "/api/status":  jsonStatus(out, q); break;
            case "/mcp":         handleMcp(out, body); break;
            case "/favicon.ico": sendBytes(out, 204, "text/plain", new byte[0]); break;
            default:             sendHtml(out, 404, page("404", "<p>" + esc(path) + "</p>"));
        }
    }

    // ------------------------------------------------------------ 页面

    private void home(OutputStream out, Map<String, String> q) throws IOException {
        StringBuilder b = new StringBuilder();
        b.append("<div class=grid>");
        b.append(card("/mini?" + k(q), "★ 小程序源码", "已抓到的包，一键导出 zip"));
        b.append(card("/wall?" + k(q), "掘金壁纸", "壁纸站，能直接看图下原图"));
        b.append(card("/pageimg?" + k(q), "页面实时图片", "小工具里刷出来的图，自动收集"));
        b.append(card("/img?" + k(q) + "&src=pkg", "包内图片", "小工具 zip 里的素材"));
        b.append(card("/img?" + k(q) + "&src=all", "全部图片", "含缓存，噪声多"));
        b.append(card("/pkg?" + k(q), "包清单", "sweep 扫出来的 zip"));
        b.append(card("/ls?p=" + enc(rootPath()) + (k(q).isEmpty() ? "" : "&" + k(q)), "私有目录", "浏览 /data/data"));
        b.append(card("/log?n=net", "网络日志", "所有请求 URL"));
        b.append(card("/log?n=file", "写文件日志", "看包落在哪"));
        b.append(card("/sweep?" + k(q), "重新扫描", "再翻一遍目录"));
        b.append("</div>");

        b.append("<h2>根目录</h2><ul class=list>");
        for (File r : roots) {
            if (r == null) continue;
            b.append("<li><a href=\"/ls?").append(k(q))
             .append("&p=").append(enc(r.getAbsolutePath())).append("\">")
             .append(esc(r.getAbsolutePath())).append("</a> ")
             .append(r.isDirectory() ? "<span class=ok>可读</span>"
                                     : "<span class=bad>不可读</span>")
             .append("</li>");
        }
        b.append("</ul>");

        b.append("<h2>日志</h2><div class=row>");
        for (String n : new String[]{"boot", "net", "resp", "webview", "file", "ssl", "pkg", "crash"}) {
            b.append("<a class=chip href=\"/log?").append(k(q))
             .append("&n=").append(n).append("\">").append(n).append("</a>");
        }
        b.append("</div>");

        b.append("<p class=hint>本服务只读，绑定 ")
         .append(lanBound ? "0.0.0.0（同一 WiFi 可访问）" : "127.0.0.1（仅本机）")
         .append(" · 端口 ").append(port).append("</p>");

        sendHtml(out, 200, page("XhsSpy", b.toString()));
    }

    private void listDir(OutputStream out, Map<String, String> q) throws IOException {
        String p = q.get("p");
        File dir = resolve(p);
        if (dir == null || !dir.exists()) {
            sendHtml(out, 404, page("找不到", "<p>" + esc(String.valueOf(p)) + "</p>"));
            return;
        }
        if (!dir.isDirectory()) { sendFile(out, dir); return; }

        StringBuilder b = new StringBuilder();
        b.append("<p class=crumb>");
        b.append(crumb(dir, q));
        b.append("</p><table>");
        b.append("<tr><th>名称</th><th>大小</th><th>时间</th></tr>");

        File[] kids = dir.listFiles();
        if (kids != null) {
            Arrays.sort(kids, (a, c) -> {
                if (a.isDirectory() != c.isDirectory()) return a.isDirectory() ? -1 : 1;
                return a.getName().compareToIgnoreCase(c.getName());
            });
            for (File f : kids) {
                String name = f.getName() + (f.isDirectory() ? "/" : "");
                String href = f.isDirectory()
                        ? "/ls?" + k(q) + "&p=" + enc(f.getAbsolutePath())
                        : "/get?" + k(q) + "&p=" + enc(f.getAbsolutePath());
                b.append("<tr><td><a href=\"").append(href).append("\">")
                 .append(esc(name)).append("</a></td><td>")
                 .append(f.isDirectory() ? "-" : human(f.length()))
                 .append("</td><td class=dim>")
                 .append(fmt(f.lastModified())).append("</td></tr>");
            }
        }
        b.append("</table>");
        sendHtml(out, 200, page(dir.getName() + "/", b.toString()));
    }

    private void getFile(OutputStream out, Map<String, String> q) throws IOException {
        File f = resolve(q.get("p"));
        if (f == null || !f.isFile()) {
            sendHtml(out, 404, page("找不到", "<p>文件不存在或不在允许范围内</p>"));
            return;
        }
        sendFile(out, f);
    }

    private void imageWall(OutputStream out, Map<String, String> q) throws IOException {
        String src = q.get("src");
        if (src == null || src.isEmpty()) src = "pkg";

        StringBuilder b = new StringBuilder();
        b.append("<div class=row style=\"margin-bottom:12px\">");
        for (String s : new String[]{"pkg", "mini", "all"}) {
            b.append("<a class=chip href=\"/img?").append(k(q)).append("&src=").append(s)
             .append("\"").append(s.equals(src) ? " style=\"background:#ff2442;color:#fff\"" : "")
             .append(">").append(s.equals("pkg") ? "包内图片" : s.equals("mini") ? "小工具目录" : "全部")
             .append("</a>");
        }
        b.append("</div>");

        if ("pkg".equals(src)) {
            List<File> zips = new ArrayList<>();
            for (File r : roots) if (r != null) findZips(r, zips, 0, 60, 4096);
            Collections.sort(zips, (x, y) -> Long.compare(y.length(), x.length()));
            b.append("<p class=hint>共 ").append(zips.size()).append(" 个包。点图看原图。</p>");
            int shown = 0;
            for (File z : zips) {
                if (shown > 900) break;
                List<String> names = new ArrayList<>();
                try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(z)) {
                    java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
                    while (en.hasMoreElements()) {
                        java.util.zip.ZipEntry e = en.nextElement();
                        if (!e.isDirectory() && isImageName(e.getName())) names.add(e.getName());
                    }
                } catch (Exception ignored) { continue; }
                if (names.isEmpty()) continue;
                b.append("<h2>").append(esc(z.getName()))
                 .append(" <span class=dim>").append(human(z.length())).append("</span></h2>");
                b.append("<p class=dim style=\"word-break:break-all\">")
                 .append(esc(z.getAbsolutePath())).append("</p>");
                b.append("<div class=wall>");
                for (String n : names) {
                    if (shown++ > 900) break;
                    String href = "/zipimg?" + k(q) + "&z=" + enc(z.getAbsolutePath())
                            + "&e=" + enc(n);
                    b.append("<a class=thumb href=\"").append(href).append("\" target=_blank>")
                     .append("<img loading=lazy src=\"").append(href).append("\">")
                     .append("<span>").append(esc(n)).append("</span></a>");
                }
                b.append("</div>");
            }
            if (shown == 0) {
                b.append("<p>没在 zip 里找到图片。试试 <a href=\"/img?")
                 .append(k(q)).append("&src=all\">全部</a>，"
                 + "或者用 MCP 的 find_packages 看看有没有包。</p>");
            }
            sendHtml(out, 200, page("包内图片", b.toString()));
            return;
        }

        List<File> imgs = new ArrayList<>();
        for (File r : roots) if (r != null)
            collectImages(r, imgs, 0, 800, "mini".equals(src));
        Collections.sort(imgs, (x, y) -> Long.compare(y.lastModified(), x.lastModified()));
        b.append("<p class=hint>共 ").append(imgs.size()).append(" 张。点图看原图。</p>");
        b.append("<div class=wall>");
        for (File f : imgs) {
            String href = "/get?" + k(q) + "&p=" + enc(f.getAbsolutePath());
            b.append("<a class=thumb href=\"").append(href).append("\" target=_blank>")
             .append("<img loading=lazy src=\"").append(href).append("\">")
             .append("<span>").append(esc(f.getName())).append("</span>")
             .append("<i>").append(human(f.length())).append("</i></a>");
        }
        b.append("</div>");
        sendHtml(out, 200, page("图片墙", b.toString()));
    }

    /** 页面实时图片墙：图片 URL 由 XhsSpy 后台轮询收集，这里用代理显示 */
    private void pageImageWall(OutputStream out, Map<String, String> q) throws IOException {
        List<String> urls = XhsSpy.getPageImages();
        StringBuilder b = new StringBuilder();
        b.append("<div class=row style=\"margin-bottom:12px\">")
         .append("<a class=chip href=\"/pageimg?").append(k(q))
         .append("\">刷新</a><a class=chip href=\"/clearimg?").append(k(q))
         .append("\">清空</a><a class=chip href=\"/clearimg?").append(k(q))
         .append("&back=1\">清空</a></div>");
        b.append("<p class=hint>共 <b>").append(urls.size())
         .append("</b> 张，每 5 秒自动刷新收集。点图看原图，长按保存。</p>");

        if (urls.isEmpty()) {
            b.append("<p>还没有抓到图片 URL。<br>"
                    + "① 确认已打开小工具并且图片已经显示出来；<br>"
                    + "② 等 5~10 秒再刷新；<br>"
                    + "③ 还是空就看 <a href=\"/log?n=boot\">boot.log</a> 里有没有 "
                    + "<code>new WebView registered</code>。</p>");
        }

        b.append("<div class=wall>");
        java.util.Collections.reverse(urls);   // 新的在前
        int max = Math.min(urls.size(), 400);
        for (int i = 0; i < max; i++) {
            String u = urls.get(i);
            String href = "/proxy?" + k(q) + "&u=" + enc(u);
            String shortName = u.length() > 60 ? u.substring(u.length() - 60) : u;
            b.append("<a class=thumb href=\"").append(href).append("\" target=_blank>")
             .append("<img loading=lazy src=\"").append(href).append("\" ")
             .append("onerror=\"this.style.opacity=.25\">")
             .append("<span title=\"").append(esc(u)).append("\">")
             .append(esc(shortName)).append("</span></a>");
        }
        b.append("</div>");
        b.append("<p class=hint>如果大量图显示成半透明，说明 CDN 拒绝了代理请求，"
                + "把链接复制到浏览器直接打开可能更好。</p>");
        sendHtml(out, 200, page("页面实时图片", b.toString()));
    }

    /** 小程序源码列表页 */
    /** 代下载网络图片，带上小工具需要的 Referer */
    private void proxyImage(OutputStream out, Map<String, String> q) throws IOException {
        String u = q.get("u");
        if (u == null || !(u.startsWith("http://") || u.startsWith("https://"))) {
            sendHtml(out, 400, page("参数错误", "<p>需要一个 http(s) URL</p>"));
            return;
        }
        java.net.HttpURLConnection c = null;
        try {
            c = (java.net.HttpURLConnection) new java.net.URL(u).openConnection();
            c.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Version/4.0 Chrome/131.0.0.0 Mobile Safari/537.36");
            c.setRequestProperty("Referer", "https://www.xiaohongshu.com/");
            c.setRequestProperty("Accept", "image/avif,image/webp,image/*,*/*;q=0.8");
            c.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
            c.setConnectTimeout(15000);
            c.setReadTimeout(25000);
            c.setInstanceFollowRedirects(true);

            int code = c.getResponseCode();
            if (code >= 400) {
                sendBytes(out, code, "text/plain; charset=utf-8",
                        ("上游返回 " + code).getBytes("UTF-8"));
                return;
            }
            String ctype = c.getContentType();
            if (ctype == null || !ctype.startsWith("image/")) ctype = "image/jpeg";

            java.io.InputStream in = c.getInputStream();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int r, total = 0;
            while ((r = in.read(buf)) > 0) {
                bos.write(buf, 0, r);
                total += r;
                if (total > 8 * 1024 * 1024) break;
            }
            in.close();
            sendBytes(out, 200, ctype, bos.toByteArray());
        } catch (Exception ex) {
            sendBytes(out, 502, "text/plain; charset=utf-8",
                    ("下载失败: " + ex).getBytes("UTF-8"));
        } finally {
            if (c != null) try { c.disconnect(); } catch (Exception ignored) {}
        }
    }
    private void zipImage(OutputStream out, Map<String, String> q) throws IOException {
        File z = resolve(q.get("z"));
        String entry = q.get("e");
        if (z == null || entry == null) { sendHtml(out, 404, page("404", "<p>参数错误</p>")); return; }
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(z)) {
            java.util.zip.ZipEntry e = zf.getEntry(entry);
            if (e == null) { sendHtml(out, 404, page("404", "<p>条目不存在</p>")); return; }
            byte[] data;
            try (java.io.InputStream in = zf.getInputStream(e)) {
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[65536];
                int r;
                while ((r = in.read(buf)) > 0) bos.write(buf, 0, r);
                data = bos.toByteArray();
            }
            String n = entry.toLowerCase(Locale.US);
            String mime = "image/jpeg";
            if (n.endsWith(".png")) mime = "image/png";
            else if (n.endsWith(".webp")) mime = "image/webp";
            else if (n.endsWith(".gif")) mime = "image/gif";
            else if (n.endsWith(".bmp")) mime = "image/bmp";
            else if (n.endsWith(".avif")) mime = "image/avif";
            else if (n.endsWith(".svg")) mime = "image/svg+xml";
            sendBytes(out, 200, mime, data);
        } catch (Exception ex) {
            sendHtml(out, 500, page("错误", "<p>" + esc(String.valueOf(ex)) + "</p>"));
        }
    }

    private void showLog(OutputStream out, Map<String, String> q) throws IOException {
        String n = q.get("n");
        if (n == null || !n.matches("[a-zA-Z]+")) n = "boot";
        File f = new File(XhsSpy.outDir(), n + ".log");
        StringBuilder b = new StringBuilder();
        for (String x : new String[]{"boot", "net", "resp", "webview", "file", "ssl", "pkg", "crash"}) {
            b.append("<a class=chip").append(x.equals(n) ? " style=\"background:#ff2442;color:#fff\"" : "")
             .append(" href=\"/log?").append(k(q)).append("&n=").append(x).append("\">")
             .append(x).append("</a>");
        }
        b.append("<pre class=log>");
        if (!f.exists()) {
            b.append("（还没有 ").append(esc(n)).append(".log）");
        } else {
            String text = readTail(f, 400 * 1024);
            int lines = text.split("\n").length;
            b.append("<div class=dim>").append(f.length() / 1024).append(" KB / ")
             .append(lines).append(" 行（只显示最后 400 KB）</div>\n");
            b.append(esc(text));
        }
        b.append("</pre>");
        sendHtml(out, 200, page("日志 · " + n, b.toString()));
    }

    private void showPkg(OutputStream out, Map<String, String> q) throws IOException {
        File dir = new File(XhsSpy.outDir(), "pkg");
        StringBuilder b = new StringBuilder();
        File[] mfs = dir.listFiles((d, nm) -> nm.startsWith("manifest_") && nm.endsWith(".txt"));
        if (mfs == null || mfs.length == 0) {
            b.append("<p>还没有扫描结果。<a href=\"/sweep?").append(k(q))
             .append("\">点这里扫一遍</a></p>");
        } else {
            Arrays.sort(mfs);
            for (File mf : mfs) {
                b.append("<h2>").append(esc(mf.getName())).append("</h2>");
                String txt = readTail(mf, 1024 * 1024);
                b.append("<pre class=log>").append(esc(txt)).append("</pre>");
            }
        }
        // 直接列出 zip
        File[] zips = dir.listFiles((d, nm) -> !nm.startsWith("manifest_"));
        if (zips != null && zips.length > 0) {
            b.append("<h2>导出的文件</h2><table>");
            for (File z : zips) {
                b.append("<tr><td><a href=\"/get?").append(k(q)).append("&p=")
                 .append(enc(z.getAbsolutePath())).append("\">")
                 .append(esc(z.getName())).append("</a></td><td>")
                 .append(human(z.length())).append("</td></tr>");
            }
            b.append("</table>");
        }
        sendHtml(out, 200, page("包清单", b.toString()));
    }

    private void doSweep(OutputStream out, Map<String, String> q) throws IOException {
        if (onSweep != null) {
            try { onSweep.run(); } catch (Exception ignored) {}
        }
        sendHtml(out, 200, page("扫描完成",
                "<p>已触发一次目录扫描。<a href=\"/pkg?" + k(q) + "\">看结果</a></p>"
                + "<meta http-equiv=refresh content=\"2;url=/pkg?" + k(q) + "\">"));
    }

    private void jsonDir(OutputStream out, Map<String, String> q) throws IOException {
        File dir = resolve(q.get("p"));
        StringBuilder b = new StringBuilder("{\"path\":\"")
                .append(esc(dir == null ? "" : dir.getAbsolutePath())).append("\",\"files\":[");
        if (dir != null && dir.isDirectory()) {
            File[] kids = dir.listFiles();
            boolean first = true;
            if (kids != null) for (File f : kids) {
                if (!first) b.append(',');
                first = false;
                b.append("{\"name\":\"").append(esc(f.getName()))
                 .append("\",\"dir\":").append(f.isDirectory())
                 .append(",\"size\":").append(f.length())
                 .append(",\"mtime\":").append(f.lastModified()).append('}');
            }
        }
        b.append("]}");
        sendBytes(out, 200, "application/json; charset=utf-8",
                b.toString().getBytes("UTF-8"));
    }

    private void jsonStatus(OutputStream out, Map<String, String> q) throws IOException {
        StringBuilder b = new StringBuilder("{");
        b.append("\"port\":").append(port)
         .append(",\"lan\":").append(lanBound)
         .append(",\"roots\":[");
        boolean first = true;
        for (File r : roots) {
            if (r == null) continue;
            if (!first) b.append(',');
            first = false;
            b.append("{\"path\":\"").append(esc(r.getAbsolutePath()))
             .append("\",\"readable\":").append(r.canRead()).append('}');
        }
        b.append("],\"logs\":{");
        first = true;
        for (String n : new String[]{"boot", "net", "resp", "webview", "file", "ssl", "pkg", "crash"}) {
            File f = new File(XhsSpy.outDir(), n + ".log");
            if (!first) b.append(',');
            first = false;
            b.append('"').append(n).append("\":").append(f.exists() ? f.length() : 0);
        }
        b.append("}}");
        sendBytes(out, 200, "application/json; charset=utf-8",
                b.toString().getBytes("UTF-8"));
    }

    // ------------------------------------------------------------ MCP（Model Context Protocol）

    /**
     * MCP over HTTP，JSON-RPC 2.0。
     * 手机上的 MCP 客户端（比如 RikkaHub）连 http://127.0.0.1:8686/mcp 就能用，
     * 工具调用由客户端执行，结果回传给模型 —— 模型侧不需要能直连这台手机。
     */
    private void handleMcp(OutputStream out, byte[] body) throws IOException {
        Object id = null;
        try {
            String s = new String(body, "UTF-8").trim();
            if (s.isEmpty()) {
                sendJson(out, 400, rpcErr(null, -32700, "空请求体"));
                return;
            }
            org.json.JSONObject req = new org.json.JSONObject(s);
            if (req.has("id")) id = req.get("id");
            String method = req.optString("method", "");
            org.json.JSONObject params = req.optJSONObject("params");
            if (params == null) params = new org.json.JSONObject();

            if ("initialize".equals(method)) {
                org.json.JSONObject res = new org.json.JSONObject();
                res.put("protocolVersion", "2024-11-05");
                org.json.JSONObject caps = new org.json.JSONObject();
                caps.put("tools", new org.json.JSONObject());
                res.put("capabilities", caps);
                org.json.JSONObject info = new org.json.JSONObject();
                info.put("name", "xhsspy");
                info.put("version", "1.1.0");
                res.put("serverInfo", info);
                sendRpc(out, id, res);

            } else if (method.startsWith("notifications/")) {
                sendBytes(out, 202, "application/json", new byte[0]);

            } else if ("ping".equals(method)) {
                sendRpc(out, id, new org.json.JSONObject());

            } else if ("tools/list".equals(method)) {
                org.json.JSONObject res = new org.json.JSONObject();
                res.put("tools", toolDefs());
                sendRpc(out, id, res);

            } else if ("tools/call".equals(method)) {
                String name = params.optString("name", "");
                org.json.JSONObject args = params.optJSONObject("arguments");
                if (args == null) args = new org.json.JSONObject();
                sendRpc(out, id, callTool(name, args));

            } else if ("resources/list".equals(method) || "prompts/list".equals(method)) {
                org.json.JSONObject res = new org.json.JSONObject();
                res.put("resources", new org.json.JSONArray());
                res.put("prompts", new org.json.JSONArray());
                sendRpc(out, id, res);

            } else {
                sendJson(out, 200, rpcErr(id, -32601, "未知方法: " + method));
            }
        } catch (Exception e) {
            try { sendJson(out, 200, rpcErr(id, -32603, "内部错误: " + e)); }
            catch (Exception ignored) {}
        }
    }

    private void sendRpc(OutputStream out, Object id, org.json.JSONObject result)
            throws Exception {
        org.json.JSONObject o = new org.json.JSONObject();
        o.put("jsonrpc", "2.0");
        if (id != null) o.put("id", id);
        o.put("result", result);
        sendJson(out, 200, o.toString());
    }

    private void sendJson(OutputStream out, int code, String json) throws IOException {
        sendBytes(out, code, "application/json; charset=utf-8",
                json.getBytes("UTF-8"));
    }

    private static String rpcErr(Object id, int code, String msg) {
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("jsonrpc", "2.0");
            if (id != null) o.put("id", id);
            org.json.JSONObject e = new org.json.JSONObject();
            e.put("code", code);
            e.put("message", msg);
            o.put("error", e);
            return o.toString();
        } catch (Exception ex) {
            return "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32603,\"message\":\"encoder\"}}";
        }
    }

    // --- 工具定义 ---

    private org.json.JSONArray toolDefs() throws Exception {
        org.json.JSONArray arr = new org.json.JSONArray();
        arr.put(tool("ls", "列出目录内容。返回文件名、大小、类型、修改时间。",
                prop("path", "string", "绝对路径，例如 /data/data/com.xingin.xhs/files"),
                "path"));
        arr.put(tool("tree", "递归列出目录树，用于快速看清包结构。",
                prop("path", "string", "绝对路径"),
                prop("depth", "integer", "递归深度，默认 3，最大 8"),
                "path"));
        arr.put(tool("search", "按文件名关键字或扩展名搜索文件。",
                prop("path", "string", "起始目录，默认第一个根目录"),
                prop("name", "string", "文件名包含的关键字（不区分大小写）"),
                prop("ext", "string", "扩展名过滤，例如 .js / .json / .png"),
                prop("limit", "integer", "最多返回条数，默认 200")));
        arr.put(tool("read_file", "读取文本文件内容（建议只读小文件）。",
                prop("path", "string", "绝对路径"),
                prop("maxBytes", "integer", "最大读取字节，默认 200000"),
                "path"));
        arr.put(tool("read_image", "读取一张图片并以图片内容返回，可直接查看。",
                prop("path", "string", "图片绝对路径"),
                "path"));
        arr.put(tool("list_images", "列出图片。source=all 扫全部（噪声多），"
                        + "source=mini 只看小工具目录，source=pkg 只看 zip 包内的图片（最准）。",
                prop("path", "string", "起始目录，留空则扫全部根目录"),
                prop("source", "string", "all / mini / pkg，默认 pkg"),
                prop("limit", "integer", "最多返回条数，默认 100")));
        arr.put(tool("find_packages", "扫描所有目录找 zip/包文件，不依赖 sweep，返回完整路径。",
                prop("path", "string", "起始目录，留空扫全部根目录"),
                prop("minKB", "integer", "最小体积 KB，默认 4"),
                prop("limit", "integer", "最多返回条数，默认 200")));
        arr.put(tool("zip_list", "列出某个 zip 包内的所有条目（不解压，直接读）。",
                prop("path", "string", "zip 文件绝对路径"),
                prop("filter", "string", "只列文件名包含该关键字的条目"),
                "path"));
        arr.put(tool("zip_image", "直接从 zip 包里读一张图片并返回图片内容（不解压）。",
                prop("path", "string", "zip 文件绝对路径"),
                prop("entry", "string", "包内条目路径，例如 assets/cover.png"),
                "path", "entry"));
        arr.put(tool("stat", "查看单个文件或目录的详细信息。",
                prop("path", "string", "绝对路径"),
                "path"));
        arr.put(tool("tail_log", "读取模块日志尾部。name 可选 boot/net/resp/webview/file/ssl/pkg/crash。",
                prop("name", "string", "日志名，默认 boot"),
                prop("kb", "integer", "读取多少 KB，默认 100")));
        arr.put(tool("sweep", "触发一次私有目录扫描，重新导出小程序包。"));
        arr.put(tool("packages", "查看已导出的小程序包清单。"));
        arr.put(tool("page_images", "让当前打开的 WebView 页面自己把 DOM 里的图片 URL 全吐出来。"
                        + "小工具实时联网加载的图，用这个抓 URL 最准。"));
        arr.put(tool("fetch_image", "用小红书进程的网络身份下载一个图片 URL，直接返回图片内容。"
                        + "用来拿需要 Referer / Cookie 的 CDN 图。",
                prop("url", "string", "图片完整 URL"),
                "url"));
        arr.put(tool("fetch_text", "用小红书进程的网络身份下载一个 URL，返回文本（接口 JSON、JS 等）。",
                prop("url", "string", "完整 URL"),
                "url"));
        return arr;
    }

    private static org.json.JSONObject tool(String name, String desc, Object... rest)
            throws Exception {
        org.json.JSONObject t = new org.json.JSONObject();
        t.put("name", name);
        t.put("description", desc);
        org.json.JSONObject schema = new org.json.JSONObject();
        schema.put("type", "object");
        org.json.JSONObject props = new org.json.JSONObject();
        org.json.JSONArray required = new org.json.JSONArray();
        for (Object o : rest) {
            if (o instanceof String) {
                required.put((String) o);
            } else if (o instanceof org.json.JSONObject) {
                org.json.JSONObject p = (org.json.JSONObject) o;
                props.put(p.getString("__name"), p.getJSONObject("__schema"));
            }
        }
        schema.put("properties", props);
        if (required.length() > 0) schema.put("required", required);
        t.put("inputSchema", schema);
        return t;
    }

    private static org.json.JSONObject prop(String name, String type, String desc)
            throws Exception {
        org.json.JSONObject inner = new org.json.JSONObject();
        inner.put("type", type);
        inner.put("description", desc);
        org.json.JSONObject wrap = new org.json.JSONObject();
        wrap.put("__name", name);
        wrap.put("__schema", inner);
        return wrap;
    }

    // --- 工具执行 ---

    private org.json.JSONObject callTool(String name, org.json.JSONObject a) throws Exception {
        try {
            switch (name) {
                case "ls":         return text(toolLs(a.optString("path", rootPath())));
                case "tree":       return text(toolTree(a.optString("path", rootPath()),
                                            Math.min(a.optInt("depth", 3), 8)));
                case "search":     return text(toolSearch(a.optString("path", rootPath()),
                                            a.optString("name", ""), a.optString("ext", ""),
                                            Math.min(a.optInt("limit", 200), 2000)));
                case "read_file":  return text(toolReadFile(a.optString("path", ""),
                                            Math.min(a.optInt("maxBytes", 200000), 4 * 1024 * 1024)));
                case "read_image": return toolReadImage(a.optString("path", ""));
                case "list_images":return text(toolListImages(a.optString("path", ""),
                                            a.optString("source", "pkg"),
                                            Math.min(a.optInt("limit", 100), 800)));
                case "find_packages": return text(toolFindPackages(a.optString("path", ""),
                                            a.optInt("minKB", 4),
                                            Math.min(a.optInt("limit", 200), 2000)));
                case "zip_list":   return text(toolZipList(a.optString("path", ""),
                                            a.optString("filter", "")));
                case "zip_image":  return toolZipImage(a.optString("path", ""),
                                            a.optString("entry", ""));
                case "stat":       return text(toolStat(a.optString("path", "")));
                case "tail_log":   return text(toolTailLog(a.optString("name", "boot"),
                                            Math.min(a.optInt("kb", 100), 2048)));
                case "sweep":      if (onSweep != null) onSweep.run();
                                   return text("已触发扫描，稍后调用 packages 查看结果。");
                case "packages":   return text(toolPackages());
                case "roots":      return text(toolRoots());
                case "page_images":return text(XhsSpy.collectPageImages());
                case "fetch_image":return toolFetch(a.optString("url", ""), true);
                case "fetch_text": return toolFetch(a.optString("url", ""), false);
                default:           return errResult("未知工具: " + name);
            }
        } catch (Exception e) {
            return errResult("执行失败: " + e);
        }
    }

    private org.json.JSONObject text(String s) throws Exception {
        org.json.JSONArray arr = new org.json.JSONArray();
        org.json.JSONObject c = new org.json.JSONObject();
        c.put("type", "text");
        c.put("text", s);
        arr.put(c);
        org.json.JSONObject r = new org.json.JSONObject();
        r.put("content", arr);
        r.put("isError", false);
        return r;
    }

    private org.json.JSONObject errResult(String msg) throws Exception {
        org.json.JSONArray arr = new org.json.JSONArray();
        org.json.JSONObject c = new org.json.JSONObject();
        c.put("type", "text");
        c.put("text", msg);
        arr.put(c);
        org.json.JSONObject r = new org.json.JSONObject();
        r.put("content", arr);
        r.put("isError", true);
        return r;
    }

    private String toolRoots() {
        StringBuilder b = new StringBuilder("允许访问的根目录：\n");
        for (File r : roots) {
            if (r == null) continue;
            b.append("  ").append(r.getAbsolutePath())
             .append("  ").append(r.isDirectory() ? "目录可读" : "不存在").append('\n');
        }
        b.append("\nHTTP: http://127.0.0.1:").append(port)
         .append("/  |  MCP: http://127.0.0.1:").append(port).append("/mcp\n");
        return b.toString();
    }

    private String toolLs(String path) {
        File d = resolve(path);
        if (d == null) return "路径不在允许范围内: " + path;
        if (!d.exists()) return "不存在: " + path;
        if (d.isFile()) return toolStat(path);

        File[] kids = d.listFiles();
        if (kids == null) return "无法列出（权限不足）: " + path;
        Arrays.sort(kids, (x, y) -> {
            if (x.isDirectory() != y.isDirectory()) return x.isDirectory() ? -1 : 1;
            return x.getName().compareToIgnoreCase(y.getName());
        });
        StringBuilder b = new StringBuilder();
        b.append(d.getAbsolutePath()).append("  (").append(kids.length).append(" 项)\n\n");
        for (File f : kids) {
            b.append(f.isDirectory() ? "d " : "f ");
            b.append(String.format(Locale.US, "%10s  ", f.isDirectory() ? "-" : human(f.length())));
            b.append(String.format(Locale.US, "%10d  ", f.length()));
            b.append(fmt(f.lastModified())).append("  ");
            b.append(f.getName()).append(f.isDirectory() ? "/" : "").append('\n');
        }
        return b.toString();
    }

    private String toolTree(String path, int depth) {
        File d = resolve(path);
        if (d == null || !d.isDirectory()) return "不是目录: " + path;
        StringBuilder b = new StringBuilder();
        b.append(d.getAbsolutePath()).append('\n');
        treeRec(d, b, "", depth, 0);
        return b.toString();
    }

    private void treeRec(File dir, StringBuilder b, String prefix, int maxDepth, int d) {
        if (d >= maxDepth) return;
        File[] kids = dir.listFiles();
        if (kids == null) return;
        Arrays.sort(kids, (x, y) -> x.getName().compareToIgnoreCase(y.getName()));
        int shown = 0;
        for (int i = 0; i < kids.length; i++) {
            if (shown >= 80) { b.append(prefix).append("... (省略)\n"); return; }
            File f = kids[i];
            shown++;
            b.append(prefix).append(f.isDirectory() ? "├─ " : "│  ").append(f.getName());
            if (!f.isDirectory()) b.append("  (").append(human(f.length())).append(')');
            b.append('\n');
            if (f.isDirectory()) {
                treeRec(f, b, prefix + "│  ", maxDepth, d + 1);
            }
        }
    }

    private String toolSearch(String path, String name, String ext, int limit) {
        File start = path == null || path.isEmpty() ? null : resolve(path);
        if (start == null || !start.exists()) start = roots.length > 0 ? roots[0] : null;
        if (start == null) return "没有可搜索的目录";
        List<String> hits = new ArrayList<>();
        String nl = name == null ? "" : name.toLowerCase(Locale.US);
        String el = ext == null ? "" : ext.toLowerCase(Locale.US);
        if (!el.isEmpty() && !el.startsWith(".")) el = "." + el;
        searchRec(start, nl, el, hits, limit, 0, new int[]{0});
        StringBuilder b = new StringBuilder();
        b.append("命中 ").append(hits.size()).append(" 项");
        if (hits.size() >= limit) b.append("（已达上限）");
        b.append("\n\n");
        for (String s : hits) b.append(s).append('\n');
        return b.toString();
    }

    private void searchRec(File dir, String name, String ext, List<String> hits,
                           int limit, int depth, int[] visited) {
        if (depth > 10 || hits.size() >= limit || visited[0] > 40000) return;
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File f : kids) {
            if (hits.size() >= limit || visited[0] > 40000) return;
            visited[0]++;
            String n = f.getName().toLowerCase(Locale.US);
            if (f.isDirectory()) {
                if (!n.startsWith(".")) searchRec(f, name, ext, hits, limit, depth + 1, visited);
                continue;
            }
            if (!name.isEmpty() && !n.contains(name)) continue;
            if (!ext.isEmpty() && !n.endsWith(ext)) continue;
            hits.add(human(f.length()) + "\t" + f.getAbsolutePath());
        }
    }

    private String toolReadFile(String path, int maxBytes) {
        File f = resolve(path);
        if (f == null) return "路径不在允许范围内: " + path;
        if (!f.isFile()) return "不是文件: " + path;
        try {
            long len = f.length();
            int n = (int) Math.min(len, maxBytes);
            byte[] buf = new byte[n];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            int read = 0;
            while (read < n) {
                int r = in.read(buf, read, n - read);
                if (r <= 0) break;
                read += r;
            }
            in.close();
            String head = "文件: " + f.getAbsolutePath() + "\n大小: " + human(len)
                    + (len > n ? "（只读了前 " + human(n) + "）" : "") + "\n\n";
            return head + new String(buf, 0, read, "UTF-8");
        } catch (Exception e) {
            return "读取失败: " + e;
        }
    }

    private org.json.JSONObject toolReadImage(String path) throws Exception {
        File f = resolve(path);
        if (f == null || !f.isFile()) return errResult("不是可读文件: " + path);
        if (f.length() > 4 * 1024 * 1024) {
            return errResult("图片太大（" + human(f.length()) + "），超过 4 MB，"
                    + "请用 HTTP 下载: http://127.0.0.1:" + port + "/get?p=" + enc(f.getAbsolutePath()));
        }
        byte[] data = new byte[(int) f.length()];
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        int read = 0;
        while (read < data.length) {
            int r = in.read(data, read, data.length - read);
            if (r <= 0) break;
            read += r;
        }
        in.close();

        String n = f.getName().toLowerCase(Locale.US);
        String mime = "image/jpeg";
        if (n.endsWith(".png")) mime = "image/png";
        else if (n.endsWith(".webp")) mime = "image/webp";
        else if (n.endsWith(".gif")) mime = "image/gif";
        else if (n.endsWith(".bmp")) mime = "image/bmp";
        else if (n.endsWith(".avif")) mime = "image/avif";

        org.json.JSONArray arr = new org.json.JSONArray();
        org.json.JSONObject c = new org.json.JSONObject();
        c.put("type", "image");
        c.put("data", android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP));
        c.put("mimeType", mime);
        arr.put(c);

        org.json.JSONObject t = new org.json.JSONObject();
        t.put("type", "text");
        t.put("text", f.getName() + "  " + human(f.length()) + "  " + mime);
        arr.put(t);

        org.json.JSONObject r = new org.json.JSONObject();
        r.put("content", arr);
        r.put("isError", false);
        return r;
    }

    // --- 图片：三种来源 ---

    static boolean isImageName(String n) {
        String l = n.toLowerCase(Locale.US);
        return l.endsWith(".jpg") || l.endsWith(".jpeg") || l.endsWith(".png")
                || l.endsWith(".webp") || l.endsWith(".gif")
                || l.endsWith(".bmp") || l.endsWith(".avif") || l.endsWith(".svg");
    }

    /** 递归找 zip / 包文件，不依赖 sweep */
    private void findZips(File dir, List<File> acc, int depth, int maxDepth, long minBytes) {
        if (depth > maxDepth || acc.size() > 400) return;
        if (dir == null || !dir.isDirectory()) return;
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File f : kids) {
            if (acc.size() > 400) return;
            if (f.isDirectory()) {
                String n = f.getName();
                if (n.equals("lib") || n.equals("databases") || n.equals("shared_prefs"))
                    continue;
                findZips(f, acc, depth + 1, maxDepth, minBytes);
            } else if (f.length() >= minBytes) {
                String l = f.getName().toLowerCase(Locale.US);
                if (l.endsWith(".zip") || l.endsWith(".pkg") || l.endsWith(".wxapkg")
                        || l.endsWith(".redpkg") || l.endsWith(".mtpkg")
                        || l.endsWith(".rpkg")) {
                    acc.add(f);
                }
            }
        }
    }

    private String toolListImages(String path, String source, int limit) {
        if (source == null || source.isEmpty()) source = "pkg";
        StringBuilder b = new StringBuilder();

        if ("pkg".equals(source)) {
            List<File> zips = new ArrayList<>();
            if (path != null && !path.isEmpty()) {
                File d = resolve(path);
                if (d != null) findZips(d.isDirectory() ? d : d.getParentFile(), zips, 0, 60, 4096);
            } else {
                for (File r : roots) if (r != null) findZips(r, zips, 0, 60, 4096);
            }
            b.append("扫到 ").append(zips.size()).append(" 个 zip 包\n\n");
            int total = 0;
            for (File z : zips) {
                if (total >= limit) break;
                List<String> names = new ArrayList<>();
                try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(z)) {
                    java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
                    while (en.hasMoreElements() && names.size() < 300) {
                        java.util.zip.ZipEntry e = en.nextElement();
                        if (!e.isDirectory() && isImageName(e.getName())) names.add(e.getName());
                    }
                } catch (Exception ignored) { continue; }
                if (names.isEmpty()) continue;
                b.append("== ").append(z.getAbsolutePath())
                 .append("  (").append(human(z.length())).append(", ")
                 .append(names.size()).append(" 张图) ==\n");
                for (String n : names) {
                    if (total >= limit) { b.append("  ...(已达上限)\n"); break; }
                    b.append("  ").append(z.getAbsolutePath()).append("::").append(n).append('\n');
                    total++;
                }
                b.append('\n');
            }
            b.append("用 zip_image 读：path=<zip路径> entry=<包内路径>。\n");
            b.append("也可以直接看 http://127.0.0.1:").append(port).append("/img?src=pkg");
            return b.toString();
        }

        List<File> imgs = new ArrayList<>();
        if (path != null && !path.isEmpty()) {
            File d = resolve(path);
            if (d != null) collectImages(d.isDirectory() ? d : d.getParentFile(),
                    imgs, 0, limit, "mini".equals(source));
        } else {
            for (File r : roots) if (r != null)
                collectImages(r, imgs, 0, limit, "mini".equals(source));
        }
        Collections.sort(imgs, (x, y) -> Long.compare(y.lastModified(), x.lastModified()));
        b.append("图片 ").append(imgs.size()).append(" 张（新的在前，source=")
         .append(source).append("）\n\n");
        for (File f : imgs) {
            b.append(human(f.length())).append('\t')
             .append(fmt(f.lastModified())).append('\t')
             .append(f.getAbsolutePath()).append('\n');
        }
        b.append("\n用 read_image 传路径即可查看。");
        return b.toString();
    }

    private String toolFindPackages(String path, int minKB, int limit) {
        List<File> zips = new ArrayList<>();
        if (path != null && !path.isEmpty()) {
            File d = resolve(path);
            if (d != null) findZips(d.isDirectory() ? d : d.getParentFile(), zips, 0, 60,
                    Math.max(1, minKB) * 1024L);
        } else {
            for (File r : roots) if (r != null)
                findZips(r, zips, 0, 60, Math.max(1, minKB) * 1024L);
        }
        Collections.sort(zips, (x, y) -> Long.compare(y.length(), x.length()));
        StringBuilder b = new StringBuilder();
        b.append("找到 ").append(zips.size()).append(" 个包文件（按体积倒序）\n\n");
        int n = 0;
        for (File z : zips) {
            if (n++ >= limit) { b.append("...(已达上限)\n"); break; }
            String mark = "";
            try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(z)) {
                int imgs = 0, htmls = 0, jss = 0;
                java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
                while (en.hasMoreElements()) {
                    java.util.zip.ZipEntry e = en.nextElement();
                    if (e.isDirectory()) continue;
                    String nm = e.getName().toLowerCase(Locale.US);
                    if (isImageName(nm)) imgs++;
                    else if (nm.endsWith(".html")) htmls++;
                    else if (nm.endsWith(".js")) jss++;
                }
                mark = "  图" + imgs + " html" + htmls + " js" + jss;
            } catch (Exception ignored) { mark = "  (不是有效 zip)"; }
            b.append(human(z.length())).append('\t').append(z.getAbsolutePath()).append(mark).append('\n');
        }
        return b.toString();
    }

    private String toolZipList(String path, String filter) {
        File f = resolve(path);
        if (f == null || !f.isFile()) return "不是可读文件: " + path;
        String fl = filter == null ? "" : filter.toLowerCase(Locale.US);
        StringBuilder b = new StringBuilder();
        b.append("包: ").append(f.getAbsolutePath()).append('\n');
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(f)) {
            java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
            int all = 0, shown = 0;
            while (en.hasMoreElements()) {
                java.util.zip.ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                all++;
                String nm = e.getName();
                if (!fl.isEmpty() && !nm.toLowerCase(Locale.US).contains(fl)) continue;
                if (shown >= 800) { b.append("...(省略)\n"); break; }
                b.append(String.format(Locale.US, "%10s  ", human(e.getSize())))
                 .append(nm);
                if (isImageName(nm)) b.append("   [图片]");
                b.append('\n');
                shown++;
            }
            b.append("\n共 ").append(all).append(" 个条目，列出 ").append(shown).append(" 个。");
            b.append("\n读图用 zip_image，参数 path + entry。");
        } catch (Exception e) {
            return "不是有效 zip: " + e;
        }
        return b.toString();
    }

    private org.json.JSONObject toolZipImage(String zipPath, String entry) throws Exception {
        File z = resolve(zipPath);
        if (z == null || !z.isFile()) return errResult("zip 不可读: " + zipPath);
        if (entry == null || entry.isEmpty()) return errResult("缺少 entry 参数");
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(z)) {
            java.util.zip.ZipEntry e = zf.getEntry(entry);
            if (e == null) {
                java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
                while (en.hasMoreElements()) {
                    java.util.zip.ZipEntry x = en.nextElement();
                    if (x.getName().equals(entry) || x.getName().endsWith("/" + entry)) {
                        e = x; break;
                    }
                }
            }
            if (e == null) return errResult("包里没有这个条目: " + entry);
            if (e.getSize() > 4 * 1024 * 1024)
                return errResult("图片太大: " + human(e.getSize()));

            byte[] data;
            try (java.io.InputStream in = zf.getInputStream(e)) {
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[65536];
                int r;
                while ((r = in.read(buf)) > 0) bos.write(buf, 0, r);
                data = bos.toByteArray();
            }

            String n = e.getName().toLowerCase(Locale.US);
            String mime = "image/jpeg";
            if (n.endsWith(".png")) mime = "image/png";
            else if (n.endsWith(".webp")) mime = "image/webp";
            else if (n.endsWith(".gif")) mime = "image/gif";
            else if (n.endsWith(".bmp")) mime = "image/bmp";
            else if (n.endsWith(".avif")) mime = "image/avif";
            else if (n.endsWith(".svg")) mime = "image/svg+xml";

            org.json.JSONArray arr = new org.json.JSONArray();
            if ("image/svg+xml".equals(mime)) {
                org.json.JSONObject c = new org.json.JSONObject();
                c.put("type", "text");
                c.put("text", "SVG 源文件：\n\n" + new String(data, "UTF-8"));
                arr.put(c);
            } else {
                org.json.JSONObject c = new org.json.JSONObject();
                c.put("type", "image");
                c.put("data", android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP));
                c.put("mimeType", mime);
                arr.put(c);
            }
            org.json.JSONObject t = new org.json.JSONObject();
            t.put("type", "text");
            t.put("text", z.getName() + " :: " + e.getName() + "  " + human(data.length) + "  " + mime);
            arr.put(t);

            org.json.JSONObject res = new org.json.JSONObject();
            res.put("content", arr);
            res.put("isError", false);
            return res;
        } catch (Exception ex) {
            return errResult("读取失败: " + ex);
        }
    }

    private String toolStat(String path) {
        File f = resolve(path);
        if (f == null) return "路径不在允许范围内: " + path;
        if (!f.exists()) return "不存在: " + path;
        StringBuilder b = new StringBuilder();
        b.append("路径: ").append(f.getAbsolutePath()).append('\n');
        b.append("类型: ").append(f.isDirectory() ? "目录" : "文件").append('\n');
        b.append("大小: ").append(human(f.length())).append(" (").append(f.length()).append(" B)\n");
        b.append("修改: ").append(fmt(f.lastModified())).append('\n');
        b.append("可读: ").append(f.canRead()).append("  可写: ").append(f.canWrite()).append('\n');
        if (f.isDirectory()) {
            String[] k = f.list();
            b.append("子项: ").append(k == null ? "?" : k.length).append('\n');
        }
        return b.toString();
    }

    private String toolTailLog(String name, int kb) {
        if (name == null || !name.matches("[a-zA-Z]+")) name = "boot";
        File f = new File(XhsSpy.outDir(), name + ".log");
        if (!f.exists()) return "还没有 " + name + ".log";
        return "== " + name + ".log (" + human(f.length()) + ") ==\n\n"
                + readTail(f, kb * 1024);
    }

    /** 用 App 的网络身份下载一个 URL（图片或文本） */
    private org.json.JSONObject toolFetch(String url, boolean asImage) throws Exception {
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            return errResult("需要完整的 http(s) URL: " + url);
        }
        java.net.HttpURLConnection c = null;
        try {
            java.net.URL u = new java.net.URL(url);
            c = (java.net.HttpURLConnection) u.openConnection();
            c.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Version/4.0 Chrome/131.0.0.0 Mobile Safari/537.36");
            c.setRequestProperty("Referer", "https://www.xiaohongshu.com/");
            c.setRequestProperty("Accept", asImage ? "image/avif,image/webp,image/*,*/*;q=0.8"
                                                   : "*/*");
            c.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
            c.setConnectTimeout(15000);
            c.setReadTimeout(25000);
            c.setInstanceFollowRedirects(true);

            int code = c.getResponseCode();
            String ctype = c.getContentType();
            if (code >= 400) {
                return errResult("HTTP " + code + "  " + url);
            }

            java.io.InputStream in = c.getInputStream();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int r, total = 0;
            while ((r = in.read(buf)) > 0) {
                bos.write(buf, 0, r);
                total += r;
                if (total > 6 * 1024 * 1024) break;
            }
            in.close();
            byte[] data = bos.toByteArray();

            // 看 magic 判断是不是图
            boolean isImg = data.length > 4 && (
                    (data[0] == (byte) 0xFF && data[1] == (byte) 0xD8)
                    || (data[0] == (byte) 0x89 && data[1] == 'P')
                    || (data[0] == 'G' && data[1] == 'I')
                    || (data[0] == 'R' && data[1] == 'I')
                    || (data.length > 12 && data[8] == 'W' && data[9] == 'E'
                        && data[10] == 'B' && data[11] == 'P'));

            org.json.JSONArray arr = new org.json.JSONArray();
            if (isImg && asImage) {
                String mime = "image/jpeg";
                if (data[0] == (byte) 0x89) mime = "image/png";
                else if (data[0] == 'G') mime = "image/gif";
                else if (data.length > 12 && data[8] == 'W') mime = "image/webp";
                else if (data[0] == 'R') mime = "image/webp";

                org.json.JSONObject img = new org.json.JSONObject();
                img.put("type", "image");
                img.put("data", android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP));
                img.put("mimeType", mime);
                arr.put(img);

                org.json.JSONObject t = new org.json.JSONObject();
                t.put("type", "text");
                t.put("text", human(data.length) + "  " + mime + "\n" + url);
                arr.put(t);
            } else {
                String txt;
                if (isImg) {
                    txt = "这是个图片（" + human(data.length) + "），用 asImage 模式能直接看。";
                } else {
                    txt = new String(data, "UTF-8");
                    if (txt.length() > 200000) txt = txt.substring(0, 200000) + "\n…(截断)";
                }
                org.json.JSONObject t = new org.json.JSONObject();
                t.put("type", "text");
                t.put("text", "HTTP " + code + "  " + (ctype == null ? "" : ctype)
                        + "  " + human(data.length) + "\n" + url + "\n\n" + txt);
                arr.put(t);
            }

            org.json.JSONObject res = new org.json.JSONObject();
            res.put("content", arr);
            res.put("isError", false);
            return res;
        } catch (Exception ex) {
            return errResult("下载失败: " + ex + "\n" + url);
        } finally {
            if (c != null) try { c.disconnect(); } catch (Exception ignored) {}
        }
    }

    private String toolPackages() {
        File dir = new File(XhsSpy.outDir(), "pkg");
        if (!dir.exists()) return "还没有扫描结果，先调用 sweep。";
        StringBuilder b = new StringBuilder();
        File[] mfs = dir.listFiles((d, nm) -> nm.startsWith("manifest_"));
        if (mfs != null && mfs.length > 0) {
            Arrays.sort(mfs);
            for (File mf : mfs) {
                b.append("== ").append(mf.getName()).append(" ==\n");
                b.append(readTail(mf, 512 * 1024)).append("\n\n");
            }
        }
        File[] zips = dir.listFiles((d, nm) -> !nm.startsWith("manifest_"));
        if (zips != null && zips.length > 0) {
            b.append("== 已导出文件 ==\n");
            for (File z : zips) {
                b.append(human(z.length())).append('\t').append(z.getAbsolutePath()).append('\n');
            }
        }
        if (b.length() == 0) return "pkg 目录还是空的。";
        return b.toString();
    }

    /** 把用户给的路径解析成真实的 File，越界返回 null */
    private File resolve(String raw) {
        if (raw == null) return null;
        try {
            String p = URLDecoder.decode(raw, "UTF-8");
            File f = new File(p);
            String canon = f.getCanonicalPath();
            for (File r : roots) {
                if (r == null) continue;
                String rc = r.getCanonicalPath();
                if (canon.equals(rc) || canon.startsWith(rc + File.separator)) {
                    return f;
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private String rootPath() {
        for (File r : roots) if (r != null) return r.getAbsolutePath();
        return "/";
    }

    /** 缓存目录/头像/图标这些图对「找小工具素材」是纯噪声，smart 模式下跳过 */
    private static final String[] JUNK_DIRS = {
            "app_webview", "image_cache", "glide", "picasso", "fresco",
            "thumb", "thumbnail", "avatars", "avatar", "emoji", "sticker",
            "emojiCache", "splash", "share_img", ".thumbnails"
    };

    private void collectImages(File dir, List<File> acc, int depth, int cap, boolean smart) {
        if (depth > 9 || acc.size() >= cap) return;
        if (!dir.isDirectory()) return;
        String dn = dir.getName().toLowerCase(Locale.US);
        if (smart) {
            for (String j : JUNK_DIRS) {
                if (dn.contains(j)) return;
            }
            // mini 模式只收「像小工具」的目录
            if (depth > 0 && !dn.contains("mini") && !dn.contains("tool")
                    && !dn.contains("game") && !dn.contains("webapp")) {
                // 允许继续往下找，但本层文件不收
            }
        }
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File f : kids) {
            if (acc.size() >= cap) return;
            if (f.isDirectory()) {
                if (f.getName().startsWith(".")) continue;
                collectImages(f, acc, depth + 1, cap, smart);
            } else {
                String n = f.getName().toLowerCase(Locale.US);
                if (isImageName(n) && f.length() > 512) acc.add(f);
            }
        }
    }

    private void sendFile(OutputStream out, File f) throws IOException {
        String name = f.getName().toLowerCase(Locale.US);
        String type = "application/octet-stream";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) type = "image/jpeg";
        else if (name.endsWith(".png")) type = "image/png";
        else if (name.endsWith(".webp")) type = "image/webp";
        else if (name.endsWith(".gif")) type = "image/gif";
        else if (name.endsWith(".bmp")) type = "image/bmp";
        else if (name.endsWith(".avif")) type = "image/avif";
        else if (name.endsWith(".txt") || name.endsWith(".log") || name.endsWith(".json")
                || name.endsWith(".js") || name.endsWith(".html") || name.endsWith(".css"))
            type = "text/plain; charset=utf-8";
        else if (name.endsWith(".zip")) type = "application/zip";

        String head = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + f.length() + "\r\n"
                + "Content-Disposition: inline; filename*=UTF-8''"
                + URLEncoder.encode(f.getName(), "UTF-8") + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes("UTF-8"));
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[65536];
            int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
        }
        out.flush();
    }

    private void sendHtml(OutputStream out, int code, String html) throws IOException {
        sendBytes(out, code, "text/html; charset=utf-8", html.getBytes("UTF-8"));
    }

    private void sendBytes(OutputStream out, int code, String type, byte[] body)
            throws IOException {
        String head = "HTTP/1.1 " + code + " OK\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes("UTF-8"));
        if (body.length > 0) out.write(body);
        out.flush();
    }

    // ------------------------------------------------------------ 杂项

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder(128);
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
            if (sb.length() > 8192) break;
        }
        if (c == -1 && sb.length() == 0) return null;
        return sb.toString();
    }

    private static Map<String, String> parseQuery(String q) {
        Map<String, String> m = new LinkedHashMap<>();
        if (q == null || q.isEmpty()) return m;
        for (String kv : q.split("&")) {
            if (kv.isEmpty()) continue;
            int i = kv.indexOf('=');
            try {
                if (i < 0) m.put(URLDecoder.decode(kv, "UTF-8"), "");
                else m.put(URLDecoder.decode(kv.substring(0, i), "UTF-8"),
                           URLDecoder.decode(kv.substring(i + 1), "UTF-8"));
            } catch (Exception ignored) {}
        }
        return m;
    }

    private String k(Map<String, String> q) {
        String t = q.get("k");
        return t == null || t.isEmpty() ? "" : "k=" + enc(t);
    }

    private static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); }
        catch (Exception e) { return s; }
    }

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    static String human(long n) {
        if (n < 1024) return n + " B";
        if (n < 1048576) return String.format(Locale.US, "%.1f KB", n / 1024.0);
        if (n < 1073741824L) return String.format(Locale.US, "%.1f MB", n / 1048576.0);
        return String.format(Locale.US, "%.2f GB", n / 1073741824.0);
    }

    private static String fmt(long ts) {
        return new SimpleDateFormat("MM-dd HH:mm", Locale.US).format(new Date(ts));
    }

    private static String readTail(File f, int max) {
        try {
            long len = f.length();
            long skip = Math.max(0, len - max);
            try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r")) {
                raf.seek(skip);
                byte[] buf = new byte[(int) Math.min(len - skip, max)];
                raf.readFully(buf);
                return new String(buf, "UTF-8");
            }
        } catch (Exception e) {
            return "（读取失败：" + e + "）";
        }
    }

    private String crumb(File dir, Map<String, String> q) {
        StringBuilder b = new StringBuilder();
        List<File> chain = new ArrayList<>();
        File cur = dir;
        while (cur != null) { chain.add(0, cur); cur = cur.getParentFile(); }
        for (int i = 0; i < chain.size(); i++) {
            File f = chain.get(i);
            if (i > 0) b.append(" / ");
            b.append("<a href=\"/ls?").append(k(q)).append("&p=")
             .append(enc(f.getAbsolutePath())).append("\">")
             .append(esc(f.getName().isEmpty() ? "/" : f.getName())).append("</a>");
        }
        return b.toString();
    }

    private static String card(String href, String title, String sub) {
        return "<a class=card href=\"" + esc(href) + "\"><b>" + esc(title)
                + "</b><span>" + esc(sub) + "</span></a>";
    }

    private static String page(String title, String body) {
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
                + ".card b{color:#1f1f22;font-size:15px}"
                + ".card span{color:#8a8a95;font-size:12px;margin-top:4px}"
                + "table{width:100%;border-collapse:collapse;background:#fff;"
                + "border-radius:12px;overflow:hidden;font-size:13px}"
                + "th,td{padding:9px 10px;text-align:left;border-bottom:1px solid #eee}"
                + "th{background:#f2f2f5;font-weight:600;color:#4a4a52}"
                + "tr:last-child td{border-bottom:none}"
                + ".dim{color:#8a8a95;font-size:12px;white-space:nowrap}"
                + ".ok{color:#12b76a;font-size:12px}.bad{color:#ff2442;font-size:12px}"
                + ".list{padding-left:18px}.list li{margin:6px 0;font-size:13px;word-break:break-all}"
                + ".row{display:flex;flex-wrap:wrap;gap:8px}"
                + ".chip{padding:6px 12px;background:#fff;border-radius:999px;"
                + "font-size:13px;box-shadow:0 1px 4px rgba(0,0,0,.06);color:#4a4a52}"
                + ".log{background:#1c1c1f;color:#d7d7dd;padding:12px;border-radius:12px;"
                + "font:12px/1.55 ui-monospace,Menlo,monospace;overflow:auto;"
                + "white-space:pre-wrap;word-break:break-all;max-height:70vh}"
                + ".hint{color:#8a8a95;font-size:12px;margin:14px 0 0}"
                + ".crumb{font-size:13px;word-break:break-all;background:#fff;padding:10px;border-radius:10px}"
                + ".wall{display:grid;grid-template-columns:repeat(auto-fill,minmax(104px,1fr));gap:8px}"
                + ".thumb{position:relative;display:block;background:#fff;border-radius:10px;"
                + "overflow:hidden;box-shadow:0 2px 8px rgba(0,0,0,.07)}"
                + ".thumb img{width:100%;height:104px;object-fit:cover;display:block;"
                + "background:#eee}"
                + ".thumb span{display:block;font-size:10px;padding:4px 6px;"
                + "white-space:nowrap;overflow:hidden;text-overflow:ellipsis;color:#4a4a52}"
                + ".thumb i{position:absolute;top:4px;right:4px;background:rgba(0,0,0,.6);"
                + "color:#fff;font-size:10px;padding:1px 5px;border-radius:6px;font-style:normal}"
                + ".listitem{background:#fff;border-radius:14px;padding:14px;margin-bottom:10px;"
                + "box-shadow:0 2px 10px rgba(0,0,0,.06)}"
                + ".li-h{display:flex;align-items:center;gap:8px}"
                + ".li-h b{font-size:15px;color:#1f1f22}"
                + ".li-d{font-size:11px;color:#8a8a95;margin:5px 0 10px;word-break:break-all}"
                + ".li-a{display:flex;flex-wrap:wrap;gap:8px}"
                + ".li-a .chip{box-shadow:none;background:#f2f2f5}"
                + "</style><h1>" + esc(title) + "</h1>" + body + "</html>";
    }
}
