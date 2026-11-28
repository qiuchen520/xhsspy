package com.rxw.xhsspy;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * FloatingWindow - 小红书内的悬浮窗
 *
 * 整个东西只有一个 window：收起是一条胶囊，点一下从胶囊里长成面板，
 * 再点一下缩回去。没有额外的球，展开和收起是一体的。
 *
 *   ┌──────────────┐
 *   │ (o) xhs      │   胶囊
 *   ├────┬─────────┤
 *   │分类│ 详细功能 │   面板：左窄右宽，各自成块，拖动时一起走
 *   │ 一 │         │
 *   │ 二 │         │
 *   └────┴─────────┘
 *
 * 内容几乎全在窗内完成，只有「预览」这种真的要用网页的才跳浏览器。
 */
public class FloatingWindow {

    private static Context sCtx;
    private static WindowManager sWm;
    private static LinearLayout sRoot;
    private static LinearLayout sCapsule;
    private static FrameLayout sPanel;
    private static LinearLayout sNav;
    private static ScrollView sScroll;
    private static LinearLayout sContent;
    private static WindowManager.LayoutParams sLp;
    private static boolean sShown = false;
    private static boolean sOpen = false;
    private static final Handler UI = new Handler(Looper.getMainLooper());

    private static String sCur = "app";
    private static int sX = -1, sY = -1;

    // 胶囊尺寸 dp
    private static final int CAP_H = 44;
    private static final int CAP_W = 156;
    // 面板目标尺寸 dp
    private static final int PANEL_W = 306;
    private static final int PANEL_H = 442;

