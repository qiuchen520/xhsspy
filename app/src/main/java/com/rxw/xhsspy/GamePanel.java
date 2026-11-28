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
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * GamePanel —— 单个小游戏的外挂面板
 *
 * 收起是一条胶囊「墨线突击」，点开从胶囊里长成两栏，
 * 再点一下缩回去。手感跟主悬浮窗一致，只是内容换成外挂。
 *
 *   ┌────────────────┐
 *   │ ● 墨线突击   ⌄ │  ← 拖这里
 *   ├──────┬─────────┤
 *   │进行时│ 无敌 [关]│
 *   │ 榜单 │ 无限钱[关]│
 *   │ 货币 │ ...     │
 *   └──────┴─────────┘
 */
public class GamePanel {

    private static Context sCtx;
    private static WindowManager sWm;
    private static LinearLayout sRoot;
    private static LinearLayout sChip;      // 收起态胶囊
    private static FrameLayout sPanel;      // 展开态面板
    private static LinearLayout sNav;
    private static LinearLayout sContent;
    private static WindowManager.LayoutParams sLp;
    private static boolean sShown = false;
    private static boolean sOpen = false;
    private static String sGame = "";
    private static String sTab = "play";
    private static final Handler UI = new Handler(Looper.getMainLooper());

    private static final Map<String, Boolean> sOn = new HashMap<>();
    private static int sSpeedMul = 1;
    private static TextView sStatus;

    static final int CAP_H = 40;
    static final int CAP_W = 128;
    static final int PANEL_W = 292;
    static final int PANEL_H = 392;

    static final int C_TEXT = 0xFFF1F3F7;
    static final int C_SUB = 0x8AFFFFFF;
    static final int C_DIM = 0x5CFFFFFF;
    static final int C_ACCENT = 0xFFFF6B85;
    static final int C_BG = 0xF00E1017;
    static final int C_STROKE = 0x2EFFFFFF;

    static final String GAME_INKSTRIKE = "6ab94378dd88340015152901";

    /* 左边三个页签 */
    static final String[] TAB_KEYS = {"play", "rank", "money"};
    static final String[] TAB_TEXT = {"游戏进行时", "榜单数值", "货币等修改"};

    /* 游戏进行时：id / 名称 / 类型（sw 开关、btn 按钮） */
    static final String[][] PLAY = {
            {"god",     "无敌",      "sw"},
            {"money",   "无限钱",    "sw"},
            {"ammo",    "无限弹药",  "sw"},
            {"esp",     "透视 ESP",  "sw"},
            {"freeze",  "定身",      "sw"},
            {"autohit", "自动命中",  "sw"},
            {"oneshot", "秒杀",      "sw"},
            {"headshot","一枪爆头",  "sw"},
            {"aimbot",  "自动瞄准",  "sw"},
            {"nospread","无后坐力",  "sw"},
            {"nades",   "无限手雷",  "sw"},
            {"superjump","超级跳",   "sw"},
            {"tp",      "瞬移",      "sw"},
            {"noclip",  "穿墙",      "sw"},
            {"slowmo",  "子弹时间",  "sw"},
            {"fly",     "飞行",      "sw"},
            {"madness", "敌人互殴",  "sw"},
            {"shop",    "强开商店",  "sw"},
            {"speed",   "移速",      "btn"},
            {"all",     "一键全开",  "btn"},
    };

    // ------------------------------------------------------------ 入口

