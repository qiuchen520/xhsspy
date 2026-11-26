package com.rxw.xhsspy;

import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Wall — 掘金壁纸独立版（跑在模块里）
 *
 * 接口全是从小红书小程序 service.js 里逆出来的：
 *   host    https://min-api.xliii.cn/
 *   key     WAG0JIUGYALACVJF      (service.js 里的 vote_key)
 *   cipher  AES-128-CBC + PKCS7，业务参数整体加密后放 requestData
 *   响应    data.data 用 data.iv 解
 *   图片    https://min-cdn.xliii.cn/ + img / thumb / preview
 *
 * 这样不用装 Python、不用 Termux，模块开着就能看。
 */
public class Wall {

    public static final String APPID = "67905222993c2f0001ad0fd6";
    public static final String VER = "10.10.1";
    public static final String KEY = "WAG0JIUGYALACVJF";
    public static final String HOST = "https://min-api.xliii.cn/";
    public static final String CDN = "https://min-cdn.xliii.cn/";

    private static final String IV_CHARS =
            "1245380796AQWERTYUIOP" + "SDFGHJKLqwSeasdzxvcfrtgbyhnmjuiklop";
    private static final Random RND = new Random();
    private static final String UA =
            "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 "
          + "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36";

    public static final Map<String, String> ENDPOINTS = new java.util.LinkedHashMap<>();
    static {
        ENDPOINTS.put("home", "miniapi/Product/home");
        ENDPOINTS.put("recommend", "miniapi/Product/recommend");
        ENDPOINTS.put("accountData", "miniapi/Product/accountNewData");
        ENDPOINTS.put("pageIndex", "miniapi/Product/pageIndex");
        ENDPOINTS.put("albumList", "miniapi/Product/albumList");
        ENDPOINTS.put("albumData", "miniapi/Product/albumData");
        ENDPOINTS.put("albumDataPage", "miniapi/Product/albumDataPage");
        ENDPOINTS.put("singleData", "miniapi/Product/singleData");
        ENDPOINTS.put("mine", "miniapi/product/mine");
    }

    // ------------------------------------------------------------ 加解密

    static String randIv() {
        StringBuilder b = new StringBuilder(16);
        for (int i = 0; i < 16; i++) {
            b.append(IV_CHARS.charAt(RND.nextInt(IV_CHARS.length())));
        }
        return b.toString();
    }

    static String aesEnc(String plain, String iv) throws Exception {
        javax.crypto.spec.SecretKeySpec k = new javax.crypto.spec.SecretKeySpec(
                KEY.getBytes("UTF-8"), "AES");
        javax.crypto.spec.IvParameterSpec s =
                new javax.crypto.spec.IvParameterSpec(iv.getBytes("UTF-8"));
        javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(javax.crypto.Cipher.ENCRYPT_MODE, k, s);
        return Base64.encodeToString(c.doFinal(plain.getBytes("UTF-8")), Base64.NO_WRAP);
    }

    static String aesDec(String b64, String iv) throws Exception {
        javax.crypto.spec.SecretKeySpec k = new javax.crypto.spec.SecretKeySpec(
                KEY.getBytes("UTF-8"), "AES");
        javax.crypto.spec.IvParameterSpec s =
                new javax.crypto.spec.IvParameterSpec(iv.getBytes("UTF-8"));
        javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(javax.crypto.Cipher.DECRYPT_MODE, k, s);
        byte[] out = c.doFinal(Base64.decode(b64, Base64.DEFAULT));
        return new String(out, "UTF-8");
    }

    // ------------------------------------------------------------ 调接口