    // 纱窗色板
    static final int C_TEXT   = 0xFFF1F3F7;
    static final int C_SUB    = 0x8AFFFFFF;
    static final int C_DIM    = 0x5CFFFFFF;
    static final int C_ACCENT = 0xFFFF6B85;
    static final int C_BG     = 0xF00E1017;
    static final int C_STROKE = 0x2EFFFFFF;
    static final int C_CARD   = 0x14FFFFFF;
    static final int C_CARD2  = 0x0AFFFFFF;

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
                if (!ok) { requestPermission(); sShown = false; return; }
                attach();
            } catch (Throwable t) {
                log("show 失败: " + t);
                sShown = false;
            }
        });
    }

    public static void hide() {
        UI.post(() -> {
            try {
                if (sWm != null && sRoot != null) sWm.removeView(sRoot);
            } catch (Throwable ignored) {}
            sRoot = null; sCapsule = null; sPanel = null;
            sShown = false; sOpen = false;
        });
    }

    static void log(String s) { XhsSpy.log("float", s); }

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

    static GradientDrawable glass(int color, int radiusDp, int strokeColor) {
        GradientDrawable g = round(color, radiusDp);
        g.setStroke(dp(1), strokeColor);
        return g;
    }

    static TextView text(String s, int color, int sizeSp) {
        TextView t = new TextView(sCtx);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(sizeSp);
        return t;
    }

    static TextView bold(String s, int color, int sizeSp) {
        TextView t = text(s, color, sizeSp);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    static int screenW() { return realW; }
    static int screenH() { return realH; }

    static int realW = 0, realH = 0;
    static boolean sRotWatching = false;

    /** 用 Display 的真实尺寸，旋转后会变。Resources 里那个不跟手。 */
    static void refreshScreen() {
        try {
            WindowManager wm = (WindowManager) sCtx.getSystemService(Context.WINDOW_SERVICE);
            android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);
            realW = dm.widthPixels;
            realH = dm.heightPixels;
        } catch (Throwable t) {
            realW = sCtx.getResources().getDisplayMetrics().widthPixels;
            realH = sCtx.getResources().getDisplayMetrics().heightPixels;
        }
    }

    /** 转屏后把窗口拽回屏幕里，别飘出去 */
    static void clampToScreen() {
        if (sLp == null || sRoot == null) return;
        refreshScreen();
        int sw = screenW(), sh = screenH();
        int w = sLp.width, h = sLp.height;
        sLp.x = Math.max(0, Math.min(sLp.x, Math.max(0, sw - w)));
        sLp.y = Math.max(0, Math.min(sLp.y, Math.max(0, sh - h)));
        try { sWm.updateViewLayout(sRoot, sLp); } catch (Throwable ignored) {}
        if (sOpen) applySize(true);
    }

    /** 盯着屏幕旋转，转了就把位置收回来 */
    static void startRotationWatch() {
        if (sRotWatching) return;
        sRotWatching = true;
        Thread t = new Thread(() -> {
            int last = -1;
            while (sShown) {
                int r = -1;
                try {
                    WindowManager wm = (WindowManager) sCtx.getSystemService(Context.WINDOW_SERVICE);
                    r = wm.getDefaultDisplay().getRotation();
                } catch (Throwable ignored) {}
                if (r != last) {
                    final boolean first = (last == -1);
                    last = r;
                    if (!first) UI.post(FloatingWindow::clampToScreen);
                }
                try { Thread.sleep(700); } catch (Throwable ignored) {}
            }
        }, "XhsSpyRot");
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------ 挂载

    static void attach() {
        sWm = (WindowManager) sCtx.getSystemService(Context.WINDOW_SERVICE);
        if (sWm == null) return;

        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        refreshScreen();
        sRoot = new LinearLayout(sCtx);
        sRoot.setOrientation(LinearLayout.VERTICAL);
        sRoot.setClipChildren(false);
        sRoot.setClipToPadding(false);

        // 胶囊
        sCapsule = makeCapsule();
        // 固定宽度：展开时窗口会变宽，但胶囊本身不能被拉长
        sRoot.addView(sCapsule, new LinearLayout.LayoutParams(
                dp(CAP_W), dp(CAP_H)));

        // 面板
        sPanel = makePanel();
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        sRoot.addView(sPanel, plp);
        sPanel.setVisibility(View.GONE);

        sLp = new WindowManager.LayoutParams(
                dp(CAP_W), dp(CAP_H), type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSLUCENT);
        sLp.gravity = Gravity.TOP | Gravity.START;
        sLp.x = (sX >= 0) ? sX : dp(10);
        sLp.y = (sY >= 0) ? sY : dp(240);

        try {
            sWm.addView(sRoot, sLp);
        } catch (Throwable t) {
            log("addView 失败 " + t);
            MiniPacker.toast(sCtx, "悬浮窗添加失败：" + t);
            sShown = false;
            return;
        }
        startRotationWatch();
        log("悬浮窗已挂载");
    }

    /** 收起 / 展开时把窗口尺寸换掉。锚点（左上角）始终不动，胶囊不会跳。 */
    static void applySize(boolean expanded) {
        if (sWm == null || sRoot == null || sLp == null) return;
        int sw = screenW(), sh = screenH();
        try {
            if (expanded) {
                int w = Math.min(dp(PANEL_W), Math.max(dp(190), sw - sLp.x - dp(8)));
                int h = Math.min(dp(PANEL_H), Math.max(dp(230), sh - sLp.y - dp(8)));
                sLp.width = w;
                sLp.height = h;
            } else {
                sLp.width = dp(CAP_W);
                sLp.height = dp(CAP_H);
            }
            sWm.updateViewLayout(sRoot, sLp);
        } catch (Throwable t) {
            log("applySize " + t);
        }
    }

    // ------------------------------------------------------------ 胶囊

    static LinearLayout makeCapsule() {
        final LinearLayout cap = new LinearLayout(sCtx);
        cap.setOrientation(LinearLayout.HORIZONTAL);
        cap.setGravity(Gravity.CENTER_VERTICAL);
        cap.setPadding(dp(7), dp(5), dp(12), dp(5));

        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xF2181B24, 0xF00E1017});
        bg.setCornerRadius(dp(CAP_H / 2f));
        bg.setStroke(dp(1), C_STROKE);
        cap.setBackground(bg);

        // 头像
        TextView avatar = bold("无", 0xFFFFFFFF, 11);
        avatar.setGravity(Gravity.CENTER);
        GradientDrawable abg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFFFF3B5C, 0xFF7C4DFF});
        abg.setShape(GradientDrawable.OVAL);
        avatar.setBackground(abg);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(dp(32), dp(32));
        cap.addView(avatar, alp);

        // 名字
        TextView name = bold("xhs", C_TEXT, 13);
        name.setPadding(dp(9), 0, dp(2), 0);
        cap.addView(name);

        TextView hint = text("▾", 0x77FFFFFF, 10);
        cap.addView(hint, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView rot = bold("⟳", 0xFFFFD6DD, 15);
        rot.setGravity(Gravity.CENTER);
        rot.setPadding(dp(6), dp(3), dp(6), dp(3));
        rot.setBackground(round(0x22FF6B85, 8));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                dp(26), dp(26));
        cap.addView(rot, rlp);

        // 整个胶囊接手势：拖动 = 移动窗口，轻点 = 头像切面板 / 文字跳网页
        cap.setOnTouchListener(new View.OnTouchListener() {
            int baseX, baseY;
            float downRX, downRY;
            boolean moved = false;
            long downAt = 0;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        baseX = sLp.x; baseY = sLp.y;
                        downRX = e.getRawX(); downRY = e.getRawY();
                        moved = false;
                        downAt = System.currentTimeMillis();
                        avatar.animate().scaleX(0.86f).scaleY(0.86f).setDuration(80).start();
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        int dx = (int) (e.getRawX() - downRX);
                        int dy = (int) (e.getRawY() - downRY);
                        if (!moved && (Math.abs(dx) > dp(8) || Math.abs(dy) > dp(8))) moved = true;
                        if (!moved) return true;
                        int nx = baseX + dx, ny = baseY + dy;
                        nx = Math.max(-dp(30), Math.min(nx, screenW() - dp(50)));
                        ny = Math.max(-dp(10), Math.min(ny, screenH() - dp(40)));
                        sLp.x = nx; sLp.y = ny;
                        try { sWm.updateViewLayout(sRoot, sLp); } catch (Throwable ignored) {}
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL: {
                        avatar.animate().scaleX(1f).scaleY(1f).setDuration(140).start();
                        if (moved) {
                            sX = sLp.x; sY = sLp.y;
                            if (sOpen) applySize(true);
                            return true;
                        }
                        if (e.getActionMasked() == MotionEvent.ACTION_CANCEL) return true;
                        long held = System.currentTimeMillis() - downAt;
                        if (held > 700) {
                            // 长按才跳网页
                            openUrl("/");
                            return true;
                        }
                        float lx = e.getX();
                        float cw = v.getWidth();
                        if (lx > cw - dp(34)) {
                            // 右边那一小块是转屏
                            String r = XhsSpy.rotate("toggle");
                            if (r.contains("error")) {
                                MiniPacker.toast(sCtx, "转不了屏：" + r);
                            }
                            return true;
                        }
                        // 其余位置轻点一律展开 / 收起
                        toggle();
                        return true;
                    }
                }
                return true;
            }
        });
        return cap;
    }

    static void toggle() {
        if (sOpen) closePanel(); else openPanel();
    }

    static void openPanel() {
        if (sPanel == null || sOpen) return;
        sOpen = true;
        sPanel.animate().cancel();
        applySize(true);
        sPanel.setVisibility(View.VISIBLE);
        sPanel.setPivotX(dp(26));
        sPanel.setPivotY(-dp(CAP_H + 4));
        sPanel.setScaleX(0.34f);
        sPanel.setScaleY(0.34f);
        sPanel.setAlpha(0f);
        sPanel.animate()
                .scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(260)
                .setInterpolator(new OvershootInterpolator(0.85f))
                .start();
        sCapsule.animate().alpha(0.62f).setDuration(200).start();
        buildNav();
        refresh();
        log("面板展开");
    }

    static void closePanel() {
        if (sPanel == null || !sOpen) return;
        sOpen = false;
        sPanel.animate().cancel();
        setImeMode(false);
        sCapsule.animate().alpha(1f).setDuration(160).start();
        sPanel.animate()
                .scaleX(0.34f).scaleY(0.34f).alpha(0f)
                .setDuration(200)
                .setInterpolator(new DecelerateInterpolator(1.6f))
                .withEndAction(() -> {
                    if (sPanel == null || sOpen) return;   // 中途又被点开了，不藏
                    sPanel.setVisibility(View.GONE);
                    applySize(false);
                })
                .start();
        log("面板收起");
    }

    // ------------------------------------------------------------ 面板骨架

    static final String[] NAV_KEYS =
            {"game", "app", "tool", "vars", "doc", "tools", "wall", "log", "mem", "proc"};
    static final String[] NAV_TEXT =
            {"游戏", "小程序", "小工具", "数值", "文档", "工具", "壁纸", "日志", "内存", "进程"};

    static FrameLayout makePanel() {
        FrameLayout wrap = new FrameLayout(sCtx);

        // 背板：Android 12+ 真模糊，低版本用不透明度兜
        wrap.setBackground(glass(C_BG, 18, C_STROKE));
        int pad = dp(8);
        wrap.setPadding(pad, dp(6), pad, pad);

        LinearLayout body = new LinearLayout(sCtx);
        body.setOrientation(LinearLayout.HORIZONTAL);
        wrap.addView(body, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        // 左栏
        LinearLayout left = new LinearLayout(sCtx);
        left.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable lbg = glass(C_CARD2, 14, 0x18FFFFFF);
        left.setBackground(lbg);
        left.setPadding(dp(4), dp(6), dp(4), dp(6));
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(dp(74),
                LinearLayout.LayoutParams.MATCH_PARENT);
        llp.rightMargin = dp(7);
        body.addView(left, llp);

        sNav = new LinearLayout(sCtx);
        sNav.setOrientation(LinearLayout.VERTICAL);
        left.addView(sNav, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // 右栏
        FrameLayout right = new FrameLayout(sCtx);
        GradientDrawable rbg = glass(C_CARD2, 14, 0x18FFFFFF);
        right.setBackground(rbg);
        body.addView(right, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        sScroll = new ScrollView(sCtx);
        sScroll.setVerticalScrollBarEnabled(false);
        sScroll.setPadding(dp(9), dp(8), dp(9), dp(8));
        sScroll.setClipToPadding(false);
        right.addView(sScroll, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        sContent = new LinearLayout(sCtx);
        sContent.setOrientation(LinearLayout.VERTICAL);
        sScroll.addView(sContent, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));

        return wrap;
    }

    static void buildNav() {
        if (sNav == null) return;
        sNav.removeAllViews();
        for (int i = 0; i < NAV_KEYS.length; i++) {
            final String key = NAV_KEYS[i];
            boolean on = key.equals(sCur);
            TextView t = bold(NAV_TEXT[i], on ? 0xFFFFFFFF : 0x8AFFFFFF, 12);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(8), 0, dp(8));
            if (on) {
                GradientDrawable g = round(0x2EFFFFFF, 11);
                g.setStroke(dp(1), 0x33FFFFFF);
                t.setBackground(g);
            } else {
                t.setBackground(round(0x00000000, 11));
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(3);
            t.setOnClickListener(v -> {
                sCur = key;
                buildNav();
                refresh();
            });
            sNav.addView(t, lp);
        }
    }

    // ------------------------------------------------------------ 内容分发

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
                case "proc": renderProc(); break;
                case "game": renderGame(); break;
                case "doc":  renderDoc(); break;
                case "tools": renderTools(); break;
            }
        } catch (Throwable t) {
            sContent.addView(text("出错：" + t, 0xFFFF6B6B, 11));
        }
    }

    static TextView head(String s) {
        TextView t = bold(s, C_ACCENT, 11);
        t.setPadding(0, dp(2), 0, dp(6));
        return t;
    }

    static TextView row(String s, int color, int size) {
        TextView t = text(s, color, size);
        t.setPadding(0, dp(3), 0, dp(3));
        return t;
    }

    static LinearLayout card() {
        LinearLayout c = new LinearLayout(sCtx);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(9), dp(8), dp(9), dp(8));
        c.setBackground(glass(C_CARD, 11, 0x14FFFFFF));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(7);
        c.setLayoutParams(lp);
        return c;
    }

    static TextView chip(String label, int bg, int fg, final Runnable act) {
        TextView t = bold(label, fg, 10);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(10), dp(5), dp(10), dp(5));
        t.setBackground(round(bg, 8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(5);
        t.setLayoutParams(lp);
        t.setOnClickListener(v -> { try { act.run(); } catch (Throwable e) { log("chip " + e); } });
        return t;
    }

    // ------------------------------------------------------------ 小程序 / 小工具

    static void renderMinis(String type) {
        final String t0 = type;
        sContent.addView(head(MiniPacker.TYPE_TOOL.equals(type) ? "小工具" : "小程序"));
        sContent.addView(row("正在扫描…", C_DIM, 11));
        new Thread(() -> {
            List<MiniPacker.Mini> got;
            try { got = MiniPacker.listByType(t0); }
            catch (Throwable t) { got = new ArrayList<>(); }
            final List<MiniPacker.Mini> list = got;
            UI.post(() -> {
                if (sContent == null || !t0.equals(sCur)) return;
                sContent.removeAllViews();
                buildMiniCards(t0, list);
            });
        }, "XhsSpyMiniList").start();
    }

    static void buildMiniCards(String type, List<MiniPacker.Mini> list) {
        boolean tool = MiniPacker.TYPE_TOOL.equals(type);
        sContent.addView(head((tool ? "小工具" : "小程序") + " · " + list.size() + " 个"));
        if (list.isEmpty()) {
            sContent.addView(row("还没有缓存\n去 App 里点开一个再回来", C_DIM, 11));
            return;
        }
        for (final MiniPacker.Mini m : list) {
            final String nm = (m.name == null || m.name.isEmpty()) ? m.appid : m.name;
            LinearLayout c = card();

            TextView title = bold(nm, C_TEXT, 12);
            title.setMaxLines(2);
            title.setOnClickListener(v -> {
                if (tool && m.hasSource) openUrl("/tool/" + m.appid + "/");
                else openUrl("/mini");
            });
            c.addView(title);

            c.addView(row(MiniPacker.human(m.bytes) + " · " + m.files + " 个文件",
                    C_SUB, 10));

            LinearLayout btns = new LinearLayout(sCtx);
            btns.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            blp.topMargin = dp(7);
            btns.setLayoutParams(blp);
            btns.addView(chip("导出", 0x2E5B8CFF, 0xFFD3E1FF, () -> doPack(m, false)));
            btns.addView(chip("分享", 0x2E8B5CF6, 0xFFDDD0FF, () -> doPack(m, true)));
            if (tool && m.hasSource) {
                btns.addView(chip("预览", 0x2E22C55E, 0xFFC8F2D8,
                        () -> openUrl("/tool/" + m.appid + "/")));
            }
            c.addView(btns);
            sContent.addView(c);
        }
    }

    static void doPack(final MiniPacker.Mini m, final boolean share) {
        MiniPacker.toast(sCtx, "正在打包 " + (m.name == null ? m.appid : m.name) + "…");
        new Thread(() -> {
            final String r = MiniPacker.packAndShare(sCtx, m, share);
            UI.post(() -> MiniPacker.toast(sCtx, r != null && r.startsWith("ok") ? "已导出" : String.valueOf(r)));
        }, "XhsSpyPackBtn").start();
    }

    // ------------------------------------------------------------ 数值（gg 味）

    static JSONObject sVarsCache = null;
    static String sVarTab = "w";     // w = window, s = storage
    static String sVarFilter = "all"; // all / number / text / bool
    static String sVarQuery = "";
    static LinearLayout sVarList = null;

    static void renderVars() {
        sContent.addView(head("运行时数值"));

        // 搜索
        final EditText search = new EditText(sCtx);
        search.setHint("搜索变量名或值");
        search.setHintTextColor(0x4DFFFFFF);
        search.setTextColor(C_TEXT);
        search.setTextSize(11);
        search.setSingleLine(true);
        search.setBackground(glass(0x14FFFFFF, 9, 0x1AFFFFFF));
        search.setPadding(dp(9), dp(6), dp(9), dp(6));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.bottomMargin = dp(7);
        search.setLayoutParams(slp);
        search.setText(sVarQuery);
        search.setOnFocusChangeListener((v, has) -> setImeMode(has));
        search.addTextChangedListener(new android.text.TextWatcher() {
            public void afterTextChanged(android.text.Editable e) {
                sVarQuery = e.toString();
                if (sVarList != null) fillVars(sVarList);
            }
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
        });
        sContent.addView(search);

        // 作用域 tab
        LinearLayout tabs = new LinearLayout(sCtx);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        tlp.bottomMargin = dp(6);
        tabs.setLayoutParams(tlp);
        tabs.addView(chip("window", sVarTab.equals("w") ? 0x33FFFFFF : 0x12FFFFFF,
                sVarTab.equals("w") ? 0xFFFFFFFF : 0x8AFFFFFF,
                () -> { sVarTab = "w"; refresh(); }));
        tabs.addView(chip("localStorage", sVarTab.equals("s") ? 0x33FFFFFF : 0x12FFFFFF,
                sVarTab.equals("s") ? 0xFFFFFFFF : 0x8AFFFFFF,
                () -> { sVarTab = "s"; refresh(); }));
        sContent.addView(tabs);

        // 类型过滤
        LinearLayout filters = new LinearLayout(sCtx);
        filters.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        flp.bottomMargin = dp(7);
        filters.setLayoutParams(flp);
        String[][] fs = {{"all", "全部"}, {"number", "数字"}, {"text", "文本"}, {"bool", "布尔"}};
        for (final String[] f : fs) {
            boolean on = f[0].equals(sVarFilter);
            filters.addView(chip(f[1], on ? 0x2EFF6B85 : 0x12FFFFFF,
                    on ? 0xFFFFB3C0 : 0x8AFFFFFF,
                    () -> { sVarFilter = f[0]; if (sVarList != null) fillVars(sVarList); }));
        }
        sContent.addView(filters);

        // 执行 JS：闭包里的状态靠这个改
        TextView jsBtn = chip("执行 JS", 0x2E8B5CF6, 0xFFDDD0FF, FloatingWindow::promptJs);
        LinearLayout.LayoutParams jlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        jlp.bottomMargin = dp(7);
        jsBtn.setLayoutParams(jlp);
        sContent.addView(jsBtn);

        TextView reloadBtn = chip("刷新页面", 0x2E22C55E, 0xFFC8F2D8, () -> {
            new Thread(() -> {
                String r = XhsSpy.evalJs("location.reload()", 3000);
                sVarsCache = null;
                UI.post(() -> MiniPacker.toast(sCtx, "已刷新页面"));
            }, "XhsSpyReload").start();
        });
        LinearLayout.LayoutParams rlb = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        rlb.bottomMargin = dp(7);
        rlb.leftMargin = dp(5);
        reloadBtn.setLayoutParams(rlb);
        sContent.addView(reloadBtn);

        // 列表容器
        sVarList = new LinearLayout(sCtx);
        sVarList.setOrientation(LinearLayout.VERTICAL);
        sContent.addView(sVarList, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        if (sVarsCache != null) { fillVars(sVarList); return; }

        sVarList.addView(row("读取页面中…", C_DIM, 11));
        new Thread(() -> {
            final String raw = XhsSpy.probeVars();
            UI.post(() -> {
                try { sVarsCache = new JSONObject(raw); }
                catch (Throwable t) { sVarsCache = null; }
                if (sVarList != null && "vars".equals(sCur)) fillVars(sVarList);
            });
        }, "XhsSpyVars").start();
    }

    /** 重新按当前缓存 + 过滤条件画列表 */
    static void fillVars(LinearLayout host) {
        host.removeAllViews();
        if (sVarsCache == null) {
            host.addView(row("读不到页面\n先在 App 里打开一个小工具或网页，再刷新", C_DIM, 11));
            host.addView(chip("重新读取", 0x2E5B8CFF, 0xFFD3E1FF,
                    () -> { sVarsCache = null; refresh(); }));
            return;
        }
        if ("w".equals(sVarTab)) fillWindowVars(host); else fillStorageVars(host);
    }

    static boolean matchFilter(String type, String key, String val) {
        String q = sVarQuery == null ? "" : sVarQuery.trim().toLowerCase(Locale.US);
        if (!q.isEmpty()) {
            boolean hit = key.toLowerCase(Locale.US).contains(q)
                    || (val != null && val.toLowerCase(Locale.US).contains(q));
            if (!hit) return false;
        }
        if ("all".equals(sVarFilter)) return true;
        if ("number".equals(sVarFilter)) return "数字".equals(type);
        if ("text".equals(sVarFilter)) return "文本".equals(type);
        if ("bool".equals(sVarFilter)) return "布尔".equals(type);
        return true;
    }

    static void fillWindowVars(LinearLayout host) {
        JSONArray vars = sVarsCache.optJSONArray("vars");
        int shown = 0, total = vars == null ? 0 : vars.length();
        if (vars != null) {
            for (int i = 0; i < vars.length(); i++) {
                JSONObject v = vars.optJSONObject(i);
                if (v == null) continue;
                if (!v.optBoolean("w", false)) continue;
                String k = v.optString("k");
                String t = v.optString("t");
                String val = v.optString("v");
                if (!matchFilter(t, k, val)) continue;
                addVarItem(host, "window", k, t, val);
                shown++;
            }
        }
        if (shown == 0) host.addView(row("没有匹配的可改变量", C_DIM, 10));
        TextView refresh = chip("重新读取", 0x12FFFFFF, 0x99FFFFFF,
                () -> { sVarsCache = null; refresh(); });
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(6);
        refresh.setLayoutParams(rlp);
        host.addView(refresh);
        host.addView(row(total + " 个变量，可改 " + shown, C_DIM, 9));
    }

    static void fillStorageVars(LinearLayout host) {
        JSONArray ls = sVarsCache.optJSONArray("storage");
        int shown = 0;
        if (ls != null) {
            for (int i = 0; i < ls.length(); i++) {
                JSONObject v = ls.optJSONObject(i);
                if (v == null) continue;
                String k = v.optString("k");
                String val = v.optString("v");
                if (!matchFilter("文本", k, val)) continue;
                addVarItem(host, "storage", k, "文本", val);
                shown++;
            }
        }
        if (shown == 0) host.addView(row("没有匹配的条目", C_DIM, 10));
        host.addView(row("localStorage · " + shown + " 条", C_DIM, 9));
    }

    /** 一条变量：点开就地展开编辑行，不弹对话框 */
    static void addVarItem(LinearLayout host, final String scope, final String key,
                           String type, String val) {
        final LinearLayout box = new LinearLayout(sCtx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(glass(0x12FFFFFF, 9, 0x14FFFFFF));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.bottomMargin = dp(5);
        box.setLayoutParams(blp);

        final LinearLayout face = new LinearLayout(sCtx);
        face.setOrientation(LinearLayout.VERTICAL);
        face.setPadding(dp(8), dp(6), dp(8), dp(6));

        LinearLayout line1 = new LinearLayout(sCtx);
        line1.setOrientation(LinearLayout.HORIZONTAL);
        line1.setGravity(Gravity.CENTER_VERTICAL);

        TextView kt = bold(key, C_TEXT, 11);
        kt.setMaxLines(1);
        kt.setEllipsize(android.text.TextUtils.TruncateAt.END);
        line1.addView(kt, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView badge = text(type, 0xB3FFFFFF, 9);
        badge.setPadding(dp(6), dp(1), dp(6), dp(1));
        badge.setBackground(round(0x26FF6B85, 6));
        line1.addView(badge);

        face.addView(line1);

        String shortVal = val == null ? "null" : val;
        if (shortVal.length() > 90) shortVal = shortVal.substring(0, 90) + "…";
        TextView vt = text(shortVal, C_SUB, 10);
        vt.setMaxLines(2);
        vt.setPadding(0, dp(2), 0, 0);
        face.addView(vt);

        box.addView(face);

        face.setOnClickListener(v -> {
            if (box.getChildCount() > 1) { box.removeViewAt(1); setImeMode(false); return; }
            setImeMode(true);
            box.addView(makeEditor(scope, key, type, val, box));
        });

        host.addView(box);
    }

    static View makeEditor(final String scope, final String key, String type,
                           String oldVal, final LinearLayout box) {
        LinearLayout ed = new LinearLayout(sCtx);
        ed.setOrientation(LinearLayout.VERTICAL);
        ed.setPadding(dp(8), 0, dp(8), dp(8));

        final EditText input = new EditText(sCtx);
        input.setText(oldVal == null ? "" : oldVal);
        input.setTextColor(C_TEXT);
        input.setTextSize(11);
        input.setSingleLine(false);
        input.setMaxLines(4);
        input.setBackground(glass(0x1AFFFFFF, 8, 0x22FFFFFF));
        input.setPadding(dp(8), dp(6), dp(8), dp(6));
        input.setOnFocusChangeListener((v, has) -> setImeMode(has));
        ed.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout btns = new LinearLayout(sCtx);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(6);
        btns.setLayoutParams(blp);

        final String[] asType = {"auto"};
        String[] typeNames = {"自动", "数字", "文本", "布尔"};
        final String[] typeVals = {"auto", "number", "string", "boolean"};
        for (int i = 0; i < typeNames.length; i++) {
            final String tv = typeVals[i];
            final boolean on = (i == 0);
            TextView c = chip(typeNames[i],
                    on ? 0x2EFF6B85 : 0x12FFFFFF,
                    on ? 0xFFFFB3C0 : 0x8AFFFFFF,
                    () -> {
                        asType[0] = tv;
                        for (int j = 0; j < btns.getChildCount(); j++) {
                            TextView x = (TextView) btns.getChildAt(j);
                            boolean sel = typeVals[j].equals(tv);
                            x.setBackground(round(sel ? 0x2EFF6B85 : 0x12FFFFFF, 8));
                            x.setTextColor(sel ? 0xFFFFB3C0 : 0x8AFFFFFF);
                        }
                    });
            btns.addView(c);
        }
        ed.addView(btns);

        LinearLayout acts = new LinearLayout(sCtx);
        acts.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        alp.topMargin = dp(7);
        acts.setLayoutParams(alp);

        acts.addView(chip("写入", 0x33FF6B85, 0xFFFFD6DD, () -> {
            final String nv = input.getText().toString();
            new Thread(() -> {
                final JSONObject r = VarsPanel.set(scope, key, nv, asType[0]);
                UI.post(() -> {
                    boolean ok = r.optBoolean("ok", false);
                    MiniPacker.toast(sCtx, ok ? ("已改成 " + r.optString("now"))
                            : ("失败：" + r.optString("error", "?")));
                    if (ok) { setImeMode(false); sVarsCache = null; refresh(); }
                });
            }, "XhsSpySet").start();
        }));
        acts.addView(chip("取消", 0x12FFFFFF, 0x99FFFFFF, () -> {
            box.removeViewAt(1);
            setImeMode(false);
        }));
        ed.addView(acts);

        return ed;
    }

    /** 一个能跑任意 JS 的小控制台，游戏里的闭包状态就靠这个 */
    static void promptJs() {
        if (sContent == null) return;
        LinearLayout box = new LinearLayout(sCtx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(8), dp(6), dp(8), dp(8));
        box.setBackground(glass(0x18FFFFFF, 10, 0x22FFFFFF));

        final EditText code = new EditText(sCtx);
        code.setTextColor(C_TEXT);
        code.setTextSize(11);
        code.setHint("比如 G.set.quality = 'low'");
        code.setHintTextColor(0x4DFFFFFF);
        code.setSingleLine(false);
        code.setMinLines(3);
        code.setBackground(glass(0x14FFFFFF, 8, 0x1FFFFFFF));
        code.setPadding(dp(8), dp(6), dp(8), dp(6));
        code.setTypeface(Typeface.MONOSPACE);
        code.setOnFocusChangeListener((v, has) -> setImeMode(has));
        box.addView(code, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(88)));

        // 已存的常用脚本，点一下填进输入框
        try {
            JSONArray snips = new JSONArray(XhsSpy.getSnippets());
            for (int i = 0; i < snips.length(); i++) {
                JSONObject o = snips.optJSONObject(i);
                if (o == null) continue;
                final String nm = o.optString("name", "脚本");
                final String cd = o.optString("code", "");
                if (cd.isEmpty()) continue;
                TextView sc = chip("★ " + nm, 0x1FFFFFFF, 0xCCE8D8FF, () -> {
                    code.setText(cd);
                    try { code.setSelection(cd.length()); } catch (Throwable ignored) {}
                });
                LinearLayout.LayoutParams slp2 = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                slp2.bottomMargin = dp(4);
                sc.setLayoutParams(slp2);
                box.addView(sc);
            }
        } catch (Throwable ignored) {}

        final TextView result = text("", 0xCCFFFFFF, 10);
        result.setPadding(0, dp(5), 0, 0);
        result.setTextIsSelectable(true);
        box.addView(result);

        LinearLayout acts = new LinearLayout(sCtx);
        acts.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        alp.topMargin = dp(7);
        acts.setLayoutParams(alp);

        acts.addView(chip("运行", 0x338B5CF6, 0xFFE0D4FF, () -> {
            final String src = code.getText().toString().trim();
            if (src.isEmpty()) return;
            result.setText("运行中…");
            new Thread(() -> {
                final JSONObject r = VarsPanel.evalJs(src);
                UI.post(() -> {
                    if (r.optBoolean("ok", false)) {
                        String v = r.optString("now", "");
                        if (v.isEmpty()) v = r.optString("_raw", "undefined");
                        result.setText("→ " + v);
                    } else {
                        result.setText("× " + r.optString("err", "执行失败"));
                    }
                });
            }, "XhsSpyJs").start();
        }));
        acts.addView(chip("存为常用", 0x2E22C55E, 0xFFC8F2D8, () -> {
            String src = code.getText().toString().trim();
            if (src.isEmpty()) { result.setText("代码是空的"); return; }
            String r = XhsSpy.addSnippet(null, src);
            result.setText(r.contains("\"ok\":true") ? "已存为常用脚本" : ("存失败：" + r));
        }));
        acts.addView(chip("关掉", 0x12FFFFFF, 0x99FFFFFF, () -> {
            setImeMode(false);
            sContent.removeView(box);
        }));
        box.addView(acts);

        sContent.addView(box, 0);
        setImeMode(true);
        try { code.requestFocus(); } catch (Throwable ignored) {}
    }

    /** 输入框拿到焦点才放开 window 的 NOT_FOCUSABLE，否则键盘弹不出来 */
    static void setImeMode(boolean on) {
        if (sLp == null || sWm == null || sRoot == null) return;
        int base = WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        if (!on) base |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        if (base == sLp.flags) return;
        sLp.flags = base;
        try { sWm.updateViewLayout(sRoot, sLp); } catch (Throwable ignored) {}
    }

    // ------------------------------------------------------------ 壁纸

    static void renderWall() {
        sContent.addView(head("掘金壁纸"));
        final LinearLayout grid = new LinearLayout(sCtx);
        grid.setOrientation(LinearLayout.VERTICAL);
        sContent.addView(grid);
        grid.addView(row("拉取首页中…", C_DIM, 10));

        new Thread(() -> {
            final List<String> thumbs = new ArrayList<>();
            final StringBuilder info = new StringBuilder();
            try {
                JSONObject d = Wall.api("home", new JSONObject());
                JSONObject data = d.optJSONObject("data");
                if (data == null) data = d;
                JSONArray list = data.optJSONArray("list");
                JSONArray rec = data.optJSONArray("recommend");
                info.append("作者 ").append(rec == null ? 0 : rec.length())
                    .append(" 位 · 图 ").append(list == null ? 0 : list.length());
                if (list != null) {
                    for (int i = 0; i < list.length() && thumbs.size() < 9; i++) {
                        JSONObject o = list.optJSONObject(i);
                        if (o == null) continue;
                        String u = o.optString("thumb");
                        if (u.isEmpty()) u = o.optString("preview");
                        if (u.isEmpty()) u = o.optString("img");
                        if (!u.isEmpty()) {
                            if (!u.startsWith("http")) u = "https://min-cdn.xliii.cn/" + u;
                            thumbs.add(u);
                        }
                    }
                }
            } catch (Throwable t) {
                info.append("拉取失败：").append(t);
            }
            final String infoStr = info.toString();
            UI.post(() -> {
                if (grid == null || !"wall".equals(sCur)) return;
                grid.removeAllViews();
                grid.addView(row(infoStr, C_SUB, 10));
                if (thumbs.isEmpty()) {
                    grid.addView(chip("打开网页版", 0x2E5B8CFF, 0xFFD3E1FF,
                            () -> openUrl("/wall")));
                    return;
                }
                LinearLayout line = null;
                for (int i = 0; i < thumbs.size(); i++) {
                    if (i % 2 == 0) {
                        line = new LinearLayout(sCtx);
                        line.setOrientation(LinearLayout.HORIZONTAL);
                        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT);
                        lp.topMargin = dp(4);
                        line.setLayoutParams(lp);
                        grid.addView(line);
                    }
                    final ImageView iv = new ImageView(sCtx);
                    iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    iv.setBackground(round(0x1AFFFFFF, 9));
                    LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(0, dp(84), 1f);
                    ip.rightMargin = dp(4);
                    iv.setLayoutParams(ip);
                    line.addView(iv);
                    loadThumb(iv, thumbs.get(i));
                }
                grid.addView(chip("打开网页版", 0x2E5B8CFF, 0xFFD3E1FF,
                        () -> openUrl("/wall")));
            });
        }, "XhsSpyWall").start();
    }

    static void loadThumb(final ImageView iv, final String url) {
        new Thread(() -> {
            Bitmap bm = null;
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(10000);
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10)");
                if (c.getResponseCode() == 200) {
                    bm = BitmapFactory.decodeStream(c.getInputStream());
                }
            } catch (Throwable ignored) {
            } finally {
                if (c != null) try { c.disconnect(); } catch (Throwable ignored) {}
            }
            final Bitmap b = bm;
            if (b == null) return;
            UI.post(() -> { try { iv.setImageBitmap(b); } catch (Throwable ignored) {} });
        }, "XhsSpyThumb").start();
    }

    // ------------------------------------------------------------ 日志

    static void renderLog() {
        sContent.addView(head("日志"));
        final LinearLayout host = new LinearLayout(sCtx);
        host.setOrientation(LinearLayout.VERTICAL);
        sContent.addView(host);

        String[] names = {"boot", "float", "mini", "net", "crash"};
        LinearLayout btns = new LinearLayout(sCtx);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.bottomMargin = dp(7);
        btns.setLayoutParams(blp);
        for (final String n : names) {
            btns.addView(chip(n, 0x12FFFFFF, 0xAAFFFFFF, () -> showLog(host, n)));
        }
        sContent.addView(btns);
        showLog(host, "float");
    }

    static File findLog(String name) {
        File out = null;
        try { out = XhsSpy.outDir(); } catch (Throwable ignored) {}
        if (out == null) return null;
        String[] cands = {name + ".log", name + ".txt", name};
        for (String c : cands) {
            File f = new File(out, c);
            if (f.exists()) return f;
        }
        return null;
    }

    static void showLog(LinearLayout host, String name) {
        host.removeAllViews();
        File f = findLog(name);
        if (f == null) {
            host.addView(row("没有 " + name + " 日志", C_DIM, 10));
            return;
        }
        host.addView(row(f.getName() + " · " + MiniPacker.human(f.length()), C_SUB, 9));
        String tail = tail(f, 60);
        TextView t = text(tail.isEmpty() ? "(空)" : tail, 0xC8FFFFFF, 9);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextIsSelectable(true);
        t.setPadding(dp(6), dp(6), dp(6), dp(6));
        t.setBackground(round(0x14FFFFFF, 8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        t.setLayoutParams(lp);
        host.addView(t);
    }

    static String tail(File f, int lines) {
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(f, "r");
            long len = raf.length();
            long start = Math.max(0, len - 24 * 1024);
            raf.seek(start);
            byte[] buf = new byte[(int) (len - start)];
            raf.readFully(buf);
            String s = new String(buf, "UTF-8");
            String[] arr = s.split("\n");
            StringBuilder sb = new StringBuilder();
            int from = Math.max(0, arr.length - lines);
            for (int i = from; i < arr.length; i++) sb.append(arr[i]).append('\n');
            return sb.toString();
        } catch (Throwable t) {
            return "读取失败：" + t;
        } finally {
            if (raf != null) try { raf.close(); } catch (Throwable ignored) {}
        }
    }

    // ------------------------------------------------------------ 内存

    static void renderMem() {
        sContent.addView(head("运行时内存"));
        try {
            Runtime r = Runtime.getRuntime();
            long total = r.totalMemory(), free = r.freeMemory(), max = r.maxMemory();
            LinearLayout c = card();
            c.addView(row("已分配   " + MiniPacker.human(total), C_TEXT, 11));
            c.addView(row("已使用   " + MiniPacker.human(total - free), C_TEXT, 11));
            c.addView(row("空闲     " + MiniPacker.human(free), C_TEXT, 11));
            c.addView(row("上限     " + MiniPacker.human(max), C_TEXT, 11));
            c.addView(row(String.format(Locale.US, "占用     %.1f%%",
                    max > 0 ? (total - free) * 100.0 / max : 0), C_ACCENT, 12));
            sContent.addView(c);

            sContent.addView(head("性能开关 · 默认全关"));
            sContent.addView(row("开着会拖慢整机，需要抓包时再打开", C_DIM, 9));
            sContent.addView(switchRow("响应 body", "body", XhsSpy.sLogBody));
            sContent.addView(switchRow("文件写入", "file", XhsSpy.sLogFile));
            sContent.addView(switchRow("网络地址", "net", XhsSpy.sLogNet));

            sContent.addView(chip("刷新", 0x12FFFFFF, 0x99FFFFFF, FloatingWindow::refresh));
        } catch (Throwable t) {
            sContent.addView(row("读不到：" + t, 0xFFFF6B6B, 11));
        }

    }

    /** 游戏：识别当前页面是哪个小游戏，勾选后唤起独立的 GamePanel */
    static void renderGame() {
        sContent.addView(head("游戏外挂"));
        sContent.addView(row("识别当前页面…", C_DIM, 10));

        new Thread(() -> {
            String info = null;
            try {
                info = XhsSpy.evalJs(
                        "(function(){try{return JSON.stringify({u:location.href,t:document.title});}"
                                + "catch(e){return '';}})()", 4000);
            } catch (Throwable ignored) {}
            final String res = info;
            UI.post(() -> {
                if (sContent == null || !"game".equals(sCur)) return;
                sContent.removeAllViews();
                buildGame(res);
            });
        }, "XhsSpyGame").start();
    }

    static void buildGame(String raw) {
        sContent.addView(head("游戏外挂"));
        if (raw == null || raw.isEmpty()) {
            sContent.addView(row("读不到页面\n先在 App 里打开一个小游戏", C_DIM, 11));
            sContent.addView(chip("重试", 0x12FFFFFF, 0x99FFFFFF, FloatingWindow::refresh));
            return;
        }

        String url = "", title = "";
        try {
            JSONObject o = new JSONObject(raw);
            url = o.optString("u", "");
            title = o.optString("t", "");
        } catch (Throwable ignored) {}

        String appid = "";
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("https?://([0-9a-zA-Z]+)\\.minitool\\.xiaohongshu\\.com")
                .matcher(url);
        if (m.find()) appid = m.group(1);

        if (appid.isEmpty()) {
            sContent.addView(row("当前不是小工具页面", C_DIM, 11));
            sContent.addView(row(title, C_SUB, 10));
            sContent.addView(row(url, C_DIM, 9));
            sContent.addView(chip("重试", 0x12FFFFFF, 0x99FFFFFF, FloatingWindow::refresh));
            return;
        }

        final String fid = appid;
        boolean known = "6ab94378dd88340015152901".equals(fid);
        LinearLayout c = card();
        TextView nm = bold(known ? "墨线突击" : title, C_TEXT, 13);
        c.addView(nm);
        c.addView(row(appid, C_DIM, 9));
        if (!title.isEmpty()) c.addView(row("页面标题 " + title, C_SUB, 10));
        c.addView(row(known ? "已适配 · 7 个功能" : "未适配 · 面板打开后需要手动注入",
                known ? 0xFF8FE0B0 : 0xFFE0C080, 10));

        LinearLayout acts = new LinearLayout(sCtx);
        acts.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        alp.topMargin = dp(7);
        acts.setLayoutParams(alp);

        if (GamePanel.isShown()) {
            acts.addView(chip("关闭外挂面板", 0x33FF6B85, 0xFFFFD6DD, () -> {
                GamePanel.hide();
                UI.postDelayed(FloatingWindow::refresh, 250);
            }));
        } else {
            acts.addView(chip("打开外挂面板", 0x3325E07A, 0xFFC8F2D8, () -> {
                GamePanel.show(sCtx, fid);
                UI.postDelayed(FloatingWindow::refresh, 400);
            }));
        }
        c.addView(acts);
        sContent.addView(c);

        sContent.addView(row("勾选后摇杆在左上角，可拖动、可 ± 缩放", C_DIM, 9));
        sContent.addView(chip("重新识别", 0x12FFFFFF, 0x99FFFFFF, FloatingWindow::refresh));
    }

    // ------------------------------------------------------------ 文档

    static void renderDoc() {
        sContent.addView(head("内置文档"));
        sContent.addView(row("离线可读 · 正文可长按选中", C_DIM, 9));

        for (int i = 0; i < DocText.INDEX.length; i++) {
            final String title = DocText.INDEX[i][0];
            final String key = DocText.INDEX[i][1];
            final String body = DocText.get(key);

            LinearLayout c = card();
            c.addView(bold(title, C_TEXT, 12));
            c.addView(row(summaryOf(body), C_DIM, 9));
            c.addView(row(body.length() + " 字 · 点开看全文", 0x66FFFFFF, 9));
            c.setOnClickListener(v -> showDoc(title, body));
            sContent.addView(c);
        }
        sContent.addView(row("正文在 docs/*.md，改完跑 gen_docs.py 重新生成", C_DIM, 9));
    }

    /** 从 md 里抽前几个二级标题当摘要 */
    static String summaryOf(String md) {
        StringBuilder sb = new StringBuilder();
        String[] ls = md.split("\n");
        for (String l : ls) {
            if (!l.startsWith("## ")) continue;
            String t = l.substring(3).trim();
            if (t.isEmpty()) continue;
            if (sb.length() > 0) sb.append(" · ");
            sb.append(t);
            if (sb.length() > 36) break;
        }
        return sb.length() == 0 ? "（无摘要）" : sb.toString();
    }

    static void showDoc(final String title, final String body) {
        if (sContent == null) return;
        sContent.removeAllViews();
        sContent.addView(head(title));

        LinearLayout acts = new LinearLayout(sCtx);
        acts.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        alp.bottomMargin = dp(6);
        acts.setLayoutParams(alp);
        acts.addView(chip("← 返回", 0x12FFFFFF, 0xCCFFFFFF, FloatingWindow::refresh));
        acts.addView(chip("复制全文", 0x2E22C55E, 0xFFC8F2D8, () -> {
            if (clip(title, body)) flash("已复制 " + body.length() + " 字");
        }));
        sContent.addView(acts);

        TextView tv = text(body, 0xC8FFFFFF, 9);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextIsSelectable(true);
        tv.setLineSpacing(dp(2), 1.12f);
        tv.setPadding(dp(8), dp(7), dp(8), dp(7));
        tv.setBackground(round(0x14FFFFFF, 9));
        sContent.addView(tv);
    }

    // ------------------------------------------------------------ 工具

    static void renderTools() {
        sContent.addView(head("页面工具"));

        // ① 当前页面速览
        LinearLayout c1 = card();
        c1.addView(bold("当前页面", C_TEXT, 12));
        final TextView info = text("读取中…", C_SUB, 9);
        info.setTypeface(Typeface.MONOSPACE);
        info.setTextIsSelectable(true);
        c1.addView(info);
        final LinearLayout acts1 = new LinearLayout(sCtx);
        acts1.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams a1lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        a1lp.topMargin = dp(6);
        acts1.setLayoutParams(a1lp);
        c1.addView(acts1);
        sContent.addView(c1);

        // ② 凭据嗅探
        LinearLayout c2 = card();
        c2.addView(bold("凭据嗅探", C_TEXT, 12));
        c2.addView(row("扫 window + localStorage + getApp().getCache，找 token / openid / sign", C_DIM, 9));
        final TextView cred = text("", 0xC8FFFFFF, 9);
        cred.setTypeface(Typeface.MONOSPACE);
        cred.setTextIsSelectable(true);
        c2.addView(cred);
        LinearLayout acts2 = new LinearLayout(sCtx);
        acts2.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams a2lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        a2lp.topMargin = dp(6);
        acts2.setLayoutParams(a2lp);
        acts2.addView(chip("开始扫描", 0x338B5CF6, 0xFFE0D4FF, () -> runCred(cred)));
        acts2.addView(chip("复制", 0x12FFFFFF, 0x99FFFFFF, () -> {
            String s = cred.getText().toString();
            if (s.isEmpty()) return;
            if (clip("凭据", s)) flash("凭据已复制");
        }));
        c2.addView(acts2);
        sContent.addView(c2);

        // ③ 存储导出
        LinearLayout c3 = card();
        c3.addView(bold("存储导出", C_TEXT, 12));
        c3.addView(row("把整个 localStorage 打成 JSON 存到 Download/XhsSpy/", C_DIM, 9));
        LinearLayout acts3 = new LinearLayout(sCtx);
        acts3.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams a3lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        a3lp.topMargin = dp(6);
        acts3.setLayoutParams(a3lp);
        final TextView st3 = text("", 0x8FE0B0, 9);
        acts3.addView(chip("导出", 0x2E22C55E, 0xFFC8F2D8, () -> exportStorage(st3)));
        c3.addView(acts3);
        c3.addView(st3);
        sContent.addView(c3);

        // ④ 执行 / 刷新
        LinearLayout c4 = card();
        c4.addView(bold("脚本", C_TEXT, 12));
        LinearLayout acts4 = new LinearLayout(sCtx);
        acts4.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams a4lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        a4lp.topMargin = dp(4);
        acts4.setLayoutParams(a4lp);
        acts4.addView(chip("执行 JS", 0x338B5CF6, 0xFFE0D4FF, FloatingWindow::promptJs));
        acts4.addView(chip("刷新页面", 0x2E22C55E, 0xFFC8F2D8, () -> {
            VarsPanel.evalJs("location.reload()");
            flash("已刷新");
        }));
        c4.addView(acts4);
        sContent.addView(c4);

        // ⑤ 神图君
        sContent.addView(head("神图君 · 接口测试"));
        sContent.addView(row("stapi.youpengw.com/minixhs/v1/", C_DIM, 9));

        LinearLayout c5 = card();
        c5.addView(bold("签名 / 探测", C_TEXT, 12));
        final EditText tok = new EditText(sCtx);
        tok.setTextColor(C_TEXT);
        tok.setHint("token（留空 = 匿名）");
        tok.setHintTextColor(0x4DFFFFFF);
        tok.setTextSize(10);
        tok.setSingleLine(true);
        tok.setBackground(glass(0x14FFFFFF, 8, 0x1FFFFFFF));
        tok.setPadding(dp(8), dp(6), dp(8), dp(6));
        tok.setOnFocusChangeListener((v, has) -> setImeMode(has));
        c5.addView(tok, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(38)));

        final TextView st5 = text("", 0xCCFFFFFF, 9);
        st5.setTypeface(Typeface.MONOSPACE);
        st5.setTextIsSelectable(true);
        LinearLayout acts5 = new LinearLayout(sCtx);
        acts5.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams a5lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        a5lp.topMargin = dp(6);
        acts5.setLayoutParams(a5lp);
        acts5.addView(chip("算签名", 0x1FFFFFFF, 0xFFE8D8FF,
                () -> stSignTo(tok.getText().toString().trim(), st5)));
        acts5.addView(chip("打接口", 0x338B5CF6, 0xFFE0D4FF,
                () -> probeSt(tok.getText().toString().trim(), st5)));
        c5.addView(acts5);
        c5.addView(st5);
        sContent.addView(c5);

        // 页面信息异步补上
        new Thread(() -> {
            JSONObject r = VarsPanel.evalJs(PAGE_INFO_JS);
            String txt;
            if (r.optBoolean("ok", false)) {
                txt = r.optString("now", "");
                if (txt.startsWith("\"") && txt.endsWith("\"") && txt.length() > 1) {
                    txt = txt.substring(1, txt.length() - 1).replace("\\n", "\n").replace("\\\"", "\"");
                }
            } else {
                txt = "读不到页面：" + r.optString("err", "没有 WebView");
            }
            final String out = txt;
            UI.post(() -> {
                if (sContent == null || !"tools".equals(sCur)) return;
                info.setText(out);
                acts1.addView(chip("复制", 0x12FFFFFF, 0x99FFFFFF, () -> {
                    if (clip("页面信息", out)) flash("已复制");
                }));
                acts1.addView(chip("重新读取", 0x12FFFFFF, 0x99FFFFFF,
                        FloatingWindow::refresh));
            });
        }, "XhsSpyTools").start();
    }

    static final String PAGE_INFO_JS =
            "(function(){var o=[];"
            + "try{o.push('url    '+location.href);}catch(e){o.push('url    读不到');}"
            + "try{o.push('title  '+document.title);}catch(e){}"
            + "try{o.push('storage '+localStorage.length+' 项');}catch(e){}"
            + "try{o.push('window '+Object.keys(window).length+' 个变量');}catch(e){}"
            + "try{o.push('ua     '+navigator.userAgent.slice(0,90));}catch(e){}"
            + "return o.join('\\n');})()";

    static final String CRED_JS =
            "(function(){var hits=[],seen=0;"
            + "function walk(o,p,d){if(!o||d>3||seen>2500)return;"
            + "for(var k in o){seen++;var v;try{v=o[k];}catch(e){continue;}"
            + "if(v===null||v===undefined)continue;var t=typeof v;"
            + "if((t==='string'||t==='number')&&/openid|token|unionid|session|sign|secret|uid|passwd|password/i.test(k)){"
            + "hits.push(p+'.'+k+' = '+String(v).slice(0,140));}"
            + "else if(t==='object'&&d<3){try{walk(v,p+'.'+k,d+1);}catch(e){}}}}"
            + "try{walk(window,'w',0);}catch(e){}"
            + "try{for(var i=0;i<localStorage.length;i++){var k=localStorage.key(i);"
            + "hits.push('[LS] '+k+' = '+String(localStorage.getItem(k)).slice(0,140));}}catch(e){}"
            + "try{var app=getApp();if(app){"
            + "if(app.getCache){var c=app.getCache('userInfo');"
            + "if(c)hits.push('[cache] userInfo = '+JSON.stringify(c).slice(0,400));}"
            + "if(app.globalData)hits.push('[global] '+JSON.stringify(app.globalData).slice(0,400));"
            + "}}catch(e){}"
            + "if(!hits.length)return '(没扫到凭据)';"
            + "return hits.slice(0,150).join('\\n');})()";

    static final String STORE_JS =
            "(function(){var o={};try{for(var i=0;i<localStorage.length;i++){var k=localStorage.key(i);"
            + "o[k]=localStorage.getItem(k);}}catch(e){return '';}"
            + "return JSON.stringify(o);})()";

    static void runCred(final TextView out) {
        out.setText("扫描中…");
        new Thread(() -> {
            JSONObject r = VarsPanel.evalJs(CRED_JS);
            String txt;
            if (r.optBoolean("ok", false)) {
                txt = r.optString("now", "");
                if (txt.startsWith("\"") && txt.endsWith("\"") && txt.length() > 1) {
                    txt = txt.substring(1, txt.length() - 1)
                            .replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\");
                }
            } else {
                txt = "执行失败：" + r.optString("err", "");
            }
            final String s = txt;
            UI.post(() -> {
                if (out == null) return;
                out.setText(s);
                out.setBackground(round(0x14FFFFFF, 8));
                out.setPadding(dp(7), dp(6), dp(7), dp(6));
            });
        }, "XhsSpyCred").start();
    }

    static void exportStorage(final TextView out) {
        out.setText("导出中…");
        new Thread(() -> {
            JSONObject r = VarsPanel.evalJs(STORE_JS);
            String raw = r.optBoolean("ok", false) ? r.optString("now", "") : "";
            if (raw.length() > 1 && raw.startsWith("\"") && raw.endsWith("\"")) {
                raw = raw.substring(1, raw.length() - 1)
                        .replace("\\\"", "\"").replace("\\\\", "\\");
            }
            final String content = raw;
            final String msg;
            if (content.isEmpty() || "null".equals(content)) {
                msg = "× 没读到 localStorage";
            } else {
                String name = "storage-" + System.currentTimeMillis() + ".json";
                boolean ok = saveText(name, content);
                msg = (ok ? "✓ 已存 " : "× 存失败 · 仅复制 ") + name
                        + "\n" + content.length() + " 字";
                if (ok) clip("storage", content);
            }
            UI.post(() -> out.setText(msg));
        }, "XhsSpyExport").start();
    }

    /** 存到 Download/XhsSpy/，Android 10 走 MediaStore */
    static boolean saveText(String name, String content) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                android.content.ContentResolver cr = sCtx.getContentResolver();
                android.content.ContentValues cv = new android.content.ContentValues();
                cv.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name);
                cv.put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/json");
                cv.put(android.provider.MediaStore.Downloads.RELATIVE_PATH,
                        android.os.Environment.DIRECTORY_DOWNLOADS + "/XhsSpy");
                cv.put(android.provider.MediaStore.Downloads.IS_PENDING, 1);
                android.net.Uri uri = cr.insert(
                        android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) return false;
                java.io.OutputStream os = cr.openOutputStream(uri);
                if (os == null) return false;
                os.write(content.getBytes("UTF-8"));
                os.close();
                cv.clear();
                cv.put(android.provider.MediaStore.Downloads.IS_PENDING, 0);
                cr.update(uri, cv, null, null);
                return true;
            }
            File dir = new File(android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS), "XhsSpy");
            if (!dir.exists()) dir.mkdirs();
            java.io.FileOutputStream fo = new java.io.FileOutputStream(new File(dir, name));
            fo.write(content.getBytes("UTF-8"));
            fo.close();
            return true;
        } catch (Throwable t) {
            log("saveText " + t);
            return false;
        }
    }

    static boolean clip(String label, String s) {
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    sCtx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) return false;
            cm.setPrimaryClip(android.content.ClipData.newPlainText(label, s));
            return true;
        } catch (Throwable t) {
            log("clip " + t);
            return false;
        }
    }

    /** 顶部闪一条提示，2.5 秒自己消失 */
    static void flash(String msg) {
        if (sContent == null) return;
        final TextView t = bold(msg, 0xFFC8F2D8, 10);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(8), dp(5), dp(8), dp(5));
        t.setBackground(round(0x2E22C55E, 8));
        sContent.addView(t, 0);
        UI.postDelayed(() -> {
            try {
                if (sContent != null) sContent.removeView(t);
            } catch (Throwable ignored) {}
        }, 2500);
    }

    // ------------------------------------------------------------ 神图君

    static final String ST_TABLE =
            "PXhw7UT1B0a9kQDKZsjIASmOezxYG4CHo5Jyfg2b8FLpEvRr3WtVnlqMidu6cN";
    static final String ST_BASE = "https://stapi.youpengw.com/minixhs/v1/";

    static String stSign(String token, long ts) {
        String e = token + "###" + ts;
        java.util.Random rnd = new java.util.Random();
        StringBuilder sb = new StringBuilder(e.length() * 3);
        for (int i = 0; i < e.length(); i++) {
            char c = e.charAt(i);
            sb.append(ST_TABLE.charAt(rnd.nextInt(62)));
            sb.append(ST_TABLE.charAt((c + 3) % 62));
            sb.append(ST_TABLE.charAt(rnd.nextInt(62)));
        }
        return sb.toString();
    }

    static void stSignTo(final String token, final TextView out) {
        long ts = Math.round(System.currentTimeMillis() / 1000.0);
        String sign = stSign(token, ts);
        out.setText("timestamp " + ts + "\nsign " + sign + "\n长度 " + sign.length());
    }

    static void probeSt(final String token, final TextView out) {
        out.setText("请求中…");
        new Thread(() -> {
            final String res = stGet("indexAuthorList", token);
            UI.post(() -> out.setText(res));
        }, "XhsSpySt").start();
    }

    /** 带签名 GET 一个接口，返回可读的短结果 */
    static String stGet(String method, String token) {
        HttpURLConnection c = null;
        try {
            long ts = Math.round(System.currentTimeMillis() / 1000.0);
            URL u = new URL(ST_BASE + method);
            c = (HttpURLConnection) u.openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            c.setRequestProperty("token", token);
            c.setRequestProperty("timestamp", String.valueOf(ts));
            c.setRequestProperty("sign", stSign(token, ts));
            c.setRequestProperty("User-Agent", "MicroMessenger/8.0.40");
            int code = c.getResponseCode();
            java.io.InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
            StringBuilder sb = new StringBuilder();
            if (is != null) {
                byte[] buf = new byte[8192];
                int r;
                while ((r = is.read(buf)) > 0) {
                    sb.append(new String(buf, 0, r, "UTF-8"));
                    if (sb.length() > 6000) break;
                }
                is.close();
            }
            String raw = sb.toString();
            int i = raw.lastIndexOf("{\"code\"");
            String body = i >= 0 ? raw.substring(i) : raw;
            if (body.length() > 700) body = body.substring(0, 700) + " …";
            return "HTTP " + code + "\n" + method + "\n\n" + body;
        } catch (Throwable t) {
            return "× " + t;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** 进程诊断：pid / 端口 / 各进程的 WebView，能不能跑 JS 一眼看出来 */
    static void renderProc() {
        sContent.addView(head("进程诊断"));
        sContent.addView(row("读取中…", C_DIM, 10));
        new Thread(() -> {
            final String txt;
            try { txt = XhsSpy.probeAll(); }
            catch (Throwable t) { UI.post(() -> { if ("proc".equals(sCur)) refresh(); }); return; }
            UI.post(() -> {
                if (sContent == null || !"proc".equals(sCur)) return;
                sContent.removeAllViews();
                sContent.addView(head("进程诊断"));
                TextView tv = text(txt, 0xC8FFFFFF, 9);
                tv.setTypeface(Typeface.MONOSPACE);
                tv.setTextIsSelectable(true);
                tv.setPadding(dp(7), dp(6), dp(7), dp(6));
                tv.setBackground(round(0x14FFFFFF, 8));
                sContent.addView(tv);
                sContent.addView(chip("刷新", 0x12FFFFFF, 0x99FFFFFF, FloatingWindow::refresh));

                // 自我了结：确认后直接把小红书进程干掉
                sContent.addView(head("危险操作"));
                sContent.addView(row("杀掉小红书全部进程，悬浮窗也会一起消失", C_DIM, 9));
                sContent.addView(chip("自我了结", 0x44FF2244, 0xFFFFD6DD,
                        FloatingWindow::confirmSuicide));
            });
        }, "XhsSpyProc").start();
    }

    /** 二次确认再杀，免得手滑 */
    static void confirmSuicide() {
        LinearLayout box = new LinearLayout(sCtx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(10), dp(10), dp(10), dp(10));
        box.setBackground(glass(0x18FFFFFF, 10, 0x30FF2244));

        box.addView(bold("确定杀掉小红书？", 0xFFFF8FA3, 13));
        TextView tip = row("主进程一死，所有子进程和这个悬浮窗都会跟着没。"
                + "\n下次打开小红书要重新等模块加载。", 0xAAFFFFFF, 10);
        tip.setPadding(0, dp(6), 0, dp(8));
        box.addView(tip);

        LinearLayout acts = new LinearLayout(sCtx);
        acts.setOrientation(LinearLayout.HORIZONTAL);
        acts.addView(chip("确定，杀", 0x55FF2244, 0xFFFFD6DD, FloatingWindow::suicide));
        acts.addView(chip("算了", 0x12FFFFFF, 0x99FFFFFF, () -> {
            if (sContent != null) refresh();
        }));
        box.addView(acts);

        sContent.addView(box, 0);
    }

    /** 真动手 */
    static void suicide() {
        try {
            XhsSpy.log("float", "自我了结: 杀进程 " + android.os.Process.myPid());
            MiniPacker.toast(sCtx, "三秒后关闭小红书");
        } catch (Throwable ignored) {}
        new Thread(() -> {
            try { Thread.sleep(600); } catch (Throwable ignored) {}
            try {
                android.app.Activity a = XhsSpy.sActivity;
                if (a != null) {
                    final android.app.Activity fa = a;
                    a.runOnUiThread(() -> {
                        try { fa.finishAffinity(); } catch (Throwable ignored) {}
                    });
                }
            } catch (Throwable ignored) {}
            try { Thread.sleep(500); } catch (Throwable ignored) {}
            try {
                android.os.Process.killProcess(android.os.Process.myPid());
            } catch (Throwable ignored) {}
            System.exit(0);
        }, "XhsSpySuicide").start();
    }

    static View switchRow(String label, final String key, boolean on) {
        LinearLayout rowv = new LinearLayout(sCtx);
        rowv.setOrientation(LinearLayout.HORIZONTAL);
        rowv.setGravity(Gravity.CENTER_VERTICAL);
        rowv.setPadding(dp(8), dp(7), dp(8), dp(7));
        rowv.setBackground(glass(on ? 0x1FFF6B85 : 0x10FFFFFF, 9, 0x18FFFFFF));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(5);
        rowv.setLayoutParams(lp);

        TextView t = text(label, on ? 0xFFFFD6DD : 0xAAFFFFFF, 11);
        rowv.addView(t, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView v = bold(on ? "开" : "关", on ? 0xFFFF8FA3 : 0x77FFFFFF, 11);
        rowv.addView(v);

        rowv.setOnClickListener(x -> {
            boolean now = !on;
            XhsSpy.setSwitch(key, now ? "1" : "0");
            refresh();
        });
        return rowv;
    }

    // ------------------------------------------------------------ 跳网页

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
}