    public static void show(final Context ctx, final String appid) {
        if (ctx == null) return;
        Context app = null;
        try { app = ctx.getApplicationContext(); } catch (Throwable ignored) {}
        sCtx = (app != null) ? app : ctx;
        sGame = (appid == null || appid.isEmpty()) ? GAME_INKSTRIKE : appid;

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
            sRoot = null; sChip = null; sPanel = null;
            sShown = false; sOpen = false;
        });
    }

    public static boolean isShown() { return sShown; }

    // ------------------------------------------------------------ 基础

    static float dpi() { return sCtx.getResources().getDisplayMetrics().density; }
    static int dp(float v) { return (int) (v * dpi() + 0.5f); }
    static int screenW() { return sCtx.getResources().getDisplayMetrics().widthPixels; }
    static int screenH() { return sCtx.getResources().getDisplayMetrics().heightPixels; }

    static GradientDrawable glass(int color, int radiusDp, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        g.setStroke(Math.max(1, dp(1)), stroke);
        return g;
    }

    static TextView txt(String s, int color, int sizeSp) {
        TextView t = new TextView(sCtx);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(sizeSp);
        return t;
    }

    static TextView bold(String s, int color, int sizeSp) {
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
        sRoot.setClipChildren(false);
        sRoot.setClipToPadding(false);

        sChip = makeChip();
        sRoot.addView(sChip, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(CAP_H)));

        sPanel = makePanel();
        sRoot.addView(sPanel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        sPanel.setVisibility(View.GONE);

        sLp = new WindowManager.LayoutParams(
                dp(CAP_W), dp(CAP_H), type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSLUCENT);
        sLp.gravity = Gravity.TOP | Gravity.START;
        sLp.x = dp(8);
        sLp.y = dp(110);

        try {
            sWm.addView(sRoot, sLp);
            sShown = true;
            XhsSpy.log("float", "GamePanel 打开: " + sGame);
        } catch (Throwable t) {
            XhsSpy.log("float", "GamePanel addView 失败: " + t);
            sShown = false;
        }
    }

    static void applySize(boolean expanded) {
        if (sWm == null || sRoot == null || sLp == null) return;
        try {
            if (expanded) {
                int w = Math.min(dp(PANEL_W), screenW() - dp(10));
                int h = Math.min(dp(PANEL_H), screenH() - dp(10));
                sLp.width = w;
                sLp.height = h;
            } else {
                sLp.width = dp(CAP_W);
                sLp.height = dp(CAP_H);
            }
            sWm.updateViewLayout(sRoot, sLp);
        } catch (Throwable ignored) {}
    }

    // ------------------------------------------------------------ 胶囊

    static LinearLayout makeChip() {
        final LinearLayout cap = new LinearLayout(sCtx);
        cap.setOrientation(LinearLayout.HORIZONTAL);
        cap.setGravity(Gravity.CENTER_VERTICAL);
        cap.setPadding(dp(7), dp(5), dp(12), dp(5));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xF2181B24, 0xF00E1017});
        bg.setCornerRadius(dp(CAP_H / 2f));
        bg.setStroke(dp(1), C_STROKE);
        cap.setBackground(bg);

        TextView dot = bold("●", 0xFFFF6B85, 11);
        dot.setShadowLayer(dp(4), 0, 0, 0xFFFF3355);
        cap.addView(dot, new LinearLayout.LayoutParams(dp(18),
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView name = bold(gameName(), C_TEXT, 12);
        name.setPadding(dp(2), 0, dp(4), 0);
        cap.addView(name);

        TextView arr = txt("▾", 0x77FFFFFF, 10);
        cap.addView(arr, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        cap.setOnTouchListener(new View.OnTouchListener() {
            int bx, by; float rx, ry; boolean moved; long at;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        bx = sLp.x; by = sLp.y;
                        rx = e.getRawX(); ry = e.getRawY();
                        moved = false; at = System.currentTimeMillis();
                        dot.animate().scaleX(0.8f).scaleY(0.8f).setDuration(80).start();
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        int dx = (int) (e.getRawX() - rx);
                        int dy = (int) (e.getRawY() - ry);
                        if (!moved && (Math.abs(dx) > dp(8) || Math.abs(dy) > dp(8))) moved = true;
                        if (!moved) return true;
                        sLp.x = bx + dx; sLp.y = by + dy;
                        try { sWm.updateViewLayout(sRoot, sLp); } catch (Throwable ignored) {}
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL: {
                        dot.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                        if (moved) { if (sOpen) applySize(true); return true; }
                        if (System.currentTimeMillis() - at > 700) { hide(); return true; }
                        toggle();
                        return true;
                    }
                }
                return true;
            }
        });
        return cap;
    }

    static void toggle() { if (sOpen) closePanel(); else openPanel(); }

    static void openPanel() {
        if (sPanel == null || sOpen) return;
        sOpen = true;
        sPanel.animate().cancel();
        applySize(true);
        sPanel.setVisibility(View.VISIBLE);
        sPanel.setPivotX(dp(24));
        sPanel.setPivotY(-dp(CAP_H + 4));
        sPanel.setScaleX(0.34f); sPanel.setScaleY(0.34f); sPanel.setAlpha(0f);
        sPanel.animate().scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(260)
                .setInterpolator(new OvershootInterpolator(0.85f)).start();
        if (sChip != null) sChip.animate().alpha(0.62f).setDuration(200).start();
        buildNav();
        refresh();
    }

    static void closePanel() {
        if (sPanel == null || !sOpen) return;
        sOpen = false;
        sPanel.animate().cancel();
        setIme(false);
        if (sChip != null) sChip.animate().alpha(1f).setDuration(160).start();
        sPanel.animate().scaleX(0.34f).scaleY(0.34f).alpha(0f)
                .setDuration(200)
                .setInterpolator(new DecelerateInterpolator(1.6f))
                .withEndAction(() -> {
                    if (sPanel == null || sOpen) return;
                    sPanel.setVisibility(View.GONE);
                    applySize(false);
                }).start();
    }

    // ------------------------------------------------------------ 面板骨架

    static FrameLayout makePanel() {
        FrameLayout wrap = new FrameLayout(sCtx);
        wrap.setBackground(glass(C_BG, 18, C_STROKE));
        wrap.setPadding(dp(8), dp(6), dp(8), dp(8));

        LinearLayout body = new LinearLayout(sCtx);
        body.setOrientation(LinearLayout.HORIZONTAL);
        wrap.addView(body, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout left = new LinearLayout(sCtx);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setBackground(glass(0x0AFFFFFF, 14, 0x18FFFFFF));
        left.setPadding(dp(4), dp(6), dp(4), dp(6));
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(dp(78),
                LinearLayout.LayoutParams.MATCH_PARENT);
        llp.rightMargin = dp(7);
        body.addView(left, llp);

        sNav = new LinearLayout(sCtx);
        sNav.setOrientation(LinearLayout.VERTICAL);
        left.addView(sNav, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        FrameLayout right = new FrameLayout(sCtx);
        right.setBackground(glass(0x0AFFFFFF, 14, 0x18FFFFFF));
        body.addView(right, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        ScrollView sc = new ScrollView(sCtx);
        sc.setVerticalScrollBarEnabled(false);
        sc.setPadding(dp(9), dp(8), dp(9), dp(8));
        sc.setClipToPadding(false);
        right.addView(sc, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        sContent = new LinearLayout(sCtx);
        sContent.setOrientation(LinearLayout.VERTICAL);
        sc.addView(sContent);

        return wrap;
    }

    static void buildNav() {
        if (sNav == null) return;
        sNav.removeAllViews();
        for (int i = 0; i < TAB_KEYS.length; i++) {
            final String key = TAB_KEYS[i];
            boolean on = key.equals(sTab);
            TextView t = bold(TAB_TEXT[i], on ? 0xFFFFFFFF : 0x8AFFFFFF, 11);
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(2), dp(10), dp(2), dp(10));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(3);
            if (on) {
                GradientDrawable g = new GradientDrawable();
                g.setColor(0x2EFFFFFF);
                g.setCornerRadius(dp(11));
                g.setStroke(dp(1), 0x33FFFFFF);
                t.setBackground(g);
            } else {
                GradientDrawable g = new GradientDrawable();
                g.setColor(0x00000000);
                g.setCornerRadius(dp(11));
                t.setBackground(g);
            }
            t.setOnClickListener(v -> { sTab = key; buildNav(); refresh(); });
            sNav.addView(t, lp);
        }
    }

    static void refresh() {
        if (sContent == null) return;
        sContent.removeAllViews();
        try {
            if ("play".equals(sTab)) renderPlay();
            else if ("rank".equals(sTab)) renderRank();
            else renderMoney();
        } catch (Throwable t) {
            sContent.addView(txt("出错：" + t, 0xFFFF6B6B, 11));
        }
    }

    static TextView head(String s) {
        TextView t = bold(s, C_ACCENT, 11);
        t.setPadding(0, dp(2), 0, dp(6));
        return t;
    }

    static TextView row(String s, int color, int size) {
        TextView t = txt(s, color, size);
        t.setPadding(0, dp(3), 0, dp(3));
        return t;
    }

    // ------------------------------------------------------------ 游戏进行时

    static void renderPlay() {
        sContent.addView(head("游戏进行时"));
        sStatus = row("就绪", C_DIM, 9);
        sContent.addView(sStatus);

        for (final String[] item : PLAY) {
            final String id = item[0];
            final String type = item[2];

            LinearLayout r = new LinearLayout(sCtx);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(dp(9), dp(9), dp(9), dp(9));
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            rlp.bottomMargin = dp(5);
            r.setLayoutParams(rlp);

            boolean on = "speed".equals(id) ? (sSpeedMul > 1) : Boolean.TRUE.equals(sOn.get(id));
            r.setBackground(glass(on ? 0x22FF6B85 : 0x10FFFFFF, 10,
                    on ? 0x44FF8FA3 : 0x1AFFFFFF));

            TextView nm = txt(item[1], on ? 0xFFFFD6DD : 0xFFD8DCE4, 11);
            r.addView(nm, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            // 开关：椭圆轨道 + 圆点
            String label = "speed".equals(id) ? (sSpeedMul + "x") : (on ? "开" : "关");
            TextView sw = bold(label, on ? 0xFFFFFFFF : 0x99FFFFFF, 9);
            sw.setGravity(Gravity.CENTER);
            sw.setPadding(dp(10), dp(3), dp(10), dp(3));
            sw.setBackground(glass(on ? 0xFFFF3355 : 0x22FFFFFF, 11,
                    on ? 0x66FF8FA3 : 0x22FFFFFF));
            r.addView(sw);

            if ("btn".equals(type)) {
                r.setOnClickListener(v -> {
                    if ("speed".equals(id)) cycleSpeed();
                    else { toggle("all"); }
                });
            } else {
                r.setOnClickListener(v -> toggle(id));
            }
            sContent.addView(r);
        }
    }

    static void toggle(final String id) {
        final boolean target = !Boolean.TRUE.equals(sOn.get(id));
        sOn.put(id, target);
        refresh();
        run("window.__XHS_HACKS && window.__XHS_HACKS." + id + "." + (target ? "on" : "off") + "()");
    }

    static void cycleSpeed() {
        int[] arr = {1, 2, 3, 5, 8, 12};
        int i = 0;
        for (int k = 0; k < arr.length; k++) if (arr[k] == sSpeedMul) { i = k; break; }
        sSpeedMul = arr[(i + 1) % arr.length];
        final int m = sSpeedMul;
        refresh();
        run("window.__XHS_HACKS && window.__XHS_HACKS.speed.set(" + m + ")");
    }

    // ------------------------------------------------------------ 榜单

    static void renderRank() {
        sContent.addView(head("榜单数值"));
        sContent.addView(row("先拉榜看清楚量级，再提交。分数只进不退。", C_DIM, 9));

        final EditText score = input("分数，例如 5200");
        sContent.addView(score, lp(7));

        sContent.addView(row("榜单", C_SUB, 10));
        LinearLayout grids = new LinearLayout(sCtx);
        grids.setOrientation(LinearLayout.VERTICAL);
        String[][] boards = {{"1", "靶场"}, {"2", "死斗"}, {"3", "撤离"}, {"4", "KD"}, {"5", "等级"}};
        for (final String[] b : boards) {
            LinearLayout line = new LinearLayout(sCtx);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.addView(row(b[1], C_TEXT, 10), new LinearLayout.LayoutParams(
                    dp(42), LinearLayout.LayoutParams.WRAP_CONTENT));
            line.addView(chip("日", () -> submit(b[0], "day", score)));
            line.addView(chip("周", () -> submit(b[0], "week", score)));
            line.addView(chip("月", () -> submit(b[0], "month", score)));
            line.addView(chip("总", () -> submit(b[0], "all", score)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(4);
            line.setLayoutParams(lp);
            grids.addView(line);
        }
        sContent.addView(grids);

        sContent.addView(chip("查看我的排名", () -> run(
                "window.__XHS_HACKS.rank.my(1,'all'); " )));
        sContent.addView(chip("读取结果", () -> pull("window.__XHS_HACKS.rank.me()")));
    }

    static void submit(String board, String period, EditText scoreView) {
        String s = scoreView.getText().toString().trim();
        if (s.isEmpty()) { setStatus("先填分数"); return; }
        run("window.__XHS_HACKS.rank.submit(" + board + ",'" + period + "'," + s + ")");
        setStatus("已提交 b" + board + " " + period + " = " + s);
    }

    // ------------------------------------------------------------ 货币

    static void renderMoney() {
        sContent.addView(head("货币等修改"));
        sContent.addView(row("写进存档后需要刷新页面才生效", C_DIM, 9));

        final EditText xp = input("经验 xp");
        final EditText bank = input("银行墨印币 raid.bank");
        sContent.addView(xp, lp(6));
        sContent.addView(bank, lp(6));

        sContent.addView(chip("写入存档", () -> {
            JSONObject p = new JSONObject();
            try {
                if (!xp.getText().toString().trim().isEmpty())
                    p.put("xp", Long.parseLong(xp.getText().toString().trim()));
                if (!bank.getText().toString().trim().isEmpty())
                    p.put("raid.bank", Long.parseLong(bank.getText().toString().trim()));
            } catch (Throwable t) {
                setStatus("数字不对");
                return;
            }
            run("window.__XHS_HACKS.save.apply(" + p.toString() + ")");
            setStatus("已写入，点下面刷新");
        }));

        sContent.addView(head("工坊"));
        final EditText pts = input("墨印币 gacha.points");
        final EditText ess = input("精华 gacha.essence");
        sContent.addView(pts, lp(6));
        sContent.addView(ess, lp(6));
        sContent.addView(chip("写入工坊", () -> {
            String a = pts.getText().toString().trim();
            String b = ess.getText().toString().trim();
            run("window.__XHS_HACKS.save.workshop("
                    + (a.isEmpty() ? "null" : a) + ","
                    + (b.isEmpty() ? "null" : b) + ")");
            setStatus("工坊已写入");
        }));

        sContent.addView(chip("刷新页面", () -> run("window.__XHS_HACKS.save.reload()")));
    }

    // ------------------------------------------------------------ 控件

    static EditText input(String hint) {
        EditText et = new EditText(sCtx);
        et.setHint(hint);
        et.setHintTextColor(0x4DFFFFFF);
        et.setTextColor(C_TEXT);
        et.setTextSize(10);
        et.setSingleLine(true);
        et.setBackground(glass(0x14FFFFFF, 9, 0x1AFFFFFF));
        et.setPadding(dp(9), dp(6), dp(9), dp(6));
        et.setOnFocusChangeListener((v, has) -> setIme(has));
        return et;
    }

    static LinearLayout.LayoutParams lp(int bottomDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        p.bottomMargin = dp(bottomDp);
        return p;
    }

    static TextView chip(String label, final Runnable act) {
        TextView t = bold(label, 0xFFE8EAF0, 9);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(9), dp(5), dp(9), dp(5));
        t.setBackground(glass(0x1AFFFFFF, 8, 0x22FFFFFF));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        p.rightMargin = dp(4);
        p.bottomMargin = dp(4);
        t.setLayoutParams(p);
        t.setOnClickListener(v -> { try { act.run(); } catch (Throwable ignored) {} });
        return t;
    }

    static void setStatus(String s) {
        if (sStatus != null) sStatus.setText(s);
    }

    // ------------------------------------------------------------ JS 桥

    static void run(final String js) {
        setStatus("执行中…");
        new Thread(() -> {
            String r;
            try { r = XhsSpy.evalJs(js, 4000); }
            catch (Throwable t) { r = "失败"; }
            final String msg = String.valueOf(r);
            UI.post(() -> setStatus(msg.length() > 70 ? msg.substring(0, 70) : msg));
        }, "XhsSpyHack").start();
    }

    static void pull(final String js) {
        new Thread(() -> {
            String r;
            try { r = XhsSpy.evalJs(js, 4000); }
            catch (Throwable t) { r = "失败"; }
            final String msg = String.valueOf(r);
            UI.post(() -> setStatus(msg.length() > 90 ? msg.substring(0, 90) : msg));
        }, "XhsSpyPull").start();
    }

    static void setIme(boolean on) {
        if (sLp == null || sWm == null || sRoot == null) return;
        int base = WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        if (!on) base |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        if (base == sLp.flags) return;
        sLp.flags = base;
        try { sWm.updateViewLayout(sRoot, sLp); } catch (Throwable ignored) {}
    }

    // ------------------------------------------------------------ 注入

    static void inject() {
        new Thread(() -> {
            String r = XhsSpy.evalJs(HackScripts.INKSTRIKE, 8000);
            try { XhsSpy.evalJs("window.__XHS_HACKS && window.__XHS_HACKS.warmup()", 3000); } catch (Throwable ignored) {}
            final String msg = (r == null) ? "页面没响应" : r;
            XhsSpy.log("float", "注入结果: " + msg);
            UI.post(() -> setStatus("ready".equals(msg) || "already".equals(msg) ? "已就绪" : ("注入 " + msg)));
        }, "XhsSpyInject").start();
    }

    static String gameName() {
        if (GAME_INKSTRIKE.equals(sGame)) return "墨线突击";
        return "游戏 " + (sGame.length() > 8 ? sGame.substring(0, 8) : sGame);
    }
}
