package com.rxw.xhsspy;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * MiniPacker — 小程序 / 小工具 源码抓取、打包、托管
 *
 * 小红书里其实有两套东西，目录也不一样：
 *
 *   小程序  /data/data/com.xingin.xhs/files/redmpV2/release/<appid>/<hash>/source/
 *           app.json + service.js + pages/*.render.js   （自研框架，JS 渲染）
 *
 *   小工具  /data/data/com.xingin.xhs/files/mini_tool/release/<appid>/<hash>/source/
 *           index.html + assets/app.js + assets/style.css   （纯 H5，能直接跑）
 *
 * 两套都是明文的，直接能读。
 */
public class MiniPacker {

    public static final File BASE_APP =
            new File("/data/data/com.xingin.xhs/files/redmpV2/release");
    public static final File BASE_TOOL =
            new File("/data/data/com.xingin.xhs/files/mini_tool/release");

    /** 兼容旧代码 */
    public static final File BASE = BASE_APP;

    public static final String TYPE_APP = "app";
    public static final String TYPE_TOOL = "tool";

    public static class Mini {
        public String type = TYPE_APP;
        public String appid = "";
        public String name = "";
        public String version = "";
        public String hashDir = "";
        public String sourceDir = "";
        public long bytes = 0;
        public int files = 0;
        public boolean hasSource = false;
        public long mtime = 0;
        public String path = "";

