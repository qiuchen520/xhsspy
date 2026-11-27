package com.rxw.xhsspy;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.Locale;

/**
 * FloatingWindow — 小红书内的悬浮窗
 *
 * 收起是一个球，点开是「左边分类 + 右边内容」的两栏面板。
 * 内容直接在窗里看，不跳浏览器；只有点头像或者点具体条目的名字才跳网页。
 *
 *   小程序  → 列出所有小程序，就地看到名字/体积/文件数，点条目就地导出
 *   小工具  → 同上，还能一键预览
 *   数值    → 就地读页面变量和 localStorage
 *   壁纸    → 就地拉掘金壁纸的首页数据
 *   日志    → 就地看最近几十行
 *   内存    → 运行时内存
 */
public class FloatingWindow {

    private static Context sCtx;
    private static WindowManager sWm;
    private static View sBall;
    private static View sPanel;
    private static WindowManager.LayoutParams sBallLp;
    private static WindowManager.LayoutParams sPanelLp;
    private static boolean sShown = false;
    private static final Handler UI = new Handler(Looper.getMainLooper());

    private static LinearLayout sNav;
    private static ScrollView sContentScroll;
    private static LinearLayout sContent;
    private static String sCur = "app";

    // ------------------------------------------------------------ 入口

    public static void show(final Context ctx) {
        if (ctx == null || sShown) return;
        Context app = null;
        try { app = ctx.getApplicationContext(); } catch (Throwable ignored) {}
        sCtx = (app != null) ? app : ctx;
        sShown = true;
        UI.post(() -> {
            try {
                boolean ok = false;
                try { ok = Settings.canDrawOverlays(sCtx); } catch (Throwable ignored) {}
                log("show 开始, canDrawOverlays=" + ok);
                if (!ok) { requestPermission(); return; }
                attach();
            } catch (Throwable t) {
                log("show 失败: " + t);
            }
        });
    }

    public static void hide() {
        UI.post(() -> {
            try {
                if (sWm == null) return;
                if (sBall != null) sWm.removeView(sBall);
                if (sPanel != null) sWm.removeView(sPanel);
            } catch (Throwable ignored) {}
            sBall = null;
            sPanel = null;
            sShown = false;
        });
    }

    static void log(String s) {
        XhsSpy.log("float", s);
    }

