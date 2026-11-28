#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把 docs/*.md 编成 DocText.java。

LSPatch 下 getAssets() 拿到的是宿主资源，assets 里的东西读不到，
所以文档必须以 Java 字符串常量的形式编进 dex。
"""
import os

DOCS = [
    ("INKSTRIKE", "inkstrike.md", "墨线突击 · 外挂手册"),
    ("WHEELIE",   "wheelie-riot.md", "翘头狂飙 · 外挂手册"),
    ("SHENTUJUN", "shentujun.md", "神图君 · 接口逆向手册"),
]

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "app/src/main/java/com/rxw/xhsspy/DocText.java")

CHUNK = 700  # 每段最多多少字符，避免常量过长


def esc(s):
    return (s.replace("\\", "\\\\")
             .replace('"', '\\"'))


def chunks_of(text):
    """按行切块，每块不超过 CHUNK 字符"""
    lines = text.splitlines(keepends=True)
    out, cur = [], ""
    for ln in lines:
        if len(cur) + len(ln) > CHUNK and cur:
            out.append(cur)
            cur = ""
        cur += ln
    if cur:
        out.append(cur)
    return out


def const(name, text):
    parts = chunks_of(text)
    body = []
    for i, p in enumerate(parts):
        seg = esc(p).replace("\n", "\\n")
        tail = ";" if i == len(parts) - 1 else ""
        prefix = "            \"" if i == 0 else "            + \""
        body.append(prefix + seg + "\\n\"" + tail)
    # 去掉最后一段多出来的 \n（原本每行都以换行结尾，拼完会多一个）
    body[-1] = body[-1].replace('\\\\n\\";', '\\";').replace('\\n";', '";')
    return "\n".join(body)


def main():
    texts = []
    for key, fn, title in DOCS:
        path = os.path.join(HERE, "docs", fn)
        with open(path, encoding="utf-8") as f:
            texts.append((key, title, f.read()))

    b = []
    b.append("package com.rxw.xhsspy;")
    b.append("")
    b.append("/**")
    b.append(" * 内置文档。LSPatch 下 getAssets() 拿到的是宿主的资源，")
    b.append(" * 所以文档直接编成字符串，不能放 assets。")
    b.append(" *")
    b.append(" * 本文件由 gen_docs.py 自动生成，不要手改 —— 改 docs/ 下的 md。")
    b.append(" */")
    b.append("public class DocText {")
    b.append("")

    for key, title, body_text in texts:
        b.append("    /** %s */" % title)
        b.append("    public static final String %s =" % key)
        b.append(const(key, body_text))
        b.append("")

    b.append("    /** 文档目录：标题 + 常量名 */")
    b.append("    public static final String[][] INDEX = {")
    for key, title, _ in texts:
        b.append('            {"%s", "%s"},' % (title, key))
    b.append("    };")
    b.append("")
    b.append("    public static String get(String key) {")
    for key, title, _ in texts:
        b.append('        if ("%s".equals(key)) return %s;' % (key, key))
    b.append('        return "(没有这份文档)";')
    b.append("    }")
    b.append("}")

    with open(OUT, "w", encoding="utf-8") as f:
        f.write("\n".join(b) + "\n")
    print("wrote", OUT)
    for key, title, body_text in texts:
        print("  %-11s %5d chars  %s" % (key, len(body_text), title))


if __name__ == "__main__":
    main()
