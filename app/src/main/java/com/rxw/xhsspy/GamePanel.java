package com.rxw.xhsspy;

import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * GamePanel —— 给小游戏专门开的一块外挂面板
 *
 * 跟 FloatingWindow 完全独立：自己的 window、自己的位置、自己的大小。
 * 悬浮窗那边只负责「发现游戏 → 勾选 → 唤起这块」。
 *
 *   ┌──────────────────────┐
 *   │ 墨线突击      −  +  ×│  ← 拖这里移动
 *   ├──────────────────────┤
 *   │ 无敌            [关] │
 *   │ 无限钱          [关] │
 *   │ ...                  │
 *   └──────────────────────┘
 */
public class GamePanel {

    private static Context sCtx;
    private static WindowManager sWm;
    private static LinearLayout sRoot;
    private static WindowManager.LayoutParams sLp;
    private static boolean sShown = false;
    private static float sScale = 1f;
    private static String sGame = "";
    private static String sScript = "";
    private static boolean sInjected = false;
    private static final Handler UI = new Handler(Looper.getMainLooper());

    private static final Map<String, Boolean> sOn = new HashMap<>();
    private static LinearLayout sList;
    private static TextView sStatus;

    // 墨线突击
    private static final String GAME_INKSTRIKE = "6ab94378dd88340015152901";
    private static final String[][] HACKS = {
            {"god",     "无敌"},
            {"money",   "无限钱"},
            {"ammo",    "无限弹药"},
            {"esp",     "透视 ESP"},
            {"freeze",  "定身"},
            {"autohit", "自动命中"},
            {"all",     "一键全开"},
    };

    // ------------------------------------------------------------ 入口

