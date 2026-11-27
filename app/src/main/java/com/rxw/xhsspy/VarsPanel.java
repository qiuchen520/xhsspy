package com.rxw.xhsspy;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Locale;
import java.util.Map;

/**
 * VarsPanel — 运行时数值面板
 *
 * 小工具是纯 H5，它所有「正在运行的值」都在两个地方：
 *   window 上的全局变量    和    localStorage
 *
 * 这个面板把两处都列出来，可以直接改：
 *   /vars                页面
 *   /vars/api/list       全部变量（JSON）
 *   /vars/api/set        改一个 window 变量或 localStorage 项
 *   /vars/api/del        删一个 localStorage 项
 *   /vars/api/eval       直接执行一段 JS（给 AI 用的口子）
 *   /vars/api/mem        进程内存
 *
 * MCP 侧对应：js_vars / js_set / js_eval / storage_set / process_mem
 */
public class VarsPanel {

    // ------------------------------------------------------------ 内存

    public static JSONObject mem() {
        JSONObject o = new JSONObject();
        try {
            Runtime r = Runtime.getRuntime();
            long total = r.totalMemory();
            long free = r.freeMemory();
            long max = r.maxMemory();
            o.put("已分配", human(total));
            o.put("已使用", human(total - free));
            o.put("空闲", human(free));
            o.put("上限", human(max));
            o.put("已用百分比", String.format(Locale.US, "%.1f%%",
                    max > 0 ? (total - free) * 100.0 / max : 0));
            o.put("totalBytes", total);
            o.put("freeBytes", free);
            o.put("maxBytes", max);
        } catch (Throwable t) {
            try { o.put("error", String.valueOf(t)); } catch (Exception ignored) {}
        }
        return o;
    }

    public static String human(long n) {
        if (n < 1024) return n + " B";
        if (n < 1048576) return String.format(Locale.US, "%.1f KB", n / 1024.0);
        if (n < 1073741824L) return String.format(Locale.US, "%.1f MB", n / 1048576.0);
        return String.format(Locale.US, "%.2f GB", n / 1073741824.0);
    }

    // ------------------------------------------------------------ 读取

    public static JSONObject snapshot() {
        String raw = XhsSpy.probeVars();
        try {
            JSONObject o = new JSONObject(raw);
            o.put("mem", mem());
            return o;
        } catch (Exception e) {
            JSONObject o = new JSONObject();
            try {
                o.put("error", "页面没返回可用数据：" + e);
                o.put("raw", raw == null ? "" : raw.substring(0, Math.min(raw.length(), 400)));
                o.put("mem", mem());
            } catch (Exception ignored) {}
            return o;
        }
    }

    // ------------------------------------------------------------ 修改

    /** scope=window 时改 window 变量；scope=storage 时改 localStorage */
    public static JSONObject set(String scope, String key, String value, String type) {
        JSONObject out = new JSONObject();
        try {
            if (key == null || key.isEmpty()) {
                out.put("ok", false);
                out.put("err", "缺少变量名");
                return out;
            }
            String k = JSONObject.quote(key);

            if ("storage".equals(scope)) {
                String v = JSONObject.quote(value == null ? "" : value);
                String js = "(function(){try{localStorage.setItem(" + k + "," + v
                        + ");var r=localStorage.getItem(" + k + ");"
                        + "return JSON.stringify({ok:true,now:r});}catch(e){"
                        + "return JSON.stringify({ok:false,err:String(e)});}})()";
                return runJs(js);
            }

            String lit;
            String t = type == null ? "auto" : type;
            if ("number".equals(t)) {
                lit = value;
            } else if ("boolean".equals(t)) {
                lit = ("true".equalsIgnoreCase(value) || "1".equals(value)) ? "true" : "false";
            } else if ("string".equals(t)) {
                lit = JSONObject.quote(value == null ? "" : value);
            } else {
                String trimmed = value == null ? "" : value.trim();
                if (trimmed.matches("-?\\d+(\\.\\d+)?")) lit = trimmed;
                else if ("true".equals(trimmed) || "false".equals(trimmed)) lit = trimmed;
                else if ("null".equals(trimmed) || "undefined".equals(trimmed)) lit = trimmed;
                else lit = JSONObject.quote(value == null ? "" : value);
            }

            String js = "(function(){try{window[" + k + "]=" + lit + ";"
                    + "var v=window[" + k + "];"
                    + "var s;try{s=String(v);if(s.length>300)s=s.slice(0,300)+'…';}catch(e){s='(不可读)';}"
                    + "return JSON.stringify({ok:true,now:s,type:typeof v});}catch(e){"
                    + "return JSON.stringify({ok:false,err:String(e)});}})()";
            return runJs(js);
        } catch (Exception e) {
            try {
                out.put("ok", false);
                out.put("err", String.valueOf(e));
            } catch (Exception ignored) {}
            return out;
        }
    }

