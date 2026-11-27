package com.rxw.xhsspy;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;

/**
 * FloatingWindow — 小红书内的悬浮窗
 *
 * 一个球，点开是面板：
 *   端口 / 抓到几个 / 内存占用
 *   一键用浏览器打开网页（/、/mini、/tool、/vars、/wall）
 *   最近抓到的小工具列表，点一下直接看
 *
 * 悬浮窗需要 SYSTEM_ALERT_WINDOW，而这个权限是「小红书」的，不是模块的。
 * 所以第一次会检查，没有就跳系统设置让用户给小红书开。
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

    // ------------------------------------------------------------ 入口

    public static void show(final Context ctx) {
        if (ctx == null || sShown) return;
        sCtx = ctx.getApplicationContext();
        sShown = true;
        UI.post(() -> {
            try {
                if (!Settings.canDrawOverlays(sCtx)) {
                    log("没有悬浮窗权限，引导授权");
                    requestPermission();
                    return;
                }
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
            try {
                Intent i2 = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
                i2.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                sCtx.startActivity(i2);
            } catch (Throwable ignored) {}
            MiniPacker.toast(sCtx, "请手动到 设置 → 应用 → 小红书 → 显示在其他应用上层 里打开");
        }
    }

    // ------------------------------------------------------------ 挂载

    static int dp(float v) {
        return (int) (v * sCtx.getResources().getDisplayMetrics().density + 0.5f);
    }

    static void attach() {
        sWm = (WindowManager) sCtx.getSystemService(Context.WINDOW_SERVICE);
        if (sWm == null) return;

        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        // --- 小球 ---
        sBall = makeBall();
        sBallLp = new WindowManager.LayoutParams(
                dp(52), dp(52), type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        sBallLp.gravity = Gravity.TOP | Gravity.START;
        sBallLp.x = dp(8);
        sBallLp.y = dp(220);
        bindDrag(sBall, sBallLp);
        sWm.addView(sBall, sBallLp);

        // --- 面板 ---
        sPanel = makePanel();
        sPanelLp = new WindowManager.LayoutParams(
                dp(272), WindowManager.LayoutParams.WRAP_CONTENT, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        sPanelLp.gravity = Gravity.TOP | Gravity.START;
        sPanelLp.x = dp(8);
        sPanelLp.y = dp(280);
        sPanel.setVisibility(View.GONE);
        sWm.addView(sPanel, sPanelLp);

        log("悬浮窗已挂载");
    }

    // ------------------------------------------------------------ 小球

    static View makeBall() {
        TextView v = new TextView(sCtx);
        v.setText("蛛");
        v.setTextColor(Color.WHITE);
        v.setTextSize(15);
        v.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{0xFFFF2442, 0xFF8B5CF6});
        bg.setShape(GradientDrawable.OVAL);
        v.setBackground(bg);
        v.setAlpha(0.92f);
        v.setOnClickListener(x -> togglePanel());
        return v;
    }

    static void togglePanel() {
        if (sPanel == null) return;
        sPanel.setVisibility(sPanel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
        if (sPanel.getVisibility() == View.VISIBLE) {
            refreshPanel();
            // 面板贴着球的位置出现
            try {
                sPanelLp.x = sBallLp.x;
                sPanelLp.y = sBallLp.y + dp(56);
                sWm.updateViewLayout(sPanel, sPanelLp);
            } catch (Throwable ignored) {}
        }
    }

    // ------------------------------------------------------------ 面板

    static LinearLayout sList;

    static View makePanel() {
        int pad = dp(12);

        LinearLayout root = new LinearLayout(sCtx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF15171C);
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(1), 0xFF2A2F3A);
        root.setBackground(bg);
        root.setAlpha(0.97f);

        // 标题栏
        LinearLayout head = new LinearLayout(sCtx);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(sCtx);
        title.setText("XhsSpy  ·  :" + (MiniServer.PORT_START));
        title.setTextColor(0xFFFF2442);
        title.setTextSize(14);
        title.setTypeface(null, 1);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        head.addView(title, tlp);

        TextView close = new TextView(sCtx);
        close.setText("收起");
        close.setTextColor(0xFF8A8A95);
        close.setTextSize(12);
        close.setPadding(dp(8), dp(4), dp(4), dp(4));
        close.setOnClickListener(x -> sPanel.setVisibility(View.GONE));
        head.addView(close);

        root.addView(head);

        // 快捷按钮
        root.addView(btnRow(new String[]{"打开网页", "小程序"}, new Runnable[]{
                () -> openUrl("/"),
                () -> openUrl("/mini")
        }));
        root.addView(btnRow(new String[]{"小工具", "数值"}, new Runnable[]{
                () -> openUrl("/tool"),
                () -> openUrl("/vars")
        }));
        root.addView(btnRow(new String[]{"壁纸站", "包清单"}, new Runnable[]{
                () -> openUrl("/wall"),
                () -> openUrl("/pkg")
        }));

        // 分割
        View line = new View(sCtx);
        line.setBackgroundColor(0xFF2A2F3A);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        llp.topMargin = dp(10);
        llp.bottomMargin = dp(8);
        root.addView(line, llp);

        // 已抓到的列表
        sList = new LinearLayout(sCtx);
        sList.setOrientation(LinearLayout.VERTICAL);
        root.addView(sList);

        return root;
    }

    static LinearLayout btnRow(String[] labels, Runnable[] actions) {
        LinearLayout row = new LinearLayout(sCtx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(8);
        row.setLayoutParams(rlp);

        for (int i = 0; i < labels.length; i++) {
            final Runnable act = actions[i];
            TextView b = new TextView(sCtx);
            b.setText(labels[i]);
            b.setTextColor(0xFFE8E8EC);
            b.setTextSize(12);
            b.setGravity(Gravity.CENTER);
            b.setPadding(0, dp(9), 0, dp(9));
            GradientDrawable g = new GradientDrawable();
            g.setColor(0xFF1F232C);
            g.setCornerRadius(dp(10));
            b.setBackground(g);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) blp.leftMargin = dp(8);
            b.setLayoutParams(blp);
            b.setOnClickListener(x -> {
                try { act.run(); } catch (Throwable t) { log("按钮异常 " + t); }
            });
            row.addView(b);
        }
        return row;
    }

    static TextView line(String text, int color, int size) {
        TextView t = new TextView(sCtx);
        t.setText(text);
        t.setTextColor(color);
        t.setTextSize(size);
        t.setPadding(0, dp(3), 0, dp(3));
        return t;
    }

    static void refreshPanel() {
        if (sList == null) return;
        sList.removeAllViews();

        // 内存
        try {
            Runtime r = Runtime.getRuntime();
            long used = (r.totalMemory() - r.freeMemory()) / 1048576L;
            long max = r.maxMemory() / 1048576L;
            sList.addView(line("内存 " + used + " / " + max + " MB", 0xFF6B7280, 11));
        } catch (Throwable ignored) {}

        List<MiniPacker.Mini> tools = MiniPacker.listByType(MiniPacker.TYPE_TOOL);
        List<MiniPacker.Mini> apps = MiniPacker.listByType(MiniPacker.TYPE_APP);

        sList.addView(line("小工具 " + tools.size() + " · 小程序 " + apps.size(),
                0xFF8A8A95, 11));

        int shown = 0;
        for (MiniPacker.Mini m : tools) {
            if (shown++ >= 3) break;
            sList.addView(item(m, true));
        }
        for (MiniPacker.Mini m : apps) {
            if (shown++ >= 5) break;
            sList.addView(item(m, false));
        }
        if (shown == 0) {
            sList.addView(line("还没抓到东西，去点开一个小工具", 0xFF5B6270, 11));
        }
    }

    static TextView item(MiniPacker.Mini m, boolean isTool) {
        String name = (m.name == null || m.name.isEmpty()) ? m.appid : m.name;
        TextView t = line((isTool ? "▸ " : "· ") + name
                + "  " + MiniPacker.human(m.bytes), 0xFFC9CFDB, 11);
        t.setOnClickListener(x -> {
            if (isTool && m.hasSource) openUrl("/tool/" + m.appid + "/");
            else openUrl("/mini");
        });
        return t;
    }

    // ------------------------------------------------------------ 动作

    static void openUrl(String path) {
        try {
            String url = "http://127.0.0.1:" + MiniServer.PORT_START + path;
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            sCtx.startActivity(i);
            log("打开 " + url);
            if (sPanel != null) sPanel.setVisibility(View.GONE);
        } catch (Throwable t) {
            MiniPacker.toast(sCtx, "没有可用的浏览器");
            log("openUrl 失败 " + t);
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

    // ------------------------------------------------------------ 状态

    public static String statusText() {
        try {
            boolean perm = Settings.canDrawOverlays(sCtx == null ? null : sCtx);
            return String.format(Locale.US, "悬浮窗=%s 权限=%s",
                    sShown ? "开" : "关", perm ? "有" : "无");
        } catch (Throwable t) {
            return "悬浮窗=未知";
        }
    }
}
