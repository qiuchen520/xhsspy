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
            String h;
            int guard = 0;
            while ((h = readLine(in)) != null && !h.isEmpty() && guard++ < 200) {
                // 丢弃请求头
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
                sendHtml(out, 405, page("不支持", "<p>只支持 GET。</p>"));
                sock.close();
                return;
            }

            route(path, params, out);
            out.flush();
            sock.close();
        } catch (Exception ignored) {
            try { sock.close(); } catch (Exception ignored2) {}
        }
    }

    private void route(String path, Map<String, String> q, OutputStream out)
            throws IOException {
        switch (path) {
            case "/":            home(out, q); break;
            case "/ls":          listDir(out, q); break;
            case "/get":         getFile(out, q); break;
            case "/img":         imageWall(out, q); break;
            case "/log":         showLog(out, q); break;
            case "/pkg":         showPkg(out, q); break;
            case "/sweep":       doSweep(out, q); break;
            case "/json":        jsonDir(out, q); break;
            case "/api/status":  jsonStatus(out, q); break;
            case "/favicon.ico": sendBytes(out, 204, "text/plain", new byte[0]); break;
            default:             sendHtml(out, 404, page("404", "<p>" + esc(path) + "</p>"));
        }
    }

    // ------------------------------------------------------------ 页面

    private void home(OutputStream out, Map<String, String> q) throws IOException {
        StringBuilder b = new StringBuilder();
        b.append("<div class=grid>");
        b.append(card("/pkg", "包清单", "扫到的小程序 zip"));
        b.append(card("/img", "图片墙", "所有图片，可预览可下载"));
        b.append(card("/ls?p=" + enc(rootPath()), "私有目录", "浏览 /data/data"));
        b.append(card("/log?n=net", "网络日志", "所有请求 URL"));
        b.append(card("/log?n=file", "写文件日志", "看包落在哪"));
        b.append(card("/sweep", "重新扫描", "再翻一遍目录"));
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
        List<File> imgs = new ArrayList<>();
        for (File r : roots) {
            if (r != null) collectImages(r, imgs, 0, 600);
        }
        StringBuilder b = new StringBuilder();
        b.append("<p class=hint>共 ").append(imgs.size())
         .append(" 张。点图看原图，长按 / 右键可保存。</p>");
        b.append("<div class=wall>");
        // 新文件排前面
        Collections.sort(imgs, (a, c) -> Long.compare(c.lastModified(), a.lastModified()));
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

    // ------------------------------------------------------------ 文件工具

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

    private void collectImages(File dir, List<File> acc, int depth, int cap) {
        if (depth > 9 || acc.size() >= cap) return;
        if (!dir.isDirectory()) return;
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File f : kids) {
            if (acc.size() >= cap) return;
            if (f.isDirectory()) {
                if (f.getName().startsWith(".")) continue;
                collectImages(f, acc, depth + 1, cap);
            } else {
                String n = f.getName().toLowerCase(Locale.US);
                if (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png")
                        || n.endsWith(".webp") || n.endsWith(".gif")
                        || n.endsWith(".bmp") || n.endsWith(".avif")) {
                    if (f.length() > 512) acc.add(f);
                }
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
                + "</style><h1>" + esc(title) + "</h1>" + body + "</html>";
    }
}