    public static JSONObject api(String name, JSONObject payload) throws Exception {
        String path = ENDPOINTS.containsKey(name) ? ENDPOINTS.get(name) : name;
        JSONObject body = payload == null ? new JSONObject() : new JSONObject(payload.toString());
        if (!body.has("appid")) body.put("appid", APPID);
        if (!body.has("ver")) body.put("ver", VER);
        if (!body.has("unionid")) body.put("unionid", "");
        if (!body.has("openid")) body.put("openid", "");
        if (!body.has("min_push")) body.put("min_push", 0);
        if (!body.has("scene")) body.put("scene", 1001);

        String iv = randIv();
        String qs = "requestData=" + URLEncoder.encode(aesEnc(body.toString(), iv), "UTF-8")
                  + "&iv=" + URLEncoder.encode(iv, "UTF-8");

        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(HOST + path + "?" + qs).openConnection();
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Referer", CDN);
            c.setRequestProperty("Accept", "application/json, text/plain, */*");
            c.setConnectTimeout(15000);
            c.setReadTimeout(25000);
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = readAll(in);
            if (code >= 400) throw new RuntimeException("上游 HTTP " + code + " " + text);

            JSONObject j = new JSONObject(text);
            JSONObject d = j.optJSONObject("data");
            if (d != null && d.optString("iv", "").length() > 0
                    && d.optString("data", "").length() > 0) {
                String plain = aesDec(d.getString("data"), d.getString("iv"));
                return new JSONObject(plain);
            }
            return d != null ? d : j;
        } finally {
            if (c != null) try { c.disconnect(); } catch (Exception ignored) {}
        }
    }

    static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int r;
        while ((r = in.read(buf)) > 0) bos.write(buf, 0, r);
        in.close();
        return new String(bos.toByteArray(), "UTF-8");
    }

    // ------------------------------------------------------------ 图片地址

    public static String imgUrl(String rel) {
        if (rel == null || rel.isEmpty()) return "";
        if (rel.startsWith("http")) return rel;
        if (rel.startsWith("/")) return CDN + rel.substring(1);
        return CDN + rel;
    }

    public static JSONObject decorate(JSONObject it) {
        try {
            JSONObject o = new JSONObject(it.toString());
            String img = it.optString("img", "");
            String thumb = it.optString("thumb", "");
            String preview = it.optString("preview", "");
            if (thumb.isEmpty()) thumb = img;
            if (preview.isEmpty()) preview = img;
            o.put("url_img", imgUrl(img));
            o.put("url_thumb", imgUrl(thumb));
            o.put("url_preview", imgUrl(preview));
            o.put("url_download", imgUrl(img));
            return o;
        } catch (Exception e) {
            return it;
        }
    }

    static JSONArray decorateList(JSONArray arr) {
        JSONArray out = new JSONArray();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject it = arr.optJSONObject(i);
            if (it != null) out.put(decorate(it));
        }
        return out;
    }

    // ------------------------------------------------------------ 路由

    /** 处理 /wall/api/xxx，返回 JSON 文本 */
    public static String apiRoute(String sub, Map<String, String> q) {
        try {
            String page = q.getOrDefault("page", "1");
            String acc = q.getOrDefault("acc_id", "1");

            if ("home".equals(sub)) {
                JSONObject d = api("home", new JSONObject());
                JSONObject data = d.optJSONObject("data");
                if (data == null) data = d;
                data.put("list", decorateList(data.optJSONArray("list")));
                JSONArray rec = data.optJSONArray("recommend");
                if (rec != null) {
                    for (int i = 0; i < rec.length(); i++) {
                        JSONObject a = rec.optJSONObject(i);
                        if (a != null && a.has("app_head_img")) {
                            a.put("app_head_img", imgUrl(a.optString("app_head_img")));
                        }
                    }
                }
                JSONObject cfg = d.optJSONObject("config");
                if (cfg != null) data.put("_config", cfg);
                return data.toString();
            }

            if ("list".equals(sub)) {
                JSONObject p = new JSONObject();
                p.put("page", Integer.parseInt(page));
                p.put("acc_id", acc);
                JSONObject d = api("pageIndex", p);
                JSONObject data = d.optJSONObject("data");
                if (data == null) data = d;
                data.put("list", decorateList(data.optJSONArray("list")));
                return data.toString();
            }

            if ("album".equals(sub)) {
                JSONObject p = new JSONObject();
                p.put("page", Integer.parseInt(page));
                p.put("acc_id", acc);
                JSONObject d = api("albumList", p);
                JSONObject data = d.optJSONObject("data");
                return (data != null ? data : d).toString();
            }

            if ("album_data".equals(sub)) {
                JSONObject p = new JSONObject();
                p.put("page", Integer.parseInt(page));
                p.put("alb_id", q.getOrDefault("alb_id", "0"));
                p.put("acc_id", acc);
                JSONObject d = api("albumDataPage", p);
                JSONObject data = d.optJSONObject("data");
                if (data == null) data = d;
                data.put("list", decorateList(data.optJSONArray("list")));
                return data.toString();
            }

            if ("item".equals(sub)) {
                JSONObject p = new JSONObject();
                p.put("res_id", q.getOrDefault("res_id", "0"));
                JSONObject d = api("singleData", p);
                JSONObject data = d.optJSONObject("data");
                return (data != null ? data : d).toString();
            }

            if ("raw".equals(sub)) {
                String name = q.getOrDefault("name", "home");
                return api(name, new JSONObject()).toString();
            }

            return "{\"error\":\"unknown api: " + sub + "\"}";
        } catch (Exception e) {
            try {
                return new JSONObject().put("error", String.valueOf(e)).toString();
            } catch (Exception ex) {
                return "{\"error\":\"internal\"}";
            }
        }
    }

    // ------------------------------------------------------------ 页面

    public static void page(OutputStream out) throws Exception {
        byte[] b = HTML.getBytes("UTF-8");
        String head = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: text/html; charset=utf-8\r\n"
                + "Content-Length: " + b.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes("UTF-8"));
        out.write(b);
        out.flush();
    }

    static final String HTML = """
<!doctype html>
<html lang="zh-CN"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<title>掘金壁纸</title>
<style>
*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
body{margin:0;background:#0f1115;color:#e8e8ec;font:15px/1.6 -apple-system,"PingFang SC","Microsoft YaHei",sans-serif}
header{position:sticky;top:0;z-index:20;backdrop-filter:blur(14px);background:rgba(15,17,21,.86);border-bottom:1px solid #1e222a}
.hd{padding:12px 16px 8px;display:flex;align-items:center;gap:10px}
.logo{font-size:17px;font-weight:700;background:linear-gradient(90deg,#638dff,#b06bff);-webkit-background-clip:text;background-clip:text;color:transparent}
.sub{font-size:11px;color:#6b7280;margin-left:auto}
.search{display:flex;gap:8px;padding:0 16px 10px}
.search input{flex:1;padding:9px 14px;border-radius:10px;border:1px solid #262b36;background:#171a21;color:#e8e8ec;font-size:13px;outline:none}
.search button{padding:9px 16px;border-radius:10px;background:linear-gradient(135deg,#638dff,#8b5cf6);color:#fff;border:none;font-size:13px}
.authors{display:flex;gap:10px;padding:4px 16px 12px;overflow-x:auto;scrollbar-width:none}
.authors::-webkit-scrollbar{display:none}
.au{flex:0 0 auto;width:64px;text-align:center;cursor:pointer}
.au img{width:52px;height:52px;border-radius:50%;object-fit:cover;border:2px solid #262b36;background:#1a1e27;display:block;margin:0 auto}
.au.on img{border-color:#638dff}
.au span{display:block;font-size:10px;color:#8b93a3;margin-top:4px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
main{padding:12px 12px 90px}
.grid{column-count:2;column-gap:10px}
@media(min-width:720px){.grid{column-count:3}}
@media(min-width:1080px){.grid{column-count:4}}
.card{break-inside:avoid;margin-bottom:10px;border-radius:14px;overflow:hidden;background:#171a21;position:relative;cursor:pointer}
.card img{width:100%;display:block;background:#1f242e;min-height:80px}
.card .meta{position:absolute;left:0;right:0;bottom:0;padding:16px 8px 6px;font-size:10px;color:#c9cfdb;background:linear-gradient(transparent,rgba(0,0,0,.72))}
.card .badge{position:absolute;top:6px;right:6px;font-size:9px;padding:2px 6px;border-radius:6px;background:rgba(0,0,0,.6);color:#fff}
.over{position:fixed;inset:0;background:#000;z-index:99;display:none;flex-direction:column}
.over.on{display:flex}
.over .bar{padding:12px 16px;display:flex;gap:10px;align-items:center;background:rgba(0,0,0,.7);position:relative;z-index:2}
.over .bar .t{font-size:13px;color:#aab;flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.btn{padding:7px 14px;border-radius:10px;background:#638dff;color:#fff;font-size:13px;border:none;cursor:pointer}
.btn.ghost{background:#22262f;color:#c9cfdb}
.over .pic{flex:1;overflow:auto;display:flex;align-items:center;justify-content:center;padding:8px}
.over .pic img{max-width:100%;max-height:100%;object-fit:contain}
.empty{text-align:center;color:#5b6270;padding:60px 20px;font-size:13px}
.loading{height:3px;background:linear-gradient(90deg,#638dff,#8b5cf6);position:fixed;top:0;left:0;width:0;transition:width .3s;z-index:50}
#more{display:block;margin:18px auto 0;padding:11px 28px;border-radius:12px;background:#1a1e27;color:#a8aebc;border:1px solid #262b36;font-size:13px}
#more:disabled{opacity:.4}
.toast{position:fixed;bottom:80px;left:50%;transform:translateX(-50%);background:rgba(40,44,54,.96);color:#fff;padding:9px 18px;border-radius:10px;font-size:13px;opacity:0;transition:.25s;pointer-events:none;z-index:200}
.toast.on{opacity:1}
</style></head><body>
<div class="loading" id="bar"></div>
<header>
  <div class="hd"><div class="logo">掘金壁纸</div><div class="sub" id="stat">加载中…</div></div>
  <div class="search">
    <input id="accInput" type="number" inputmode="numeric" placeholder="输入作者编号 acc_id，回车直达">
    <button id="accGo">跳转</button>
  </div>
  <div class="authors" id="authors"></div>
</header>
<main>
  <div class="grid" id="grid"></div>
  <div class="empty" id="empty" style="display:none">这里还什么都没有</div>
  <button id="more">加载更多</button>
</main>
<div class="over" id="over">
  <div class="bar"><div class="t" id="otitle"></div>
    <button class="btn" id="odl">下载原图</button>
    <button class="btn ghost" id="oclose">关闭</button></div>
  <div class="pic"><img id="oimg" alt=""></div>
</div>
<div class="toast" id="toast"></div>
<script>
var $=function(s){return document.querySelector(s)};
var state={acc:1,page:1,loading:false,end:false,list:[]};
function toast(t){var e=$('#toast');e.textContent=t;e.classList.add('on');clearTimeout(e._t);e._t=setTimeout(function(){e.classList.remove('on')},1600);}
function bar(p){$('#bar').style.width=p+'%';if(p>=100)setTimeout(function(){$('#bar').style.width='0'},300);}
function get(u){return fetch(u).then(function(r){if(!r.ok)throw new Error('HTTP '+r.status);return r.json()});}
function boot(){
  bar(30);
  return get('/wall/api/home').then(function(d){
    bar(70);
    var rec=d.recommend||[], list=d.list||[];
    var box=$('#authors');box.innerHTML='';
    var all=document.createElement('div');all.className='au on';
    all.innerHTML='<div style="width:52px;height:52px;border-radius:50%;background:linear-gradient(135deg,#638dff,#8b5cf6);display:flex;align-items:center;justify-content:center;font-size:13px;margin:0 auto;border:2px solid #262b36">全部</div><span>全部</span>';
    all.onclick=function(){pick(1,all)};box.appendChild(all);
    rec.slice(0,40).forEach(function(a){
      var el=document.createElement('div');el.className='au';
      el.innerHTML='<img loading="lazy" src="'+a.app_head_img+'"><span>'+(a.app_name||'')+'</span>';
      el.title='acc_id='+a.acc_id;
      el.onclick=function(){pick(a.acc_id,el)};
      box.appendChild(el);
    });
    render(list);
    $('#stat').textContent=list.length+' 张 · 作者 '+rec.length;
    bar(100);
  }).catch(function(e){
    bar(100);$('#empty').style.display='block';
    $('#empty').textContent='加载失败：'+e.message;
  });
}
function pick(id,el){
  if(el){var p=el.parentNode;[].slice.call(p.children).forEach(function(x){x.classList.remove('on')});el.classList.add('on');}
  else{$('#authors').innerHTML='';}
  state.acc=id;state.page=1;state.end=false;
  $('#grid').innerHTML='';state.list=[];
  $('#accInput').value=id;
  loadPage();
}
function render(items){
  var g=$('#grid');
  items.forEach(function(it){
    var c=document.createElement('div');c.className='card';
    var src=it.url_thumb||it.url_img,date=(it.date||'').slice(0,10);
    c.innerHTML='<img loading="lazy" src="'+src+'">'+(it.class===4?'<div class="badge">图集</div>':'')+'<div class="meta">'+date+' · #'+it.res_id+'</div>';
    c.onclick=function(){openBig(it)};
    g.appendChild(c);
  });
  $('#empty').style.display=(state.list.length===0&&!state.loading)?'block':'none';
  $('#empty').textContent=state.end&&state.list.length===0?'这个作者还没有图':'这里还什么都没有';
}
function loadPage(){
  if(state.loading||state.end)return;
  state.loading=true;$('#more').disabled=true;$('#more').textContent='加载中…';bar(40);
  get('/wall/api/list?page='+state.page+'&acc_id='+state.acc).then(function(d){
    var list=d.list||[];
    if(list.length===0){state.end=true;}
    else{state.list=state.list.concat(list);render(list);state.page++;}
  }).catch(function(e){toast('加载失败：'+e.message)}).then(function(){
    state.loading=false;bar(100);$('#more').disabled=false;
    $('#more').textContent=state.end?'没有更多了':'加载更多';
    $('#stat').textContent=state.list.length+' 张 · acc_id='+state.acc;
  });
}
function openBig(it){
  var u=it.url_img||it.url_preview;
  $('#oimg').src=it.url_preview||u;
  $('#otitle').textContent='#'+it.res_id+' · '+(it.date||'').slice(0,10);
  $('#odl').onclick=function(){
    var a=document.createElement('a');
    a.href=u;a.download='jj_'+it.res_id+'.jpg';a.target='_blank';
    document.body.appendChild(a);a.click();a.remove();
    toast('已触发下载，长按图片也可保存');
  };
  $('#over').classList.add('on');
}
$('#oclose').onclick=function(){$('#over').classList.remove('on')};
$('#over').onclick=function(e){if(e.target.id==='over')$('#over').classList.remove('on')};
$('#more').onclick=loadPage;
function goAcc(){
  var v=parseInt($('#accInput').value,10);
  if(!v||v<1){toast('请输入数字编号');return;}
  pick(v,null);
}
$('#accGo').onclick=goAcc;
$('#accInput').addEventListener('keydown',function(e){if(e.key==='Enter')goAcc()});
boot().then(function(){loadPage()});
addEventListener('scroll',function(){
  if(state.loading||state.end)return;
  if(innerHeight+scrollY>document.body.offsetHeight-600)loadPage();
});
</script></body></html>
""";
}
