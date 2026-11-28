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
import android.widget.HorizontalScrollView;
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
            {"oneshot", "秒杀"},
            {"fly",     "飞行"},
            {"madness", "敌人互殴"},
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

        int sw = sCtx.getResources().getDisplayMetrics().widthPixels;
        int panelW = Math.min(sw - dp(10), dp(360));

        sRoot = new LinearLayout(sCtx);
        sRoot.setOrientation(LinearLayout.HORIZONTAL);
        sRoot.setGravity(Gravity.CENTER_VERTICAL);
        sRoot.setBackground(glass(0xF00C0E14, 26, 0x33FFFFFF));
        sRoot.setPadding(dp(10), dp(6), dp(6), dp(6));

        // 左边：名字 + 状态（竖排，窄）
        LinearLayout head = new LinearLayout(sCtx);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setPadding(0, 0, dp(8), 0);
        TextView title = bold(gameName(), 0xFFFF8FA3, 11);
        head.addView(title);
        sStatus = txt("…", 0x66FFFFFF, 8);
        head.addView(sStatus);
        sRoot.addView(head, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // 中间：横向滚动的功能按钮
        HorizontalScrollView hs = new HorizontalScrollView(sCtx);
        hs.setHorizontalScrollBarEnabled(false);
        sList = new LinearLayout(sCtx);
        sList.setOrientation(LinearLayout.HORIZONTAL);
        sList.setGravity(Gravity.CENTER_VERTICAL);
        hs.addView(sList);
        sRoot.addView(hs, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        // 右边：缩放和关闭
        LinearLayout ctrl = new LinearLayout(sCtx);
        ctrl.setOrientation(LinearLayout.VERTICAL);
        ctrl.setGravity(Gravity.CENTER);
        LinearLayout rowA = new LinearLayout(sCtx);
        rowA.setOrientation(LinearLayout.HORIZONTAL);
        rowA.addView(btn("−", () -> { sScale = Math.max(0.7f, sScale - 0.15f); relayout(); }));
        rowA.addView(btn("+", () -> { sScale = Math.min(2.0f, sScale + 0.15f); relayout(); }));
        ctrl.addView(rowA);
        TextView close = bold("×", 0xFFFF8FA3, 11);
        close.setGravity(Gravity.CENTER);
        close.setPadding(0, dp(2), 0, 0);
        close.setOnClickListener(v -> hide());
        ctrl.addView(close);
        sRoot.addView(ctrl, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        buildList();

        sLp = new WindowManager.LayoutParams(
                panelW, WindowManager.LayoutParams.WRAP_CONTENT, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSLUCENT);
        sLp.gravity = Gravity.TOP | Gravity.START;
        sLp.x = dp(5);
        sLp.y = dp(90);

        bindDrag(head);

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

            TextView chip = bold(h[1], on ? 0xFFFFFFFF : 0xFFB8BEC8, 10);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(11), dp(7), dp(11), dp(7));
            chip.setBackground(glass(on ? 0xF0FF3355 : 0x1AFFFFFF, 18,
                    on ? 0x66FF8FA3 : 0x22FFFFFF));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(5);
            chip.setLayoutParams(lp);
            chip.setOnClickListener(v -> toggle(id));
            sList.addView(chip);
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