    public static void show(final Context ctx, final String appid) {
        if (ctx == null) return;
        Context app = null;
        try { app = ctx.getApplicationContext(); } catch (Throwable ignored) {}
        sCtx = (app != null) ? app : ctx;
        sGame = appid == null ? GAME_INKSTRIKE : appid;

        UI.post(() -> {
            try {
                if (sShown) { hide(); return; }
                boolean ok = false;
                try { ok = Settings.canDrawOverlays(sCtx); } catch (Throwable ignored) {}
                if (!ok) {
                    try {
                        Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + sCtx.getPackageName()));
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        sCtx.startActivity(i);
                    } catch (Throwable ignored) {}
                    return;
                }
                attach();
                inject();
            } catch (Throwable t) {
                XhsSpy.log("float", "GamePanel.show 失败: " + t);
            }
        });
    }

    public static void hide() {
        UI.post(() -> {
            try { if (sWm != null && sRoot != null) sWm.removeView(sRoot); } catch (Throwable ignored) {}
            sRoot = null;
            sShown = false;
            sInjected = false;
        });
    }

    public static boolean isShown() { return sShown; }

    // ------------------------------------------------------------ 基础

    static float dpi() { return sCtx.getResources().getDisplayMetrics().density; }

    static int dp(float v) { return (int) (v * sScale * dpi() + 0.5f); }

    static int sp(float v) { return (int) (v * sScale + 0.5f); }

    static GradientDrawable glass(int color, int radiusDp, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        g.setStroke(Math.max(1, dp(1)), strokeColor);
        return g;
    }

    static TextView txt(String s, int color, float sizeSp) {
        TextView t = new TextView(sCtx);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(sp(sizeSp));
        return t;
    }

    static TextView bold(String s, int color, float sizeSp) {
        TextView t = txt(s, color, sizeSp);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    // ------------------------------------------------------------ 骨架

    static void attach() {
        sWm = (WindowManager) sCtx.getSystemService(Context.WINDOW_SERVICE);
        if (sWm == null) return;

        int type = android.os.Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        sRoot = new LinearLayout(sCtx);
        sRoot.setOrientation(LinearLayout.VERTICAL);
        sRoot.setBackground(glass(0xF00C0E14, 14, 0x33FFFFFF));
        sRoot.setPadding(dp(8), dp(6), dp(8), dp(8));

        // 标题栏
        LinearLayout bar = new LinearLayout(sCtx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = bold(gameName(), 0xFFFF8FA3, 12);
        bar.addView(title, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        bar.addView(btn("−", () -> { sScale = Math.max(0.7f, sScale - 0.15f); relayout(); }));
        bar.addView(btn("+", () -> { sScale = Math.min(2.0f, sScale + 0.15f); relayout(); }));
        bar.addView(btn("×", GamePanel::hide));

        sRoot.addView(bar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // 分隔
        View line = new View(sCtx);
        line.setBackgroundColor(0x22FFFFFF);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        llp.topMargin = dp(5);
        llp.bottomMargin = dp(5);
        sRoot.addView(line, llp);

        // 列表
        ScrollView sc = new ScrollView(sCtx);
        sc.setVerticalScrollBarEnabled(false);
        sList = new LinearLayout(sCtx);
        sList.setOrientation(LinearLayout.VERTICAL);
        sc.addView(sList);
        sRoot.addView(sc, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // 状态栏
        sStatus = txt("未连接", 0x66FFFFFF, 9);
        sStatus.setPadding(0, dp(4), 0, 0);
        sRoot.addView(sStatus);

        buildList();

        sLp = new WindowManager.LayoutParams(
                dp(200), dp(300), type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSLUCENT);
        sLp.gravity = Gravity.TOP | Gravity.START;
        sLp.x = dp(8);
        sLp.y = dp(120);

        bindDrag(bar);

        try {
            sWm.addView(sRoot, sLp);
            sShown = true;
            XhsSpy.log("float", "GamePanel 打开: " + sGame);
        } catch (Throwable t) {
            XhsSpy.log("float", "GamePanel addView 失败: " + t);
            sShown = false;
        }
    }

    static String gameName() {
        if (GAME_INKSTRIKE.equals(sGame)) return "墨线突击";
        return "游戏 " + (sGame != null && sGame.length() > 8 ? sGame.substring(0, 8) : sGame);
    }

    static TextView btn(String label, final Runnable act) {
        TextView t = bold(label, 0xFFE8EAF0, 13);
        t.setGravity(Gravity.CENTER);
        t.setBackground(glass(0x1FFFFFFF, 8, 0x22FFFFFF));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(26), dp(26));
        lp.leftMargin = dp(4);
        t.setLayoutParams(lp);
        t.setOnClickListener(v -> { try { act.run(); } catch (Throwable ignored) {} });
        return t;
    }

    static void relayout() {
        // 尺寸和字号都按 scale 重算，直接重建最省事
        if (sWm != null && sRoot != null) {
            try { sWm.removeView(sRoot); } catch (Throwable ignored) {}
        }
        sShown = false;
        attach();
    }

    // ------------------------------------------------------------ 功能区

    static void buildList() {
        if (sList == null) return;
        sList.removeAllViews();
        for (final String[] h : HACKS) {
            final String id = h[0];
            boolean on = Boolean.TRUE.equals(sOn.get(id));

            LinearLayout row = new LinearLayout(sCtx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(9), dp(9), dp(9), dp(9));
            row.setBackground(glass(on ? 0x2EFF6B85 : 0x12FFFFFF, 9, 0x20FFFFFF));
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            rlp.bottomMargin = dp(5);
            row.setLayoutParams(rlp);

            TextView name = txt(h[1], on ? 0xFFFFD6DD : 0xFFD8DCE4, 11);
            row.addView(name, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            TextView state = bold(on ? "开" : "关", on ? 0xFFFF8FA3 : 0x77FFFFFF, 10);
            row.addView(state);

            row.setOnClickListener(v -> toggle(id));
            sList.addView(row);
        }
    }

    static void toggle(final String id) {
        final boolean target = !Boolean.TRUE.equals(sOn.get(id));
        sOn.put(id, target);
        buildList();
        sStatus.setText("执行中…");

        new Thread(() -> {
            String r = callHack(id, target);
            UI.post(() -> {
                if (sStatus == null) return;
                String s = String.valueOf(r);
                sStatus.setText(s.length() > 90 ? s.substring(0, 90) : s);
            });
        }, "XhsSpyHack").start();
    }

    static String callHack(String id, boolean on) {
        String js = "window.__XHS_HACKS && window.__XHS_HACKS." + id
                + "." + (on ? "on" : "off") + "()";
        try {
            String r = XhsSpy.evalJs(js, 4000);
            return r == null ? "没连上页面" : r;
        } catch (Throwable t) {
            return "失败: " + t;
        }
    }

    // ------------------------------------------------------------ 注入

    static void inject() {
        if (sInjected) return;
        sInjected = true;
        new Thread(() -> {
            try {
                sScript = readAsset("hacks/inkstrike.js");
            } catch (Throwable t) {
                XhsSpy.log("float", "读外挂脚本失败: " + t);
                UI.post(() -> { if (sStatus != null) sStatus.setText("脚本读取失败"); });
                return;
            }
            String r = XhsSpy.evalJs(sScript, 8000);
            final String msg = (r == null) ? "页面没响应" : r;
            UI.post(() -> {
                if (sStatus == null) return;
                sStatus.setText("ready".equals(msg) || "already".equals(msg)
                        ? "已就绪 · 勾选功能即生效" : ("注入: " + msg));
            });
        }, "XhsSpyInject").start();
    }

    static String readAsset(String path) throws Exception {
        InputStream is = sCtx.getAssets().open(path);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
        is.close();
        return bo.toString("UTF-8");
    }

    // ------------------------------------------------------------ 拖动

    static void bindDrag(final View handle) {
        handle.setOnTouchListener(new View.OnTouchListener() {
            int baseX, baseY;
            float downRX, downRY;
            boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        baseX = sLp.x; baseY = sLp.y;
                        downRX = e.getRawX(); downRY = e.getRawY();
                        moved = false;
                        return false;
                    case MotionEvent.ACTION_MOVE: {
                        int dx = (int) (e.getRawX() - downRX);
                        int dy = (int) (e.getRawY() - downRY);
                        if (!moved && (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6))) moved = true;
                        if (!moved) return false;
                        sLp.x = baseX + dx;
                        sLp.y = baseY + dy;
                        try { sWm.updateViewLayout(sRoot, sLp); } catch (Throwable ignored) {}
                        return true;
                    }
                }
                return false;
            }
        });
    }
}
