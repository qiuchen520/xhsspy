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
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * MiniPacker — 小程序源码抓取 / 打包 / 分享
 *
 * 小红书的小程序包落在：
 *   /data/data/com.xingin.xhs/files/redmpV2/release/<appid>/<hash>/
 *       ├── source/          ← 全部源码（app.json / service.js / pages/…）
 *       ├── icon.jpg
 *       └── versionInfo.json
 *
 * 这里干三件事：
 *   1. 扫出所有已缓存的小程序，报出名字 / appid / 体积
 *   2. 把某个 appid 的整个目录打成 zip
 *   3. 把 zip 落到手机「下载」目录，并弹系统分享
 */
public class MiniPacker {

    public static final File BASE =
            new File("/data/data/com.xingin.xhs/files/redmpV2/release");

    public static class Mini {
        public String appid = "";
        public String name = "";
        public String version = "";
        public String hashDir = "";
        public long bytes = 0;
        public int files = 0;
        public boolean hasSource = false;
        public long mtime = 0;
        public String path = "";

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("appid", appid);
                o.put("name", name);
                o.put("version", version);
                o.put("bytes", bytes);
                o.put("files", files);
                o.put("hasSource", hasSource);
                o.put("path", path);
                o.put("mtime", mtime);
            } catch (Exception ignored) {}
            return o;
        }

        public String sizeText() {
            return human(bytes);
        }
    }

    public static String human(long n) {
        if (n < 1024) return n + " B";
        if (n < 1048576) return String.format(Locale.US, "%.1f KB", n / 1024.0);
        if (n < 1073741824L) return String.format(Locale.US, "%.2f MB", n / 1048576.0);
        return String.format(Locale.US, "%.2f GB", n / 1073741824.0);
    }

    // ------------------------------------------------------------ 扫描

    public static List<Mini> list() {
        List<Mini> out = new ArrayList<>();
        File[] kids = BASE.listFiles();
        if (kids == null) return out;
        Arrays.sort(kids, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (File d : kids) {
            if (!d.isDirectory()) continue;
            Mini m = read(d);
            if (m != null) out.add(m);
        }
        return out;
    }

    public static Mini read(File appDir) {
        if (appDir == null || !appDir.isDirectory()) return null;
        Mini m = new Mini();
        m.appid = appDir.getName();
        m.path = appDir.getAbsolutePath();
        m.mtime = appDir.lastModified();

        // 取最新的一个 hash 子目录
        File[] subs = appDir.listFiles();
        File newest = null;
        if (subs != null) {
            for (File s : subs) {
                if (!s.isDirectory() || "usr".equals(s.getName())) continue;
                if (newest == null || s.lastModified() > newest.lastModified()) newest = s;
            }
        }
        if (newest == null) return null;
        m.hashDir = newest.getName();

        File src = new File(newest, "source");
        m.hasSource = src.isDirectory();

        // 名字：project.config.json 的 projectname，退到 versionInfo.json
        m.name = readProjectName(src, m.appid);
        m.version = readVersion(src);

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

    static String readProjectName(File src, String fallback) {
        try {
            File pj = new File(src, "project.config.json");
            if (!pj.isFile()) return fallback;
            String t = readText(pj, 65536);
            JSONObject o = new JSONObject(t);
            String n = o.optString("projectname", "");
            if (!n.isEmpty()) {
                // "掘金壁纸67905222993c2f0001ad0fd6" -> 去掉尾部的 appid
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
            File v = new File(src.getParentFile(), "source/versionInfo.json");
            if (v.isFile()) {
                return new JSONObject(readText(v, 4096)).optString("version", "");
            }
        } catch (Throwable ignored) {}
        return "";
    }

    static String readText(File f, int max) {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) Math.min(f.length(), max)];
            int r = in.read(b);
            return new String(b, 0, Math.max(r, 0), "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    // ------------------------------------------------------------ 打包

    /** 把某个 appid 的目录打成 zip，写到 cache 目录，返回文件 */
    public static File pack(Context ctx, Mini m) throws Exception {
        File outDir = new File(ctx.getCacheDir(), "minipack");
        if (!outDir.exists() && !outDir.mkdirs()) throw new Exception("建目录失败");
        String safe = m.name.isEmpty() ? m.appid : m.name;
        safe = safe.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        File zip = new File(outDir, safe + "_" + m.appid + ".zip");

        File root = new File(m.path);
        FileOutputStream fos = new FileOutputStream(zip);
        ZipOutputStream zos = new ZipOutputStream(fos);
        try {
            addDir(zos, root, root, 0);
        } finally {
            zos.close();
            fos.close();
        }
        return zip;
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

    /**
     * 把 zip 复制到手机「下载」目录。
     * Android 10+ 走 MediaStore，低版本走 /sdcard/Download。
     * 返回可以直接拿去分享的 Uri（拿不到就返回 null）。
     */
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
        // 兜底：直接写 /sdcard/Download/XhsSpy
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
            return null;
        } catch (Throwable t) {
            XhsSpy.log("crash", "saveToDownloads fallback " + t);
            return null;
        }
    }

    /** 弹系统分享面板 */
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

    // ------------------------------------------------------------ 一键：打包 + 落地 + 分享

    /** 完整流程，返回提示文本 */
    public static String packAndShare(Context ctx, Mini m, boolean openShare) {
        try {
            File zip = pack(ctx, m);
            Uri uri = saveToDownloads(ctx, zip);
            String title = (m.name.isEmpty() ? m.appid : m.name);
            String text = title + "\nappid: " + m.appid
                    + "\n版本: " + (m.version.isEmpty() ? "?" : m.version)
                    + "\n体积: " + human(m.bytes)
                    + "\n文件数: " + m.files
                    + "\n\n源码在 zip 里的 source/ 目录，app.json 是路由，"
                    + "service.js 是业务逻辑。";
            toast(ctx, "已打包 " + title + " · " + human(zip.length()));
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

    /** 每 15 秒扫一次，发现新小程序就提示一次 */
    public static void startWatcher(final Context ctx) {
        if (sWatching) return;
        sWatching = true;
        Thread t = new Thread(() -> {
            boolean first = true;
            while (true) {
                try {
                    Thread.sleep(15000);
                    List<Mini> all = list();
                    for (Mini m : all) {
                        if (sKnown.add(m.appid)) {
                            if (first) continue;   // 首次扫描只登记，不刷屏
                            String name = m.name.isEmpty() ? m.appid : m.name;
                            XhsSpy.log("mini", "new miniapp: " + name
                                    + " (" + m.appid + ") " + human(m.bytes)
                                    + " source=" + m.hasSource);
                            toast(ctx, "抓到小程序：" + name
                                    + "\n" + human(m.bytes) + " · " + m.appid
                                    + "\n打开 http://127.0.0.1:8686/mini 导出源码");
                        }
                    }
                    first = false;
                } catch (Throwable ex) {
                    XhsSpy.log("crash", "watcher " + ex);
                }
            }
        }, "XhsSpyMiniWatch");
        t.setDaemon(true);
        t.start();
    }
}