    static void requestPermission() {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + sCtx.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            sCtx.startActivity(i);
            MiniPacker.toast(sCtx, "请给「小红书」开启「显示在其他应用上层」，然后重启 App");
        } catch (Throwable t) {
            MiniPacker.toast(sCtx, "设置 → 应用 → 小红书 → 显示在其他应用上层");
        }
    }

    // ------------------------------------------------------------ 基础

    static int dp(float v) {
        return (int) (v * sCtx.getResources().getDisplayMetrics().density + 0.5f);
    }

    static GradientDrawable round(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    static TextView text(String s, int color, int sizeSp) {
        TextView t = new TextView(sCtx);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(sizeSp);
        return t;
    }

    // ------------------------------------------------------------ 挂载

    static void attach() {
        sWm = (WindowManager) sCtx.getSystemService(Context.WINDOW_SERVICE);
        if (sWm == null) return;

        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        // 球
        sBall = makeBall();
        sBallLp = new WindowManager.LayoutParams(
                dp(50), dp(50), type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        sBallLp.gravity = Gravity.TOP | Gravity.START;
        sBallLp.x = dp(8);
        sBallLp.y = dp(220);
        bindDrag(sBall, sBallLp);
        sWm.addView(sBall, sBallLp);

        // 面板
        sPanel = makePanel();
        sPanelLp = new WindowManager.LayoutParams(
                dp(292), dp(360), type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        sPanelLp.gravity = Gravity.TOP | Gravity.START;
        sPanelLp.x = dp(8);
        sPanelLp.y = dp(280);
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                sPanelLp.flags |= WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
                sPanelLp.setBlurBehindRadius(dp(24));
            } catch (Throwable ignored) {}
        }
        sPanel.setVisibility(View.GONE);
        sWm.addView(sPanel, sPanelLp);

        log("悬浮窗已挂载");
    }

    // ------------------------------------------------------------ 球

    static View makeBall() {
        TextView v = text("x", Color.WHITE, 18);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setGravity(Gravity.CENTER);
        GradientDrawable bg = round(0xFF2B2F3A, 25);
        bg.setStroke(dp(1), 0x33FFFFFF);
        v.setBackground(bg);
        v.setAlpha(0.92f);
        v.setOnClickListener(x -> togglePanel());
        return v;
    }

    static void togglePanel() {
        if (sPanel == null) return;
        boolean open = sPanel.getVisibility() != View.VISIBLE;
        sPanel.setVisibility(open ? View.VISIBLE : View.GONE);
        if (open) {
            refresh();
            try {
                sPanelLp.x = Math.max(dp(8), Math.min(sBallLp.x,
                        sCtx.getResources().getDisplayMetrics().widthPixels - dp(300)));
                sPanelLp.y = Math.min(sBallLp.y + dp(54),
                        sCtx.getResources().getDisplayMetrics().heightPixels - dp(380));
                sWm.updateViewLayout(sPanel, sPanelLp);
            } catch (Throwable ignored) {}
        }
    }

    // ------------------------------------------------------------ 面板

    static final String[] NAV_KEYS = {"app", "tool", "vars", "wall", "log", "mem"};
    static final String[] NAV_TEXT = {"小程序", "小工具", "数值", "壁纸", "日志", "内存"};

    static View makePanel() {
        // 外层：竖排 = 顶部胶囊 + 两栏
        LinearLayout root = new LinearLayout(sCtx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(10), dp(10), dp(10), dp(10));
        GradientDrawable bg = round(0xE6141720, 20);
        bg.setStroke(dp(1), 0x22FFFFFF);
        root.setBackground(bg);

        // 顶部胶囊：头像 + xhs
        LinearLayout head = new LinearLayout(sCtx);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(6), dp(5), dp(12), dp(5));
        GradientDrawable hbg = round(0x1AFFFFFF, 999);
        head.setBackground(hbg);

        TextView avatar = text("蛛", 0xFFFFFFFF, 12);
        avatar.setTypeface(Typeface.DEFAULT_BOLD);
        avatar.setGravity(Gravity.CENTER);
        GradientDrawable abg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{0xFFFF2442, 0xFF8B5CF6});
        abg.setShape(GradientDrawable.OVAL);
        avatar.setBackground(abg);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(dp(26), dp(26));
        head.addView(avatar, alp);
        bindAvatarScale(avatar);

        TextView name = text("xhs", 0xFFFFFFFF, 13);
        name.setPadding(dp(8), 0, 0, 0);
        name.setOnClickListener(x -> openUrl("/"));
        head.addView(name);

        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        hlp.bottomMargin = dp(10);
        root.addView(head, hlp);

        // 两栏
        LinearLayout body = new LinearLayout(sCtx);
        body.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(body, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT));

        // 左：分类
        sNav = new LinearLayout(sCtx);
        sNav.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(dp(78),
                LinearLayout.LayoutParams.MATCH_PARENT);
        nlp.rightMargin = dp(8);
        body.addView(sNav, nlp);

        // 右：内容
        sContentScroll = new ScrollView(sCtx);
        sContentScroll.setVerticalScrollBarEnabled(false);
        GradientDrawable cbg = round(0x0DFFFFFF, 14);
        sContentScroll.setBackground(cbg);
        sContent = new LinearLayout(sCtx);
        sContent.setOrientation(LinearLayout.VERTICAL);
        sContent.setPadding(dp(10), dp(10), dp(10), dp(10));
        sContentScroll.addView(sContent);
        body.addView(sContentScroll, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        buildNav();
        return root;
    }

    static void bindAvatarScale(View v) {
        v.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        view.animate().scaleX(0.82f).scaleY(0.82f).setDuration(90).start();
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        view.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                        break;
                }
                return false;
            }
        });
        v.setOnClickListener(x -> openUrl("/mini"));
    }

    static void buildNav() {
        if (sNav == null) return;
        sNav.removeAllViews();
        for (int i = 0; i < NAV_KEYS.length; i++) {
            final String key = NAV_KEYS[i];
            boolean on = key.equals(sCur);
            TextView t = text(NAV_TEXT[i], on ? 0xFFFFFFFF : 0x99FFFFFF, 12);
            t.setPadding(dp(9), dp(9), dp(9), dp(9));
            t.setBackground(round(on ? 0x26FFFFFF : 0x00000000, 12));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(4);
            t.setOnClickListener(x -> {
                sCur = key;
                buildNav();
                refresh();
            });
            sNav.addView(t, lp);
        }
    }

    // ------------------------------------------------------------ 内容渲染

    static void refresh() {
        if (sContent == null) return;
        sContent.removeAllViews();
        try {
            switch (sCur) {
                case "app":  renderMinis(MiniPacker.TYPE_APP); break;
                case "tool": renderMinis(MiniPacker.TYPE_TOOL); break;
                case "vars": renderVars(); break;
                case "wall": renderWall(); break;
                case "log":  renderLog(); break;
                case "mem":  renderMem(); break;
            }
        } catch (Throwable t) {
            sContent.addView(text("出错：" + t, 0xFFFF6B6B, 11));
        }
    }

    static TextView row(String s, int color, int size) {
        TextView t = text(s, color, size);
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    static TextView head(String s) {
        TextView t = text(s, 0xFFFF7A8A, 11);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(2), 0, dp(6));
        return t;
    }

    static void renderMinis(String type) {
        List<MiniPacker.Mini> list = MiniPacker.listByType(type);
        sContent.addView(head((type.equals(MiniPacker.TYPE_TOOL) ? "小工具 · " : "小程序 · ")
                + list.size()));
        if (list.isEmpty()) {
            sContent.addView(row("还没有缓存\n去 App 里点开一个再回来", 0x66FFFFFF, 11));
            return;
        }
        for (final MiniPacker.Mini m : list) {
            final String nm = (m.name == null || m.name.isEmpty()) ? m.appid : m.name;
            LinearLayout card = new LinearLayout(sCtx);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(8), dp(8), dp(8), dp(8));
            card.setBackground(round(0x14FFFFFF, 10));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            clp.bottomMargin = dp(6);
            card.setLayoutParams(clp);

            TextView title = text(nm, 0xFFFFFFFF, 12);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setOnClickListener(x -> {
                if (type.equals(MiniPacker.TYPE_TOOL) && m.hasSource) {
                    openUrl("/tool/" + m.appid + "/");
                } else {
                    openUrl("/mini");
                }
            });
            card.addView(title);

            card.addView(text(MiniPacker.human(m.bytes) + " · " + m.files + " 个文件",
                    0x88FFFFFF, 10));

            LinearLayout btns = new LinearLayout(sCtx);
            btns.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            blp.topMargin = dp(6);
            btns.setLayoutParams(blp);

            btns.addView(chip("导出", 0x33638DFF, () -> doPack(m, false)));
            btns.addView(chip("分享", 0x338B5CF6, () -> doPack(m, true)));
            if (type.equals(MiniPacker.TYPE_TOOL) && m.hasSource) {
                btns.addView(chip("预览", 0x3322C55E,
                        () -> openUrl("/tool/" + m.appid + "/")));
            }
            card.addView(btns);
            sContent.addView(card);
        }
    }

    static TextView chip(String label, int bg, final Runnable act) {
        TextView t = text(label, 0xFFE8E8EC, 10);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(10), dp(5), dp(10), dp(5));
        t.setBackground(round(bg, 8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(6);
        t.setLayoutParams(lp);
        t.setOnClickListener(x -> {
            try { act.run(); } catch (Throwable e) { log("按钮 " + e); }
        });
        return t;
    }

    static void doPack(final MiniPacker.Mini m, final boolean share) {
        new Thread(() -> {
            final String r = MiniPacker.packAndShare(sCtx, m, share);
            UI.post(() -> MiniPacker.toast(sCtx, r.startsWith("ok") ? "已导出" : r));
        }, "XhsSpyPackBtn").start();
    }

    static void renderVars() throws Exception {
        String raw = XhsSpy.probeVars();
        JSONObject o;
        try {
            o = new JSONObject(raw);
        } catch (Exception e) {
            sContent.addView(head("数值"));
            sContent.addView(row("读不到页面：没有 WebView\n先在 App 里打开一个小工具", 0x66FFFFFF, 11));
            return;
        }
        sContent.addView(head("页面 · " + o.optString("title", "")));
        sContent.addView(row(o.optString("url", ""), 0x66FFFFFF, 9));

        JSONArray vars = o.optJSONArray("vars");
        sContent.addView(head("变量 · " + (vars == null ? 0 : vars.length())));
        if (vars != null) {
            int n = 0;
            for (int i = 0; i < vars.length() && n < 40; i++) {
                JSONObject v = vars.optJSONObject(i);
                if (v == null) continue;
                if (!v.optBoolean("w", false)) continue;
                n++;
                String k = v.optString("k");
                String val = v.optString("v");
                LinearLayout it = new LinearLayout(sCtx);
                it.setOrientation(LinearLayout.VERTICAL);
                it.setPadding(dp(6), dp(5), dp(6), dp(5));
                it.setBackground(round(0x12FFFFFF, 8));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.bottomMargin = dp(4);
                it.setLayoutParams(lp);
                it.addView(text(k, 0xFFFFFFFF, 11));
                it.addView(text(v.optString("t") + " · " + val, 0x88FFFFFF, 9));
                it.setOnClickListener(x -> editVar(k, val));
                sContent.addView(it);
            }
            if (n == 0) sContent.addView(row("没有可直接改的变量", 0x66FFFFFF, 10));
        }

        JSONArray ls = o.optJSONArray("storage");
        sContent.addView(head("本地存储 · " + (ls == null ? 0 : ls.length())));
        if (ls != null) {
            for (int i = 0; i < ls.length() && i < 20; i++) {
                JSONObject v = ls.optJSONObject(i);
                if (v == null) continue;
                String k = v.optString("k");
                String val = v.optString("v");
                LinearLayout it = new LinearLayout(sCtx);
                it.setOrientation(LinearLayout.VERTICAL);
                it.setPadding(dp(6), dp(5), dp(6), dp(5));
                it.setBackground(round(0x12FFFFFF, 8));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.bottomMargin = dp(4);
                it.setLayoutParams(lp);
                it.addView(text(k, 0xFFFFFFFF, 11));
                String shortVal = val.length() > 60 ? val.substring(0, 60) + "…" : val;
                it.addView(text(shortVal, 0x88FFFFFF, 9));
                it.setOnClickListener(x -> editStorage(k, val));
                sContent.addView(it);
            }
        }
    }

    static void editVar(final String k, String old) {
        promptInput("改 " + k, old, v -> {
            final JSONObject r = VarsPanel.set("window", k, v, "auto");
            UI.post(() -> {
                MiniPacker.toast(sCtx, r.optBoolean("ok", false)
                        ? "已改成 " + r.optString("now") : "失败");
                refresh();
            });
        });
    }

    static void editStorage(final String k, String old) {
        promptInput("改 " + k, old, v -> {
            final JSONObject r = VarsPanel.set("storage", k, v, "auto");
            UI.post(() -> {
                MiniPacker.toast(sCtx, r.optBoolean("ok", false) ? "已保存" : "失败");
                refresh();
            });
        });
    }

    interface OnText { void run(String v); }

    static void promptInput(String title, String old, final OnText cb) {
        try {
            final android.widget.EditText et = new android.widget.EditText(sCtx);
            et.setText(old == null ? "" : old);
            et.setTextSize(12);
            et.setPadding(dp(10), dp(8), dp(10), dp(8));
            new android.app.AlertDialog.Builder(sCtx)
                    .setTitle(title)
                    .setView(et)
                    .setPositiveButton("确定", (d, w) -> cb.run(et.getText().toString()))
                    .setNegativeButton("取消", null)
                    .show();
        } catch (Throwable t) {
            log("弹窗失败 " + t);
        }
    }

    static void renderWall() {
        sContent.addView(head("掘金壁纸"));
        sContent.addView(row("正在拉首页…", 0x66FFFFFF, 10));
        new Thread(() -> {
            final StringBuilder sb = new StringBuilder();
            try {
                JSONObject d = Wall.api("home", new JSONObject());
                JSONObject data = d.optJSONObject("data");
                if (data == null) data = d;
                JSONArray list = data.optJSONArray("list");
                JSONArray rec = data.optJSONArray("recommend");
                sb.append("作者 ").append(rec == null ? 0 : rec.length())
                  .append(" 位 · 图 ").append(list == null ? 0 : list.length()).append(" 张\n");
                if (rec != null) {
                    for (int i = 0; i < rec.length() && i < 6; i++) {
                        JSONObject a = rec.optJSONObject(i);
                        if (a != null) sb.append("· ").append(a.optString("app_name"))
                                .append("  #").append(a.optInt("acc_id")).append('\n');
                    }
                }
            } catch (Throwable t) {
                sb.append("拉取失败：").append(t);
            }
            UI.post(() -> {
                if (sContent == null || !"wall".equals(sCur)) return;
                sContent.removeAllViews();
                sContent.addView(head("掘金壁纸"));
                sContent.addView(row(sb.toString(), 0xCCFFFFFF, 10));
                sContent.addView(chip("打开网页版", 0x33638DFF, () -> openUrl("/wall")));
            });
        }, "XhsSpyWall").start();
    }

    static void renderLog() {
        sContent.addView(head("日志"));
        String[] names = {"boot", "float", "mini", "net", "crash"};
        for (String n : names) {
            sContent.addView(chip(n, 0x14FFFFFF, () -> openUrl("/log?n=" + n)));
        }
        sContent.addView(chip("查看全部", 0x33638DFF, () -> openUrl("/")));
    }

    static void renderMem() {
        try {
            Runtime r = Runtime.getRuntime();
            long total = r.totalMemory(), free = r.freeMemory(), max = r.maxMemory();
            sContent.addView(head("运行时内存"));
            sContent.addView(row("已分配 " + MiniPacker.human(total), 0xCCFFFFFF, 11));
            sContent.addView(row("已使用 " + MiniPacker.human(total - free), 0xCCFFFFFF, 11));
            sContent.addView(row("空闲   " + MiniPacker.human(free), 0xCCFFFFFF, 11));
            sContent.addView(row("上限   " + MiniPacker.human(max), 0xCCFFFFFF, 11));
            sContent.addView(row(String.format(Locale.US, "占用   %.1f%%",
                    max > 0 ? (total - free) * 100.0 / max : 0), 0xFFFF7A8A, 12));
        } catch (Throwable t) {
            sContent.addView(row("读不到：" + t, 0xFFFF6B6B, 11));
        }
    }

    // ------------------------------------------------------------ 动作

    static void openUrl(String path) {
        try {
            String url = "http://127.0.0.1:" + MiniServer.PORT_START + path;
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            sCtx.startActivity(i);
        } catch (Throwable t) {
            MiniPacker.toast(sCtx, "没有可用浏览器");
        }
    }

    // ------------------------------------------------------------ 拖动

    static void bindDrag(final View v, final WindowManager.LayoutParams lp) {
        v.setOnTouchListener(new View.OnTouchListener() {
            int downX, downY;
            float touchX, touchY;
            boolean moved = false;

            @Override
            public boolean onTouch(View view, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = lp.x;
                        downY = lp.y;
                        touchX = e.getRawX();
                        touchY = e.getRawY();
                        moved = false;
                        return false;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (e.getRawX() - touchX);
                        int dy = (int) (e.getRawY() - touchY);
                        if (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6)) moved = true;
                        if (!moved) return false;
                        lp.x = downX + dx;
                        lp.y = downY + dy;
                        try { sWm.updateViewLayout(v, lp); } catch (Throwable ignored) {}
                        return true;
                    case MotionEvent.ACTION_UP:
                        return moved;
                }
                return false;
            }
        });
    }
}