    public static JSONObject del(String key) {
        String js = "(function(){try{localStorage.removeItem("
                + JSONObject.quote(key == null ? "" : key)
                + ");return JSON.stringify({ok:true});}catch(e){"
                + "return JSON.stringify({ok:false,err:String(e)});}})()";
        return runJs(js);
    }

    public static JSONObject evalJs(String code) {
        if (code == null || code.trim().isEmpty()) {
            JSONObject o = new JSONObject();
            try {
                o.put("ok", false);
                o.put("err", "代码为空");
            } catch (Exception ignored) {}
            return o;
        }
        return runJs(code);
    }

    static JSONObject runJs(String js) {
        JSONObject out = new JSONObject();
        try {
            String r = XhsSpy.evalJs(js, 6000);
            if (r == null) {
                out.put("ok", false);
                out.put("err", "页面无响应（可能没有 WebView，或者页面还没加载完）");
                return out;
            }
            try {
                JSONObject o = new JSONObject(r);
                o.put("_raw", r);
                return o;
            } catch (Exception e) {
                out.put("ok", true);
                out.put("now", r);
                out.put("_raw", r);
                return out;
            }
        } catch (Exception e) {
            try {
                out.put("ok", false);
                out.put("err", String.valueOf(e));
            } catch (Exception ignored) {}
            return out;
        }
    }

    // ------------------------------------------------------------ 页面

    public static void page(OutputStream out) throws IOException {
        JSONObject d = snapshot();
        JSONObject memO = d.optJSONObject("mem");
        String title = d.optString("title", "");
        String url = d.optString("url", "");
        JSONArray vars = d.optJSONArray("vars");
        JSONArray ls = d.optJSONArray("storage");

        StringBuilder b = new StringBuilder();
        b.append("<div class=row style=\"margin-bottom:12px\">")
         .append("<a class=chip href=\"/vars\">刷新</a>")
         .append("<a class=chip href=\"/mini\">小程序列表</a>")
         .append("<a class=chip href=\"/tool\">小工具</a>")
         .append("</div>");

        if (d.has("error")) {
            b.append("<p class=hint>").append(MiniView.esc(d.optString("error"))).append("</p>");
        }

        b.append("<h2>当前页面</h2>");
        b.append("<div class=listitem><div class=li-d style=\"margin:0\">")
         .append("标题：<b>").append(MiniView.esc(title)).append("</b><br>")
         .append("地址：").append(MiniView.esc(url))
         .append("</div></div>");

        b.append("<h2>进程内存</h2>");
        if (memO != null) {
            b.append("<div class=listitem><div class=li-d style=\"margin:0\">");
            b.append("已分配 ").append(MiniView.esc(memO.optString("已分配")))
             .append(" · 已用 ").append(MiniView.esc(memO.optString("已使用")))
             .append(" · 空闲 ").append(MiniView.esc(memO.optString("空闲")))
             .append("<br>上限 ").append(MiniView.esc(memO.optString("上限")))
             .append(" · 占用 ").append(MiniView.esc(memO.optString("已用百分比")));
            b.append("</div></div>");
        }

        // window 变量
        b.append("<h2>页面变量 · ")
         .append(vars == null ? 0 : vars.length()).append("</h2>");
        if (vars == null || vars.length() == 0) {
            b.append("<p class=hint>没有读到变量。先在小红书里打开小工具或页面。</p>");
        } else {
            b.append("<table><tr><th>变量名</th><th>类型</th><th>当前值</th><th></th></tr>");
            for (int i = 0; i < vars.length(); i++) {
                JSONObject v = vars.optJSONObject(i);
                if (v == null) continue;
                String k = v.optString("k");
                String t = v.optString("t");
                String val = v.optString("v");
                boolean writable = v.optBoolean("w", false);
                b.append("<tr><td><code>").append(MiniView.esc(k)).append("</code></td>")
                 .append("<td class=dim>").append(MiniView.esc(t)).append("</td>")
                 .append("<td class=dim style=\"max-width:180px;overflow:hidden;text-overflow:ellipsis\">")
                 .append(MiniView.esc(val)).append("</td><td>");
                if (writable) {
                    b.append("<a class=chip href=\"#\" onclick=\"editVar('")
                     .append(MiniView.esc(k).replace("'", "&#39;")).append("','")
                     .append(MiniView.esc(val).replace("'", "&#39;").replace("\n", " "))
                     .append("');return false;\">改</a>");
                } else {
                    b.append("<span class=dim>只读</span>");
                }
                b.append("</td></tr>");
            }
            b.append("</table>");
        }

        // localStorage
        b.append("<h2>本地存储 · ")
         .append(ls == null ? 0 : ls.length()).append("</h2>");
        if (ls == null || ls.length() == 0) {
            b.append("<p class=hint>localStorage 是空的。</p>");
        } else {
            b.append("<table><tr><th>键</th><th>值</th><th></th></tr>");
            for (int i = 0; i < ls.length(); i++) {
                JSONObject v = ls.optJSONObject(i);
                if (v == null) continue;
                String k = v.optString("k");
                String val = v.optString("v");
                b.append("<tr><td><code>").append(MiniView.esc(k)).append("</code></td>")
                 .append("<td class=dim style=\"max-width:200px;overflow:hidden;")
                 .append("text-overflow:ellipsis\">")
                 .append(MiniView.esc(val)).append("</td><td>");
                b.append("<a class=chip href=\"#\" onclick=\"editLs('")
                 .append(MiniView.esc(k).replace("'", "&#39;")).append("','")
                 .append(MiniView.esc(val).replace("'", "&#39;").replace("\n", " "))
                 .append("');return false;\">改</a> ");
                b.append("<a class=chip href=\"#\" onclick=\"delLs('")
                 .append(MiniView.esc(k).replace("'", "&#39;"))
                 .append("');return false;\">删</a>");
                b.append("</td></tr>");
            }
            b.append("</table>");
        }

        // 任意 JS
        b.append("<h2>执行 JavaScript</h2>");
        b.append("<div class=listitem>")
         .append("<textarea id=codeBox placeholder=\"return 表达式 或 直接写语句\" ")
         .append("style=\"width:100%;height:90px;padding:10px;border-radius:10px;")
         .append("border:1px solid #ddd;font:12px ui-monospace,Menlo,monospace\"></textarea>")
         .append("<div class=li-a style=\"margin-top:10px\">")
         .append("<a class=chip href=\"#\" onclick=\"runCode();return false;\">执行</a>")
         .append("</div></div>");
        b.append("<pre class=log id=jsOut style=\"display:none\"></pre>");

        b.append(SCRIPT);
        MiniView.send(out, MiniView.page("运行时数值面板", b.toString()));
    }