        public boolean isTool() {
            return TYPE_TOOL.equals(type);
        }

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("type", type);
                o.put("appid", appid);
                o.put("name", name);
                o.put("version", version);
                o.put("bytes", bytes);
                o.put("files", files);
                o.put("hasSource", hasSource);
                o.put("path", path);
                o.put("sourceDir", sourceDir);
                o.put("mtime", mtime);
            } catch (Exception ignored) {}
            return o;
        }
    }

    public static String human(long n) {
        if (n < 1024) return n + " B";
        if (n < 1048576) return String.format(Locale.US, "%.1f KB", n / 1024.0);
        if (n < 1073741824L) return String.format(Locale.US, "%.2f MB", n / 1048576.0);
        return String.format(Locale.US, "%.2f GB", n / 1073741824.0);
    }

    // ------------------------------------------------------------ 扫描

    // 扫一遍要递归 count 所有文件，125 个小工具目录下很贵，缓存 60 秒
    static volatile List<Mini> sCache = null;
    static volatile long sCacheAt = 0;
    static final long CACHE_MS = 60000;

    public static List<Mini> list() { return list(false); }

    public static List<Mini> listFresh() { return list(true); }

    static synchronized List<Mini> list(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && sCache != null && now - sCacheAt < CACHE_MS) return sCache;
        List<Mini> out = new ArrayList<>();
        scanRoot(BASE_APP, TYPE_APP, out);
        scanRoot(BASE_TOOL, TYPE_TOOL, out);
        out.sort((a, b) -> Long.compare(b.mtime, a.mtime));
        sCache = out;
        sCacheAt = now;
        return out;
    }

    public static synchronized void invalidate() {
        sCache = null;
        sCacheAt = 0;
    }

    /** 只列目录名，不递归。用来判断有没有新包，代价极低 */
    static String dirSig(File base) {
        File[] ks;
        try { ks = base.listFiles(); } catch (Throwable t) { return ""; }
        if (ks == null) return "";
        ArrayList<String> names = new ArrayList<>();
        for (File k : ks) if (k.isDirectory()) names.add(k.getName());
        java.util.Collections.sort(names);
        return names.toString();
    }

    public static List<Mini> listByType(String type) {
        List<Mini> out = new ArrayList<>();
        for (Mini m : list()) if (m.type.equals(type)) out.add(m);
        return out;
    }

    static void scanRoot(File base, String type, List<Mini> out) {
        File[] kids = base.listFiles();
        if (kids == null) return;
        for (File d : kids) {
            if (!d.isDirectory()) continue;
            Mini m = read(d, type);
            if (m != null) out.add(m);
        }
    }

    public static Mini read(File appDir, String type) {
        if (appDir == null || !appDir.isDirectory()) return null;
        Mini m = new Mini();
        m.type = type;
        m.appid = appDir.getName();
        m.path = appDir.getAbsolutePath();
        m.mtime = appDir.lastModified();

        File[] subs = appDir.listFiles();
        File newest = null;
        if (subs != null) {
            for (File s : subs) {
                if (!s.isDirectory()) continue;
                String n = s.getName();
                if ("usr".equals(n) || "tmp".equals(n)) continue;
                if (newest == null || s.lastModified() > newest.lastModified()) newest = s;
            }
        }
        if (newest == null) return null;
        m.hashDir = newest.getName();
        m.sourceDir = new File(newest, "source").getAbsolutePath();
        m.hasSource = new File(newest, "source").isDirectory();

        if (m.hasSource) {
            m.name = TYPE_TOOL.equals(type)
                    ? readToolName(new File(m.sourceDir), m.appid)
                    : readAppName(new File(m.sourceDir), m.appid);
            m.version = readVersion(new File(m.sourceDir));
        } else {
            m.name = m.appid;
        }

        long[] acc = new long[]{0, 0};
        count(newest, acc, 0);
        m.bytes = acc[0];
        m.files = (int) acc[1];
        return m;
    }

    static void count(File f, long[] acc, int depth) {
        if (depth > 12) return;
        if (f.isDirectory()) {
            File[] ks = f.listFiles();
            if (ks == null) return;
            for (File k : ks) count(k, acc, depth + 1);
        } else {
            acc[0] += f.length();
            acc[1]++;
        }
    }

    // --- 名字解析 ---

    static final Pattern TITLE = Pattern.compile(
            "<title[^>]*>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** 小工具：从 index.html 的 <title> 里取 */
    static String readToolName(File src, String fallback) {
        File idx = new File(src, "index.html");
        if (idx.isFile()) {
            String t = readText(idx, 65536);
            Matcher mm = TITLE.matcher(t);
            if (mm.find()) {
                String n = mm.group(1).trim();
                if (n.isEmpty()) n = charSet(t, "charset=utf-8");
                if (n != null && !n.isEmpty()) return n;
            }
        }
        // 退到 mini_tool.json
        File j = new File(src, "mini_tool.json");
        if (j.isFile()) {
            try {
                JSONObject o = new JSONObject(readText(j, 8192));
                String n = o.optString("name", "");
                if (!n.isEmpty()) return n;
            } catch (Throwable ignored) {}
        }
        return fallback;
    }

    static String charSet(String html, String key) {
        return null;
    }

    /** 小程序：从 project.config.json 的 projectname 里取，去掉尾部 appid */
    static String readAppName(File src, String fallback) {
        try {
            File pj = new File(src, "project.config.json");
            if (!pj.isFile()) return fallback;
            String t = readText(pj, 65536);
            JSONObject o = new JSONObject(t);
            String n = o.optString("projectname", "");
            if (!n.isEmpty()) {
                if (n.endsWith(fallback)) n = n.substring(0, n.length() - fallback.length());
                return n.trim().isEmpty() ? fallback : n.trim();
            }
        } catch (Throwable ignored) {}
        return fallback;
    }

    static String readVersion(File src) {
        try {
            File p = new File(src, "pkgInfo.json");
            if (p.isFile()) {
                return new JSONObject(readText(p, 4096)).optString("version", "");
            }
        } catch (Throwable ignored) {}
        return "";
    }

    static String readText(File f, int max) {
        try (FileInputStream in = new FileInputStream(f)) {
            int len = (int) Math.min(f.length(), max);
            byte[] b = new byte[Math.max(len, 0)];
            int r = in.read(b);
            return new String(b, 0, Math.max(r, 0), "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    // ------------------------------------------------------------ 打包

    public static File pack(Context ctx, Mini m) throws Exception {
        File outDir = new File(ctx.getCacheDir(), "minipack");
        if (!outDir.exists() && !outDir.mkdirs()) throw new Exception("建目录失败");
        String safe = m.name == null || m.name.isEmpty() ? m.appid : m.name;
        safe = safe.replaceAll("[^a-zA-Z0-9._\\u4e00-\\u9fa5-]", "_");
        String prefix = m.isTool() ? "小工具" : "小程序";
        File zip = new File(outDir, prefix + "_" + safe + "_" + m.appid + ".zip");

        File root = new File(m.path);
        FileOutputStream fos = new FileOutputStream(zip);
        ZipOutputStream zos = new ZipOutputStream(fos);
        try {
            addDir(zos, root, root, 0);
        } finally {
            try { zos.close(); } catch (Exception ignored) {}
            try { fos.close(); } catch (Exception ignored) {}
        }
        return zip;
    }

    /** 上传用的排除清单：平台生成的、调试用的，都不该进上传包 */
    static final String[] UPLOAD_SKIP = {
            "mini_tool.json", "latestIdentity", "versionInfo.json",
            ".DS_Store", "__MACOSX", "tmp"
    };

    static boolean skipOnUpload(String name) {
        for (String k : UPLOAD_SKIP) {
            if (name.equals(k) || name.endsWith("/" + k)) return true;
        }
        String low = name.toLowerCase(Locale.US);
        return low.endsWith(".map") || low.endsWith(".pyc");
    }

    /**
     * 打成「可以直接上传」的 zip：
     * 根目录就是 index.html，没有 appid / hash 那两层壳，也不带平台生成的配置。
     */
    public static File packForUpload(Context ctx, Mini m) throws Exception {
        File outDir = new File(ctx.getCacheDir(), "minipack");
        if (!outDir.exists() && !outDir.mkdirs()) throw new Exception("建目录失败");
        String safe = m.name == null || m.name.isEmpty() ? m.appid : m.name;
        safe = safe.replaceAll("[^a-zA-Z0-9._\\u4e00-\\u9fa5-]", "_");
        File src = new File(m.sourceDir);
        if (!src.isDirectory()) throw new Exception("没有 source 目录");

        File zip = new File(outDir, "upload_" + safe + ".zip");
        FileOutputStream fos = new FileOutputStream(zip);
        ZipOutputStream zos = new ZipOutputStream(fos);
        try {
            addDirFlat(zos, src, src, 0);
        } finally {
            try { zos.close(); } catch (Exception ignored) {}
            try { fos.close(); } catch (Exception ignored) {}
        }
        return zip;
    }

    static void addDirFlat(ZipOutputStream zos, File root, File dir, int depth) throws Exception {
        if (depth > 14) return;
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File f : kids) {
            String rel = f.getAbsolutePath().substring(root.getAbsolutePath().length() + 1);
            if (skipOnUpload(rel)) continue;
            if (f.isDirectory()) {
                addDirFlat(zos, root, f, depth + 1);
                continue;
            }
            ZipEntry e = new ZipEntry(rel.replace('\\', '/'));
            e.setTime(f.lastModified());
            zos.putNextEntry(e);
            try (FileInputStream in = new FileInputStream(f)) {
                byte[] buf = new byte[65536];
                int r;
                while ((r = in.read(buf)) > 0) zos.write(buf, 0, r);
            }
            zos.closeEntry();
        }
    }

    static void addDir(ZipOutputStream zos, File root, File dir, int depth) throws Exception {
        if (depth > 14) return;
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File f : kids) {
            if (f.isDirectory()) {
                addDir(zos, root, f, depth + 1);
                continue;
            }
            String rel = f.getAbsolutePath().substring(root.getAbsolutePath().length() + 1);
            ZipEntry e = new ZipEntry(rel.replace('\\', '/'));
            e.setTime(f.lastModified());
            zos.putNextEntry(e);
            try (FileInputStream in = new FileInputStream(f)) {
                byte[] buf = new byte[65536];
                int r;
                while ((r = in.read(buf)) > 0) zos.write(buf, 0, r);
            }
            zos.closeEntry();
        }
    }

    // ------------------------------------------------------------ 落地 + 分享

    public static Uri saveToDownloads(Context ctx, File zip) {
        String name = zip.getName();
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                ContentResolver cr = ctx.getContentResolver();
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
                cv.put(MediaStore.Downloads.MIME_TYPE, "application/zip");
                cv.put(MediaStore.Downloads.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/XhsSpy");
                cv.put(MediaStore.Downloads.IS_PENDING, 1);
                Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) return null;
                try (OutputStream os = cr.openOutputStream(uri);
                     InputStream is = new FileInputStream(zip)) {
                    if (os == null) return null;
                    byte[] buf = new byte[65536];
                    int r;
                    while ((r = is.read(buf)) > 0) os.write(buf, 0, r);
                }
                cv.clear();
                cv.put(MediaStore.Downloads.IS_PENDING, 0);
                cr.update(uri, cv, null, null);
                return uri;
            } catch (Throwable t) {
                XhsSpy.log("crash", "saveToDownloads " + t);
            }
        }
        try {
            File dst = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), "XhsSpy");
            if (!dst.exists()) dst.mkdirs();
            File out = new File(dst, name);
            try (InputStream in = new FileInputStream(zip);
                 OutputStream os = new FileOutputStream(out)) {
                byte[] buf = new byte[65536];
                int r;
                while ((r = in.read(buf)) > 0) os.write(buf, 0, r);
            }
            XhsSpy.log("mini", "saved to " + out.getAbsolutePath());
        } catch (Throwable t) {
            XhsSpy.log("crash", "saveToDownloads fallback " + t);
        }
        return null;
    }

    public static void share(Context ctx, Uri uri, String title, String text) {
        try {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("application/zip");
            if (uri != null) i.putExtra(Intent.EXTRA_STREAM, uri);
            i.putExtra(Intent.EXTRA_SUBJECT, title);
            i.putExtra(Intent.EXTRA_TEXT, text);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(Intent.createChooser(i, "分享 " + title)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable t) {
            XhsSpy.log("crash", "share " + t);
        }
    }

    public static void toast(Context ctx, String msg) {
        if (ctx == null) return;
        try {
            final Context app = ctx.getApplicationContext();
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    Toast.makeText(app, msg, Toast.LENGTH_LONG).show();
                } catch (Throwable ignored) {}
            });
        } catch (Throwable ignored) {}
    }

    public static String packAndShare(Context ctx, Mini m, boolean openShare) {
        try {
            File zip = pack(ctx, m);
            Uri uri = saveToDownloads(ctx, zip);
            String title = (m.name == null || m.name.isEmpty()) ? m.appid : m.name;
            String kind = m.isTool() ? "小工具" : "小程序";
            String text = title + "\n类型: " + kind
                    + "\nappid: " + m.appid
                    + "\n体积: " + human(m.bytes)
                    + "\n文件数: " + m.files
                    + (m.isTool()
                        ? "\n\n小工具是纯 H5，source/index.html 直接双击就能在浏览器打开。"
                        : "\n\n小程序源码在 source/，app.json 是路由，service.js 是业务逻辑。");
            toast(ctx, "已打包" + kind + " " + title + " · " + human(zip.length()));
            if (openShare) share(ctx, uri, title, text);
            return "ok: " + zip.getAbsolutePath() + " (" + human(zip.length()) + ")";
        } catch (Throwable t) {
            XhsSpy.log("crash", "packAndShare " + t);
            toast(ctx, "打包失败：" + t);
            return "fail: " + t;
        }
    }

    // ------------------------------------------------------------ 监控

    static final java.util.Set<String> sKnown = java.util.Collections.synchronizedSet(
            new java.util.HashSet<String>());
    static volatile boolean sWatching = false;

    public static void startWatcher(final Context ctx) {
        if (sWatching) return;
        sWatching = true;
        Thread th = new Thread(() -> {
            boolean first = true;
            String lastSig = dirSig(BASE_APP) + "|" + dirSig(BASE_TOOL);
            while (true) {
                try {
                    Thread.sleep(20000);
                    String sig = dirSig(BASE_APP) + "|" + dirSig(BASE_TOOL);
                    if (sig.equals(lastSig)) continue;
                    lastSig = sig;
                    invalidate();
                    for (Mini m : listFresh()) {
                        String key = m.type + ":" + m.appid;
                        if (sKnown.add(key)) {
                            if (first) continue;
                            String kind = m.isTool() ? "小工具" : "小程序";
                            String nm = (m.name == null || m.name.isEmpty()) ? m.appid : m.name;
                            XhsSpy.log("mini", "new " + kind + ": " + nm
                                    + " (" + m.appid + ") " + human(m.bytes));
                            toast(ctx, "抓到" + kind + "：" + nm
                                    + "\n" + human(m.bytes) + " · " + m.appid
                                    + "\n打开 http://127.0.0.1:8686/mini 看");
                        }
                    }
                    first = false;
                } catch (Throwable ex) {
                    XhsSpy.log("crash", "watcher " + ex);
                }
            }
        }, "XhsSpyMiniWatch");
        th.setDaemon(true);
        th.start();
    }

    /** 找一个 appid 对应的 Mini（优先按类型） */
    public static Mini find(String appid, String type) {
        if (appid == null) return null;
        Mini best = null;
        for (Mini m : list()) {
            if (!m.appid.equals(appid)) continue;
            if (type != null && type.equals(m.type)) return m;
            if (best == null) best = m;
        }
        return best;
    }
}