    static final String SCRIPT = """
<script>
function post(url, data){
  var body = Object.keys(data).map(function(k){
    return encodeURIComponent(k)+'='+encodeURIComponent(data[k]);
  }).join('&');
  return fetch(url, {method:'POST',
    headers:{'Content-Type':'application/x-www-form-urlencoded'},
    body:body}).then(function(r){return r.json()});
}
function toast(t){
  var d=document.createElement('div');
  d.textContent=t;
  d.style.cssText='position:fixed;left:50%;bottom:60px;transform:translateX(-50%);'
    +'background:rgba(40,44,54,.95);color:#fff;padding:9px 16px;border-radius:10px;'
    +'font-size:13px;z-index:999';
  document.body.appendChild(d);
  setTimeout(function(){d.remove()},1800);
}
function editVar(k, v){
  var nv = prompt('把变量 ' + k + ' 改成：', v);
  if(nv === null) return;
  post('/vars/api/set', {scope:'window', k:k, v:nv, type:'auto'}).then(function(r){
    if(r.ok){ toast('已修改，现在值是 ' + r.now); setTimeout(function(){location.reload()}, 600); }
    else toast('失败：' + (r.err || r._raw || '未知'));
  });
}
function editLs(k, v){
  var nv = prompt('把 localStorage[' + k + '] 改成：', v);
  if(nv === null) return;
  post('/vars/api/set', {scope:'storage', k:k, v:nv}).then(function(r){
    if(r.ok){ toast('已修改'); setTimeout(function(){location.reload()}, 600); }
    else toast('失败：' + (r.err || r._raw || '未知'));
  });
}
function delLs(k){
  if(!confirm('删除 localStorage 里的 ' + k + ' ？')) return;
  post('/vars/api/del', {k:k}).then(function(r){
    if(r.ok){ toast('已删除'); setTimeout(function(){location.reload()}, 600); }
    else toast('删除失败');
  });
}
function runCode(){
  var c = document.getElementById('codeBox').value;
  if(!c) return;
  post('/vars/api/eval', {code:c}).then(function(r){
    var el = document.getElementById('jsOut');
    el.style.display = 'block';
    el.textContent = JSON.stringify(r, null, 2);
  });
}
</script>
""";
}
